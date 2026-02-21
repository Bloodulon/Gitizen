package org.gitizen.gitizen

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import java.io.File

class GitManager(
    private val repoUrl: String,
    private val token: String,
    private val scriptsDir: File
) {
    private val credentials = UsernamePasswordCredentialsProvider(token, "")

    fun sync(): String {
        return try {
            if (!isRepoInitialized()) {
                cloneRepo()
                "Репозиторий успешно клонирован."
            } else {
                pullRepo()
                "Файлы успешно обновлены через pull."
            }
        } catch (e: Exception) {
            "Ошибка Git: ${e.message}"
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
            git.pull()
                .setCredentialsProvider(credentials)
                .setRemote("origin")
                .call()
        }
    }
}