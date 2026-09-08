package org.gitizen.gitizen

import org.bukkit.plugin.java.JavaPlugin
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

class NotificationService(private val plugin: JavaPlugin) {
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    fun send(result: DeploymentResult, config: NotificationConfig) {
        val text = buildMessage(result)
        config.discordWebhook?.let { webhook ->
            runCatching { sendDiscord(webhook, text) }
                .onFailure { plugin.logger.warning("Discord-уведомление не отправлено: ${it.message}") }
        }
        if (config.telegramBotToken != null && config.telegramChatId != null) {
            runCatching {
                sendTelegram(config.telegramBotToken, config.telegramChatId, text)
            }.onFailure {
                plugin.logger.warning("Telegram-уведомление не отправлено: ${it.message}")
            }
        }
    }

    private fun buildMessage(result: DeploymentResult): String {
        val icon = if (result.success) "✅" else "❌"
        val rollback = if (result.automaticallyRolledBack) " Автоматический rollback выполнен." else ""
        return buildString {
            append(icon).append(" Gitizen [").append(result.profile).append("]: ")
            append(result.message)
            result.commit?.let {
                append("\nCommit: ").append(it.shortHash)
                append(" — ").append(sanitize(it.message))
                append(" (").append(sanitize(it.author)).append(')')
            }
            append("\nИзменений: ").append(result.changes.size)
            append(", время: ").append(result.durationMillis).append(" мс.")
            append(rollback)
        }.take(1900)
    }

    private fun sendDiscord(webhook: String, text: String) {
        requireHttps(webhook)
        val body = "{\"content\":\"${jsonEscape(text)}\",\"allowed_mentions\":{\"parse\":[]}}"
        send(
            HttpRequest.newBuilder(URI(webhook))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
        )
    }

    private fun sendTelegram(token: String, chatId: String, text: String) {
        val url = "https://api.telegram.org/bot$token/sendMessage"
        val body = form("chat_id", chatId) + "&" + form("text", text)
        send(
            HttpRequest.newBuilder(URI(url))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
        )
    }

    private fun send(request: HttpRequest) {
        val response = client.send(request, HttpResponse.BodyHandlers.discarding())
        check(response.statusCode() in 200..299) {
            "HTTP ${response.statusCode()}"
        }
    }

    private fun requireHttps(url: String) {
        require(URI(url).scheme.equals("https", ignoreCase = true)) {
            "Webhook должен использовать HTTPS."
        }
    }

    private fun form(name: String, value: String): String {
        val charset = StandardCharsets.UTF_8
        return URLEncoder.encode(name, charset) + "=" + URLEncoder.encode(value, charset)
    }

    private fun jsonEscape(value: String): String = buildString(value.length + 16) {
        value.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char.code < 0x20) {
                    append("\\u").append(char.code.toString(16).padStart(4, '0'))
                } else {
                    append(char)
                }
            }
        }
    }

    private fun sanitize(value: String): String {
        return value.replace(Regex("[\\p{Cntrl}&&[^\\n\\t]]"), "").take(200)
    }
}
