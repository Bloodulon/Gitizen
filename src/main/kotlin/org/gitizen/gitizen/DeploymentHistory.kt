package org.gitizen.gitizen

import org.bukkit.configuration.file.YamlConfiguration
import java.io.File

class DeploymentHistory(
    dataFolder: File,
    private var maxHistory: Int
) {
    private val file = File(dataFolder, "state.yml")
    private val yaml = YamlConfiguration.loadConfiguration(file)

    @Synchronized
    fun updateLimit(newLimit: Int) {
        maxHistory = newLimit
    }

    @Synchronized
    fun records(profile: String): List<DeploymentRecord> {
        return yaml.getMapList("profiles.$profile.history").mapNotNull(::mapToRecord)
    }

    @Synchronized
    fun last(profile: String): DeploymentRecord? = records(profile).firstOrNull()

    @Synchronized
    fun rollbackTarget(profile: String, number: Int): String? {
        if (number < 1) return null
        return records(profile).drop(1).getOrNull(number - 1)?.commit
    }

    @Synchronized
    fun metrics(profile: String): ProfileMetrics {
        val path = "profiles.$profile.metrics"
        return ProfileMetrics(
            successfulDeployments = yaml.getLong("$path.successful-deployments", 0),
            failedDeployments = yaml.getLong("$path.failed-deployments", 0),
            lastDurationMillis = yaml.get("$path.last-duration-ms")?.let { yaml.getLong("$path.last-duration-ms") },
            lastChangedFiles = yaml.get("$path.last-changed-files")?.let { yaml.getInt("$path.last-changed-files") },
            lastSuccessfulAtMillis = yaml.get("$path.last-successful-at")?.let { yaml.getLong("$path.last-successful-at") },
            lastSuccessfulCommit = yaml.getString("$path.last-successful-commit")
        )
    }

    @Synchronized
    fun recordSuccess(profile: String, record: DeploymentRecord) {
        val history = records(profile).toMutableList()
        history.add(0, record)
        val serialized = history.take(maxHistory).map(::recordToMap)
        yaml.set("profiles.$profile.history", serialized)

        val old = metrics(profile)
        val path = "profiles.$profile.metrics"
        yaml.set("$path.successful-deployments", old.successfulDeployments + 1)
        yaml.set("$path.failed-deployments", old.failedDeployments)
        yaml.set("$path.last-duration-ms", record.durationMillis)
        yaml.set("$path.last-changed-files", record.changedFiles)
        yaml.set("$path.last-successful-at", record.deployedAtMillis)
        yaml.set("$path.last-successful-commit", record.commit)
        save()
    }

    @Synchronized
    fun recordFailure(profile: String, durationMillis: Long) {
        val old = metrics(profile)
        val path = "profiles.$profile.metrics"
        yaml.set("$path.successful-deployments", old.successfulDeployments)
        yaml.set("$path.failed-deployments", old.failedDeployments + 1)
        yaml.set("$path.last-duration-ms", durationMillis)
        save()
    }

    private fun recordToMap(record: DeploymentRecord): Map<String, Any> = linkedMapOf(
        "commit" to record.commit,
        "author" to record.author,
        "message" to record.message,
        "deployed-at" to record.deployedAtMillis,
        "duration-ms" to record.durationMillis,
        "changed-files" to record.changedFiles,
        "kind" to record.kind.name
    )

    private fun mapToRecord(map: Map<*, *>): DeploymentRecord? {
        val commit = map["commit"]?.toString() ?: return null
        return DeploymentRecord(
            commit = commit,
            author = map["author"]?.toString().orEmpty(),
            message = map["message"]?.toString().orEmpty(),
            deployedAtMillis = (map["deployed-at"] as? Number)?.toLong() ?: 0,
            durationMillis = (map["duration-ms"] as? Number)?.toLong() ?: 0,
            changedFiles = (map["changed-files"] as? Number)?.toInt() ?: 0,
            kind = runCatching {
                DeploymentKind.valueOf(map["kind"]?.toString() ?: DeploymentKind.SYNC.name)
            }.getOrDefault(DeploymentKind.SYNC)
        )
    }

    private fun save() {
        file.parentFile.mkdirs()
        yaml.save(file)
    }
}
