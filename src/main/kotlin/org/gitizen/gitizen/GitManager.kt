package org.gitizen.gitizen

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import java.io.File
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.treewalk.CanonicalTreeParser

class GitManager(
    private val repoUrl: String,
    token: String,
    private val scriptsDir: File
) {
    private val credentials = UsernamePasswordCredentialsProvider(token, "")

    fun sync(): List<String> {
        return try {
            if (!isRepoInitialized()) {
                cloneRepo()
                listOf("§a[Gitizen] Репозиторий впервые клонирован. Все скрипты загружены.")
            } else {
                val changes = pullAndGetChanges()
                if (changes.isEmpty()) {
                    listOf("§e[Gitizen] Скрипты уже актуальны. Новых коммитов нет.")
                } else {
                    val msg = mutableListOf("§a[Gitizen] Синхронизация успешна! Изменения:")
                    msg.addAll(changes)
                    msg
                }
            }
        } catch (e: Exception) {
            listOf("§c[Gitizen] Ошибка синхронизации: ${e.message}")
        }
    }

    private fun pullAndGetChanges(): List<String> {
        Git.open(scriptsDir).use { git ->
            val oldHead = git.repository.resolve("HEAD")
            git.fetch()
                .setCredentialsProvider(credentials)
                .setRemote("origin")
                .call()

            git.reset()
                .setMode(org.eclipse.jgit.api.ResetCommand.ResetType.HARD)
                .setRef("origin/main")
                .call()

            val newHead = git.repository.resolve("HEAD")

            if (oldHead == newHead) return emptyList()

            val changes = mutableListOf<String>()
            val reader = git.repository.newObjectReader()

            val oldTreeIter = CanonicalTreeParser()
            if (oldHead != null) {
                val oldTree = git.repository.parseCommit(oldHead).tree.id
                oldTreeIter.reset(reader, oldTree)
            }

            val newTreeIter = CanonicalTreeParser()
            val newTree = git.repository.parseCommit(newHead).tree.id
            newTreeIter.reset(reader, newTree)

            git.diff()
                .setNewTree(newTreeIter)
                .setOldTree(oldTreeIter)
                .call()
                .forEach { diff ->
                    // Красиво форматируем вывод
                    val type = when (diff.changeType) {
                        DiffEntry.ChangeType.ADD -> "§a[+]§f"     // Добавлен
                        DiffEntry.ChangeType.MODIFY -> "§e[*]§f"  // Изменен
                        DiffEntry.ChangeType.DELETE -> "§c[-]§f"  // Удален
                        else -> "§7[?]§f"
                    }
                    val path = if (diff.changeType == DiffEntry.ChangeType.DELETE) diff.oldPath else diff.newPath
                    changes.add("  $type $path")
                }

            return changes
        }
    }

    private fun isRepoInitialized(): Boolean {
        return File(scriptsDir, ".git").exists()
    }

    private fun cloneRepo() {
        Git.cloneRepository()
            .setURI(repoUrl)
            .setDirectory(scriptsDir)
            .setCredentialsProvider(credentials)
            .setCloneAllBranches(false)
            .setBranch("main")
            .call()
            .use { it.close() }
    }

    private fun pullRepo() {
        Git.open(scriptsDir).use { git ->
            val pullResult = git.pull()
                .setCredentialsProvider(credentials)
                .setRemote("origin")
                .setRebase(true)
                .call()
            if (!pullResult.isSuccessful) {
                git.reset()
                    .setMode(org.eclipse.jgit.api.ResetCommand.ResetType.HARD)
                    .setRef("origin/main")
                    .call()
            }
        }
    }
}