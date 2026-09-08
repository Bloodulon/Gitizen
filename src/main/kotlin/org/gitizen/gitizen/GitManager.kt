package org.gitizen.gitizen

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.eclipse.jgit.treewalk.EmptyTreeIterator
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID
import kotlin.io.path.exists

class GitManager(private val repositoriesRoot: File) {

    fun prepareSync(profile: ProfileConfig, deployedCommit: String?): PreparationResult {
        val started = System.nanoTime()
        return try {
            openOrClone(profile).use { git ->
                fetch(git, profile)
                val remoteId = git.repository.resolve(remoteRef(profile))
                    ?: return PreparationResult.Failure("Ветка ${profile.branch} не найдена в origin.")
                val previousHead = git.repository.resolve("HEAD")?.name
                val commit = commitInfo(git, remoteId)
                val baseId = deployedCommit?.let { resolveCommit(git, it) }
                val changes = diff(git, baseId, remoteId)

                resetAndClean(git, remoteId)
                if (deployedCommit == remoteId.name &&
                    deploymentMatchesCheckout(profile, repositoryDirectory(profile))
                ) {
                    return PreparationResult.UpToDate(commit)
                }

                val staging = createStagingCopy(profile, repositoryDirectory(profile))
                PreparationResult.Ready(
                    PreparedDeployment(
                        profile = profile,
                        commit = commit,
                        previousRepositoryHead = previousHead,
                        changes = changes,
                        stagingDirectory = staging,
                        kind = DeploymentKind.SYNC,
                        startedAtNanos = started
                    )
                )
            }
        } catch (e: Exception) {
            PreparationResult.Failure(safeError(e), e)
        }
    }

    fun prepareRollback(
        profile: ProfileConfig,
        revision: String,
        deployedCommit: String?
    ): PreparationResult {
        val started = System.nanoTime()
        return try {
            openOrClone(profile).use { git ->
                fetch(git, profile)
                val targetId = resolveCommit(git, revision)
                    ?: return PreparationResult.Failure("Commit '$revision' не найден.")
                val previousHead = git.repository.resolve("HEAD")?.name
                val commit = commitInfo(git, targetId)
                val baseId = deployedCommit?.let { resolveCommit(git, it) }
                val changes = diff(git, baseId, targetId)
                resetAndClean(git, targetId)
                if (deployedCommit == targetId.name &&
                    deploymentMatchesCheckout(profile, repositoryDirectory(profile))
                ) {
                    return PreparationResult.UpToDate(commit)
                }
                val staging = createStagingCopy(profile, repositoryDirectory(profile))
                PreparationResult.Ready(
                    PreparedDeployment(
                        profile = profile,
                        commit = commit,
                        previousRepositoryHead = previousHead,
                        changes = changes,
                        stagingDirectory = staging,
                        kind = DeploymentKind.ROLLBACK,
                        startedAtNanos = started
                    )
                )
            }
        } catch (e: Exception) {
            PreparationResult.Failure(safeError(e), e)
        }
    }

    fun status(
        profile: ProfileConfig,
        lastDeployment: DeploymentRecord?,
        metrics: ProfileMetrics
    ): RepositoryStatus {
        openOrClone(profile).use { git ->
            fetch(git, profile)
            return RepositoryStatus(
                profile = profile.name,
                branch = profile.branch,
                repositoryHead = git.repository.resolve("HEAD")?.name,
                remoteHead = git.repository.resolve(remoteRef(profile))?.name,
                deployedCommit = lastDeployment?.commit,
                lastDeployment = lastDeployment,
                metrics = metrics
            )
        }
    }

    fun recentLogs(profile: ProfileConfig, limit: Int): List<CommitInfo> {
        openOrClone(profile).use { git ->
            fetch(git, profile)
            val remoteId = git.repository.resolve(remoteRef(profile)) ?: return emptyList()
            return git.log()
                .add(remoteId)
                .setMaxCount(limit.coerceIn(1, 20))
                .call()
                .map(::commitInfo)
        }
    }

    fun restoreRepositoryHead(deployment: PreparedDeployment) {
        val previous = deployment.previousRepositoryHead ?: return
        runCatching {
            Git.open(repositoryDirectory(deployment.profile)).use { git ->
                git.reset()
                    .setMode(org.eclipse.jgit.api.ResetCommand.ResetType.HARD)
                    .setRef(previous)
                    .call()
                git.clean().setCleanDirectories(true).setIgnore(false).call()
            }
        }
    }

    fun discard(deployment: PreparedDeployment) {
        deleteRecursively(deployment.stagingDirectory)
        restoreRepositoryHead(deployment)
    }

    private fun openOrClone(profile: ProfileConfig): Git {
        repositoriesRoot.mkdirs()
        val repoDir = repositoryDirectory(profile)
        if (File(repoDir, ".git").isDirectory) {
            val git = Git.open(repoDir)
            val currentOrigin = git.remoteList().call()
                .firstOrNull { it.name == "origin" }
                ?.urIs
                ?.firstOrNull()
                ?.toString()
            if (currentOrigin != profile.repoUrl) {
                git.remoteSetUrl()
                    .setRemoteName("origin")
                    .setRemoteUri(URIish(profile.repoUrl))
                    .call()
            }
            return git
        }

        if (repoDir.exists()) {
            deleteRecursively(repoDir.toPath())
        }
        val temp = File(repositoriesRoot, ".${profile.name}-clone-${UUID.randomUUID()}")
        deleteRecursively(temp.toPath())
        val credentials = credentials(profile)
        try {
            val clone = Git.cloneRepository()
                .setURI(profile.repoUrl)
                .setDirectory(temp)
                .setCloneAllBranches(false)
                .setBranchesToClone(listOf("refs/heads/${profile.branch}"))
                .setBranch("refs/heads/${profile.branch}")
            credentials?.let(clone::setCredentialsProvider)
            clone.call().use { }
            moveDirectory(temp.toPath(), repoDir.toPath())
        } catch (e: Exception) {
            deleteRecursively(temp.toPath())
            throw e
        } finally {
            credentials?.clear()
        }
        return Git.open(repoDir)
    }

    private fun fetch(git: Git, profile: ProfileConfig) {
        val credentials = credentials(profile)
        try {
            val command = git.fetch()
                .setRemote("origin")
                .setRemoveDeletedRefs(true)
                .setRefSpecs(
                    RefSpec("+refs/heads/${profile.branch}:refs/remotes/origin/${profile.branch}")
                )
            credentials?.let(command::setCredentialsProvider)
            command.call()
        } finally {
            credentials?.clear()
        }
    }

    private fun credentials(profile: ProfileConfig): UsernamePasswordCredentialsProvider? {
        if (profile.token.isBlank()) return null
        return UsernamePasswordCredentialsProvider(profile.username, profile.token)
    }

    private fun resetAndClean(git: Git, target: ObjectId) {
        git.reset()
            .setMode(org.eclipse.jgit.api.ResetCommand.ResetType.HARD)
            .setRef(target.name)
            .call()
        git.clean()
            .setCleanDirectories(true)
            .setIgnore(false)
            .call()
    }

    private fun diff(git: Git, oldId: ObjectId?, newId: ObjectId): List<FileChange> {
        git.repository.newObjectReader().use { reader ->
            val newTree = git.repository.parseCommit(newId).tree.id
            val newIterator = CanonicalTreeParser().apply { reset(reader, newTree) }
            val command = git.diff().setNewTree(newIterator)
            if (oldId == null) {
                command.setOldTree(EmptyTreeIterator())
            } else {
                val oldTree = git.repository.parseCommit(oldId).tree.id
                command.setOldTree(CanonicalTreeParser().apply { reset(reader, oldTree) })
            }
            return command.call().map { entry ->
                val type = when (entry.changeType) {
                    DiffEntry.ChangeType.ADD -> ChangeType.ADD
                    DiffEntry.ChangeType.MODIFY -> ChangeType.MODIFY
                    DiffEntry.ChangeType.DELETE -> ChangeType.DELETE
                    DiffEntry.ChangeType.RENAME -> ChangeType.RENAME
                    DiffEntry.ChangeType.COPY -> ChangeType.COPY
                    else -> ChangeType.UNKNOWN
                }
                val path = if (entry.changeType == DiffEntry.ChangeType.DELETE) {
                    entry.oldPath
                } else {
                    entry.newPath
                }
                FileChange(type, path)
            }
        }
    }

    private fun createStagingCopy(profile: ProfileConfig, repoDir: File): Path {
        val source = deploymentSource(profile, repoDir)

        val target = profile.targetDirectory.toPath()
        Files.createDirectories(target.parent)
        val staging = target.parent.resolve(".gitizen-${profile.name}-staging-${UUID.randomUUID()}")
        deleteRecursively(staging)
        Files.createDirectories(staging)

        try {
            Files.walk(source).use { paths ->
                paths.forEach { path ->
                    val relative = source.relativize(path)
                    if (relative.nameCount > 0 && relative.getName(0).toString() == ".git") {
                        return@forEach
                    }
                    require(!Files.isSymbolicLink(path)) {
                        "Символические ссылки запрещены: $relative"
                    }
                    val destination = staging.resolve(relative.toString())
                    if (Files.isDirectory(path)) {
                        Files.createDirectories(destination)
                    } else {
                        Files.createDirectories(destination.parent)
                        Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING)
                    }
                }
            }
            return staging
        } catch (e: Exception) {
            deleteRecursively(staging)
            throw e
        }
    }

    private fun deploymentMatchesCheckout(profile: ProfileConfig, repoDir: File): Boolean {
        val target = profile.targetDirectory.toPath()
        if (!Files.isDirectory(target)) return false
        val source = deploymentSource(profile, repoDir)
        if (containsSymlink(source) || containsSymlink(target)) return false

        val sourceFiles = relativeFiles(source, excludeGit = true)
        val targetFiles = relativeFiles(target, excludeGit = false)
        if (sourceFiles.keys != targetFiles.keys) return false
        return sourceFiles.all { (relative, sourceFile) ->
            val targetFile = targetFiles.getValue(relative)
            Files.size(sourceFile) == Files.size(targetFile) &&
                Files.mismatch(sourceFile, targetFile) == -1L
        }
    }

    private fun deploymentSource(profile: ProfileConfig, repoDir: File): Path {
        val repoRoot = repoDir.canonicalFile.toPath()
        val source = if (profile.repositorySubdirectory.isBlank()) {
            repoRoot
        } else {
            repoRoot.resolve(profile.repositorySubdirectory).normalize()
        }
        require(source.startsWith(repoRoot)) {
            "repository-subdirectory выходит за пределы репозитория."
        }
        require(Files.isDirectory(source)) {
            "Подкаталог репозитория не найден: ${profile.repositorySubdirectory}"
        }
        return source
    }

    private fun relativeFiles(root: Path, excludeGit: Boolean): Map<String, Path> {
        val files = linkedMapOf<String, Path>()
        Files.walk(root).use { paths ->
            paths.filter(Files::isRegularFile).forEach { file ->
                val relative = root.relativize(file)
                if (!excludeGit ||
                    relative.nameCount == 0 ||
                    relative.getName(0).toString() != ".git"
                ) {
                    files[relative.toString().replace('\\', '/')] = file
                }
            }
        }
        return files
    }

    private fun containsSymlink(root: Path): Boolean {
        Files.walk(root).use { paths ->
            return paths.anyMatch(Files::isSymbolicLink)
        }
    }

    private fun commitInfo(git: Git, id: ObjectId): CommitInfo {
        return commitInfo(git.repository.parseCommit(id))
    }

    private fun commitInfo(commit: RevCommit): CommitInfo = CommitInfo(
        hash = commit.name,
        author = commit.authorIdent.name,
        message = commit.shortMessage,
        committedAt = Instant.ofEpochSecond(commit.commitTime.toLong())
    )

    private fun resolveCommit(git: Git, revision: String): ObjectId? {
        return runCatching { git.repository.resolve("${revision}^{commit}") }.getOrNull()
    }

    private fun repositoryDirectory(profile: ProfileConfig): File {
        val root = repositoriesRoot.canonicalFile
        val directory = File(root, profile.name).canonicalFile
        require(directory.toPath().startsWith(root.toPath())) {
            "Некорректное имя профиля."
        }
        return directory
    }

    private fun remoteRef(profile: ProfileConfig) = "refs/remotes/origin/${profile.branch}"

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

    private fun safeError(error: Throwable): String {
        return error.message
            ?.replace(Regex("https://[^\\s@]+@"), "https://***@")
            ?.take(300)
            ?: error.javaClass.simpleName
    }
}
