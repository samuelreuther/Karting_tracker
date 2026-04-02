package com.kartingtracker.data

import android.content.Context
import android.net.Uri
import android.util.Log
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
                    val manifest = JSONObject().apply {
                        put("formatVersion", BACKUP_FORMAT_VERSION)
                        put("createdAtEpochMs", System.currentTimeMillis())
                        put("appPackage", appContext.packageName)
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
                    addDirectoryToZip(File(appContext.filesDir, "sessions"), zipOutputStream, "$exportRootName/files/sessions")
                    addDirectoryToZip(File(appContext.filesDir, "track_layouts"), zipOutputStream, "$exportRootName/files/track_layouts")
                    addDirectoryToZip(File(appContext.filesDir, "track_maps"), zipOutputStream, "$exportRootName/files/track_maps")
                    addDirectoryToZip(File(appContext.filesDir, "track_profiles"), zipOutputStream, "$exportRootName/files/track_profiles")
                    addDirectoryToZip(prefsDirectory, zipOutputStream, "$exportRootName/shared_prefs")
                }
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

    private fun addDirectoryToZip(sourceDirectory: File, zipOutputStream: ZipOutputStream, zipRoot: String) {
        if (!sourceDirectory.exists()) {
            return
        }
        sourceDirectory.walkTopDown()
            .filter { file -> file.isFile }
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
}
