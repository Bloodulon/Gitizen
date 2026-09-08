package org.gitizen.gitizen

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.CommandSender
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object Messages {
    private val timeFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss")
        .withZone(ZoneId.systemDefault())

    fun info(sender: CommandSender, text: String) = sender.sendMessage(
        prefix().append(Component.text(text, NamedTextColor.YELLOW))
    )

    fun success(sender: CommandSender, text: String) = sender.sendMessage(
        prefix().append(Component.text(text, NamedTextColor.GREEN))
    )

    fun error(sender: CommandSender, text: String) = sender.sendMessage(
        prefix().append(Component.text(text, NamedTextColor.RED))
    )

    fun commit(sender: CommandSender, commit: CommitInfo, repoUrl: String? = null) {
        val hash = Component.text(commit.shortHash, NamedTextColor.AQUA)
            .clickEvent(ClickEvent.copyToClipboard(commit.hash))
            .hoverEvent(
                HoverEvent.showText(
                    Component.text("Скопировать полный hash\n${commit.hash}", NamedTextColor.GRAY)
                )
            )
        val message = Component.text(" ${clean(commit.message)}", NamedTextColor.WHITE)
            .hoverEvent(
                HoverEvent.showText(
                    Component.text(
                        "Автор: ${clean(commit.author)}\nВремя: ${formatTime(commit.committedAt)}",
                        NamedTextColor.GRAY
                    )
                )
            )
        var line = Component.text("  ").append(hash).append(message)
        commitUrl(repoUrl, commit.hash)?.let { url ->
            line = line.clickEvent(ClickEvent.openUrl(url))
                .hoverEvent(HoverEvent.showText(Component.text("Открыть commit на GitHub")))
        }
        sender.sendMessage(line)
    }

    fun deployment(sender: CommandSender, result: DeploymentResult, maxChanges: Int) {
        if (result.success) {
            success(sender, "${result.message} Профиль: ${result.profile}, ${result.durationMillis} мс.")
        } else {
            error(sender, result.message)
            if (result.automaticallyRolledBack) {
                info(sender, "Предыдущая версия восстановлена автоматически.")
            }
        }
        result.commit?.let { commit(sender, it) }
        result.changes.take(maxChanges).forEach { change ->
            val color = when (change.type) {
                ChangeType.ADD -> NamedTextColor.GREEN
                ChangeType.MODIFY -> NamedTextColor.YELLOW
                ChangeType.DELETE -> NamedTextColor.RED
                ChangeType.RENAME, ChangeType.COPY -> NamedTextColor.AQUA
                ChangeType.UNKNOWN -> NamedTextColor.GRAY
            }
            sender.sendMessage(
                Component.text("  [${change.type.name}] ", color)
                    .append(Component.text(clean(change.path), NamedTextColor.WHITE))
                    .hoverEvent(HoverEvent.showText(Component.text("Изменённый файл")))
            )
        }
        if (result.changes.size > maxChanges) {
            info(sender, "Ещё ${result.changes.size - maxChanges} изменений скрыто.")
        }
    }

    fun formatTime(epochMillis: Long?): String {
        return epochMillis?.let { timeFormat.format(Instant.ofEpochMilli(it)) } ?: "никогда"
    }

    private fun formatTime(instant: Instant): String = timeFormat.format(instant)

    private fun prefix(): Component = Component.text("[Gitizen] ", NamedTextColor.GOLD)

    private fun clean(value: String): String {
        return value.replace(Regex("[\\p{Cntrl}&&[^\\n\\t]]"), "").take(300)
    }

    private fun commitUrl(repoUrl: String?, hash: String): String? {
        val url = repoUrl?.takeIf { it.startsWith("https://github.com/") } ?: return null
        return url.removeSuffix(".git").removeSuffix("/") + "/commit/" + hash
    }
}
