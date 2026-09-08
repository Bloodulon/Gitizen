package org.gitizen.gitizen

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile

class Gitizen : JavaPlugin(), CommandExecutor, TabCompleter {
    private lateinit var configService: ConfigService
    @Volatile
    private lateinit var appConfig: AppConfig
    private lateinit var gitManager: GitManager
    private lateinit var history: DeploymentHistory
    private lateinit var deploymentService: DeploymentService
    private lateinit var notificationService: NotificationService
    private val busyProfiles = ConcurrentHashMap.newKeySet<String>()

    override fun onEnable() {
        saveDefaultConfig()
        configService = ConfigService(this)
        appConfig = configService.load()
        history = DeploymentHistory(dataFolder, appConfig.maxHistory)
        gitManager = GitManager(File(dataFolder, "repositories"))
        deploymentService = DeploymentService(
            this,
            gitManager,
            history,
            DenizenValidator(server)
        )
        notificationService = NotificationService(this)

        val command = getCommand("gitizen")
        if (command == null) {
            logger.severe("Команда gitizen не зарегистрирована.")
            server.pluginManager.disablePlugin(this)
            return
        }
        command.setExecutor(this)
        command.tabCompleter = this
        logger.info("Gitizen ${pluginMeta.version} запущен. Профилей: ${appConfig.profiles.size}.")
    }

    override fun onCommand(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>
    ): Boolean {
        when (args.getOrNull(0)?.lowercase(Locale.ROOT)) {
            null, "help" -> showHelp(sender)
            "sync" -> {
                if (!allowed(sender, "gitizen.sync")) return true
                val profile = profile(sender, args.getOrNull(1)) ?: return true
                startDeployment(sender, profile, DeploymentKind.SYNC, null)
            }
            "rollback" -> {
                if (!allowed(sender, "gitizen.rollback")) return true
                handleRollback(sender, args)
            }
            "status" -> {
                if (!allowed(sender, "gitizen.status")) return true
                val profile = profile(sender, args.getOrNull(1)) ?: return true
                showStatus(sender, profile)
            }
            "logs" -> {
                if (!allowed(sender, "gitizen.logs")) return true
                showLogs(sender, args)
            }
            "list", "scripts" -> {
                if (!allowed(sender, "gitizen.list")) return true
                val profile = profile(sender, args.getOrNull(1)) ?: return true
                showScripts(sender, profile)
            }
            "profiles" -> {
                if (!allowed(sender, "gitizen.status")) return true
                Messages.info(sender, "Профили: ${appConfig.profiles.keys.joinToString(", ")}")
            }
            "setup" -> {
                if (!allowed(sender, "gitizen.admin")) return true
                setupProfile(sender, args)
            }
            "reload" -> {
                if (!allowed(sender, "gitizen.admin")) return true
                reloadPluginConfig(sender)
            }
            else -> showHelp(sender)
        }
        return true
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>
    ): List<String> {
        if (args.size == 1) {
            val commands = buildList {
                add("help")
                if (sender.hasPermission("gitizen.sync")) add("sync")
                if (sender.hasPermission("gitizen.rollback")) add("rollback")
                if (sender.hasPermission("gitizen.status")) addAll(listOf("status", "profiles"))
                if (sender.hasPermission("gitizen.logs")) add("logs")
                if (sender.hasPermission("gitizen.list")) add("list")
                if (sender.hasPermission("gitizen.admin")) addAll(listOf("setup", "reload"))
            }
            return filter(commands, args[0])
        }
        if (args.size == 2 && args[0].lowercase(Locale.ROOT) in
            setOf("sync", "status", "logs", "list", "setup")
        ) {
            return filter(appConfig.profiles.keys, args[1])
        }
        if (args.size == 2 && args[0].equals("rollback", true)) {
            return filter(listOf("1") + appConfig.profiles.keys, args[1])
        }
        if (args.size == 3 && args[0].equals("rollback", true)) {
            return listOf("1")
        }
        if (args.size == 3 && args[0].equals("setup", true)) {
            return listOf("https://github.com/USER/REPOSITORY.git")
        }
        return emptyList()
    }

    private fun startDeployment(
        sender: CommandSender,
        profile: ProfileConfig,
        kind: DeploymentKind,
        revision: String?
    ) {
        configService.validate(profile)?.let {
            Messages.error(sender, it)
            return
        }
        if (!busyProfiles.add(profile.name)) {
            Messages.info(sender, "Для профиля ${profile.name} уже выполняется операция.")
            return
        }
        Messages.info(
            sender,
            if (kind == DeploymentKind.SYNC) {
                "Подготавливаю синхронизацию профиля ${profile.name}..."
            } else {
                "Подготавливаю rollback профиля ${profile.name}..."
            }
        )
        val deployedCommit = history.last(profile.name)?.commit
        server.scheduler.runTaskAsynchronously(this, Runnable {
            val prepared = try {
                if (kind == DeploymentKind.SYNC) {
                    gitManager.prepareSync(profile, deployedCommit)
                } else {
                    gitManager.prepareRollback(profile, revision!!, deployedCommit)
                }
            } catch (e: Exception) {
                PreparationResult.Failure(e.message ?: e.javaClass.simpleName, e)
            }

            when (prepared) {
                is PreparationResult.Failure -> {
                    history.recordFailure(profile.name, 0)
                    val result = DeploymentResult(
                        false,
                        profile.name,
                        null,
                        "Подготовка не выполнена: ${prepared.message}"
                    )
                    finish(sender, result)
                }
                is PreparationResult.UpToDate -> {
                    server.scheduler.runTask(this, Runnable {
                        busyProfiles.remove(profile.name)
                        Messages.success(
                            sender,
                            "Профиль ${profile.name} уже актуален (${prepared.commit.shortHash})."
                        )
                        Messages.commit(sender, prepared.commit, profile.repoUrl)
                    })
                }
                is PreparationResult.Ready -> {
                    val report = deploymentService.preflight(prepared.deployment, appConfig)
                    if (!report.valid) {
                        finish(sender, deploymentService.reject(prepared.deployment, report))
                    } else {
                        server.scheduler.runTask(this, Runnable {
                            val result = deploymentService.activate(prepared.deployment)
                            busyProfiles.remove(profile.name)
                            Messages.deployment(sender, result, appConfig.maxChangesInChat)
                            sendNotifications(result)
                        })
                    }
                }
            }
        })
    }

    private fun finish(sender: CommandSender, result: DeploymentResult) {
        server.scheduler.runTask(this, Runnable {
            busyProfiles.remove(result.profile)
            Messages.deployment(sender, result, appConfig.maxChangesInChat)
            sendNotifications(result)
        })
    }

    private fun sendNotifications(result: DeploymentResult) {
        val notificationConfig = appConfig.notifications
        if (notificationConfig.discordWebhook == null &&
            (notificationConfig.telegramBotToken == null ||
                notificationConfig.telegramChatId == null)
        ) {
            return
        }
        server.scheduler.runTaskAsynchronously(this, Runnable {
            notificationService.send(result, notificationConfig)
        })
    }

    private fun handleRollback(sender: CommandSender, args: Array<out String>) {
        if (args.size < 2) {
            Messages.info(sender, "Использование: /gitizen rollback [profile] <номер|hash>")
            return
        }
        val explicitProfile = args.size >= 3
        val profile = profile(sender, if (explicitProfile) args[1] else null) ?: return
        val selector = if (explicitProfile) args[2] else args[1]
        val revision = selector.toIntOrNull()?.let { number ->
            history.rollbackTarget(profile.name, number)
        } ?: selector.takeIf { it.matches(Regex("[0-9a-fA-F]{4,40}")) }
        if (revision == null) {
            Messages.error(
                sender,
                "Версия rollback не найдена. Номер 1 означает deployment перед текущим."
            )
            return
        }
        startDeployment(sender, profile, DeploymentKind.ROLLBACK, revision)
    }

    private fun showStatus(sender: CommandSender, profile: ProfileConfig) {
        Messages.info(sender, "Проверяю состояние профиля ${profile.name}...")
        server.scheduler.runTaskAsynchronously(this, Runnable {
            val result = runCatching {
                gitManager.status(profile, history.last(profile.name), history.metrics(profile.name))
            }
            server.scheduler.runTask(this, Runnable {
                result.onSuccess { status ->
                    sender.sendMessage(Component.text("Gitizen / ${status.profile}", NamedTextColor.GOLD))
                    Messages.info(sender, "Ветка: ${status.branch}")
                    hashLine(sender, "Repository HEAD", status.repositoryHead)
                    hashLine(sender, "Remote HEAD", status.remoteHead)
                    hashLine(sender, "Активный deployment", status.deployedCommit)
                    Messages.info(
                        sender,
                        if (status.deployedCommit == status.remoteHead) {
                            "Состояние: актуально"
                        } else {
                            "Состояние: доступно обновление или активен rollback"
                        }
                    )
                    Messages.info(
                        sender,
                        "Последний успешный deploy: ${Messages.formatTime(status.metrics.lastSuccessfulAtMillis)}"
                    )
                    Messages.info(
                        sender,
                        "Метрики: успешно ${status.metrics.successfulDeployments}, " +
                            "ошибок ${status.metrics.failedDeployments}, " +
                            "последняя длительность ${status.metrics.lastDurationMillis ?: 0} мс, " +
                            "изменений ${status.metrics.lastChangedFiles ?: 0}"
                    )
                }.onFailure {
                    Messages.error(sender, "Не удалось получить status: ${it.message}")
                }
            })
        })
    }

    private fun showLogs(sender: CommandSender, args: Array<out String>) {
        val profile = profile(sender, args.getOrNull(1)) ?: return
        val limit = args.getOrNull(2)?.toIntOrNull()?.coerceIn(1, 20) ?: 5
        server.scheduler.runTaskAsynchronously(this, Runnable {
            val result = runCatching { gitManager.recentLogs(profile, limit) }
            server.scheduler.runTask(this, Runnable {
                result.onSuccess { commits ->
                    Messages.info(sender, "Последние commits профиля ${profile.name}:")
                    commits.forEach { Messages.commit(sender, it, profile.repoUrl) }
                    if (commits.isEmpty()) Messages.info(sender, "История пуста.")
                }.onFailure {
                    Messages.error(sender, "Не удалось прочитать историю: ${it.message}")
                }
            })
        })
    }

    private fun showScripts(sender: CommandSender, profile: ProfileConfig) {
        val root = profile.targetDirectory.toPath()
        server.scheduler.runTaskAsynchronously(this, Runnable {
            val files = if (!root.toFile().isDirectory) {
                emptyList()
            } else {
                java.nio.file.Files.walk(root).use { paths ->
                    paths.filter { it.isRegularFile() && it.extension.equals("dsc", true) }
                        .map { root.relativize(it).toString() }
                        .sorted()
                        .toList()
                }
            }
            server.scheduler.runTask(this, Runnable {
                Messages.info(sender, "Скрипты профиля ${profile.name}: ${files.size}")
                files.take(100).forEach { sender.sendMessage(Component.text("  $it")) }
                if (files.size > 100) Messages.info(sender, "Ещё ${files.size - 100} файлов скрыто.")
            })
        })
    }

    private fun setupProfile(sender: CommandSender, args: Array<out String>) {
        if (args.size < 3) {
            Messages.info(sender, "Использование: /gitizen setup <profile> <url> [branch]")
            Messages.info(sender, "PAT задаётся через token-env или config.yml, но не через команду.")
            return
        }
        val name = args[1].lowercase(Locale.ROOT)
        if (!name.matches(Regex("[a-z0-9_-]{1,32}"))) {
            Messages.error(sender, "Некорректное имя профиля.")
            return
        }
        val path = "profiles.$name"
        config.set("$path.repo-url", args[2])
        config.set("$path.branch", args.getOrNull(3) ?: "main")
        if (!config.contains("$path.target-directory")) {
            config.set("$path.target-directory", if (name == "production") "scripts" else "scripts-$name")
        }
        if (!config.contains("$path.reload-denizen")) {
            config.set("$path.reload-denizen", name == "production")
        }
        if (!config.contains("$path.token-env")) {
            config.set("$path.token-env", "GITIZEN_GITHUB_TOKEN")
        }
        saveConfig()
        appConfig = configService.load(reload = true)
        history.updateLimit(appConfig.maxHistory)
        val profile = appConfig.profiles[name]
        val error = profile?.let(configService::validate)
        if (profile == null || error != null) {
            Messages.error(sender, error ?: "Профиль не удалось загрузить.")
        } else {
            Messages.success(sender, "Профиль $name сохранён. Токен в команду не передавался.")
        }
    }

    private fun reloadPluginConfig(sender: CommandSender) {
        runCatching {
            appConfig = configService.load(reload = true)
            history.updateLimit(appConfig.maxHistory)
        }.onSuccess {
            Messages.success(sender, "Конфигурация перечитана.")
        }.onFailure {
            Messages.error(sender, "Ошибка конфигурации: ${it.message}")
        }
    }

    private fun profile(sender: CommandSender, requested: String?): ProfileConfig? {
        val name = requested?.lowercase(Locale.ROOT) ?: appConfig.defaultProfile
        return appConfig.profiles[name] ?: run {
            Messages.error(
                sender,
                "Профиль '$name' не найден. Доступны: ${appConfig.profiles.keys.joinToString(", ")}"
            )
            null
        }
    }

    private fun allowed(sender: CommandSender, permission: String): Boolean {
        if (sender.hasPermission(permission) || sender.hasPermission("gitizen.admin")) return true
        Messages.error(sender, "Недостаточно прав: $permission")
        return false
    }

    private fun showHelp(sender: CommandSender) {
        Messages.info(sender, "Команды:")
        if (sender.hasPermission("gitizen.sync")) sender.sendMessage("/gitizen sync [profile]")
        if (sender.hasPermission("gitizen.status")) sender.sendMessage("/gitizen status [profile]")
        if (sender.hasPermission("gitizen.rollback")) {
            sender.sendMessage("/gitizen rollback [profile] <номер|hash>")
        }
        if (sender.hasPermission("gitizen.logs")) sender.sendMessage("/gitizen logs [profile] [count]")
        if (sender.hasPermission("gitizen.list")) sender.sendMessage("/gitizen list [profile]")
        if (sender.hasPermission("gitizen.admin")) {
            sender.sendMessage("/gitizen setup <profile> <url> [branch]")
            sender.sendMessage("/gitizen reload")
        }
    }

    private fun hashLine(sender: CommandSender, label: String, hash: String?) {
        val value = hash ?: "нет"
        sender.sendMessage(
            Component.text("$label: ", NamedTextColor.GRAY)
                .append(Component.text(value.take(12), NamedTextColor.AQUA))
                .clickEvent(hash?.let(ClickEvent::copyToClipboard))
                .hoverEvent(
                    HoverEvent.showText(
                        Component.text(if (hash == null) "Нет данных" else "Скопировать $hash")
                    )
                )
        )
    }

    private fun filter(values: Collection<String>, input: String): List<String> {
        return values.filter { it.startsWith(input, ignoreCase = true) }.sorted()
    }
}
