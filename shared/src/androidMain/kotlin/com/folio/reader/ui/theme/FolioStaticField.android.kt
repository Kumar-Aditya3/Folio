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

/**
 * The one Mirage family with **no clock of its own**, hosted with the clock switched off.
 *
 * ## The defect this exists for
 *
 * `HalftoneDots` is static by construction: the shader declares no `u_time` uniform and the
 * public entry point exposes no `speed` parameter, but forwards to `mirageEffect` *without*
 * passing one:
 *
 * ```
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
 * The host reads that time `State` inside its `graphicsLayer` block, so the layer is
 * invalidated **every frame at the display's refresh rate** and its `RenderEffect` is
 * re-evaluated — running a full-screen shader to reproduce a *bit-identical image*.
 *
 * `PaperTexture` shared this host until it was removed outright: it was by a wide margin
 * the dearest shader in the set (per fragment: a 3-tap roughness fbm, two 9-cell crumple
 * lattices, a 4-octave fibre gradient sampled four times, two 15-iteration fold searches
 * and a 9-cell drop lattice) and it was the field the user reported as making the app
 * unusable — see MeshField.android.kt's dispatch note for the full record.
 *
 * ## The fix
 *
 * `mirageEffect` is public and *does* take `speed`. The only thing the public wrapper
 * withholds is its shader source, which is a private top-level `val` in the same class — so
 * it is read once, reflectively, and handed back to the library's own host with `speed = 0f`.
 * The clock never starts and the layer is invalidated only when a uniform actually changes.
 *
 * Nothing is reimplemented: same shader, same host, same uniforms, same sizing. The only
 * difference is the one argument the wrapper declined to forward.
 *
 * ## Why reflection rather than vendoring the AGSL
 *
 * The alternative was copying ~270 lines of AGSL into this repo. That forks a shader we do
 * not own on the first upgrade, and a single transcription slip is a runtime shader-compile
 * failure rather than a compile error. Reading the field keeps one source of truth, and the
 * failure mode is benign: [staticShaderSource] returns null and the modifier falls through
 * to the library's own wrapper, i.e. exactly the clock-forever behaviour this exists to fix.
 *
 * **R8:** the field is private, so release builds need it kept. See
 * `androidApp/proguard-rules.pro`. If that rule is ever dropped the fallback takes over and
 * the only symptom is the return of the cost — never a crash and never a missing field.
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
        val source = runCatching {
            Class.forName(className)
                .getDeclaredField(MIRAGE_SHADER_FIELD)
                .apply { isAccessible = true }
                .get(null) as? String
        }.getOrNull()?.takeIf { it.isNotBlank() }
        if (source == null) {
            // Degradation is silent by design at the call site -- this log is the one place
            // the fallback becomes visible. Without it, a stripped or renamed class costs a
            // full-screen shader per vsync in release with nothing anywhere to say why, which
            // is exactly how this survived four rounds of fixes: the failure mode was
            // invisible wherever anyone was looking.
            android.util.Log.w(
                "FolioStaticField",
                "Mirage shader source for $className is unavailable in this build (stripped or " +
                    "renamed? See androidApp/proguard-rules.pro); falling back to the library " +
                    "wrapper, whose per-vsync clock re-runs the static full-screen shader every frame.",
            )
        }
        source
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
