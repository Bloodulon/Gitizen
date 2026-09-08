package org.gitizen.gitizen

import org.bukkit.Server
import org.bukkit.plugin.Plugin
import java.lang.reflect.InvocationTargetException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile

data class ValidationReport(
    val valid: Boolean,
    val checkedFiles: Int,
    val errors: List<String>
)

class DenizenValidator(private val server: Server) {
    private val denizen: Plugin? = server.pluginManager.getPlugin("Denizen")

    fun validate(directory: Path, requireDscFiles: Boolean, maxFileSizeBytes: Long): ValidationReport {
        val denizen = denizen
            ?: return ValidationReport(false, 0, listOf("Плагин Denizen не установлен или не запущен."))
        val loader = denizen.javaClass.classLoader
        val errors = mutableListOf<String>()
        var checked = 0

        val scriptHelper = try {
            Class.forName("com.denizenscript.denizencore.scripts.ScriptHelper", true, loader)
        } catch (e: Exception) {
            return ValidationReport(false, 0, listOf("API Denizen ScriptHelper недоступен."))
        }
        val yamlClass = try {
            Class.forName("com.denizenscript.denizencore.utilities.YamlConfiguration", true, loader)
        } catch (e: Exception) {
            return ValidationReport(false, 0, listOf("API Denizen YamlConfiguration недоступен."))
        }
        val clearComments = scriptHelper.getMethod(
            "clearComments",
            String::class.java,
            String::class.java,
            Boolean::class.javaPrimitiveType
        )
        val loadYaml = yamlClass.getMethod("load", String::class.java)
        val saveYaml = yamlClass.getMethod("saveToString", Boolean::class.javaPrimitiveType)

        Files.walk(directory).use { paths ->
            paths.filter { it.isRegularFile() && it.extension.equals("dsc", ignoreCase = true) }
                .forEach { file ->
                    checked++
                    val relative = directory.relativize(file).toString()
                    try {
                        require(file.fileSize() <= maxFileSizeBytes) {
                            "файл превышает лимит ${maxFileSizeBytes / 1024} КБ"
                        }
                        val source = Files.readString(file, StandardCharsets.UTF_8)
                        require(source.isNotBlank()) { "пустой файл" }
                        val cleaned = clearComments.invoke(null, relative, source, false) as String
                        val parsed = loadYaml.invoke(null, cleaned)
                        val serialized = saveYaml.invoke(parsed, false) as? String
                        require(!serialized.isNullOrBlank()) { "не найдено ни одного script container" }
                    } catch (e: Exception) {
                        val cause = unwrap(e)
                        errors += "$relative: ${cause.message ?: cause.javaClass.simpleName}".take(300)
                    }
                }
        }

        if (requireDscFiles && checked == 0) {
            errors += "В deployment нет ни одного файла .dsc."
        }
        return ValidationReport(errors.isEmpty(), checked, errors.take(20))
    }

    fun reloadAndCheck(): ValidationReport {
        check(server.isPrimaryThread) { "Перезагрузка Denizen должна выполняться в основном потоке." }
        val denizen = denizen
            ?: return ValidationReport(false, 0, listOf("Плагин Denizen недоступен."))
        return try {
            val loader = denizen.javaClass.classLoader
            val core = Class.forName("com.denizenscript.denizencore.DenizenCore", true, loader)
            val helper = Class.forName(
                "com.denizenscript.denizencore.scripts.ScriptHelper",
                true,
                loader
            )
            var completed = false
            val callback = Runnable { completed = true }
            core.getMethod(
                "reloadScripts",
                Boolean::class.javaPrimitiveType,
                Runnable::class.java
            ).invoke(null, false, callback)
            val hadError = helper.getMethod("hadError").invoke(null) as Boolean
            when {
                !completed -> ValidationReport(false, 0, listOf("Denizen не завершил reload синхронно."))
                hadError -> ValidationReport(false, 0, listOf("Denizen обнаружил ошибки при загрузке скриптов."))
                else -> ValidationReport(true, 0, emptyList())
            }
        } catch (e: Exception) {
            val cause = unwrap(e)
            ValidationReport(
                false,
                0,
                listOf("Ошибка API Denizen: ${cause.message ?: cause.javaClass.simpleName}".take(300))
            )
        }
    }

    fun activeScriptDirectory(): Path? {
        val denizen = denizen ?: return null
        return runCatching {
            val core = Class.forName(
                "com.denizenscript.denizencore.DenizenCore",
                true,
                denizen.javaClass.classLoader
            )
            val implementation = core.getField("implementation").get(null)
            val directory = implementation.javaClass
                .getMethod("getScriptFolder")
                .invoke(implementation) as java.io.File
            directory.canonicalFile.toPath()
        }.getOrNull()
    }

    private fun unwrap(error: Throwable): Throwable {
        return if (error is InvocationTargetException && error.targetException != null) {
            error.targetException
        } else {
            error
        }
    }
}
