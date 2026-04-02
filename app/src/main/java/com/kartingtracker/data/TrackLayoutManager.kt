package com.kartingtracker.data

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.kartingtracker.domain.TrackCornerTypeDetector
import java.io.File

class TrackLayoutManager(
    context: Context
) {
    private val appContext = context.applicationContext
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val preferences = appContext.getSharedPreferences("karting_track_layouts", Context.MODE_PRIVATE)
    private val layoutDirectory = File(appContext.filesDir, "track_layouts").apply { mkdirs() }
    private val imageDirectory = File(layoutDirectory, "images").apply { mkdirs() }
    private val cornerTypeDetector = TrackCornerTypeDetector()

    fun loadLayout(trackName: String): TrackLayout? {
        val file = File(layoutDirectory, buildLayoutFileName(trackName))
        if (!file.exists()) {
            return null
        }

        return try {
            val persisted = gson.fromJson(file.readText(), PersistedTrackLayout::class.java)
            persisted.toTrackLayout(trackName)
        } catch (exception: Exception) {
            Log.w(TAG, "Failed to parse track layout for $trackName", exception)
            null
        }
    }

    fun loadOrCreateLayout(trackName: String): TrackLayout {
        return loadLayout(trackName) ?: emptyLayout(trackName)
    }

    fun saveLayout(layout: TrackLayout) {
        val file = File(layoutDirectory, buildLayoutFileName(layout.trackName))
        file.writeText(gson.toJson(layout.normalize()))
    }

    fun deleteLayout(trackName: String): Boolean {
        val layout = loadLayout(trackName)
        val imageDeleted = layout?.imagePath
            ?.takeIf(::isManagedImagePath)
            ?.let { imagePath -> File(imagePath).delete() || !File(imagePath).exists() }
            ?: true
        val file = File(layoutDirectory, buildLayoutFileName(trackName))
        val layoutDeleted = !file.exists() || file.delete()
        return imageDeleted && layoutDeleted
    }

    fun importTrackImage(trackName: String, sourceUri: Uri): String? {
        val fileExtension = resolveExtension(sourceUri)
        val targetFile = File(imageDirectory, buildImageFileName(trackName, fileExtension))
        return try {
            appContext.contentResolver.openInputStream(sourceUri)?.use { inputStream ->
                targetFile.outputStream().use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            } ?: return null

            loadLayout(trackName)?.imagePath
                ?.takeIf(::isManagedImagePath)
                ?.takeIf { existingPath -> existingPath != targetFile.absolutePath }
                ?.let { existingPath -> File(existingPath).delete() }

            targetFile.absolutePath
        } catch (exception: Exception) {
            Log.w(TAG, "Failed to import track image for $trackName", exception)
            null
        }
    }


    fun detectAndClassifyCorners(layout: TrackLayout): TrackLayout {
        if (layout.imagePath.isBlank() && layout.centerlinePoints.isEmpty()) {
            return layout.copy(detectedCorners = emptyList())
        }

        val centerline = if (layout.centerlinePoints.isNotEmpty()) {
            layout.centerlinePoints
        } else {
            val bitmap = BitmapFactory.decodeFile(layout.imagePath)
            if (bitmap == null) {
                Log.w(TAG, "Could not decode track image for corner detection: ${layout.trackName}")
                emptyList()
            } else {
                cornerTypeDetector.extractCenterlineFromBitmap(bitmap).also { bitmap.recycle() }
            }
        }

        if (centerline.isEmpty()) {
            return layout.copy(detectedCorners = emptyList())
        }

        val detectedCorners = cornerTypeDetector.detectFromCenterline(centerline)
        return layout.copy(
            centerlinePoints = centerline,
            detectedCorners = detectedCorners
        )
    }

    fun emptyLayout(trackName: String): TrackLayout {
        return TrackLayout(
            trackName = trackName,
            imagePath = "",
            lengthMeters = null,
            startPoint = TrackLayout.DEFAULT_START_POINT,
            direction = TrackDirection.CLOCKWISE,
            corners = emptyList(),
            detectedCorners = emptyList(),
            centerlinePoints = emptyList()
        )
    }

    fun seedBundledTracks(trackManager: TrackManager) {
        if (preferences.getInt(KEY_BUNDLED_LAYOUT_VERSION, 0) >= bundledLayoutVersion) {
            return
        }

        val bundledTracks = loadBundledTrackAssets()
        if (bundledTracks.isEmpty()) {
            return
        }

        legacyBundledTrackNames.forEach { legacyTrackName ->
            trackManager.deleteTrack(legacyTrackName)
        }
        bundledTracks.forEach { bundledTrack ->
            copyBundledTrackAsset(bundledTrack, trackManager)
        }

        if (trackManager.getSelectedTrackName().isNullOrBlank()) {
            bundledTracks.firstOrNull()?.trackName?.let(trackManager::setSelectedTrack)
        }
        preferences.edit().putInt(KEY_BUNDLED_LAYOUT_VERSION, bundledLayoutVersion).apply()
    }

    private fun buildLayoutFileName(trackName: String): String {
        return "layout_${sanitizeTrackName(trackName)}.json"
    }

    private fun buildImageFileName(trackName: String, extension: String): String {
        return "layout_${sanitizeTrackName(trackName)}.$extension"
    }

    private fun sanitizeTrackName(trackName: String): String {
        val trimmed = trackName.trim().ifBlank { "track" }
        return trimmed.replace(Regex("[^A-Za-z0-9_-]+"), "_")
    }

    private fun resolveExtension(sourceUri: Uri): String {
        val mimeType = appContext.contentResolver.getType(sourceUri).orEmpty()
        return when {
            mimeType.contains("png", ignoreCase = true) -> "png"
            mimeType.contains("webp", ignoreCase = true) -> "webp"
            else -> "jpg"
        }
    }

    private fun resolveExtensionFromName(fileName: String): String {
        return fileName.substringAfterLast('.', "png")
    }

    private fun isManagedImagePath(path: String): Boolean {
        return path.startsWith(imageDirectory.absolutePath)
    }

    private fun loadBundledTrackAssets(): List<BundledTrackAsset> {
        return try {
            appContext.assets.open(bundledManifestAssetPath).bufferedReader().use { reader ->
                gson.fromJson(reader, Array<BundledTrackAsset>::class.java)?.toList().orEmpty()
            }
        } catch (exception: Exception) {
            Log.w(TAG, "Failed to load bundled track manifest", exception)
            emptyList()
        }
    }

    private fun copyBundledTrackAsset(
        bundledTrack: BundledTrackAsset,
        trackManager: TrackManager
    ) {
        try {
            trackManager.saveTrack(bundledTrack.trackName)

            val targetImage = File(
                imageDirectory,
                buildImageFileName(bundledTrack.trackName, resolveExtensionFromName(bundledTrack.imageAsset))
            )
            appContext.assets.open(bundledTrack.imageAsset).use { inputStream ->
                targetImage.outputStream().use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }

            val layout = appContext.assets.open(bundledTrack.layoutAsset).bufferedReader().use { reader ->
                gson.fromJson(reader, PersistedTrackLayout::class.java).toTrackLayout(bundledTrack.trackName)
            }
            saveLayout(
                layout.copy(
                    trackName = bundledTrack.trackName,
                    imagePath = targetImage.absolutePath
                )
            )
        } catch (exception: Exception) {
            Log.w(TAG, "Failed to seed bundled track ${bundledTrack.trackName}", exception)
        }
    }

    private fun TrackLayout.normalize(): TrackLayout {
        val safeImagePath = imagePath.takeIf { path -> path.isNotBlank() } ?: ""
        val safeCorners = corners.mapIndexed { index, corner ->
            TrackCorner(
                name = corner.name.ifBlank { "Kurve ${index + 1}" },
                point = TrackPoint(
                    x = corner.point.x.coerceIn(0f, 1f),
                    y = corner.point.y.coerceIn(0f, 1f)
                )
            )
        }
        val safeCenterline = centerlinePoints.map { point ->
            TrackPoint(
                x = point.x.coerceIn(0f, 1f),
                y = point.y.coerceIn(0f, 1f)
            )
        }
        val safeDetectedCorners = detectedCorners.mapIndexed { index, corner ->
            corner.copy(index = index)
        }

        return copy(
            imagePath = safeImagePath,
            startPoint = TrackPoint(
                x = startPoint.x.coerceIn(0f, 1f),
                y = startPoint.y.coerceIn(0f, 1f)
            ),
            corners = safeCorners,
            detectedCorners = safeDetectedCorners,
            centerlinePoints = safeCenterline
        )
    }

    private fun PersistedTrackLayout.toTrackLayout(trackNameFallback: String): TrackLayout {
        return TrackLayout(
            trackName = trackName?.takeIf { it.isNotBlank() } ?: trackNameFallback,
            imagePath = imagePath.orEmpty(),
            lengthMeters = lengthMeters,
            startPoint = startPoint ?: TrackLayout.DEFAULT_START_POINT,
            direction = direction ?: TrackDirection.CLOCKWISE,
            corners = corners.orEmpty().mapIndexed { index, corner ->
                TrackCorner(
                    name = corner.name.ifBlank { "Kurve ${index + 1}" },
                    point = TrackPoint(
                        x = corner.point.x.coerceIn(0f, 1f),
                        y = corner.point.y.coerceIn(0f, 1f)
                    )
                )
            },
            detectedCorners = detectedCorners.orEmpty(),
            centerlinePoints = centerlinePoints.orEmpty()
        )
    }

    private data class PersistedTrackLayout(
        val trackName: String? = null,
        val imagePath: String? = null,
        val lengthMeters: Float? = null,
        val startPoint: TrackPoint? = null,
        val direction: TrackDirection? = null,
        val corners: List<TrackCorner> = emptyList(),
        val detectedCorners: List<DetectedTrackCorner> = emptyList(),
        val centerlinePoints: List<TrackPoint> = emptyList()
    )

    private data class BundledTrackAsset(
        val trackName: String,
        val imageAsset: String,
        val layoutAsset: String
    )

    companion object {
        private const val TAG = "TrackLayoutManager"
        private const val KEY_BUNDLED_LAYOUT_VERSION = "bundled_layout_version"
        private const val bundledLayoutVersion = 9
        private const val bundledManifestAssetPath = "preloaded_tracks/manifest.json"
        private val legacyBundledTrackNames = listOf(
            "Loerrach VM Kart Racing",
            "Rheinfelden Kartbahn",
            "Basel Kart",
            "Basel SBB Kartbasel",
            "Demo Indoor Track",
            "Test Track"
        )
    }
}
