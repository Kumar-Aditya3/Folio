package com.folio.reader.epub

import java.io.File
import java.io.RandomAccessFile

/**
 * Reads an image's intrinsic pixel size from its file header without decoding
 * the pixels. Used to stamp `width`/`height` on `<img>` before the reader lays
 * out, so the browser reserves the correct box on the first frame instead of
 * measuring a zero-height image and stacking the following text on top of the
 * art (the "text in front of images" race) or reflowing when the bytes decode.
 *
 * foliate-js and Readium rely on the browser's own box reservation from
 * `width`/`height` attributes; that only works when the attributes are present,
 * which publisher markup often omits — this fills them in from the bytes.
 *
 * Header-only: PNG/GIF/BMP/WebP need a few dozen bytes; JPEG's SOF marker can
 * sit past a large EXIF/ICC block, so up to [HEADER_BYTES] are scanned before
 * giving up (the caller then simply leaves the image unstamped).
 */
object ImageDimensions {

    data class Size(val width: Int, val height: Int)

    private const val HEADER_BYTES = 256 * 1024

    /** Intrinsic size for a resolved resource [file], or null when it is not a parseable image. */
    fun read(file: File): Size? {
        if (!file.isFile) return null
        val ext = file.extension.lowercase()
        // SVG is text; the others are read as a header byte window.
        if (ext == "svg") return runCatching { readSvg(file) }.getOrNull()
        val len = file.length()
        if (len < 16) return null
        val buf = ByteArray(minOf(len, HEADER_BYTES.toLong()).toInt())
        runCatching {
            RandomAccessFile(file, "r").use { it.readFully(buf) }
        }.getOrElse { return null }
        return when {
            isPng(buf) -> readPng(buf)
            isJpeg(buf) -> readJpeg(buf)
            isGif(buf) -> readGif(buf)
            isWebp(buf) -> readWebp(buf)
            isBmp(buf) -> readBmp(buf)
            else -> null
        }?.takeIf { it.width in 1..20000 && it.height in 1..20000 }
    }

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun be16(b: ByteArray, i: Int) = (u8(b, i) shl 8) or u8(b, i + 1)
    private fun le16(b: ByteArray, i: Int) = (u8(b, i + 1) shl 8) or u8(b, i)
    private fun be32(b: ByteArray, i: Int) =
        (u8(b, i) shl 24) or (u8(b, i + 1) shl 16) or (u8(b, i + 2) shl 8) or u8(b, i + 3)
    private fun le32(b: ByteArray, i: Int) =
        (u8(b, i + 3) shl 24) or (u8(b, i + 2) shl 16) or (u8(b, i + 1) shl 8) or u8(b, i)

    private fun isPng(b: ByteArray) = b.size >= 24 &&
        u8(b, 0) == 0x89 && u8(b, 1) == 0x50 && u8(b, 2) == 0x4E && u8(b, 3) == 0x47
    private fun readPng(b: ByteArray) = Size(be32(b, 16), be32(b, 20))

    private fun isGif(b: ByteArray) = b.size >= 10 &&
        u8(b, 0) == 0x47 && u8(b, 1) == 0x49 && u8(b, 2) == 0x46
    private fun readGif(b: ByteArray) = Size(le16(b, 6), le16(b, 8))

    private fun isBmp(b: ByteArray) = b.size >= 26 && u8(b, 0) == 0x42 && u8(b, 1) == 0x4D
    private fun readBmp(b: ByteArray) = Size(le32(b, 18), kotlin.math.abs(le32(b, 22)))

    private fun isJpeg(b: ByteArray) = b.size >= 4 && u8(b, 0) == 0xFF && u8(b, 1) == 0xD8

    private val SOF_MARKERS = intArrayOf(
        0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF
    )

    private fun readJpeg(b: ByteArray): Size? {
        var i = 2
        val n = b.size
        while (i + 1 < n) {
            if (u8(b, i) != 0xFF) { i++; continue }
            val marker = u8(b, i + 1)
            // Padding fill bytes.
            if (marker == 0xFF) { i++; continue }
            // Standalone markers carry no length: RSTn/SOI/EOI/TEM.
            if (marker in 0xD0..0xD9 || marker == 0x01) { i += 2; continue }
            if (i + 3 >= n) break
            val seg = be16(b, i + 2)
            if (marker in SOF_MARKERS) {
                if (i + 8 >= n) break
                return Size(be16(b, i + 7), be16(b, i + 5))
            }
            if (seg < 2) break
            i += 2 + seg
        }
        return null
    }

    private fun isWebp(b: ByteArray) = b.size >= 30 &&
        u8(b, 0) == 0x52 && u8(b, 1) == 0x49 && u8(b, 2) == 0x46 && u8(b, 3) == 0x46 && // RIFF
        u8(b, 8) == 0x57 && u8(b, 9) == 0x45 && u8(b, 10) == 0x42 && u8(b, 11) == 0x50 // WEBP

    private fun readWebp(b: ByteArray): Size? {
        // Chunk fourCC at 12..15.
        val c0 = u8(b, 12); val c1 = u8(b, 13); val c2 = u8(b, 14); val c3 = u8(b, 15)
        // "VP8X" extended: 24-bit little-endian canvas (width-1, height-1) at 24 and 27.
        if (c0 == 0x56 && c1 == 0x50 && c2 == 0x38 && c3 == 0x58) {
            val w = (u8(b, 24) or (u8(b, 25) shl 8) or (u8(b, 26) shl 16)) + 1
            val h = (u8(b, 27) or (u8(b, 28) shl 8) or (u8(b, 29) shl 16)) + 1
            return Size(w, h)
        }
        // "VP8 " lossy: start code 0x9d 0x01 0x2a at 23..25, then 14-bit width/height.
        if (c0 == 0x56 && c1 == 0x50 && c2 == 0x38 && c3 == 0x20) {
            if (b.size < 30) return null
            val w = le16(b, 26) and 0x3FFF
            val h = le16(b, 28) and 0x3FFF
            return Size(w, h)
        }
        // "VP8L" lossless: signature 0x2F at 20, then packed 14-bit (width-1, height-1).
        if (c0 == 0x56 && c1 == 0x50 && c2 == 0x38 && c3 == 0x4C) {
            if (b.size < 25 || u8(b, 20) != 0x2F) return null
            val b0 = u8(b, 21); val b1 = u8(b, 22); val b2 = u8(b, 23); val b3 = u8(b, 24)
            val w = ((b0 or ((b1 and 0x3F) shl 8)) and 0x3FFF) + 1
            val h = ((((b1 shr 6) or (b2 shl 2) or ((b3 and 0x0F) shl 10))) and 0x3FFF) + 1
            return Size(w, h)
        }
        return null
    }

    private val SVG_TAG = Regex("(?is)<svg\\b[^>]*>")
    private val ATTR_W = Regex("(?i)\\bwidth\\s*=\\s*[\"']?\\s*([0-9]*\\.?[0-9]+)")
    private val ATTR_H = Regex("(?i)\\bheight\\s*=\\s*[\"']?\\s*([0-9]*\\.?[0-9]+)")
    private val VIEWBOX = Regex("(?i)\\bviewBox\\s*=\\s*[\"']\\s*([-0-9.]+)[,\\s]+([-0-9.]+)[,\\s]+([-0-9.]+)[,\\s]+([-0-9.]+)")

    private fun readSvg(file: File): Size? {
        // Only the opening tag is needed; cap the read for large inline SVGs.
        val head = file.bufferedReader().use { r ->
            val cbuf = CharArray(8192)
            val read = r.read(cbuf)
            if (read <= 0) "" else String(cbuf, 0, read)
        }
        val tag = SVG_TAG.find(head)?.value ?: return null
        val w = ATTR_W.find(tag)?.groupValues?.get(1)?.toDoubleOrNull()
        val h = ATTR_H.find(tag)?.groupValues?.get(1)?.toDoubleOrNull()
        if (w != null && h != null && w >= 1 && h >= 1) return Size(w.toInt(), h.toInt())
        val vb = VIEWBOX.find(tag) ?: return null
        val vw = vb.groupValues[3].toDoubleOrNull() ?: return null
        val vh = vb.groupValues[4].toDoubleOrNull() ?: return null
        if (vw < 1 || vh < 1) return null
        return Size(vw.toInt(), vh.toInt())
    }
}
