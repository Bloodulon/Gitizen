package org.gitizen.gitizen

import org.bukkit.plugin.java.JavaPlugin
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Comparator
import java.util.UUID
import kotlin.io.path.exists

class DeploymentService(
    private val plugin: JavaPlugin,
    private val gitManager: GitManager,
    private val history: DeploymentHistory,
    private val validator: DenizenValidator
) {
    fun preflight(
        deployment: PreparedDeployment,
        config: AppConfig
    ): ValidationReport {
        if (deployment.profile.reloadDenizen) {
            val activeDirectory = validator.activeScriptDirectory()
                ?: return ValidationReport(
                    false,
                    0,
                    listOf("Не удалось определить активный каталог скриптов Denizen.")
                )
            if (activeDirectory != deployment.profile.targetDirectory.canonicalFile.toPath()) {
                return ValidationReport(
                    false,
                    0,
                    listOf(
                        "target-directory не совпадает с активным каталогом Denizen: $activeDirectory"
                    )
                )
            }
        }
        return validator.validate(
            deployment.stagingDirectory,
            config.requireDscFiles,
            config.maxScriptSizeBytes
        )
    }

    fun reject(deployment: PreparedDeployment, report: ValidationReport): DeploymentResult {
        val duration = elapsedMillis(deployment)
        gitManager.discard(deployment)
        history.recordFailure(deployment.profile.name, duration)
        return DeploymentResult(
            success = false,
            profile = deployment.profile.name,
            commit = deployment.commit,
            message = "Проверка скриптов не пройдена: ${report.errors.joinToString("; ")}",
            changes = deployment.changes,
            durationMillis = duration
        )
    }

    fun activate(deployment: PreparedDeployment): DeploymentResult {
        check(plugin.server.isPrimaryThread) { "Активация должна выполняться в основном потоке." }
        val target = deployment.profile.targetDirectory.toPath()
        val previous = target.parent.resolve(
            ".gitizen-${deployment.profile.name}-previous-${UUID.randomUUID()}"
        )
        var previousMoved = false
        var newMoved = false

        try {
            if (target.exists()) {
                moveDirectory(target, previous)
                previousMoved = true
            }
            moveDirectory(deployment.stagingDirectory, target)
            newMoved = true

            if (deployment.profile.reloadDenizen) {
                val denizenResult = validator.reloadAndCheck()
                if (!denizenResult.valid) {
                    rollbackFilesystem(target, previous, previousMoved)
                    validator.reloadAndCheck()
                    gitManager.restoreRepositoryHead(deployment)
                    val duration = elapsedMillis(deployment)
                    history.recordFailure(deployment.profile.name, duration)
                    return DeploymentResult(
                        success = false,
                        profile = deployment.profile.name,
                        commit = deployment.commit,
                        message = denizenResult.errors.joinToString("; "),
                        changes = deployment.changes,
                        durationMillis = duration,
                        automaticallyRolledBack = true
                    )
                }
            }

            val duration = elapsedMillis(deployment)
            history.recordSuccess(
                deployment.profile.name,
                DeploymentRecord(
                    commit = deployment.commit.hash,
                    author = deployment.commit.author,
                    message = deployment.commit.message,
                    deployedAtMillis = System.currentTimeMillis(),
                    durationMillis = duration,
                    changedFiles = deployment.changes.size,
                    kind = deployment.kind
                )
            )
            if (previousMoved) {
                plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
                    runCatching { deleteRecursively(previous) }
                        .onFailure { plugin.logger.warning("Не удалось удалить временную копию: ${it.message}") }
                })
            }
            return DeploymentResult(
                success = true,
                profile = deployment.profile.name,
                commit = deployment.commit,
                message = if (deployment.kind == DeploymentKind.ROLLBACK) {
                    "Rollback успешно применён."
                } else {
                    "Deployment успешно применён."
                },
                changes = deployment.changes,
                durationMillis = duration
            )
        } catch (e: Exception) {
            if (newMoved || previousMoved) {
                runCatching { rollbackFilesystem(target, previous, previousMoved) }
            }
            gitManager.restoreRepositoryHead(deployment)
            val duration = elapsedMillis(deployment)
            history.recordFailure(deployment.profile.name, duration)
            plugin.logger.severe("Ошибка deployment профиля ${deployment.profile.name}: ${e.message}")
            return DeploymentResult(
                success = false,
                profile = deployment.profile.name,
                commit = deployment.commit,
                message = "Не удалось активировать deployment: ${e.message ?: e.javaClass.simpleName}",
                changes = deployment.changes,
                durationMillis = duration,
                automaticallyRolledBack = previousMoved
            )
        }
    }

    private fun rollbackFilesystem(target: Path, previous: Path, previousMoved: Boolean) {
        deleteRecursively(target)
        if (previousMoved && previous.exists()) {
            moveDirectory(previous, target)
        }
    }

    private fun moveDirectory(from: Path, to: Path) {
        runCatching {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE)
        }.getOrElse {
            Files.move(from, to)
        }
    }

    private fun deleteRecursively(path: Path) {
        if (!path.exists()) return
        Files.walk(path).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    private fun elapsedMillis(deployment: PreparedDeployment): Long {
        return (System.nanoTime() - deployment.startedAtNanos) / 1_000_000
    }
}
