package com.folio.reader.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import io.androidpoet.mirage.HalftoneDotsGrid
import io.androidpoet.mirage.HalftoneDotsType
import io.androidpoet.mirage.core.SizingParams
import io.androidpoet.mirage.core.mirageEffect
import io.androidpoet.mirage.core.setColorUniform4f
import io.androidpoet.mirage.halftoneDots
import io.androidpoet.mirage.paperTexture

/**
 * The two Mirage families that have **no clock of their own**, hosted with the clock switched off.
 *
 * ## The defect this exists for
 *
 * `PaperTexture` and `HalftoneDots` are static by construction: neither shader declares a `u_time`
 * uniform, and neither public entry point exposes a `speed` parameter. But both forward to
 * `mirageEffect` *without* passing one:
 *
 * ```
 * // io.androidpoet.mirage.paperTexture, decompiled
 * ): Modifier = mirageEffect(shaderSource = SHADER, sizing = sizing) { … }
 * ```
 *
 * so `speed` takes its default of `1f`, and `rememberShaderTime` then runs
 *
 * ```
 * LaunchedEffect(speed) {
 *   if (speed == 0f) return@LaunchedEffect        // ← the early-out we want and cannot reach
 *   while (true) withFrameNanos { … time += … }  // ← every vsync, forever
 * }
 * ```
 *
 * The host reads that time `State` inside its `graphicsLayer` block, so the layer is invalidated
 * **every frame at the display's refresh rate** and its `RenderEffect` is re-evaluated — running a
 * full-screen shader to reproduce a *bit-identical image*. PaperTexture is by a wide margin the
 * dearest shader in the set (per fragment: a 3-tap roughness fbm, two 9-cell crumple lattices, a
 * 4-octave fibre gradient sampled four times, two 15-iteration fold searches and a 9-cell drop
 * lattice), so at 1080×2400 and 90 Hz this is the single largest avoidable cost in the app. It is
 * why the paper field was reported as making the app unusable while the genuinely animated
 * families were fine.
 *
 * It is also why the earlier mitigation made paper *look worse*: with the clock unreachable the
 * only lever left was area, so the family was rendered at half scale, which coarsens the one term
 * (`roughness`) that reads `fragCoord` directly — the fibre-grain the texture is actually for. The
 * cost was halved in the one dimension that costs quality.
 *
 * ## The fix
 *
 * `mirageEffect` is public and *does* take `speed`. The only thing the public wrappers withhold is
 * their shader source, which is a private top-level `val` in the same class — so it is read once,
 * reflectively, and handed back to the library's own host with `speed = 0f`. The clock never
 * starts, the layer is invalidated only when a uniform actually changes (a theme switch), and the
 * family can therefore go back to **full resolution**, which is what it looked right at.
 *
 * Nothing is reimplemented: same shader, same host, same uniforms, same sizing. The only
 * difference is the one argument the wrapper declined to forward.
 *
 * ## Why reflection rather than vendoring the AGSL
 *
 * The alternative was copying ~270 lines of AGSL into this repo. That forks a shader we do not own
 * on the first upgrade, and a single transcription slip is a runtime shader-compile failure rather
 * than a compile error. Reading the field keeps one source of truth, and the failure mode is
 * benign: [staticShaderSource] returns null and each modifier falls through to the library's own
 * wrapper, i.e. exactly today's behaviour.
 *
 * **R8:** the field is private, so release builds need it kept. See `androidApp/proguard-rules.pro`.
 * If that rule is ever dropped the fallback takes over and the only symptom is the return of the
 * cost — never a crash and never a missing field.
 */
private const val MIRAGE_SHADER_FIELD = "SHADER"

/**
 * The private `SHADER` constant from one of Mirage's generated family classes, or null.
 *
 * Resolved at most once per class and cached, including the failure, so a stripped or renamed
 * field costs one reflective lookup for the life of the process rather than one per composition.
 */
private val staticShaderCache = HashMap<String, String?>()

private fun staticShaderSource(className: String): String? = synchronized(staticShaderCache) {
    staticShaderCache.getOrPut(className) {
        runCatching {
            Class.forName(className)
                .getDeclaredField(MIRAGE_SHADER_FIELD)
                .apply { isAccessible = true }
                .get(null) as? String
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }
}

/**
 * `Modifier.paperTexture`, with the dead vsync clock switched off.
 *
 * Signature-compatible with the parameters this app actually passes. Everything else is pinned to
 * the library's own defaults so the image is unchanged: `fiberSize` 0.2, `crumples` 0.3,
 * `crumpleSize` 0.35, `foldCount` 5, `fade` 0, `drops` 0.2, `seed` 5.8.
 */
@Composable
internal fun Modifier.folioStaticPaperTexture(
    colorFront: Color,
    colorBack: Color,
    contrast: Float,
    roughness: Float,
    fiber: Float,
    folds: Float,
    sizing: SizingParams,
): Modifier {
    val source = staticShaderSource("io.androidpoet.mirage.PaperTextureKt")
        ?: return paperTexture(
            colorFront = colorFront,
            colorBack = colorBack,
            contrast = contrast,
            roughness = roughness,
            fiber = fiber,
            folds = folds,
            sizing = sizing,
        )
    return mirageEffect(
        shaderSource = source,
        // The whole point. The shader declares no `u_time`, so this cannot change a pixel —
        // it only stops `rememberShaderTime` from requesting a frame forever.
        speed = 0f,
        startFrame = 0f,
        sizing = sizing,
    ) { _ ->
        setColorUniform4f("u_colorFront", colorFront)
        setColorUniform4f("u_colorBack", colorBack)
        setFloatUniform("u_contrast", contrast)
        setFloatUniform("u_roughness", roughness)
        setFloatUniform("u_fiber", fiber)
        setFloatUniform("u_fiberSize", 0.2f)
        setFloatUniform("u_crumples", 0.3f)
        setFloatUniform("u_crumpleSize", 0.35f)
        setFloatUniform("u_folds", folds)
        setFloatUniform("u_foldCount", 5f)
        setFloatUniform("u_fade", 0f)
        setFloatUniform("u_drops", 0.2f)
        setFloatUniform("u_seed", 5.8f)
    }
}

/**
 * `Modifier.halftoneDots`, with the dead vsync clock switched off.
 *
 * Same defect, same fix. Cheaper per fragment than paper, but it was paying the same
 * refresh-rate-locked full-screen re-render for an image that cannot change.
 */
@Composable
internal fun Modifier.folioStaticHalftoneDots(
    colorFront: Color,
    colorBack: Color,
    size: Float,
    radius: Float,
    contrast: Float,
    grid: HalftoneDotsGrid,
    type: HalftoneDotsType,
    sizing: SizingParams,
): Modifier {
    val source = staticShaderSource("io.androidpoet.mirage.HalftoneDotsKt")
        ?: return halftoneDots(
            colorFront = colorFront,
            colorBack = colorBack,
            size = size,
            radius = radius,
            contrast = contrast,
            grid = grid,
            type = type,
            sizing = sizing,
        )
    return mirageEffect(
        shaderSource = source,
        speed = 0f,
        startFrame = 0f,
        sizing = sizing,
    ) { _ ->
        setColorUniform4f("u_colorFront", colorFront)
        setColorUniform4f("u_colorBack", colorBack)
        setFloatUniform("u_size", size)
        setFloatUniform("u_radius", radius)
        setFloatUniform("u_contrast", contrast)
        setFloatUniform("u_originalColors", 0f)
        setFloatUniform("u_inverted", 0f)
        setFloatUniform("u_grainMixer", 0f)
        setFloatUniform("u_grainOverlay", 0f)
        setFloatUniform("u_grainSize", 0.5f)
        setFloatUniform("u_grid", grid.value)
        setFloatUniform("u_type", type.value)
    }
}
