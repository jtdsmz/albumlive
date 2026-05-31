package io.github.jtdsmz.albumlive.data

import android.media.MediaExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.math.min

internal object LivePhotoUtils {
    private const val XMP_SCAN_BYTES = 512 * 1024
    private const val TAIL_SCAN_BYTES = 64L * 1024L * 1024L
    private const val NEAR_OFFSET_SCAN_BYTES = 256 * 1024L
    private const val COPY_BUFFER_SIZE = 64 * 1024

    private val imageExtensions = setOf("jpg", "jpeg", "heic", "heif")
    private val videoExtensions = setOf("mov", "mp4")
    private val heicBrands = setOf("heic", "heix", "hevc", "hevx", "heim", "heis", "mif1", "msf1")

    data class LivePhotoAssets(
        val coverImagePath: String,
        val videoPath: String
    )

    data class LivePhotoInfo(
        val isLivePhoto: Boolean,
        val type: String,
        val coverPath: String? = null,
        val videoPath: String? = null,
        val embeddedVideoOffset: Long? = null
    )

    private data class LivePhotoSource(
        val coverSourceFile: File,
        val videoSourceFile: File? = null,
        val embeddedVideoOffset: Long? = null,
        val coverExtension: String,
        val videoExtension: String
    ) {
        val isEmbeddedVideo: Boolean get() = embeddedVideoOffset != null
    }

    fun isLivePhotoFile(inputPath: String): Boolean {
        val inputFile = File(inputPath)
        if (!inputFile.exists() || !inputFile.isFile) return false
        return runCatching { resolveLivePhotoSource(inputFile) != null }.getOrDefault(false)
    }

    fun inspect(inputPath: String): LivePhotoInfo {
        val file = File(inputPath)
        val source = if (file.exists() && file.isFile) {
            runCatching { resolveLivePhotoSource(file) }.getOrNull()
        } else {
            null
        }
        return if (source == null) {
            LivePhotoInfo(isLivePhoto = false, type = "普通媒体")
        } else if (source.isEmbeddedVideo) {
            LivePhotoInfo(
                isLivePhoto = true,
                type = "Motion Photo",
                coverPath = source.coverSourceFile.absolutePath,
                embeddedVideoOffset = source.embeddedVideoOffset
            )
        } else {
            LivePhotoInfo(
                isLivePhoto = true,
                type = "配对实况",
                coverPath = source.coverSourceFile.absolutePath,
                videoPath = source.videoSourceFile?.absolutePath
            )
        }
    }

    suspend fun extractLivePhotoAssets(
        inputPath: String,
        outputDir: String,
        outputFilePrefix: String = File(inputPath).nameWithoutExtension
    ): Result<LivePhotoAssets> = withContext(Dispatchers.IO) {
        runCatching {
            val inputFile = File(inputPath)
            if (!inputFile.exists() || !inputFile.isFile) {
                throw IllegalArgumentException("input file does not exist: $inputPath")
            }
            val source = resolveLivePhotoSource(inputFile)
                ?: throw IllegalArgumentException("not a live photo: $inputPath")
            val outputFolder = File(outputDir)
            if (!outputFolder.exists()) outputFolder.mkdirs()

            val safePrefix = outputFilePrefix.ifBlank { "live_photo" }
            val coverOutput = File(outputFolder, "${safePrefix}_cover.${source.coverExtension}")
            val videoOutput = File(outputFolder, "${safePrefix}_video.${source.videoExtension}")
            if (coverOutput.exists()) coverOutput.delete()
            if (videoOutput.exists()) videoOutput.delete()

            if (source.isEmbeddedVideo) {
                val offset = source.embeddedVideoOffset ?: error("missing embedded video offset")
                copyFileRange(source.coverSourceFile, coverOutput, 0L, offset)
                copyFileRange(source.coverSourceFile, videoOutput, offset, source.coverSourceFile.length())
                if (!isVideoFile(videoOutput.absolutePath)) {
                    coverOutput.delete()
                    videoOutput.delete()
                    throw IllegalStateException("extracted video is invalid")
                }
            } else {
                val video = source.videoSourceFile ?: error("missing paired video")
                copyWholeFile(source.coverSourceFile, coverOutput)
                copyWholeFile(video, videoOutput)
            }

            LivePhotoAssets(coverOutput.absolutePath, videoOutput.absolutePath)
        }.onFailure {
            if (it is CancellationException) throw it
        }
    }

    private fun resolveLivePhotoSource(inputFile: File): LivePhotoSource? {
        val imageMime = detectStillImageMime(inputFile)
        if (imageMime != null) {
            resolveEmbeddedLivePhotoSource(inputFile, imageMime)?.let { return it }
            resolvePairedLivePhotoFromImage(inputFile, imageMime)?.let { return it }
        }
        if (isVideoFile(inputFile.absolutePath)) {
            resolvePairedLivePhotoFromVideo(inputFile)?.let { return it }
        }
        return null
    }

    private fun resolveEmbeddedLivePhotoSource(inputFile: File, imageMime: String): LivePhotoSource? {
        val fileLength = inputFile.length()
        if (fileLength <= 16L) return null
        val header = readBinaryWindowAsLatin1(inputFile, 0L, XMP_SCAN_BYTES.toLong())
        val tail = readBinaryWindowAsLatin1(inputFile, (fileLength - XMP_SCAN_BYTES).coerceAtLeast(0L), XMP_SCAN_BYTES.toLong())
        val metadata = "$header\n$tail"
        val preferredOffsets = mutableListOf<Long>()
        extractEmbeddedVideoOffsetFromMetadata(metadata, fileLength)?.let { preferredOffsets += it }
        val offset = findEmbeddedVideoOffset(inputFile, preferredOffsets) ?: return null
        if (offset <= 0L) return null
        return LivePhotoSource(
            coverSourceFile = inputFile,
            embeddedVideoOffset = offset,
            coverExtension = extensionForStillImageMime(imageMime, inputFile.extension),
            videoExtension = detectEmbeddedVideoExtension(inputFile, offset)
        )
    }

    private fun resolvePairedLivePhotoFromImage(imageFile: File, imageMime: String): LivePhotoSource? {
        val video = findPairedFile(imageFile, videoExtensions) { isVideoFile(it.absolutePath) } ?: return null
        return LivePhotoSource(
            coverSourceFile = imageFile,
            videoSourceFile = video,
            coverExtension = extensionForStillImageMime(imageMime, imageFile.extension),
            videoExtension = extensionForVideoFile(video)
        )
    }

    private fun resolvePairedLivePhotoFromVideo(videoFile: File): LivePhotoSource? {
        val image = findPairedFile(videoFile, imageExtensions) { detectStillImageMime(it) != null } ?: return null
        val mime = detectStillImageMime(image) ?: return null
        return LivePhotoSource(
            coverSourceFile = image,
            videoSourceFile = videoFile,
            coverExtension = extensionForStillImageMime(mime, image.extension),
            videoExtension = extensionForVideoFile(videoFile)
        )
    }

    private fun findPairedFile(sourceFile: File, extensions: Set<String>, accept: (File) -> Boolean): File? {
        val parent = sourceFile.parentFile ?: return null
        val baseName = sourceFile.nameWithoutExtension
        val names = extensions.map { "$baseName.$it" }.toSet()
        return parent.listFiles()?.asSequence()?.firstOrNull {
            it.absolutePath != sourceFile.absolutePath &&
                it.isFile &&
                it.name.lowercase() in names &&
                accept(it)
        }
    }

    private fun detectStillImageMime(file: File): String? {
        if (!file.exists() || !file.isFile) return null
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                if (raf.length() < 12L) return@use null
                val header = ByteArray(12)
                raf.readFully(header)
                if (header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte()) return@use "image/jpeg"
                if (
                    header[4] == 'f'.code.toByte() &&
                    header[5] == 't'.code.toByte() &&
                    header[6] == 'y'.code.toByte() &&
                    header[7] == 'p'.code.toByte()
                ) {
                    val brand = String(header, 8, 4, StandardCharsets.US_ASCII)
                    if (brand in heicBrands) {
                        return@use if (brand == "mif1" || brand == "msf1") "image/heif" else "image/heic"
                    }
                }
                when (file.extension.lowercase()) {
                    "jpg", "jpeg" -> "image/jpeg"
                    "heic" -> "image/heic"
                    "heif" -> "image/heif"
                    else -> null
                }
            }
        }.getOrNull()
    }

    private fun isVideoFile(path: String): Boolean {
        var extractor: MediaExtractor? = null
        return try {
            extractor = MediaExtractor().apply { setDataSource(path) }
            selectTrack(extractor, isVideo = true) >= 0
        } catch (_: Throwable) {
            false
        } finally {
            runCatching { extractor?.release() }
        }
    }

    private fun selectTrack(extractor: MediaExtractor, isVideo: Boolean): Int {
        val prefix = if (isVideo) "video/" else "audio/"
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString("mime") ?: continue
            if (mime.startsWith(prefix)) return i
        }
        return -1
    }

    private fun extensionForStillImageMime(mime: String, fallbackExtension: String): String {
        return when (mime) {
            "image/jpeg" -> "jpg"
            "image/heic" -> "heic"
            "image/heif" -> "heif"
            else -> fallbackExtension.lowercase().ifBlank { "jpg" }
        }
    }

    private fun extensionForVideoFile(file: File): String {
        return when (val ext = file.extension.lowercase()) {
            "mov", "mp4" -> ext
            else -> "mp4"
        }
    }

    private fun detectEmbeddedVideoExtension(file: File, startOffset: Long): String {
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                if (startOffset <= 0L || startOffset + 12L > raf.length()) return@use "mp4"
                raf.seek(startOffset)
                val header = ByteArray(12)
                raf.readFully(header)
                if (
                    header[4] == 'f'.code.toByte() &&
                    header[5] == 't'.code.toByte() &&
                    header[6] == 'y'.code.toByte() &&
                    header[7] == 'p'.code.toByte()
                ) {
                    val brand = String(header, 8, 4, StandardCharsets.US_ASCII)
                    if (brand == "qt  ") "mov" else "mp4"
                } else {
                    "mp4"
                }
            }
        }.getOrDefault("mp4")
    }

    private fun extractEmbeddedVideoOffsetFromMetadata(text: String, fileLength: Long): Long? {
        val trailerLength = listOf(
            Regex("""(?:GCamera|Camera):MicroVideoOffset\s*=\s*["'](\d+)["']""", RegexOption.IGNORE_CASE),
            Regex("""(?:GCamera|Camera):MotionPhotoOffset\s*=\s*["'](\d+)["']""", RegexOption.IGNORE_CASE),
            Regex("""Item:Semantic\s*=\s*["']MotionPhoto["'][^>]*Item:Length\s*=\s*["'](\d+)["']""", RegexOption.IGNORE_CASE),
            Regex("""Item:Length\s*=\s*["'](\d+)["'][^>]*Item:Semantic\s*=\s*["']MotionPhoto["']""", RegexOption.IGNORE_CASE)
        ).asSequence()
            .mapNotNull { it.find(text)?.groupValues?.getOrNull(1)?.toLongOrNull() }
            .firstOrNull { it > 0L } ?: return null
        return (fileLength - trailerLength).takeIf { it in 1 until fileLength }
    }

    private fun readBinaryWindowAsLatin1(file: File, startOffset: Long, maxBytes: Long): String {
        if (!file.exists() || !file.isFile || maxBytes <= 0L) return ""
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val fileLength = raf.length()
                if (fileLength <= 0L || startOffset >= fileLength) return@use ""
                val length = min(maxBytes, fileLength - startOffset).toInt()
                val buffer = ByteArray(length)
                raf.seek(startOffset)
                raf.readFully(buffer)
                String(buffer, StandardCharsets.ISO_8859_1)
            }
        }.getOrDefault("")
    }

    private fun findEmbeddedVideoOffset(file: File, preferredOffsets: List<Long>): Long? {
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val fileLength = raf.length()
                preferredOffsets.asSequence()
                    .mapNotNull { findIsoBmffOffsetNear(raf, fileLength, it) }
                    .firstOrNull()
                    ?.let { return@use it }

                val scanStart = (fileLength - TAIL_SCAN_BYTES).coerceAtLeast(0L)
                val buffer = ByteArray((fileLength - scanStart).toInt())
                raf.seek(scanStart)
                raf.readFully(buffer)
                var bestOffset: Long? = null
                var index = 0
                while (index <= buffer.size - 8) {
                    if (
                        buffer[index + 4] == 'f'.code.toByte() &&
                        buffer[index + 5] == 't'.code.toByte() &&
                        buffer[index + 6] == 'y'.code.toByte() &&
                        buffer[index + 7] == 'p'.code.toByte()
                    ) {
                        val candidate = scanStart + index.toLong()
                        if (candidate > 0L && looksLikeIsoBmffVideoAtOffset(raf, candidate, fileLength)) {
                            bestOffset = candidate
                        }
                    }
                    index++
                }
                bestOffset
            }
        }.getOrNull()
    }

    private fun findIsoBmffOffsetNear(raf: RandomAccessFile, fileLength: Long, targetOffset: Long): Long? {
        if (targetOffset !in 1 until fileLength) return null
        val start = (targetOffset - NEAR_OFFSET_SCAN_BYTES).coerceAtLeast(0L)
        val end = (targetOffset + NEAR_OFFSET_SCAN_BYTES).coerceAtMost(fileLength)
        val buffer = ByteArray((end - start).toInt())
        if (buffer.size < 8) return null
        raf.seek(start)
        raf.readFully(buffer)
        var bestOffset: Long? = null
        var bestDistance = Long.MAX_VALUE
        var index = 0
        while (index <= buffer.size - 8) {
            if (
                buffer[index + 4] == 'f'.code.toByte() &&
                buffer[index + 5] == 't'.code.toByte() &&
                buffer[index + 6] == 'y'.code.toByte() &&
                buffer[index + 7] == 'p'.code.toByte()
            ) {
                val candidate = start + index.toLong()
                if (candidate > 0L && looksLikeIsoBmffVideoAtOffset(raf, candidate, fileLength)) {
                    val distance = abs(candidate - targetOffset)
                    if (distance < bestDistance) {
                        bestDistance = distance
                        bestOffset = candidate
                    }
                }
            }
            index++
        }
        return bestOffset
    }

    private fun looksLikeIsoBmffVideoAtOffset(raf: RandomAccessFile, startOffset: Long, fileLength: Long): Boolean {
        if (startOffset <= 0L || startOffset + 8L > fileLength) return false
        return runCatching {
            var current = startOffset
            var count = 0
            var foundMovie = false
            var foundData = false
            while (current + 8L <= fileLength && count < 128) {
                raf.seek(current)
                val header = ByteArray(8)
                raf.readFully(header)
                val boxType = String(header, 4, 4, StandardCharsets.US_ASCII)
                if (count == 0 && boxType != "ftyp") return@runCatching false
                val boxSize32 = readUnsignedInt(header, 0)
                val boxSize = when {
                    boxSize32 == 0L -> fileLength - current
                    boxSize32 == 1L -> {
                        val extSize = ByteArray(8)
                        raf.readFully(extSize)
                        readUnsignedLong(extSize, 0)
                    }
                    else -> boxSize32
                }
                val headerSize = if (boxSize32 == 1L) 16L else 8L
                if (boxSize < headerSize || current + boxSize > fileLength) return@runCatching false
                if (boxType == "moov" || boxType == "moof") foundMovie = true
                if (boxType == "mdat") foundData = true
                current += boxSize
                count++
                if (foundMovie && foundData) return@runCatching true
            }
            foundMovie && foundData
        }.getOrDefault(false)
    }

    private fun readUnsignedInt(buffer: ByteArray, offset: Int): Long {
        return ((buffer[offset].toLong() and 0xFF) shl 24) or
            ((buffer[offset + 1].toLong() and 0xFF) shl 16) or
            ((buffer[offset + 2].toLong() and 0xFF) shl 8) or
            (buffer[offset + 3].toLong() and 0xFF)
    }

    private fun readUnsignedLong(buffer: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0 until 8) {
            value = (value shl 8) or (buffer[offset + i].toLong() and 0xFF)
        }
        return value
    }

    private fun copyWholeFile(inputFile: File, outputFile: File) {
        outputFile.parentFile?.let { if (!it.exists()) it.mkdirs() }
        inputFile.inputStream().use { input ->
            outputFile.outputStream().use { output ->
                input.copyTo(output, COPY_BUFFER_SIZE)
            }
        }
    }

    private fun copyFileRange(inputFile: File, outputFile: File, startOffset: Long, endOffsetExclusive: Long) {
        require(startOffset >= 0L)
        require(endOffsetExclusive > startOffset)
        require(endOffsetExclusive <= inputFile.length())
        outputFile.parentFile?.let { if (!it.exists()) it.mkdirs() }
        RandomAccessFile(inputFile, "r").use { raf ->
            outputFile.outputStream().use { output ->
                raf.seek(startOffset)
                val buffer = ByteArray(COPY_BUFFER_SIZE)
                var remaining = endOffsetExclusive - startOffset
                while (remaining > 0L) {
                    val readSize = min(buffer.size.toLong(), remaining).toInt()
                    val actual = raf.read(buffer, 0, readSize)
                    if (actual <= 0) error("read failed: ${inputFile.absolutePath}")
                    output.write(buffer, 0, actual)
                    remaining -= actual.toLong()
                }
            }
        }
    }
}
