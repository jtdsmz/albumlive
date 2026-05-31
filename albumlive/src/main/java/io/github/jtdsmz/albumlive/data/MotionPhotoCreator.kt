package io.github.jtdsmz.albumlive.data

import android.media.MediaMetadataRetriever
import io.github.jtdsmz.albumlive.CreateMotionPhotoOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File

internal object MotionPhotoCreator {
    private const val DEFAULT_PRESENTATION_TIMESTAMP_US = 1_500_000L

    suspend fun createMotionPhoto(
        coverPath: String,
        videoPath: String,
        options: CreateMotionPhotoOptions
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val coverFile = File(coverPath)
            val videoFile = File(videoPath)
            require(coverFile.exists() && coverFile.isFile) { "cover not found" }
            require(videoFile.exists() && videoFile.isFile) { "video not found" }
            val jpegBytes = coverFile.readBytes()
            require(isJpeg(jpegBytes)) { "cover must be JPEG" }
            val videoBytes = videoFile.readBytes()
            require(videoBytes.isNotEmpty()) { "video is empty" }
            val outputDir = options.outputDir
            if (!outputDir.exists()) outputDir.mkdirs()
            val outputPrefix = options.outputFileNamePrefix?.takeIf { it.isNotBlank() } ?: "motion_photo"
            val output = File(outputDir, "${outputPrefix}_${System.currentTimeMillis()}.jpg")
            val presentation = resolvePresentationTimestampUs(videoPath)
            val encodeResult = MotionPhotoOemHelpers.encode(
                MotionPhotoOemHelpers.EncodeRequest(
                    coverJpegBytes = jpegBytes,
                    motionVideoBytes = videoBytes,
                    presentationTimestampUs = presentation,
                    oplusOwner = options.oplusOwner,
                    vivoLivePhotoId = options.vivoLivePhotoId
                )
            )
            val encodedBytes = encodeResult?.outputBytes
                ?: MotionPhotoOemHelpers.encodeGeneric(jpegBytes, videoBytes, presentation)
            BufferedOutputStream(output.outputStream()).use {
                it.write(encodedBytes)
                it.flush()
            }
            require(LivePhotoUtils.isLivePhotoFile(output.absolutePath)) { "generated file is not live photo" }
            output
        }
    }

    private fun resolvePresentationTimestampUs(videoPath: String): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(videoPath)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.coerceAtLeast(0L)
                ?: 0L
            if (durationMs <= 0L) DEFAULT_PRESENTATION_TIMESTAMP_US else (durationMs * 1_000L / 2L)
        } catch (_: Throwable) {
            DEFAULT_PRESENTATION_TIMESTAMP_US
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun isJpeg(bytes: ByteArray): Boolean {
        return bytes.size >= 2 &&
            (bytes[0].toInt() and 0xFF) == 0xFF &&
            (bytes[1].toInt() and 0xFF) == 0xD8
    }
}
