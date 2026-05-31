package io.github.jtdsmz.albumlive.data

import android.os.Build
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile

internal object MotionPhotoOemHelpers {
    private const val JPEG_MARKER_PREFIX = 0xFF
    private const val JPEG_SOI = 0xD8
    private const val JPEG_APP1 = 0xE1
    private const val JPEG_APP2 = 0xE2
    private const val XMP_HEADER = "http://ns.adobe.com/xap/1.0/\u0000"
    private const val GCAMERA_NS = "http://ns.google.com/photos/1.0/camera/"
    private const val MICAMERA_NS = "http://ns.xiaomi.com/photos/1.0/camera/"
    private const val OP_CAMERA_NS = "http://ns.oplus.com/photos/1.0/camera/"
    private const val VIVO_CAMERA_NS = "http://ns.vivo.com/photos/1.0/camera/"
    private const val VIVO_MEDIA_KIT_VERSION = "1.0.0.5"
    private const val VIVO_LIVE_PHOTO_EXTEND_KEY = "com.android.camera.livephoto"
    private const val VIVO_EXTEND_VERSION_CODE = 2103
    private const val HONOR_TAIL_SEGMENT_LENGTH = 20
    private const val HONOR_TAIL_TOTAL_LENGTH = 60
    private const val TIFF_TYPE_UNDEFINED = 7
    private const val TIFF_TYPE_BYTE = 1
    private const val TIFF_TYPE_LONG = 4
    private const val EXIF_TAG_EXIF_IFD_POINTER = 0x8769
    private const val EXIF_TAG_MOTION_PHOTO = 0x8897
    private const val EXIF_TAG_LIVE_PHOTO = 0x9A01
    private const val MPF_TAG_VERSION = 0xB000
    private const val MPF_TAG_NUMBER_OF_IMAGES = 0xB001
    private const val MPF_TAG_MP_ENTRY = 0xB002
    private val VIVO_LIVE_PHOTO_ID_JSON_REGEX =
        Regex("\"com\\.android\\.camera\\.livephoto\"\\s*:\\s*\"([^\"]+)\"")
    private val VIVO_LIVE_PHOTO_ID_RAW_REGEX =
        Regex("""motionphoto[0-9A-Za-z]{0,17}""")

    data class EncodeRequest(
        val coverJpegBytes: ByteArray,
        val motionVideoBytes: ByteArray,
        val presentationTimestampUs: Long,
        val oplusOwner: String? = null,
        val vivoLivePhotoId: String? = null
    )

    data class EncodeResult(
        val helperName: String,
        val outputBytes: ByteArray,
        val vivoLivePhotoId: String? = null
    )

    interface OemMotionPhotoHelper {
        val helperName: String
        fun encode(request: EncodeRequest): ByteArray
    }

    fun encode(request: EncodeRequest, manufacturer: String = Build.MANUFACTURER): EncodeResult? {
        val helper = resolve(manufacturer) ?: return null
        val vivoId = if (helper === VivoHelper) {
            request.vivoLivePhotoId ?: generateVivoLivePhotoId()
        } else {
            null
        }
        val output = helper.encode(request.copy(vivoLivePhotoId = vivoId))
        return EncodeResult(helper.helperName, output, vivoId)
    }

    fun readVivoLivePhotoId(file: File): String? {
        if (!file.exists() || !file.isFile) return null
        return runCatching {
            val scanSize = minOf(file.length(), 2L * 1024L * 1024L).toInt()
            if (scanSize <= 0) return@runCatching null
            val buffer = ByteArray(scanSize)
            RandomAccessFile(file, "r").use { raf ->
                raf.seek((raf.length() - scanSize).coerceAtLeast(0L))
                raf.readFully(buffer)
            }
            val text = String(buffer, Charsets.ISO_8859_1)
            VIVO_LIVE_PHOTO_ID_JSON_REGEX.find(text)?.groupValues?.getOrNull(1)
                ?: VIVO_LIVE_PHOTO_ID_RAW_REGEX.find(text)?.value
        }.getOrNull()
    }

    private fun resolve(manufacturer: String): OemMotionPhotoHelper? {
        val m = manufacturer.lowercase()
        return when {
            m.contains("oppo") || m.contains("oplus") || m.contains("oneplus") || m.contains("realme") -> OplusHelper
            m.contains("vivo") -> VivoHelper
            m.contains("xiaomi") -> XiaomiHelper
            m.contains("honor") || m.contains("huawei") -> HonorHelper
            else -> null
        }
    }

    object OplusHelper : OemMotionPhotoHelper {
        override val helperName = "OplusHelper"

        override fun encode(request: EncodeRequest): ByteArray {
            require(isJpeg(request.coverJpegBytes))
            val videoLength = request.motionVideoBytes.size.toLong()
            val xmp = buildOpMotionPhotoXmp(videoLength, request.presentationTimestampUs, request.oplusOwner)
            val xmpSegment = buildXmpApp1Segment(xmp)
            val mpfSegment = buildMpfApp2Segment((request.coverJpegBytes.size + xmpSegment.size).toLong())
            val jpegWithXmp = ByteArrayOutputStream(request.coverJpegBytes.size + xmpSegment.size).use {
                it.write(JPEG_MARKER_PREFIX)
                it.write(JPEG_SOI)
                it.write(xmpSegment)
                it.write(request.coverJpegBytes, 2, request.coverJpegBytes.size - 2)
                it.toByteArray()
            }
            val jpegWithMpf = insertApp2SegmentBeforeFirstNonApp(jpegWithXmp, mpfSegment)
            return ByteArrayOutputStream(jpegWithMpf.size + request.motionVideoBytes.size).use {
                it.write(jpegWithMpf)
                it.write(request.motionVideoBytes)
                it.toByteArray()
            }
        }
    }

    object VivoHelper : OemMotionPhotoHelper {
        override val helperName = "VivoHelper"

        override fun encode(request: EncodeRequest): ByteArray {
            require(isJpeg(request.coverJpegBytes))
            val livePhotoId = request.vivoLivePhotoId ?: generateVivoLivePhotoId()
            val videoBytes = appendVivoLivePhotoExtendInfo(request.motionVideoBytes, livePhotoId)
            val videoLength = videoBytes.size.toLong()
            val xmp = buildVivoMotionPhotoXmp(videoLength, request.presentationTimestampUs)
            val xmpSegment = buildXmpApp1Segment(xmp)
            val jpegWithXmp = ByteArrayOutputStream(request.coverJpegBytes.size + xmpSegment.size).use {
                it.write(JPEG_MARKER_PREFIX)
                it.write(JPEG_SOI)
                it.write(xmpSegment)
                it.write(request.coverJpegBytes, 2, request.coverJpegBytes.size - 2)
                it.toByteArray()
            }
            return ByteArrayOutputStream(jpegWithXmp.size + videoBytes.size).use {
                it.write(jpegWithXmp)
                it.write(videoBytes)
                it.toByteArray()
            }
        }
    }

    object XiaomiHelper : OemMotionPhotoHelper {
        override val helperName = "XiaomiHelper"

        override fun encode(request: EncodeRequest): ByteArray {
            require(isJpeg(request.coverJpegBytes))
            val baseJpeg = injectXiaomiLivePhotoExif(request.coverJpegBytes)
            val xmpSegment = buildXmpApp1Segment(buildMiMotionPhotoXmp(request.motionVideoBytes.size.toLong(), request.presentationTimestampUs))
            val jpegWithXmp = ByteArrayOutputStream(baseJpeg.size + xmpSegment.size).use {
                it.write(JPEG_MARKER_PREFIX)
                it.write(JPEG_SOI)
                it.write(xmpSegment)
                it.write(baseJpeg, 2, baseJpeg.size - 2)
                it.toByteArray()
            }
            return ByteArrayOutputStream(jpegWithXmp.size + request.motionVideoBytes.size).use {
                it.write(jpegWithXmp)
                it.write(request.motionVideoBytes)
                it.toByteArray()
            }
        }
    }

    object HonorHelper : OemMotionPhotoHelper {
        override val helperName = "HonorHelper"

        override fun encode(request: EncodeRequest): ByteArray {
            require(isJpeg(request.coverJpegBytes))
            val tail = buildHonorLiveTail(request.motionVideoBytes.size.toLong())
            return ByteArrayOutputStream(request.coverJpegBytes.size + request.motionVideoBytes.size + tail.size).use {
                it.write(request.coverJpegBytes)
                it.write(request.motionVideoBytes)
                it.write(tail)
                it.toByteArray()
            }
        }
    }

    fun injectXiaomiLivePhotoExif(jpegBytes: ByteArray): ByteArray {
        require(isJpeg(jpegBytes))
        val exifSegment = buildXiaomiLivePhotoExifApp1Segment()
        return ByteArrayOutputStream(jpegBytes.size + exifSegment.size).use {
            it.write(JPEG_MARKER_PREFIX)
            it.write(JPEG_SOI)
            it.write(exifSegment)
            it.write(jpegBytes, 2, jpegBytes.size - 2)
            it.toByteArray()
        }
    }

    private fun appendVivoLivePhotoExtendInfo(videoBytes: ByteArray, livePhotoId: String): ByteArray {
        return runCatching {
            val utilClass = Class.forName("com.vivo.mediaextendinfo.MediaExtendInfoUtil")
            val appendMethod = utilClass.getMethod(
                "appendExtendInfo",
                java.io.OutputStream::class.java,
                Map::class.java,
                Boolean::class.javaPrimitiveType
            )
            val payload = hashMapOf(VIVO_LIVE_PHOTO_EXTEND_KEY to livePhotoId)
            ByteArrayOutputStream(videoBytes.size + 512).use { output ->
                output.write(videoBytes)
                val result = appendMethod.invoke(null, output, payload, true)
                val ok = result !is Int || result > 0
                if (ok) output.toByteArray() else null
            }
        }.getOrNull() ?: ByteArrayOutputStream(videoBytes.size + 128).use {
            it.write(videoBytes)
            it.write(buildVivoVideoBoxExtendBlock(livePhotoId))
            it.toByteArray()
        }
    }

    private fun buildVivoVideoBoxExtendBlock(livePhotoId: String): ByteArray {
        val infos = linkedMapOf<String, Any>(
            VIVO_LIVE_PHOTO_EXTEND_KEY to livePhotoId,
            "version" to VIVO_EXTEND_VERSION_CODE
        )
        val extendedData = buildVivoExtendedData(JSONObject(infos))
        val livePhotoData = buildVivoLivePhotoData(livePhotoId)
        val uuidInfo = "vivoMediaExtInfo".toByteArray(Charsets.UTF_8)
        return ByteArrayOutputStream().use {
            writeU32(it, 8 + uuidInfo.size + extendedData.size + livePhotoData.size)
            writeAscii(it, "uuid")
            it.write(uuidInfo)
            it.write(extendedData)
            it.write(livePhotoData)
            it.toByteArray()
        }
    }

    private fun buildVivoExtendedData(json: JSONObject): ByteArray {
        val jsonBytes = json.toString().toByteArray(Charsets.UTF_8)
        val result = ByteArray(4 + jsonBytes.size + 4 + 11)
        result[0] = 'v'.code.toByte()
        result[1] = 'i'.code.toByte()
        result[2] = 'v'.code.toByte()
        result[3] = 'o'.code.toByte()
        System.arraycopy(jsonBytes, 0, result, 4, jsonBytes.size)
        val lenOffset = 4 + jsonBytes.size
        result[lenOffset] = ((jsonBytes.size shr 24) and 0xFF).toByte()
        result[lenOffset + 1] = ((jsonBytes.size shr 16) and 0xFF).toByte()
        result[lenOffset + 2] = ((jsonBytes.size shr 8) and 0xFF).toByte()
        result[lenOffset + 3] = (jsonBytes.size and 0xFF).toByte()
        val suffix = "cameralbum!".toByteArray(Charsets.UTF_8)
        System.arraycopy(suffix, 0, result, lenOffset + 4, suffix.size)
        return result
    }

    private fun buildVivoLivePhotoData(livePhotoId: String): ByteArray {
        val bytes = ByteArray(0x2F)
        bytes[3] = 0x2F
        val idBytes = livePhotoId.toByteArray(Charsets.UTF_8)
        System.arraycopy(idBytes, 0, bytes, 4, minOf(idBytes.size, 0x1C))
        bytes[0x20] = 0xFF.toByte()
        bytes[0x21] = 0xFF.toByte()
        bytes[0x22] = 0xFF.toByte()
        bytes[0x23] = 0xFF.toByte()
        val tail = byteArrayOf(0x1B, 0x2A, 0x39, 0x48, 0x57, 0x66, 0x75, 0x84.toByte(), 0x93.toByte(), 0xA2.toByte(), 0xB3.toByte())
        System.arraycopy(tail, 0, bytes, 0x24, tail.size)
        return bytes
    }

    private fun generateVivoLivePhotoId(): String {
        return "motionphoto0001".padEnd(28, '0').take(28)
    }

    private fun buildXmpApp1Segment(xmpXml: String): ByteArray {
        val headerBytes = XMP_HEADER.toByteArray(Charsets.UTF_8)
        val xmpBytes = xmpXml.toByteArray(Charsets.UTF_8)
        val app1Length = headerBytes.size + xmpBytes.size + 2
        require(app1Length <= 0xFFFF)
        return ByteArrayOutputStream(app1Length + 2).use {
            it.write(JPEG_MARKER_PREFIX)
            it.write(JPEG_APP1)
            it.write((app1Length shr 8) and 0xFF)
            it.write(app1Length and 0xFF)
            it.write(headerBytes)
            it.write(xmpBytes)
            it.toByteArray()
        }
    }

    private fun buildMotionPhotoXmp(videoLength: Long, presentationTimestampUs: Long, primaryImageLength: Long): String {
        return """
            <?xpacket begin="\uFEFF" id="W5M0MpCehiHzreSzNTczkc9d"?>
            <x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="Adobe XMP Core 5.1.0-jc003">
              <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                <rdf:Description rdf:about=""
                  xmlns:Camera="$GCAMERA_NS"
                  xmlns:GCamera="$GCAMERA_NS"
                  xmlns:Container="http://ns.google.com/photos/1.0/container/"
                  xmlns:Item="http://ns.google.com/photos/1.0/container/item/"
                  Camera:MotionPhoto="1"
                  Camera:MotionPhotoVersion="1"
                  Camera:MotionPhotoPresentationTimestampUs="$presentationTimestampUs"
                  GCamera:MicroVideo="1"
                  GCamera:MicroVideoVersion="1"
                  GCamera:MicroVideoOffset="$videoLength"
                  GCamera:MicroVideoPresentationTimestampUs="$presentationTimestampUs"/>
                <rdf:Description rdf:about=""
                  xmlns:Container="http://ns.google.com/photos/1.0/container/"
                  xmlns:Item="http://ns.google.com/photos/1.0/container/item/">
                  <Container:Directory>
                    <rdf:Seq>
                      <rdf:li Item:Mime="image/jpeg" Item:Semantic="Primary" Item:Length="$primaryImageLength" Item:Padding="0"/>
                      <rdf:li Item:Mime="video/mp4" Item:Semantic="MotionPhoto" Item:Length="$videoLength" Item:Padding="0"/>
                    </rdf:Seq>
                  </Container:Directory>
                </rdf:Description>
              </rdf:RDF>
            </x:xmpmeta>
            <?xpacket end="w"?>
        """.trimIndent()
    }

    private fun buildMiMotionPhotoXmp(videoLength: Long, presentationTimestampUs: Long): String {
        return """
            <?xpacket begin="\uFEFF" id="W5M0MpCehiHzreSzNTczkc9d"?>
            <x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="Adobe XMP Core 5.1.0-jc003">
              <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                <rdf:Description rdf:about=""
                  xmlns:GCamera="$GCAMERA_NS"
                  xmlns:MiCamera="$MICAMERA_NS"
                  GCamera:MicroVideo="1"
                  GCamera:MicroVideoVersion="1"
                  GCamera:MicroVideoOffset="$videoLength"
                  GCamera:MicroVideoPresentationTimestampUs="$presentationTimestampUs"
                  MiCamera:livePhoto="1"
                  MiCamera:motionPhoto="1"/>
              </rdf:RDF>
            </x:xmpmeta>
            <?xpacket end="w"?>
        """.trimIndent()
    }

    private fun buildVivoMotionPhotoXmp(videoLength: Long, presentationTimestampUs: Long): String {
        return """
            <x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="Adobe XMP Core 5.1.0-jc003">
              <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                <rdf:Description rdf:about=""
                  xmlns:GCamera="$GCAMERA_NS"
                  xmlns:VCamera="$VIVO_CAMERA_NS"
                  xmlns:Container="http://ns.google.com/photos/1.0/container/"
                  xmlns:Item="http://ns.google.com/photos/1.0/container/item/"
                  GCamera:MotionPhoto="1"
                  GCamera:MotionPhotoVersion="1"
                  GCamera:MotionPhotoPresentationTimestampUs="$presentationTimestampUs"
                  VCamera:VMotionPhotoVersion="1"
                  VCamera:VMotionPhotoSource="1"
                  VCamera:VMediaKitVersion="$VIVO_MEDIA_KIT_VERSION">
                  <Container:Directory>
                    <rdf:Seq>
                      <rdf:li rdf:parseType="Resource"><Container:Item Item:Mime="image/jpeg" Item:Semantic="Primary" Item:Length="0" Item:Padding="0"/></rdf:li>
                      <rdf:li rdf:parseType="Resource"><Container:Item Item:Mime="video/mp4" Item:Semantic="MotionPhoto" Item:Length="$videoLength" Item:Padding="0"/></rdf:li>
                    </rdf:Seq>
                  </Container:Directory>
                </rdf:Description>
              </rdf:RDF>
            </x:xmpmeta>
        """.trimIndent()
    }

    private fun buildOpMotionPhotoXmp(videoLength: Long, presentationTimestampUs: Long, owner: String?): String {
        val ownerAttribute = owner?.takeIf { it.isNotBlank() }
            ?.let { """OpCamera:MotionPhotoOwner="$it"""" }
            .orEmpty()
        return """
            <x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="Adobe XMP Core 5.1.0-jc003">
              <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                <rdf:Description rdf:about=""
                  xmlns:GCamera="$GCAMERA_NS"
                  xmlns:OpCamera="$OP_CAMERA_NS"
                  xmlns:Container="http://ns.google.com/photos/1.0/container/"
                  xmlns:Item="http://ns.google.com/photos/1.0/container/item/"
                  GCamera:MotionPhoto="1"
                  GCamera:MotionPhotoVersion="1"
                  GCamera:MotionPhotoPresentationTimestampUs="$presentationTimestampUs"
                  OpCamera:MotionPhotoPrimaryPresentationTimestampUs="$presentationTimestampUs"
                  $ownerAttribute
                  OpCamera:OLivePhotoVersion="2"
                  OpCamera:VideoLength="$videoLength">
                  <Container:Directory>
                    <rdf:Seq>
                      <rdf:li rdf:parseType="Resource"><Container:Item Item:Mime="image/jpeg" Item:Semantic="Primary"/></rdf:li>
                      <rdf:li rdf:parseType="Resource"><Container:Item Item:Mime="video/mp4" Item:Semantic="MotionPhoto" Item:Length="$videoLength"/></rdf:li>
                    </rdf:Seq>
                  </Container:Directory>
                </rdf:Description>
              </rdf:RDF>
            </x:xmpmeta>
        """.trimIndent()
    }

    private fun buildHonorLiveTail(videoLength: Long): ByteArray {
        return ByteArrayOutputStream(HONOR_TAIL_TOTAL_LENGTH).use {
            it.write(fixedAsciiBlock("v2_f17"))
            it.write(fixedAsciiBlock("0:500"))
            it.write(fixedAsciiBlock("LIVE_${videoLength + HONOR_TAIL_SEGMENT_LENGTH}"))
            it.toByteArray()
        }
    }

    private fun fixedAsciiBlock(value: String): ByteArray {
        val raw = value.toByteArray(Charsets.UTF_8)
        val dst = ByteArray(HONOR_TAIL_SEGMENT_LENGTH) { 0x20 }
        System.arraycopy(raw, 0, dst, 0, minOf(raw.size, dst.size))
        return dst
    }

    private fun injectGenericXmp(jpegBytes: ByteArray, videoLength: Long, presentationTimestampUs: Long): ByteArray {
        var xmp = buildMotionPhotoXmp(videoLength, presentationTimestampUs, jpegBytes.size.toLong())
        var segment = buildXmpApp1Segment(xmp)
        repeat(3) {
            val next = buildMotionPhotoXmp(videoLength, presentationTimestampUs, (jpegBytes.size + segment.size).toLong())
            if (next == xmp) return@repeat
            xmp = next
            segment = buildXmpApp1Segment(xmp)
        }
        return ByteArrayOutputStream(jpegBytes.size + segment.size).use {
            it.write(JPEG_MARKER_PREFIX)
            it.write(JPEG_SOI)
            it.write(segment)
            it.write(jpegBytes, 2, jpegBytes.size - 2)
            it.toByteArray()
        }
    }

    fun encodeGeneric(coverJpegBytes: ByteArray, videoBytes: ByteArray, presentationTimestampUs: Long): ByteArray {
        val jpeg = injectGenericXmp(coverJpegBytes, videoBytes.size.toLong(), presentationTimestampUs)
        return ByteArrayOutputStream(jpeg.size + videoBytes.size).use {
            it.write(jpeg)
            it.write(videoBytes)
            it.toByteArray()
        }
    }

    private fun buildXiaomiLivePhotoExifApp1Segment(): ByteArray {
        val tiff = ByteArrayOutputStream().use {
            writeAscii(it, "MM")
            writeU16(it, 0x002A)
            writeU32(it, 0x00000008)
            writeU16(it, 1)
            writeIfdEntry(it, EXIF_TAG_EXIF_IFD_POINTER, TIFF_TYPE_LONG, 1, 26)
            writeU32(it, 0)
            writeU16(it, 2)
            writeIfdEntry(it, EXIF_TAG_MOTION_PHOTO, TIFF_TYPE_BYTE, 1, 1 shl 24)
            writeIfdEntry(it, EXIF_TAG_LIVE_PHOTO, TIFF_TYPE_BYTE, 1, 1 shl 24)
            writeU32(it, 0)
            it.toByteArray()
        }
        val payload = ByteArrayOutputStream().use {
            writeAscii(it, "Exif")
            it.write(0)
            it.write(0)
            it.write(tiff)
            it.toByteArray()
        }
        return app1Segment(payload)
    }

    private fun buildMpfApp2Segment(primaryImageLength: Long): ByteArray {
        val entry = ByteArrayOutputStream(16).use {
            writeU32(it, 0x00030000)
            writeU32(it, primaryImageLength.toInt())
            writeU32(it, 0)
            writeU16(it, 0)
            writeU16(it, 0)
            it.toByteArray()
        }
        val entryOffset = 8 + 2 + (12 * 3) + 4
        val tiff = ByteArrayOutputStream().use {
            writeAscii(it, "MM")
            writeU16(it, 0x002A)
            writeU32(it, 0x00000008)
            writeU16(it, 3)
            writeIfdEntry(it, MPF_TAG_VERSION, TIFF_TYPE_UNDEFINED, 4, 0x30313030)
            writeIfdEntry(it, MPF_TAG_NUMBER_OF_IMAGES, TIFF_TYPE_LONG, 1, 1)
            writeIfdEntry(it, MPF_TAG_MP_ENTRY, TIFF_TYPE_UNDEFINED, entry.size, entryOffset)
            writeU32(it, 0)
            it.write(entry)
            it.toByteArray()
        }
        val payload = ByteArrayOutputStream().use {
            writeAscii(it, "MPF")
            it.write(0)
            it.write(tiff)
            it.toByteArray()
        }
        val length = payload.size + 2
        return ByteArrayOutputStream(length + 2).use {
            it.write(JPEG_MARKER_PREFIX)
            it.write(JPEG_APP2)
            writeU16(it, length)
            it.write(payload)
            it.toByteArray()
        }
    }

    private fun app1Segment(payload: ByteArray): ByteArray {
        val length = payload.size + 2
        return ByteArrayOutputStream(length + 2).use {
            it.write(JPEG_MARKER_PREFIX)
            it.write(JPEG_APP1)
            writeU16(it, length)
            it.write(payload)
            it.toByteArray()
        }
    }

    private fun insertApp2SegmentBeforeFirstNonApp(jpegBytes: ByteArray, app2Segment: ByteArray): ByteArray {
        if (!isJpeg(jpegBytes)) return jpegBytes
        var offset = 2
        while (offset + 4 <= jpegBytes.size && (jpegBytes[offset].toInt() and 0xFF) == JPEG_MARKER_PREFIX) {
            val marker = jpegBytes[offset + 1].toInt() and 0xFF
            if (marker !in 0xE0..0xEF && marker != 0xFE) break
            val length = ((jpegBytes[offset + 2].toInt() and 0xFF) shl 8) or (jpegBytes[offset + 3].toInt() and 0xFF)
            if (length < 2 || offset + 2 + length > jpegBytes.size) break
            offset += 2 + length
        }
        return ByteArrayOutputStream(jpegBytes.size + app2Segment.size).use {
            it.write(jpegBytes, 0, offset)
            it.write(app2Segment)
            it.write(jpegBytes, offset, jpegBytes.size - offset)
            it.toByteArray()
        }
    }

    private fun isJpeg(bytes: ByteArray): Boolean {
        return bytes.size >= 2 &&
            (bytes[0].toInt() and 0xFF) == JPEG_MARKER_PREFIX &&
            (bytes[1].toInt() and 0xFF) == JPEG_SOI
    }

    private fun writeIfdEntry(out: ByteArrayOutputStream, tag: Int, type: Int, count: Int, valueOrOffset: Int) {
        writeU16(out, tag)
        writeU16(out, type)
        writeU32(out, count)
        writeU32(out, valueOrOffset)
    }

    private fun writeAscii(out: ByteArrayOutputStream, text: String) {
        out.write(text.toByteArray(Charsets.US_ASCII))
    }

    private fun writeU16(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    private fun writeU32(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 24) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }
}
