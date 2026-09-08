package org.gitizen.gitizen

import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.net.URI
import java.util.Locale

class ConfigService(private val plugin: JavaPlugin) {

    fun load(reload: Boolean = false): AppConfig {
        if (reload) {
            plugin.reloadConfig()
        }

        val config = plugin.config
        val rawConfig = YamlConfiguration.loadConfiguration(File(plugin.dataFolder, "config.yml"))
        val security = config.getConfigurationSection("security")
        val requireHttps = security?.getBoolean("require-https", true) ?: true
        val allowedHosts = (security?.getStringList("allowed-hosts") ?: listOf("github.com"))
            .map { it.lowercase(Locale.ROOT) }
            .toSet()

        val denizenRoot = File(plugin.dataFolder.parentFile, "Denizen").canonicalFile
        val profilesSection = rawConfig.getConfigurationSection("profiles")
            ?: config.getConfigurationSection("profiles")
        val profiles = linkedMapOf<String, ProfileConfig>()
        profilesSection?.getKeys(false)?.forEach { name ->
            val section = profilesSection.getConfigurationSection(name) ?: return@forEach
            profiles[name.lowercase(Locale.ROOT)] = readProfile(
                name.lowercase(Locale.ROOT),
                section,
                denizenRoot,
                requireHttps,
                allowedHosts
            )
        }

        if (rawConfig.contains("repo-url") && rawConfig.getConfigurationSection("profiles") == null) {
            profiles.clear()
            profiles["production"] = legacyProfile(rawConfig, denizenRoot, requireHttps, allowedHosts)
            plugin.logger.warning("Обнаружен старый формат config.yml. Настройки прочитаны как профиль production.")
        }

        val defaultProfile = config.getString("default-profile", "production")!!
            .lowercase(Locale.ROOT)
        val notifications = NotificationConfig(
            discordWebhook = secret(
                config.getString("notifications.discord.webhook-url"),
                config.getString("notifications.discord.webhook-url-env", "GITIZEN_DISCORD_WEBHOOK")
            ),
            telegramBotToken = secret(
                config.getString("notifications.telegram.bot-token"),
                config.getString("notifications.telegram.bot-token-env", "GITIZEN_TELEGRAM_BOT_TOKEN")
            ),
            telegramChatId = secret(
                config.getString("notifications.telegram.chat-id"),
                config.getString("notifications.telegram.chat-id-env", "GITIZEN_TELEGRAM_CHAT_ID")
            )
        )

        return AppConfig(
            defaultProfile = defaultProfile,
            profiles = profiles,
            notifications = notifications,
            maxHistory = config.getInt("deployment.max-history", 20).coerceIn(2, 200),
            maxChangesInChat = config.getInt("deployment.max-changes-in-chat", 30).coerceIn(1, 200),
            requireDscFiles = config.getBoolean("validation.require-dsc-files", true),
            maxScriptSizeBytes = config.getLong("validation.max-script-size-kb", 1024)
                .coerceIn(1, 10240) * 1024
        )
    }

    fun validate(profile: ProfileConfig): String? {
        if (!profile.name.matches(Regex("[a-z0-9_-]{1,32}"))) {
            return "Недопустимое имя профиля: ${profile.name}"
        }
        if (profile.repoUrl.isBlank()) {
            return "Для профиля ${profile.name} не указан repo-url."
        }
        if (!profile.branch.matches(Regex("[A-Za-z0-9._/-]{1,200}")) ||
            profile.branch.contains("..") ||
            profile.branch.contains("@{") ||
            profile.branch.startsWith("/") ||
            profile.branch.endsWith("/")
        ) {
            return "Недопустимое имя ветки: ${profile.branch}"
        }
        val uri = try {
            URI(profile.repoUrl)
        } catch (_: Exception) {
            return "Некорректный URL репозитория."
        }
        if (profile.requireHttps && !uri.scheme.equals("https", ignoreCase = true)) {
            return "Разрешены только HTTPS-репозитории."
        }
        if (profile.allowedHosts.isNotEmpty() &&
            uri.host?.lowercase(Locale.ROOT) !in profile.allowedHosts
        ) {
            return "Хост ${uri.host ?: "(не указан)"} отсутствует в security.allowed-hosts."
        }
        return null
    }

    private fun readProfile(
        name: String,
        section: ConfigurationSection,
        denizenRoot: File,
        requireHttps: Boolean,
        allowedHosts: Set<String>
    ): ProfileConfig {
        val targetName = section.getString("target-directory", "scripts")!!
        val target = File(denizenRoot, targetName).canonicalFile
        require(target.toPath().startsWith(denizenRoot.toPath())) {
            "target-directory профиля $name выходит за пределы каталога Denizen"
        }
        val token = secret(
            section.getString("token"),
            section.getString("token-env", "GITIZEN_GITHUB_TOKEN")
        ).orEmpty()

        return ProfileConfig(
            name = name,
            repoUrl = section.getString("repo-url", "")!!.trim(),
            branch = section.getString("branch", "main")!!.trim(),
            repositorySubdirectory = section.getString("repository-subdirectory", "")!!.trim(),
            targetDirectory = target,
            reloadDenizen = section.getBoolean("reload-denizen", name == "production"),
            username = section.getString("username", "gitizen")!!.ifBlank { "gitizen" },
            token = token,
            requireHttps = requireHttps,
            allowedHosts = allowedHosts
        )
    }

    private fun legacyProfile(
        config: ConfigurationSection,
        denizenRoot: File,
        requireHttps: Boolean,
        allowedHosts: Set<String>
    ) = ProfileConfig(
        name = "production",
        repoUrl = config.getString("repo-url", "")!!.trim(),
        branch = config.getString("branch", "main")!!.trim(),
        repositorySubdirectory = "",
        targetDirectory = File(denizenRoot, "scripts").canonicalFile,
        reloadDenizen = true,
        username = "gitizen",
        token = secret(config.getString("github-token"), "GITIZEN_GITHUB_TOKEN").orEmpty(),
        requireHttps = requireHttps,
        allowedHosts = allowedHosts
    )

    private fun secret(configValue: String?, environmentName: String?): String? {
        if (!environmentName.isNullOrBlank()) {
            System.getenv(environmentName)?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return configValue?.takeIf { it.isNotBlank() }
    }
}
