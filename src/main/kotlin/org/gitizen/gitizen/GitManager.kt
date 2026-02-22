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

    fun getRecentLogs(limit: Int = 3): List<String> {
        val logs = mutableListOf<String>()
        try {
            org.eclipse.jgit.api.Git.open(scriptsDir).use { git ->
                val history = git.log().setMaxCount(limit).call()

                for (commit in history) {
                    val author = commit.authorIdent.name
                    val message = commit.shortMessage
                    val hash = commit.name.substring(0, 7)

                    logs.add("§8[§7$hash§8] §f$message §7($author)")

                    val repository = git.repository
                    val reader = repository.newObjectReader()

                    val oldTreeIter = org.eclipse.jgit.treewalk.EmptyTreeIterator()
                    val newTreeIter = org.eclipse.jgit.treewalk.CanonicalTreeParser()
                    newTreeIter.reset(reader, commit.tree)

                    val diffCommand = git.diff().setNewTree(newTreeIter)

                    if (commit.parentCount > 0) {
                        val parent = repository.parseCommit(commit.getParent(0).id)
                        val parentTreeIter = org.eclipse.jgit.treewalk.CanonicalTreeParser()
                        parentTreeIter.reset(reader, parent.tree)
                        diffCommand.setOldTree(parentTreeIter)
                    } else {
                        diffCommand.setOldTree(oldTreeIter)
                    }

                    diffCommand.call().forEach { diff ->
                        val path = if (diff.changeType == org.eclipse.jgit.diff.DiffEntry.ChangeType.DELETE) diff.oldPath else diff.newPath
                        val prefix = when (diff.changeType) {
                            org.eclipse.jgit.diff.DiffEntry.ChangeType.ADD -> "§a+"
                            org.eclipse.jgit.diff.DiffEntry.ChangeType.MODIFY -> "§e*"
                            org.eclipse.jgit.diff.DiffEntry.ChangeType.DELETE -> "§c-"
                            else -> "§7?"
                        }
                        logs.add("  §8$prefix §7$path")
                    }
                }
            }
        } catch (e: Exception) {
            logs.add("§cОшибка при чтении логов: ${e.message}")
        }
        return logs
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

}