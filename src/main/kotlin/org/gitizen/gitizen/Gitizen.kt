package org.gitizen.gitizen

import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.plugin.java.JavaPlugin
import java.io.File


class Gitizen : JavaPlugin(), CommandExecutor, TabCompleter {

    private lateinit var gitManager: GitManager
    private lateinit var scriptsDir: File

    override fun onEnable() {
        saveDefaultConfig()

        scriptsDir = File(dataFolder.parentFile, "Denizen/scripts")
        val repoUrl = config.getString("repo-url") ?: ""
        val token = config.getString("github-token") ?: ""
        val scriptsDir = File(dataFolder.parentFile, "Denizen/scripts")

        gitManager = GitManager(repoUrl, token, scriptsDir)

        val cmd = getCommand("gitizen")
        cmd?.setExecutor(this)
        cmd?.tabCompleter = this

        logger.info("Gitizen has been launched successfully!")
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>
    ): List<String> {
        if (args.size == 1) {
            val suggestions = mutableListOf<String>()
            if (sender.hasPermission("gitizen.sync")) suggestions.add("sync")
            if (sender.hasPermission("gitizen.admin")) {
                suggestions.add("setup")
                suggestions.add("list")
                suggestions.add("logs")
            }
            return suggestions.filter { it.startsWith(args[0], ignoreCase = true) }
        }
        if (args.size == 2 && args[0].lowercase() == "setup") {
            return listOf("https://github.com/USER/REPO.git")
        }

        return emptyList()
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("gitizen.admin")) {
            sender.sendMessage("У вас нет прав!")
            return true
        }

        when (args.getOrNull(0)?.lowercase()) {
            "setup" -> {
                if (args.size < 3) {
                    sender.sendMessage("§7Использование: §e/gitizen setup <url> <token>")
                    return true
                }
                val newUrl = args[1]
                val newToken = args[2]

                config.set("repo-url", newUrl)
                config.set("github-token", newToken)
                saveConfig()

                scriptsDir = File(dataFolder.parentFile, "Denizen/scripts")
                gitManager = GitManager(newUrl, newToken, scriptsDir)

                sender.sendMessage("§a[Gitizen] Настройки сохранены!")
            }

            "sync" -> {
                sender.sendMessage("§e[Gitizen] Подключаюсь к GitHub...")
                server.scheduler.runTaskAsynchronously(this, Runnable {

                    val resultList = gitManager.sync()

                    server.scheduler.runTask(this, Runnable {
                        resultList.forEach { line -> sender.sendMessage(line) }

                        if (!resultList[0].contains("Ошибка") && !resultList[0].contains("актуальны")) {
                            server.dispatchCommand(server.consoleSender, "ex reload")
                        }
                    })
                })
            }
            "list" -> {
                sender.sendMessage("Полный список скриптов:")
                val files = scriptsDir.listFiles()?.filter { it.extension == "dsc" }
                if (files.isNullOrEmpty()) {
                    sender.sendMessage("Папка со скриптами пустая")
                } else {
                    files.forEach { sender.sendMessage("${it.name}") }
                }
            }
            "logs" -> {
                sender.sendMessage("§b[Gitizen] Последние 3 изменения в репозитории:")
                server.scheduler.runTaskAsynchronously(this, Runnable {
                    val logLines = gitManager.getRecentLogs(3)

                    server.scheduler.runTask(this, Runnable {
                        if (logLines.isEmpty()) {
                            sender.sendMessage("§7История коммитов пуста.")
                        } else {
                            logLines.forEach { line -> sender.sendMessage(line) }
                        }
                    })
                })
            }
        }
        return true
    }
}