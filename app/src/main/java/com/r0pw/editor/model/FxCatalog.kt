package com.r0pw.editor.model

class FxParam(val key: String, val label: String, val min: Float, val max: Float, val def: Float)

/** One step of a plugin effect: a built-in effect type plus parameter expressions. */
class PluginStep(val type: String, val params: Map<String, String>)

class FxDef(
    val type: String,
    val name: String,
    val category: String,
    val params: List<FxParam>,
    val stack: List<PluginStep>? = null,
    val pluginId: String? = null
)

object FxCatalog {
    val builtin: List<FxDef> = listOf(
        FxDef("color", "Color Correction", "Color", listOf(
            FxParam("brightness", "Brightness", -100f, 100f, 0f),
            FxParam("contrast", "Contrast", 0f, 3f, 1f),
            FxParam("saturation", "Saturation", 0f, 3f, 1f),
            FxParam("hue", "Hue", -180f, 180f, 0f))),
        FxDef("colorize", "Colorize", "Color", listOf(
            FxParam("hue", "Hue", 0f, 360f, 200f),
            FxParam("amount", "Amount", 0f, 100f, 70f))),
        FxDef("invert", "Invert", "Color", listOf(
            FxParam("amount", "Amount", 0f, 100f, 100f))),
        FxDef("chromakey", "Chroma Key", "Keying", listOf(
            FxParam("hue", "Key hue", 0f, 360f, 120f),
            FxParam("tolerance", "Tolerance", 0f, 180f, 40f),
            FxParam("softness", "Softness", 0f, 90f, 20f),
            FxParam("minsat", "Min saturation", 0f, 100f, 25f))),
        FxDef("blur", "Gaussian Blur", "Blur", listOf(
            FxParam("radius", "Radius", 0f, 100f, 12f))),
        FxDef("dirblur", "Motion Blur", "Blur", listOf(
            FxParam("length", "Length", 0f, 200f, 40f),
            FxParam("angle", "Angle", -180f, 180f, 0f))),
        FxDef("zoomblur", "Zoom Blur", "Blur", listOf(
            FxParam("amount", "Amount", 0f, 100f, 20f),
            FxParam("cx", "Center X %", 0f, 100f, 50f),
            FxParam("cy", "Center Y %", 0f, 100f, 50f))),
        FxDef("glow", "Glow", "Light", listOf(
            FxParam("radius", "Radius", 0f, 100f, 20f),
            FxParam("intensity", "Intensity %", 0f, 300f, 100f))),
        FxDef("dropshadow", "Drop Shadow", "Light", listOf(
            FxParam("distance", "Distance", 0f, 200f, 20f),
            FxParam("angle", "Angle", -180f, 180f, 45f),
            FxParam("blur", "Blur", 0f, 100f, 12f),
            FxParam("opacity", "Opacity %", 0f, 100f, 60f))),
        FxDef("vignette", "Vignette", "Light", listOf(
            FxParam("amount", "Amount %", 0f, 100f, 60f),
            FxParam("size", "Size", 0.2f, 1.5f, 0.7f),
            FxParam("softness", "Softness", 0f, 1f, 0.6f))),
        FxDef("chroma", "Chromatic Aberration", "Distort", listOf(
            FxParam("amount", "Amount", 0f, 100f, 8f),
            FxParam("angle", "Angle", -180f, 180f, 0f))),
        FxDef("glitch", "Glitch", "Distort", listOf(
            FxParam("amount", "Amount %", 0f, 100f, 50f),
            FxParam("slices", "Slices", 1f, 30f, 10f),
            FxParam("rate", "Rate (fps)", 1f, 30f, 12f),
            FxParam("rgb", "RGB split", 0f, 50f, 10f))),
        FxDef("shake", "Camera Shake", "Distort", listOf(
            FxParam("amplitude", "Amplitude", 0f, 200f, 20f),
            FxParam("freq", "Frequency", 0.1f, 30f, 8f),
            FxParam("rotation", "Rotation", 0f, 20f, 2f))),
        FxDef("wave", "Wave Warp", "Distort", listOf(
            FxParam("amplitude", "Amplitude", 0f, 100f, 15f),
            FxParam("frequency", "Frequency", 0f, 20f, 4f),
            FxParam("speed", "Speed", 0f, 20f, 3f))),
        FxDef("pixelate", "Pixelate", "Stylize", listOf(
            FxParam("size", "Block size", 1f, 100f, 12f))),
        FxDef("mirror", "Mirror", "Stylize", listOf(
            FxParam("mode", "0 = horizontal, 1 = vertical", 0f, 1f, 0f))),
        FxDef("noise", "Film Grain", "Stylize", listOf(
            FxParam("amount", "Amount %", 0f, 100f, 20f))),
        FxDef("sharpen", "Sharpen", "Stylize", listOf(
            FxParam("amount", "Amount %", 0f, 300f, 100f))),
        FxDef("wipe", "Wipe (transition)", "Transition", listOf(
            FxParam("progress", "Progress %", 0f, 100f, 100f),
            FxParam("direction", "0 L>R 1 R>L 2 T>B 3 B>T", 0f, 3f, 0f)))
    )

    @Volatile
    var plugins: List<FxDef> = emptyList()

    fun all(): List<FxDef> = builtin + plugins

    fun find(type: String): FxDef? = all().firstOrNull { it.type == type }

    fun create(type: String): EffectInst? {
        val d = find(type) ?: return null
        val e = EffectInst(newId(), type)
        for (p in d.params) e.tracks[p.key] = Track(p.def)
        return e
    }

    fun paramDef(type: String, key: String): FxParam? = find(type)?.params?.firstOrNull { it.key == key }
}
