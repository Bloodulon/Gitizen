package org.gitizen.gitizen

import org.eclipse.jgit.api.Git
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class GitManagerTest {
    @TempDir
    lateinit var temp: Path

    @Test
    fun `configured branch is deployed and remote changes are detected`() {
        val remote = createRepository("remote", "main", "main version")
        remote.use { git ->
            git.branchCreate().setName("staging").call()
            git.checkout().setName("staging").call()
            writeAndCommit(git, "staging version", "staging commit")

            val manager = GitManager(temp.resolve("repositories").toFile())
            val profile = profile("test", git.repository.workTree.toURI().toString(), "staging")
            val first = manager.prepareSync(profile, null) as PreparationResult.Ready
            assertEquals(
                "staging version",
                Files.readString(first.deployment.stagingDirectory.resolve("example.dsc"))
            )
            val firstHash = first.deployment.commit.hash
            manager.discard(first.deployment)

            Files.createDirectories(profile.targetDirectory.toPath())
            writeAndCommit(git, "staging version 2", "second staging commit")
            val second = manager.prepareSync(profile, firstHash) as PreparationResult.Ready
            assertEquals(
                "staging version 2",
                Files.readString(second.deployment.stagingDirectory.resolve("example.dsc"))
            )
            assertTrue(second.deployment.changes.any { it.type == ChangeType.MODIFY })
            manager.discard(second.deployment)
        }
    }

    @Test
    fun `changing profile url updates origin instead of using old repository`() {
        createRepository("remote-one", "main", "one").use { firstRemote ->
            createRepository("remote-two", "main", "two").use { secondRemote ->
                val manager = GitManager(temp.resolve("repositories").toFile())
                val firstProfile = profile(
                    "same-profile",
                    firstRemote.repository.workTree.toURI().toString(),
                    "main"
                )
                val first = manager.prepareSync(firstProfile, null) as PreparationResult.Ready
                manager.discard(first.deployment)

                val secondProfile = firstProfile.copy(
                    repoUrl = secondRemote.repository.workTree.toURI().toString()
                )
                val second = manager.prepareSync(secondProfile, null) as PreparationResult.Ready
                assertEquals(
                    "two",
                    Files.readString(second.deployment.stagingDirectory.resolve("example.dsc"))
                )
                manager.discard(second.deployment)
            }
        }
    }

    @Test
    fun `repository subdirectory cannot escape checkout`() {
        createRepository("remote", "main", "content").use { remote ->
            val manager = GitManager(temp.resolve("repositories").toFile())
            val result = manager.prepareSync(
                profile("test", remote.repository.workTree.toURI().toString(), "main")
                    .copy(repositorySubdirectory = "../outside"),
                null
            )
            assertTrue(result is PreparationResult.Failure)
        }
    }

    @Test
    fun `manual target drift causes redeployment even when commit is unchanged`() {
        createRepository("remote", "main", "expected").use { remote ->
            val manager = GitManager(temp.resolve("repositories").toFile())
            val profile = profile("drift", remote.repository.workTree.toURI().toString(), "main")
            val first = manager.prepareSync(profile, null) as PreparationResult.Ready
            val commit = first.deployment.commit.hash
            manager.discard(first.deployment)

            Files.createDirectories(profile.targetDirectory.toPath())
            Files.writeString(profile.targetDirectory.toPath().resolve("example.dsc"), "manually changed")

            val second = manager.prepareSync(profile, commit)
            assertTrue(second is PreparationResult.Ready)
            second as PreparationResult.Ready
            assertEquals(
                "expected",
                Files.readString(second.deployment.stagingDirectory.resolve("example.dsc"))
            )
            manager.discard(second.deployment)
        }
    }

    private fun createRepository(name: String, branch: String, content: String): Git {
        val directory = temp.resolve(name)
        Files.createDirectories(directory)
        val git = Git.init()
            .setDirectory(directory.toFile())
            .setInitialBranch(branch)
            .call()
        writeAndCommit(git, content, "initial")
        return git
    }

    private fun writeAndCommit(git: Git, content: String, message: String) {
        Files.writeString(git.repository.workTree.toPath().resolve("example.dsc"), content)
        git.add().addFilepattern(".").call()
        git.commit()
            .setMessage(message)
            .setAuthor("Gitizen Test", "gitizen@example.invalid")
            .setCommitter("Gitizen Test", "gitizen@example.invalid")
            .call()
    }

    private fun profile(name: String, repoUrl: String, branch: String) = ProfileConfig(
        name = name,
        repoUrl = repoUrl,
        branch = branch,
        repositorySubdirectory = "",
        targetDirectory = temp.resolve("targets").resolve(name).toFile(),
        reloadDenizen = false,
        username = "gitizen",
        token = "",
        requireHttps = false,
        allowedHosts = emptySet()
    )
}
