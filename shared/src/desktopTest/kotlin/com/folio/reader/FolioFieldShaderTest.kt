package com.folio.reader

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.folio.reader.ui.theme.AppPalette
import com.folio.reader.ui.theme.FOLIO_FIELD_UNIFIED
import com.folio.reader.ui.theme.FolioFieldKind
import com.folio.reader.ui.theme.atmosphereFor
import com.folio.reader.ui.theme.designedFieldFrameMs
import com.folio.reader.ui.theme.designedFieldShader
import com.folio.reader.ui.theme.fieldSpeed
import com.folio.reader.ui.theme.folioDesignedFieldColors
import com.folio.reader.ui.theme.oklabChroma
import org.jetbrains.skia.Data
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Paint
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.Surface
import java.awt.image.BufferedImage
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Raster the actual programs, not a Kotlin approximation of their geometry or colour. */
class FolioFieldShaderTest {
    private val kinds = listOf(
        FolioFieldKind.Warp, FolioFieldKind.Voronoi, FolioFieldKind.MeshGradient,
        FolioFieldKind.ColorPanels, FolioFieldKind.Heatmap, FolioFieldKind.LiquidMetal,
        FolioFieldKind.Metaballs, FolioFieldKind.Waves, FolioFieldKind.Water,
        FolioFieldKind.Spiral, FolioFieldKind.PerlinNoise, FolioFieldKind.SmokeRing,
    )
    private val directory = File("build/visual-review/fields")

    @Test
    fun eachCriticizedFieldCompilesCoversTheScreenAndActuallyMoves() {
        assertEquals(kinds.toSet(), FolioFieldKind.entries.filter { it.designedFieldShader() != null }.toSet())
        assertEquals(FolioFieldKind.DotOrbit, FOLIO_FIELD_UNIFIED)
        assertEquals(null, FolioFieldKind.DotOrbit.designedFieldShader(), "Keep the acclaimed Orbit renderer")
        assertEquals(0.9f, FolioFieldKind.DotOrbit.fieldSpeed())
        val panels = mutableListOf<Pair<String, BufferedImage>>()
        for (kind in kinds) {
            assertTrue(kind.designedFieldFrameMs() in 33L..50L, "$kind is refresh-rate locked")
            RuntimeEffect.makeForShader(requireNotNull(kind.designedFieldShader())).use { effect ->
                for (palette in listOf(AppPalette.MAGENTA, AppPalette.PINK)) {
                    val colors = colors(palette)
                    val first = render(effect, colors, 0f, 180, 400)
                    val next = render(effect, colors, 8f * requireNotNull(kind.fieldSpeed()), 180, 400)
                    var changed = 0
                    val unique = mutableSetOf<Int>()
                    for (y in 0 until first.height step 3) for (x in 0 until first.width step 3) {
                        val a = first.getRGB(x, y)
                        val b = next.getRGB(x, y)
                        assertEquals(255, a ushr 24, "$kind leaves transparent ground at $x/$y")
                        unique += a
                        if (channelDifference(a, b) > 3) changed++
                    }
                    val minimumColors = if (kind == FolioFieldKind.ColorPanels) 5 else 40
                    assertTrue(unique.size >= minimumColors, "$kind/${palette.id} is an almost-flat fill (${unique.size} colours)")
                    assertTrue(changed > 40, "$kind/${palette.id} did not visibly move ($changed samples)")
                    val zero = render(effect, colors, 5f, 73, 151, intensity = 0f)
                    assertTrue((0 until zero.height).all { y ->
                        (0 until zero.width).all { x -> channelDifference(zero.getRGB(x, y), argb(colors[0])) <= 1 }
                    }, "$kind ignores the zero-presence control")
                    panels += "${kind.name}-${palette.id}" to next
                    save(next, "${kind.name}-${palette.id}.png")
                }
                // Wide and tiny bounds, both ends of the size control, and the polar core.
                for (scale in listOf(0.5f, 2f)) {
                    val image = render(effect, colors(AppPalette.MAGENTA), 2.1f, 320, 143, scale)
                    assertTrue((0 until image.height).all { y ->
                        (0 until image.width).all { x -> image.getRGB(x, y) ushr 24 == 255 }
                    }, "$kind has an unpainted edge at scale $scale")
                }
            }
        }
        val sheet = BufferedImage(6 * 180, 4 * 428, BufferedImage.TYPE_INT_RGB)
        val graphics = sheet.createGraphics()
        try {
            graphics.color = java.awt.Color(0x202027)
            graphics.fillRect(0, 0, sheet.width, sheet.height)
            panels.forEachIndexed { index, (label, image) ->
                val x = index % 6 * 180
                val y = index / 6 * 428
                graphics.drawImage(image, x, y + 28, null)
                graphics.color = java.awt.Color.WHITE
                graphics.drawString(label, x + 4, y + 18)
            }
        } finally {
            graphics.dispose()
        }
        save(sheet, "contact-sheet.png")
    }

    @Test
    fun authoredDisplayColorsKeepOrbitsSwatchOrderWithStrongerDarkPigment() {
        for (palette in AppPalette.entries) for (vibrancy in listOf(0f, 0.5f, 1f)) {
            val colors = colors(palette, vibrancy)
            assertEquals(6, colors.size)
            val atmo = atmosphereFor(palette.colors, vibrancy = vibrancy)
            val authored = (listOf(palette.colors.primary, palette.colors.secondary, palette.colors.tertiary) +
                atmo.pools).distinct()
            val up = ((vibrancy - 0.5f) / 0.5f).coerceIn(0f, 1f)
            val sink = 0.36f * (1f - up * 0.5f)
            for ((index, color) in colors.drop(1).withIndex()) {
                val expected = if (palette.isDark) lerp(authored[index % authored.size], colors[0], sink)
                else authored[index % authored.size]
                assertEquals(expected, color, "${palette.id}/$vibrancy swatch $index changed identity")
                assertEquals(1f, color.alpha)
                if (palette.isDark && vibrancy == 0.5f) {
                    val orbit = lerp(authored[index % authored.size], colors[0], 0.52f)
                    assertTrue(oklabChroma(color) >= oklabChroma(orbit) * 1.05f,
                        "${palette.id} swatch $index still looks as faded as before")
                }
            }
        }
        val back = Color(0xFF101010)
        assertEquals(List(6) { back }, folioDesignedFieldColors(back, emptyList(), false, 0.5f))
    }

    @Test
    fun colorPanelsAreFlatRigidPlanesNotGlossyDeformingTubes() {
        RuntimeEffect.makeForShader(requireNotNull(FolioFieldKind.ColorPanels.designedFieldShader())).use { effect ->
            val colors = colors(AppPalette.MAGENTA)
            for (seconds in listOf(0f, 1f, 3f)) {
                val image = render(effect, colors, seconds * requireNotNull(FolioFieldKind.ColorPanels.fieldSpeed()),
                    180, 400, intensity = 1f)
                var interior = 0
                for (y in 0 until image.height step 3) for (x in 0 until image.width step 3) {
                    val px = (x + 0.5f - image.width * 0.5f) / image.width
                    val py = (y + 0.5f - image.height * 0.5f) / image.width
                    val column = (px - 0.12f * py - seconds * requireNotNull(FolioFieldKind.ColorPanels.fieldSpeed()) * 0.20f) / 0.34f
                    val local = column - kotlin.math.floor(column)
                    if (local !in 0.06f..0.94f) continue
                    interior++
                    val swatch = ((kotlin.math.floor(column).toInt() % 5) + 5) % 5
                    assertTrue(channelDifference(image.getRGB(x, y), argb(colors[1 + swatch])) <= 2,
                        "Panel $swatch at $x/$y has a glossy rim or fading body")
                }
                assertTrue(interior > 1_000)
            }
        }
    }

    @Test
    fun designsHaveNoImageInputOrRayMarchingDependency() {
        for (kind in kinds) {
            val source = requireNotNull(kind.designedFieldShader())
            assertFalse(source.contains("uniform shader"), "$kind still needs a filtered image to exist")
            assertFalse(source.contains("while ("), "$kind has unbounded fragment work")
        }
    }

    @Test
    fun movementIsVisibleInOneSecondAndShapesKeepThePalettesChroma() {
        val report = StringBuilder("field,palette,startSeconds,activeSamples,changedFraction,meanDifference,chroma90\n")
        val problems = mutableListOf<String>()
        for (kind in kinds) {
            RuntimeEffect.makeForShader(requireNotNull(kind.designedFieldShader())).use { effect ->
                for (palette in listOf(AppPalette.MAGENTA, AppPalette.PINK, AppPalette.DEEPSEA)) {
                    val colors = colors(palette)
                    for (seconds in listOf(0f, 3f, 7f)) {
                        val first = render(effect, colors, seconds * requireNotNull(kind.fieldSpeed()), 120, 264)
                        val next = render(effect, colors, (seconds + 1f) * requireNotNull(kind.fieldSpeed()), 120, 264)
                        var active = 0
                        var changed = 0
                        var totalDifference = 0L
                        val chroma = mutableListOf<Float>()
                        for (y in 0 until first.height step 3) for (x in 0 until first.width step 3) {
                            val a = first.getRGB(x, y)
                            val b = next.getRGB(x, y)
                            if (maxOf(channelDifference(a, argb(colors[0])), channelDifference(b, argb(colors[0]))) < 12) continue
                            active++
                            val difference = channelDifference(a, b)
                            if (difference >= 15) changed++
                            totalDifference += difference
                            chroma += oklabChroma(Color(a))
                        }
                        val fraction = changed.toDouble() / active.coerceAtLeast(1)
                        val difference = totalDifference.toDouble() / active.coerceAtLeast(1)
                        chroma.sort()
                        val percentile = chroma.getOrElse((chroma.size * 0.9).toInt()) { 0f }
                        val paletteChroma = colors.drop(1).maxOf { oklabChroma(it) }
                        report.append("$kind,${palette.id},$seconds,$active,$fraction,$difference,$percentile\n")
                        if (fraction < 0.40 || difference < 16.0) {
                            problems += "$kind/${palette.id} at ${seconds}s has weak one-second movement: $fraction/$difference"
                        }
                        if (percentile < paletteChroma * 0.25f) {
                            problems += "$kind/${palette.id} at ${seconds}s loses chroma: $percentile from $paletteChroma"
                        }
                    }
                }
            }
        }
        directory.mkdirs()
        File(directory, "movement-color-metrics.csv").writeText(report.toString())
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    @Test
    fun fixedTimeMotionStripUsesTheSamePaletteAndClockAsThePhone() {
        val width = 120
        val height = 264
        val sheet = BufferedImage(width * 6, (height + 24) * kinds.size, BufferedImage.TYPE_INT_RGB)
        val graphics = sheet.createGraphics()
        try {
            kinds.forEachIndexed { row, kind ->
                RuntimeEffect.makeForShader(requireNotNull(kind.designedFieldShader())).use { effect ->
                    for (frame in 0 until 6) {
                        val image = render(effect, colors(AppPalette.MAGENTA),
                            frame * requireNotNull(kind.fieldSpeed()), width, height)
                        graphics.drawImage(image, frame * width, row * (height + 24) + 24, null)
                        graphics.color = java.awt.Color.WHITE
                        graphics.drawString("${kind.name} ${frame}s", frame * width + 4, row * (height + 24) + 17)
                    }
                }
            }
        } finally {
            graphics.dispose()
        }
        save(sheet, "motion-strip.png")
    }

    @Test
    fun compareEveryFieldWithTheActualShippedDotOrbitShader() {
        val source = requireNotNull(javaClass.getResource("/field-reference-dot-orbit.sksl")).readText()
        val width = 120
        val height = 264
        val families = listOf(FolioFieldKind.DotOrbit) + kinds
        val palettes = listOf(AppPalette.MAGENTA, AppPalette.PINK, AppPalette.DEEPSEA, AppPalette.DARK)
        val sheet = BufferedImage(width * palettes.size, (height + 24) * families.size, BufferedImage.TYPE_INT_RGB)
        val graphics = sheet.createGraphics()
        try {
            families.forEachIndexed { row, kind ->
                RuntimeEffect.makeForShader(if (kind == FolioFieldKind.DotOrbit) source
                else requireNotNull(kind.designedFieldShader())).use { effect ->
                    palettes.forEachIndexed { column, palette ->
                        val colors = colors(palette, orbit = kind == FolioFieldKind.DotOrbit)
                        val time = 3f * requireNotNull(kind.fieldSpeed())
                        val image = if (kind == FolioFieldKind.DotOrbit) renderOrbit(effect, colors, time, width, height)
                        else render(effect, colors, time, width, height)
                        graphics.drawImage(image, column * width, row * (height + 24) + 24, null)
                        graphics.color = java.awt.Color.WHITE
                        graphics.drawString("${kind.name}-${palette.id}", column * width + 3, row * (height + 24) + 17)
                        if (kind == FolioFieldKind.DotOrbit) save(image, "DotOrbit-${palette.id}.png")
                    }
                }
            }
        } finally {
            graphics.dispose()
        }
        save(sheet, "orbit-comparison.png")
    }

    private fun colors(palette: AppPalette, vibrancy: Float = 0.5f, orbit: Boolean = false): List<Color> {
        val atmo = atmosphereFor(palette.colors, vibrancy = vibrancy)
        val back = if (palette.isDark) lerp(lerp(atmo.fieldTop, atmo.fieldBottom, 0.35f), Color.Black, 0.22f)
        else atmo.fieldTop
        val authored = (listOf(palette.colors.primary, palette.colors.secondary, palette.colors.tertiary) +
            atmo.pools).distinct()
        if (!orbit) return folioDesignedFieldColors(back, authored, palette.isDark, vibrancy)
        val up = ((vibrancy - 0.5f) / 0.5f).coerceIn(0f, 1f)
        val sink = 0.52f * (1f - up * 0.5f)
        val working = if (palette.isDark) authored.map { lerp(it, back, sink) } else authored
        return listOf(back) + List(5) { working[it % working.size] }
    }

    private fun render(
        effect: RuntimeEffect,
        colors: List<Color>,
        time: Float,
        width: Int,
        height: Int,
        scale: Float = 1f,
        intensity: Float = 0.9f,
    ): BufferedImage {
        // SkSL uniforms are tightly packed, in declaration order (23 float slots).
        val buffer = ByteBuffer.allocate(92).order(ByteOrder.nativeOrder())
        buffer.putFloat(width.toFloat()).putFloat(height.toFloat())
            .putFloat(time).putFloat(scale).putFloat(intensity)
        colors.forEach { buffer.putFloat(it.red).putFloat(it.green).putFloat(it.blue) }
        return raster(effect, buffer, width, height)
    }

    private fun renderOrbit(effect: RuntimeEffect, colors: List<Color>, time: Float, width: Int, height: Int): BufferedImage {
        val buffer = ByteBuffer.allocate(252).order(ByteOrder.nativeOrder())
        // SizingParams(scale = 1): contain, centred, unrotated. One pixel here is a half-scale dp.
        listOf(width.toFloat(), height.toFloat(), 1f, 1f, 0.5f, 0.5f, 0f, 0f, 1f, 1f, 0f, 0f, 0f, time)
            .forEach { buffer.putFloat(it) }
        (listOf(colors[0]) + List(10) { colors[1 + it % 5] }).forEach {
            buffer.putFloat(it.red).putFloat(it.green).putFloat(it.blue).putFloat(it.alpha)
        }
        // The actual unchanged Folio dispatch: five swatches, 16 gradations, .85 size, .14 range.
        listOf(5f, 16f, 0.85f, 0.14f, 0.56f + 0.9f * 0.28f).forEach { buffer.putFloat(it) }
        return raster(effect, buffer, width, height)
    }

    private fun raster(effect: RuntimeEffect, buffer: ByteBuffer, width: Int, height: Int): BufferedImage {
        Data.makeFromBytes(buffer.array()).use { uniforms ->
            effect.makeShader(uniforms, emptyArray(), null).use { shader ->
                Paint().use { paint ->
                    paint.shader = shader
                    Surface.makeRasterN32Premul(width, height).use { surface ->
                        surface.canvas.drawPaint(paint)
                        surface.makeImageSnapshot().use { image ->
                            image.encodeToData(EncodedImageFormat.PNG)!!.use { png ->
                                return ImageIO.read(png.bytes.inputStream())
                            }
                        }
                    }
                }
            }
        }
    }

    private fun channelDifference(a: Int, b: Int): Int =
        abs((a and 255) - (b and 255)) + abs((a shr 8 and 255) - (b shr 8 and 255)) +
            abs((a shr 16 and 255) - (b shr 16 and 255))

    private fun argb(color: Color): Int = (255 shl 24) or ((color.red * 255 + 0.5f).toInt() shl 16) or
        ((color.green * 255 + 0.5f).toInt() shl 8) or (color.blue * 255 + 0.5f).toInt()

    private fun save(image: BufferedImage, name: String) {
        directory.mkdirs()
        ImageIO.write(image, "png", File(directory, name))
    }
}
