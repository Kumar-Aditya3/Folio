package com.folio.reader

import com.folio.reader.epub.ImageDimensions
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Header-only intrinsic-size parsing for the image-box reservation (see
 * ImageReserve). Synthetic minimal headers stand in for real files — only the
 * dimension fields matter.
 */
class ImageDimensionsTest {

    private fun bytes(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

    private fun tmp(ext: String, data: ByteArray): File {
        val f = File.createTempFile("imgdim", ".$ext")
        f.deleteOnExit()
        f.writeBytes(data)
        return f
    }

    private fun tmpText(ext: String, text: String): File {
        val f = File.createTempFile("imgdim", ".$ext")
        f.deleteOnExit()
        f.writeText(text)
        return f
    }

    @Test
    fun `png width and height`() {
        // 8-byte signature, IHDR length+type, width=800 (BE), height=600 (BE), padding.
        val data = bytes(
            0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x03, 0x20, 0x00, 0x00, 0x02, 0x58,
            0x08, 0x06, 0x00, 0x00, 0x00
        )
        assertEquals(ImageDimensions.Size(800, 600), ImageDimensions.read(tmp("png", data)))
    }

    @Test
    fun `gif width and height`() {
        // "GIF89a", width=320 (LE), height=240 (LE).
        val data = bytes(0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0x40, 0x01, 0xF0, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
        assertEquals(ImageDimensions.Size(320, 240), ImageDimensions.read(tmp("gif", data)))
    }

    @Test
    fun `bmp width and height`() {
        val data = ByteArray(26)
        data[0] = 0x42; data[1] = 0x4D
        // width=128 at 18, height=64 at 22 (LE32).
        data[18] = 0x80.toByte()
        data[22] = 0x40
        assertEquals(ImageDimensions.Size(128, 64), ImageDimensions.read(tmp("bmp", data)))
    }

    @Test
    fun `jpeg sof width and height`() {
        // SOI, SOF0 marker, length, precision, height=768 (BE), width=1024 (BE), padding.
        val data = bytes(
            0xFF, 0xD8, 0xFF, 0xC0, 0x00, 0x11, 0x08,
            0x03, 0x00, 0x04, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        )
        assertEquals(ImageDimensions.Size(1024, 768), ImageDimensions.read(tmp("jpg", data)))
    }

    @Test
    fun `webp vp8x width and height`() {
        val data = ByteArray(30)
        "RIFF".forEachIndexed { i, c -> data[i] = c.code.toByte() }
        "WEBP".forEachIndexed { i, c -> data[8 + i] = c.code.toByte() }
        "VP8X".forEachIndexed { i, c -> data[12 + i] = c.code.toByte() }
        // canvas width-1 = 1999 (LE24) at 24, height-1 = 1499 (LE24) at 27.
        data[24] = 0xCF.toByte(); data[25] = 0x07; data[26] = 0x00
        data[27] = 0xDB.toByte(); data[28] = 0x05; data[29] = 0x00
        assertEquals(ImageDimensions.Size(2000, 1500), ImageDimensions.read(tmp("webp", data)))
    }

    @Test
    fun `svg explicit width height`() {
        val svg = "<?xml version=\"1.0\"?><svg xmlns=\"http://www.w3.org/2000/svg\" width=\"640\" height=\"480\"><rect/></svg>"
        assertEquals(ImageDimensions.Size(640, 480), ImageDimensions.read(tmpText("svg", svg)))
    }

    @Test
    fun `svg falls back to viewBox`() {
        val svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 100 50\"><rect/></svg>"
        assertEquals(ImageDimensions.Size(100, 50), ImageDimensions.read(tmpText("svg", svg)))
    }

    @Test
    fun `non-image returns null`() {
        assertNull(ImageDimensions.read(tmp("bin", ByteArray(64) { 0x7A })))
    }
}
