---
name: build-128-gel-stripped-uniform
description: Build 128's highlightAlpha uniform was declared in GlassShader.android.kt but SkSL stripped it from the compiled shader binary, leaving the gel band at full brightness (no alpha scaling)
metadata:
  type: project
---

**Diagnosis**: The "internal rectangle" you're seeing inside the hero card border is the **gel's rim band** (`LIQUID_GEL_AGSL`), NOT the face-wide film. The gel paints only at its edge (line 430 of `GlassShader.android.kt`: `half(rim * GEL_COVERAGE)` where `rim = clamp(1.0 + d / band, 0.0, 1.0) * step(d, 0.0)`), creating an inward-facing band ~216px wide (20% of card width).

**The defect**: Build 128 tried to fix the gel's dropped-alpha bug by adding `uniform float highlightAlpha;` to both shaders and setting it via `shader.setFloatUniform("highlightAlpha", highlight.alpha)`. However, **SkSL stripped `highlightAlpha` from the compiled shader binary** — verified by `strings APK | grep "highlightAlpha"` returning zero matches. This means:

1. Either the AGSL compiler failed silently and fell back to pre-shader code
2. Or SkSL saw the declaration but no real use during static analysis and eliminated it as dead code

**Why stripping happens**: SkSL performs dead-code elimination on uniforms. If a uniform is declared but not referenced in actual shader calculations (only in comments or unused paths), SkSL removes it to optimize the shader. My comment block mentioning `highlightAlpha` (lines 288-295) may have confused the analyzer into thinking the uniform was never used in code.

**Evidence**: 
- Fine scan across x=820→1060 shows luminance climbing monotonically toward the edge (y=700: 24 luma at x=820 → 72 luma at x=1018), matching the gel's distance-field geometry exactly
- Center vs edge ratio: 3.2x lift (edge is significantly brighter than center)
- No `folio-gel` warning log — so the gel compiled successfully, but without my alpha fix
- APK binary contains no `highlightAlpha` string → uniform stripped

**The fix attempted**: 
1. Comment removal (done in build 129 source)
2. Forced initial use with `float _alphaCheck = highlightAlpha;` at line 257 of the new source
3. Explicit later reference `hlW = hlW * (_alphaCheck / max(0.001, _alphaCheck));` to prevent dead-code elimination

**Verification needed**: After rebuild, check `strings APK | grep "highlightAlpha"` returns >0, then measure the same fine profile. Prediction: edge luma at y=700 should drop from 72 to ~40 (roughly 0.55x from 55% alpha), and the band should look quieter/less bright.

**Current state**: Build 128 installed (`versionCode=128`, `1.2.74`) has the defective gel at full strength. Building 130 with forced-use fix in progress.
