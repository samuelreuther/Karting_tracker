package com.kartingtracker.data

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.gson.JsonParser
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class AppBackupManager(
    private val context: Context
) {
    private val appContext = context.applicationContext
    private val prefsDirectory = File(appContext.applicationInfo.dataDir, "shared_prefs")
    private val exportRootName = "karting_tracker_backup"

    fun exportBackup(targetUri: Uri): Boolean {
        return runCatching {
            appContext.contentResolver.openOutputStream(targetUri)?.use { outputStream ->
                ZipOutputStream(outputStream.buffered()).use { zipOutputStream ->
                    val sessionsDir = File(appContext.filesDir, "sessions")
                    val sessionValidation = validateSessionFiles(sessionsDir)
                    val manifest = JSONObject().apply {
                        put("formatVersion", BACKUP_FORMAT_VERSION)
                        put("createdAtEpochMs", System.currentTimeMillis())
                        put("appPackage", appContext.packageName)
                        put("summary", JSONObject().apply {
                            put("finalSessionCount", sessionValidation.finalSessions)
                            put("partialSessionCount", sessionValidation.partialSessions)
                            put("emptySessionFiles", sessionValidation.emptyFiles)
                            put("corruptSessionFiles", sessionValidation.corruptFiles)
                            put("trackProfileCount", countFiles(File(appContext.filesDir, "track_profiles")))
                            put("trackLayoutCount", countFiles(File(appContext.filesDir, "track_layouts")))
                        })
                        put("sessionValidation", sessionValidation.toJson())
                        put(
                            "includedRoots",
                            listOf("files/sessions", "files/track_layouts", "files/track_maps", "files/track_profiles", "shared_prefs")
                        )
                    }
                    addStringEntry(
                        zipOutputStream,
                        "$exportRootName/manifest.json",
                        manifest.toString(2)
                    )
                    addDirectoryToZip(
                        sessionsDir,
                        zipOutputStream,
                        "$exportRootName/files/sessions"
                    ) { file -> sessionValidation.exportableSessionNames.contains(file.name) }
                    addDirectoryToZip(File(appContext.filesDir, "track_layouts"), zipOutputStream, "$exportRootName/files/track_layouts")
                    addDirectoryToZip(File(appContext.filesDir, "track_maps"), zipOutputStream, "$exportRootName/files/track_maps")
                    addDirectoryToZip(File(appContext.filesDir, "track_profiles"), zipOutputStream, "$exportRootName/files/track_profiles")
                    addDirectoryToZip(prefsDirectory, zipOutputStream, "$exportRootName/shared_prefs")
                }
                true
            } ?: false
        }.onFailure { exception ->
            Log.e(TAG, "Failed to export backup", exception)
        }.getOrDefault(false)
    }

    fun importBackup(sourceUri: Uri): Boolean {
        val tempDirectory = File(appContext.cacheDir, "backup_import_${System.currentTimeMillis()}").apply { mkdirs() }
        val extractedRoot = File(tempDirectory, exportRootName)
        return runCatching {
            appContext.contentResolver.openInputStream(sourceUri)?.use { inputStream ->
                ZipInputStream(inputStream.buffered()).use { zipInputStream ->
                    var entry = zipInputStream.nextEntry
                    while (entry != null) {
                        val targetFile = File(tempDirectory, entry.name)
                        if (entry.isDirectory) {
                            targetFile.mkdirs()
                        } else {
                            targetFile.parentFile?.mkdirs()
                            FileOutputStream(targetFile).use { fileOutput ->
                                zipInputStream.copyTo(fileOutput)
                            }
                        }
                        zipInputStream.closeEntry()
                        entry = zipInputStream.nextEntry
                    }
                }
            } ?: return false

            val manifestFile = File(extractedRoot, "manifest.json")
            if (!manifestFile.exists()) {
                Log.e(TAG, "Backup manifest missing")
                return false
            }
            val manifest = JSONObject(manifestFile.readText())
            if (manifest.optInt("formatVersion", -1) != BACKUP_FORMAT_VERSION) {
                Log.e(TAG, "Unsupported backup format version")
                return false
            }

            restoreDirectory(File(extractedRoot, "files/sessions"), File(appContext.filesDir, "sessions"))
            restoreDirectory(File(extractedRoot, "files/track_layouts"), File(appContext.filesDir, "track_layouts"))
            restoreDirectory(File(extractedRoot, "files/track_maps"), File(appContext.filesDir, "track_maps"))
            restoreDirectory(File(extractedRoot, "files/track_profiles"), File(appContext.filesDir, "track_profiles"))
            restoreDirectory(File(extractedRoot, "shared_prefs"), prefsDirectory)
            true
        }.onFailure { exception ->
            Log.e(TAG, "Failed to import backup", exception)
        }.getOrDefault(false).also {
            tempDirectory.deleteRecursively()
        }
    }

    private fun restoreDirectory(source: File, destination: File) {
        if (!source.exists()) {
            return
        }
        if (destination.exists()) {
            destination.deleteRecursively()
        }
        destination.mkdirs()
        source.copyRecursively(destination, overwrite = true)
    }

    private fun addStringEntry(zipOutputStream: ZipOutputStream, entryName: String, value: String) {
        zipOutputStream.putNextEntry(ZipEntry(entryName))
        zipOutputStream.write(value.toByteArray())
        zipOutputStream.closeEntry()
    }

    private fun addDirectoryToZip(
        sourceDirectory: File,
        zipOutputStream: ZipOutputStream,
        zipRoot: String,
        fileFilter: ((File) -> Boolean)? = null
    ) {
        if (!sourceDirectory.exists()) {
            return
        }
        sourceDirectory.walkTopDown()
            .filter { file -> file.isFile }
            .filter { file -> fileFilter?.invoke(file) ?: true }
            .forEach { file ->
                val relativePath = file.relativeTo(sourceDirectory).invariantSeparatorsPath
                val entryName = "$zipRoot/$relativePath"
                zipOutputStream.putNextEntry(ZipEntry(entryName))
                file.inputStream().use { inputStream ->
                    inputStream.copyTo(zipOutputStream)
                }
                zipOutputStream.closeEntry()
            }
    }

    companion object {
        private const val TAG = "AppBackupManager"
        private const val BACKUP_FORMAT_VERSION = 1
    }

    private fun countFiles(directory: File): Int {
        return directory.listFiles { file -> file.isFile }?.size ?: 0
    }

    private fun validateSessionFiles(directory: File): SessionValidationSummary {
        if (!directory.exists()) {
            return SessionValidationSummary()
        }
        val files = directory.listFiles { file -> file.isFile && file.extension.equals("json", ignoreCase = true) }.orEmpty()
        val exportable = mutableSetOf<String>()
        val invalidEntries = mutableListOf<JSONObject>()
        var finalSessions = 0
        var partialSessions = 0
        var emptyFiles = 0
        var corruptFiles = 0
        files.forEach { file ->
            val isPartial = file.name.endsWith("_partial.json")
            if (isPartial) partialSessions += 1 else finalSessions += 1
            if (file.length() <= 0L) {
                emptyFiles += 1
                invalidEntries += JSONObject().apply {
                    put("file", file.name)
                    put("reason", "empty")
                    put("sizeBytes", file.length())
                }
                return@forEach
            }
            val validJson = runCatching { JsonParser.parseString(file.readText()).asJsonObject }.isSuccess
            if (!validJson) {
                corruptFiles += 1
                invalidEntries += JSONObject().apply {
                    put("file", file.name)
                    put("reason", "corrupt_json")
                    put("sizeBytes", file.length())
                }
                return@forEach
            }
            exportable += file.name
        }
        return SessionValidationSummary(
            finalSessions = finalSessions,
            partialSessions = partialSessions,
            emptyFiles = emptyFiles,
            corruptFiles = corruptFiles,
            exportableSessionNames = exportable,
            invalidEntries = invalidEntries
        )
    }

    private data class SessionValidationSummary(
        val finalSessions: Int = 0,
        val partialSessions: Int = 0,
        val emptyFiles: Int = 0,
        val corruptFiles: Int = 0,
        val exportableSessionNames: Set<String> = emptySet(),
        val invalidEntries: List<JSONObject> = emptyList()
    ) {
        fun toJson(): JSONObject {
            return JSONObject().apply {
                put("finalSessionCount", finalSessions)
                put("partialSessionCount", partialSessions)
                put("emptySessionFiles", emptyFiles)
                put("corruptSessionFiles", corruptFiles)
                put("exportedSessions", exportableSessionNames.sorted())
                put("invalidSessions", invalidEntries)
            }
        }
    }
}
