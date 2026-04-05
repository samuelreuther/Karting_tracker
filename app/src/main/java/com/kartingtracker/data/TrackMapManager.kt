package com.kartingtracker.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PointF
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File
import kotlin.math.atan2

class TrackMapManager(
    context: Context
) {
    private val appContext = context.applicationContext
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val rootDirectory = File(appContext.filesDir, "track_maps").apply { mkdirs() }
    private val preferences = appContext.getSharedPreferences("karting_track_maps", Context.MODE_PRIVATE)

    fun saveMap(trackName: String, bitmap: Bitmap): String? {
        val trackDirectory = resolveTrackDirectory(trackName)
        val mapFile = File(trackDirectory, DEFAULT_MAP_FILE_NAME)
        return try {
            mapFile.outputStream().use { outputStream ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)) {
                    return null
                }
            }
            mapFile.absolutePath
        } catch (exception: Exception) {
            Log.w(TAG, "Failed to save map for $trackName", exception)
            null
        }
    }

    fun loadMap(trackName: String): Bitmap? {
        val mapFile = getMapFile(trackName) ?: return null
        return BitmapFactory.decodeFile(mapFile.absolutePath)
    }

    fun getMapFile(trackName: String): File? {
        return resolveTrackDirectories(trackName)
            .asSequence()
            .flatMap { directory -> directory.listFiles().orEmpty().asSequence() }
            .firstOrNull { file ->
                file.name.equals(DEFAULT_MAP_FILE_NAME, ignoreCase = true) ||
                    file.nameWithoutExtension.equals("map", ignoreCase = true)
            }
            ?.takeIf(File::exists)
    }

    fun saveMetadata(trackName: String, metadata: TrackMapMetadata): TrackMapMetadata {
        val normalizedMetadata = metadata.copy(
            trackName = trackName,
            curves = metadata.curves.mapIndexed { index, curve ->
                curve.copy(
                    index = index + 1,
                    startPercent = curve.startPercent.coerceIn(0f, 100f),
                    endPercent = curve.endPercent.coerceIn(0f, 100f),
                    peakPercent = curve.peakPercent.coerceIn(0f, 100f),
                    intensity = curve.intensity.coerceIn(0f, 1f)
                )
            }
        )
        val metadataFile = File(resolveTrackDirectory(trackName), METADATA_FILE_NAME)
        metadataFile.writeText(gson.toJson(normalizedMetadata))
        return normalizedMetadata
    }

    fun loadMetadata(trackName: String): TrackMapMetadata? {
        val metadataFile = resolveTrackDirectories(trackName)
            .map { directory -> File(directory, METADATA_FILE_NAME) }
            .firstOrNull(File::exists)
            ?: return null
        return runCatching { gson.fromJson(metadataFile.readText(), TrackMapMetadata::class.java) }
            .onFailure { exception -> Log.w(TAG, "Failed to load metadata for $trackName", exception) }
            .getOrNull()
    }

    fun deleteTrackMap(trackName: String): Boolean {
        val trackDirectory = resolveTrackDirectory(trackName, createIfMissing = false) ?: return true
        return trackDirectory.deleteRecursively() || !trackDirectory.exists()
    }

    fun seedBundledMaps(trackManager: TrackManager) {
        if (preferences.getInt(KEY_BUNDLED_MAP_VERSION, 0) >= bundledMapVersion) {
            return
        }

        val bundledTracks = loadBundledTrackAssets()
        if (bundledTracks.isEmpty()) {
            return
        }

        bundledTracks.forEach { asset ->
            seedBundledTrack(asset, trackManager)
        }
        preferences.edit().putInt(KEY_BUNDLED_MAP_VERSION, bundledMapVersion).apply()
    }

    private fun seedBundledTrack(asset: BundledTrackMapAsset, trackManager: TrackManager) {
        try {
            val trackDirectory = resolveTrackDirectory(asset.trackName)
            val targetExtension = asset.imageAsset.substringAfterLast('.', "png")
            val targetMapFile = File(trackDirectory, "map.$targetExtension")
            appContext.assets.open(asset.imageAsset).use { inputStream ->
                targetMapFile.outputStream().use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }

            val layout = appContext.assets.open(asset.layoutAsset).bufferedReader().use { reader ->
                gson.fromJson(reader, PersistedTrackLayoutAsset::class.java)
            }
            saveMetadata(
                trackName = asset.trackName,
                metadata = TrackMapMetadata(
                    trackName = asset.trackName,
                    curves = buildBundledCurves(layout)
                )
            )

            val startPoint = layout.startPoint?.toPointF()
            val startDirectionDeg = startPoint?.let { point ->
                layout.direction.toStartDirectionDeg(point)
            }
            trackManager.saveTrack(
                Track(
                    name = asset.trackName,
                    mapImagePath = targetMapFile.absolutePath,
                    mapWidthMeters = asset.mapWidthMeters ?: layout.lengthMeters,
                    mapHeightMeters = asset.mapHeightMeters,
                    startPoint = startPoint,
                    startDirectionDeg = startDirectionDeg
                )
            )
        } catch (exception: Exception) {
            Log.w(TAG, "Failed to seed bundled map ${asset.trackName}", exception)
        }
    }

    private fun buildBundledCurves(layout: PersistedTrackLayoutAsset): List<CurveDefinition> {
        val corners = layout.corners.orEmpty()
        if (corners.isEmpty()) {
            return emptyList()
        }

        val spacingPercent = 100f / corners.size.toFloat()
        return corners.mapIndexed { index, _ ->
            val peakPercent = (index * spacingPercent) + (spacingPercent / 2f)
            CurveDefinition(
                index = index + 1,
                startPercent = (peakPercent - (spacingPercent * 0.35f)).coerceIn(0f, 100f),
                endPercent = (peakPercent + (spacingPercent * 0.35f)).coerceIn(0f, 100f),
                peakPercent = peakPercent.coerceIn(0f, 100f),
                intensity = (0.55f + ((index.toFloat() / corners.lastIndex.coerceAtLeast(1).toFloat()) * 0.35f)).coerceIn(0.55f, 0.9f)
            )
        }
    }

    private fun loadBundledTrackAssets(): List<BundledTrackMapAsset> {
        return try {
            appContext.assets.open(BUNDLED_MANIFEST_ASSET_PATH).bufferedReader().use { reader ->
                gson.fromJson(reader, Array<BundledTrackMapAsset>::class.java)?.toList().orEmpty()
            }
        } catch (exception: Exception) {
            Log.w(TAG, "Failed to load bundled track maps manifest", exception)
            emptyList()
        }
    }

    private fun resolveTrackDirectory(trackName: String, createIfMissing: Boolean = true): File? {
        val directory = File(rootDirectory, sanitizeTrackName(trackName))
        return if (!createIfMissing && !directory.exists()) {
            null
        } else {
            directory.apply { mkdirs() }
        }
    }

    private fun resolveTrackDirectories(trackName: String): List<File> {
        return TrackNameCanonicalizer.possibleStorageKeys(trackName)
            .map { key -> File(rootDirectory, key) }
            .filter { it.exists() && it.isDirectory }
    }

    private fun sanitizeTrackName(trackName: String): String {
        return FileNameNormalizer.normalize(trackName)
    }

    private fun PersistedTrackPointAsset.toPointF(): PointF {
        return PointF(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
    }

    private fun String?.toStartDirectionDeg(startPoint: PointF): Float {
        val centerX = 0.5f
        val centerY = 0.5f
        val radialAngleDeg = Math.toDegrees(
            atan2(
                (centerY - startPoint.y).toDouble(),
                (startPoint.x - centerX).toDouble()
            )
        ).toFloat()
        val tangentOffset = if (equals(TrackDirection.COUNTER_CLOCKWISE.name, ignoreCase = true)) {
            90f
        } else {
            -90f
        }
        return ((radialAngleDeg + tangentOffset) + 360f) % 360f
    }

    private data class BundledTrackMapAsset(
        val trackName: String,
        val imageAsset: String,
        val layoutAsset: String,
        val mapWidthMeters: Float? = null,
        val mapHeightMeters: Float? = null
    )

    private data class PersistedTrackLayoutAsset(
        val trackName: String? = null,
        val lengthMeters: Float? = null,
        val startPoint: PersistedTrackPointAsset? = null,
        val direction: String? = null,
        val corners: List<PersistedTrackCornerAsset>? = emptyList()
    )

    private data class PersistedTrackCornerAsset(
        val name: String = "",
        val point: PersistedTrackPointAsset
    )

    private data class PersistedTrackPointAsset(
        val x: Float,
        val y: Float
    )

    companion object {
        private const val TAG = "TrackMapManager"
        private const val KEY_BUNDLED_MAP_VERSION = "bundled_map_version"
        private const val bundledMapVersion = 4
        private const val DEFAULT_MAP_FILE_NAME = "map.png"
        private const val METADATA_FILE_NAME = "metadata.json"
        private const val BUNDLED_MANIFEST_ASSET_PATH = "preloaded_tracks/manifest.json"
    }
}
