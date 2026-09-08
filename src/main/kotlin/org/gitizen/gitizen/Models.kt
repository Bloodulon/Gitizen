package org.gitizen.gitizen

import java.io.File
import java.nio.file.Path
import java.time.Instant

data class ProfileConfig(
    val name: String,
    val repoUrl: String,
    val branch: String,
    val repositorySubdirectory: String,
    val targetDirectory: File,
    val reloadDenizen: Boolean,
    val username: String,
    val token: String,
    val requireHttps: Boolean,
    val allowedHosts: Set<String>
)

data class NotificationConfig(
    val discordWebhook: String?,
    val telegramBotToken: String?,
    val telegramChatId: String?
)

data class AppConfig(
    val defaultProfile: String,
    val profiles: Map<String, ProfileConfig>,
    val notifications: NotificationConfig,
    val maxHistory: Int,
    val maxChangesInChat: Int,
    val requireDscFiles: Boolean,
    val maxScriptSizeBytes: Long
)

enum class ChangeType {
    ADD, MODIFY, DELETE, RENAME, COPY, UNKNOWN
}

data class FileChange(val type: ChangeType, val path: String)

data class CommitInfo(
    val hash: String,
    val author: String,
    val message: String,
    val committedAt: Instant
) {
    val shortHash: String
        get() = hash.take(7)
}

enum class DeploymentKind {
    SYNC, ROLLBACK
}

data class PreparedDeployment(
    val profile: ProfileConfig,
    val commit: CommitInfo,
    val previousRepositoryHead: String?,
    val changes: List<FileChange>,
    val stagingDirectory: Path,
    val kind: DeploymentKind,
    val startedAtNanos: Long
)

sealed interface PreparationResult {
    data class Ready(val deployment: PreparedDeployment) : PreparationResult
    data class UpToDate(val commit: CommitInfo) : PreparationResult
    data class Failure(val message: String, val cause: Throwable? = null) : PreparationResult
}

data class DeploymentRecord(
    val commit: String,
    val author: String,
    val message: String,
    val deployedAtMillis: Long,
    val durationMillis: Long,
    val changedFiles: Int,
    val kind: DeploymentKind
)

data class ProfileMetrics(
    val successfulDeployments: Long = 0,
    val failedDeployments: Long = 0,
    val lastDurationMillis: Long? = null,
    val lastChangedFiles: Int? = null,
    val lastSuccessfulAtMillis: Long? = null,
    val lastSuccessfulCommit: String? = null
)

data class RepositoryStatus(
    val profile: String,
    val branch: String,
    val repositoryHead: String?,
    val remoteHead: String?,
    val deployedCommit: String?,
    val lastDeployment: DeploymentRecord?,
    val metrics: ProfileMetrics
)

data class DeploymentResult(
    val success: Boolean,
    val profile: String,
    val commit: CommitInfo?,
    val message: String,
    val changes: List<FileChange> = emptyList(),
    val durationMillis: Long = 0,
    val automaticallyRolledBack: Boolean = false
)
