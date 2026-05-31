package io.github.jtdsmz.albumlive.data

import org.junit.Assert.assertTrue
import org.junit.Test

class MotionPhotoOemHelpersTest {
    @Test
    fun genericEncoderKeepsJpegHeaderAndMotionMetadata() {
        val jpeg = byteArrayOf(
            0xFF.toByte(),
            0xD8.toByte(),
            0xFF.toByte(),
            0xD9.toByte()
        )
        val video = "ftypmp42mdatmoov".toByteArray()
        val output = MotionPhotoOemHelpers.encodeGeneric(jpeg, video, 1_500_000L)
        val text = output.decodeToString()

        assertTrue(output[0] == 0xFF.toByte())
        assertTrue(output[1] == 0xD8.toByte())
        assertTrue(text.contains("MotionPhoto"))
        assertTrue(text.contains("Item:Semantic=\"MotionPhoto\""))
    }
}
