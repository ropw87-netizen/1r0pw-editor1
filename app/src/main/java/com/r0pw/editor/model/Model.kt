package com.r0pw.editor.model

import java.util.UUID
import kotlin.math.abs

fun newId(): String = UUID.randomUUID().toString().substring(0, 8)

enum class LayerType { VIDEO, IMAGE, AUDIO, TEXT, SHAPE, NULL, CAMERA }

enum class BlendMode(val label: String) {
    NORMAL("Normal"), ADD("Add"), SCREEN("Screen"), MULTIPLY("Multiply"),
    OVERLAY("Overlay"), DARKEN("Darken"), LIGHTEN("Lighten")
}

enum class ShapeKind(val label: String) {
    RECT("Rectangle"), ROUNDRECT("Rounded"), ELLIPSE("Ellipse"), STAR("Star"), POLYGON("Polygon")
}

enum class TextAnim(val label: String) {
    NONE("None"), TYPEWRITER("Typewriter"), FADE_UP("Fade up"), POP("Pop"), WAVE("Wave")
}

enum class Interp { LINEAR, HOLD, BEZIER }

/** One keyframe. Time is in LAYER time (seconds since the layer's start). */
class Key(var t: Float, var v: Float) {
    var interp: Interp = Interp.LINEAR
    // cubic-bezier easing of the segment that STARTS at this key (CSS style control points)
    var x1 = 0.42f
    var y1 = 0f
    var x2 = 0.58f
    var y2 = 1f

    fun setEase(p: FloatArray) {
        interp = Interp.BEZIER
        x1 = p[0]; y1 = p[1]; x2 = p[2]; y2 = p[3]
    }
}

object Easing {
    val presets: List<Pair<String, FloatArray>> = listOf(
        "Linear" to floatArrayOf(0f, 0f, 1f, 1f),
        "Ease" to floatArrayOf(0.25f, 0.1f, 0.25f, 1f),
        "Ease In" to floatArrayOf(0.42f, 0f, 1f, 1f),
        "Ease Out" to floatArrayOf(0f, 0f, 0.58f, 1f),
        "In-Out" to floatArrayOf(0.42f, 0f, 0.58f, 1f),
        "Back Out" to floatArrayOf(0.34f, 1.56f, 0.64f, 1f),
        "Back In" to floatArrayOf(0.36f, 0f, 0.66f, -0.56f),
        "Expo Out" to floatArrayOf(0.16f, 1f, 0.3f, 1f),
        "Sharp" to floatArrayOf(0.9f, 0f, 0.1f, 1f)
    )

    private fun cubic(t: Float, a: Float, b: Float): Float {
        val u = 1f - t
        return 3f * a * u * u * t + 3f * b * u * t * t + t * t * t
    }

    private fun dCubic(t: Float, a: Float, b: Float): Float {
        val u = 1f - t
        return 3f * a * u * (1f - 3f * t) + 3f * b * t * (2f - 3f * t) + 3f * t * t
    }

    fun bezier(x: Float, x1: Float, y1: Float, x2: Float, y2: Float): Float {
        if (x <= 0f) return 0f
        if (x >= 1f) return 1f
        var t = x
        for (i in 0 until 8) {
            val err = cubic(t, x1, x2) - x
            if (abs(err) < 1e-5f) break
            val d = dCubic(t, x1, x2)
            if (abs(d) < 1e-5f) break
            t -= err / d
        }
        if (t < 0f || t > 1f) {
            var lo = 0f
            var hi = 1f
            t = x
            for (i in 0 until 24) {
                if (cubic(t, x1, x2) < x) lo = t else hi = t
                t = (lo + hi) / 2f
            }
        }
        return cubic(t, y1, y2)
    }

    /** Overshoot used by text animations. */
    fun backOut(u: Float): Float {
        val c1 = 1.70158f
        val c3 = c1 + 1f
        val x = u - 1f
        return 1f + c3 * x * x * x + c1 * x * x
    }

    fun smooth(u: Float): Float {
        val c = u.coerceIn(0f, 1f)
        return c * c * (3f - 2f * c)
    }
}

class Track(var base: Float) {
    val keys = mutableListOf<Key>()
    val animated: Boolean get() = keys.isNotEmpty()

    fun valueAt(t: Float): Float {
        if (keys.isEmpty()) return base
        if (t <= keys.first().t) return keys.first().v
        if (t >= keys.last().t) return keys.last().v
        var i = 0
        while (i < keys.size - 1 && keys[i + 1].t <= t) i++
        val a = keys[i]
        val b = keys[i + 1]
        val span = b.t - a.t
        if (span <= 1e-6f) return b.v
        val u = (t - a.t) / span
        val k: Float = when (a.interp) {
            Interp.HOLD -> return a.v
            Interp.LINEAR -> u
            Interp.BEZIER -> Easing.bezier(u, a.x1, a.y1, a.x2, a.y2)
        }
        return a.v + (b.v - a.v) * k
    }

    fun keyNear(t: Float, eps: Float = 0.004f): Key? = keys.firstOrNull { abs(it.t - t) <= eps }

    fun setKey(t: Float, v: Float): Key {
        val existing = keyNear(t)
        if (existing != null) {
            existing.v = v
            return existing
        }
        val k = Key(t, v)
        keys.add(k)
        keys.sortBy { it.t }
        return k
    }

    fun sortKeys() {
        keys.sortBy { it.t }
    }

    fun collapse(t: Float) {
        base = valueAt(t)
        keys.clear()
    }
}

class EffectInst(val id: String, val type: String) {
    var enabled = true
    val tracks = linkedMapOf<String, Track>()
}

class TextSpec {
    var text = "1r0pw"
    var color = 0xFFFFFFFF.toInt()
    var strokeColor = 0xFF000000.toInt()
    var shadowColor = 0xFF000000.toInt()
    var fontPath: String? = null
    var family = 0 // 0 sans, 1 serif, 2 mono
    var bold = true
    var italic = false
    var align = 1 // 0 left, 1 center, 2 right
    var anim = TextAnim.NONE
    var stagger = 0.05f
    var animDur = 0.45f
}

class ShapeSpec {
    var kind = ShapeKind.ROUNDRECT
    var fill = 0xFF7C5CFF.toInt()
    var fill2 = 0xFF2DE2E6.toInt()
    var gradient = false
    var gradAngle = 45f
    var strokeColor = 0xFFFFFFFF.toInt()
    var points = 5
    var inner = 0.5f
}

class MaskSpec {
    var kind = 0 // 0 rectangle, 1 ellipse
    var inverted = false
}

class Layer(val id: String, var name: String, var type: LayerType) {
    var start = 0f
    var duration = 5f
    var inPoint = 0f
    var speed = 1f
    var source: String? = null
    var mediaId: String? = null
    var srcW = 0
    var srcH = 0
    var srcDur = 0f // 0 means unlimited (images, text, shapes)
    var parentId: String? = null
    var visible = true
    var locked = false
    var threeD = false
    var blend = BlendMode.NORMAL
    var fadeIn = 0f
    var fadeOut = 0f
    var color = 0xFF7C5CFF.toInt()
    var text: TextSpec? = null
    var shape: ShapeSpec? = null
    var mask: MaskSpec? = null
    val tracks = linkedMapOf<String, Track>()
    val effects = mutableListOf<EffectInst>()

    val end: Float get() = start + duration

    fun track(key: String, def: Float = 0f): Track = tracks.getOrPut(key) { Track(def) }

    fun v(key: String, lt: Float, def: Float): Float = tracks[key]?.valueAt(lt) ?: def

    fun hasAudioSource(): Boolean = (type == LayerType.AUDIO || type == LayerType.VIDEO) && source != null
}

class MediaItem(val id: String, val uri: String, val name: String, val type: LayerType) {
    var duration = 0f
    var width = 0
    var height = 0
    var hasAudio = false
}

class Project(val id: String, var name: String) {
    var width = 1920
    var height = 1080
    var fps = 30
    var duration = 10f
    var bg = 0xFF000000.toInt()
    val layers = mutableListOf<Layer>() // index 0 = top-most layer
    val media = mutableListOf<MediaItem>()
    var modified = System.currentTimeMillis()

    fun layer(id: String?): Layer? = if (id == null) null else layers.firstOrNull { it.id == id }

    /** True if [candidate] is [root] or a descendant of it (used to prevent parenting cycles). */
    fun isDescendantOrSelf(candidate: Layer, root: Layer): Boolean {
        var cur: Layer? = candidate
        var guard = 0
        while (cur != null && guard < 64) {
            if (cur.id == root.id) return true
            cur = layer(cur.parentId)
            guard++
        }
        return false
    }
}

class PropDef(
    val key: String,
    val label: String,
    val def: Float,
    val min: Float,
    val max: Float,
    val group: String
)

object Props {
    fun defs(l: Layer, cw: Int, ch: Int): List<PropDef> {
        val out = ArrayList<PropDef>()
        val w = cw.toFloat()
        val h = ch.toFloat()
        if (l.type == LayerType.CAMERA) {
            out += PropDef("x", "Position X", w / 2f, -w, 2f * w, "Camera")
            out += PropDef("y", "Position Y", h / 2f, -h, 2f * h, "Camera")
            out += PropDef("rotation", "Rotation", 0f, -360f, 360f, "Camera")
            out += PropDef("zoom", "Zoom %", 100f, 10f, 400f, "Camera")
            out += PropDef("persp", "Perspective", 1000f, 200f, 4000f, "Camera")
            return out
        }
        out += PropDef("x", "Position X", w / 2f, -w, 2f * w, "Transform")
        out += PropDef("y", "Position Y", h / 2f, -h, 2f * h, "Transform")
        if (l.threeD) out += PropDef("z", "Position Z", 0f, -2000f, 2000f, "Transform")
        out += PropDef("scaleX", "Scale X %", 100f, 0f, 400f, "Transform")
        out += PropDef("scaleY", "Scale Y %", 100f, 0f, 400f, "Transform")
        out += PropDef("rotation", "Rotation", 0f, -360f, 360f, "Transform")
        if (l.threeD) {
            out += PropDef("rotX", "Rotate X", 0f, -180f, 180f, "Transform")
            out += PropDef("rotY", "Rotate Y", 0f, -180f, 180f, "Transform")
        }
        out += PropDef("anchorX", "Anchor X", 0f, -w / 2f, w / 2f, "Transform")
        out += PropDef("anchorY", "Anchor Y", 0f, -h / 2f, h / 2f, "Transform")
        if (l.type != LayerType.NULL) out += PropDef("opacity", "Opacity %", 100f, 0f, 100f, "Transform")
        when (l.type) {
            LayerType.AUDIO -> out += PropDef("volume", "Volume %", 100f, 0f, 200f, "Audio")
            LayerType.VIDEO -> out += PropDef("volume", "Volume %", 100f, 0f, 200f, "Audio")
            LayerType.TEXT -> {
                out += PropDef("size", "Font size", 72f, 4f, 400f, "Text")
                out += PropDef("stroke", "Stroke width", 0f, 0f, 40f, "Text")
                out += PropDef("shadowR", "Shadow / glow", 0f, 0f, 60f, "Text")
                out += PropDef("shadowX", "Shadow X", 0f, -50f, 50f, "Text")
                out += PropDef("shadowY", "Shadow Y", 0f, -50f, 50f, "Text")
                out += PropDef("spacing", "Letter spacing", 0f, -20f, 60f, "Text")
            }
            LayerType.SHAPE -> {
                out += PropDef("w", "Width", 400f, 1f, 2000f, "Shape")
                out += PropDef("h", "Height", 300f, 1f, 2000f, "Shape")
                out += PropDef("stroke", "Stroke width", 0f, 0f, 60f, "Shape")
                out += PropDef("corner", "Corner radius", 40f, 0f, 500f, "Shape")
            }
            else -> {}
        }
        return out
    }

    fun maskDefs(): List<PropDef> = listOf(
        PropDef("mask.x", "Mask X %", 50f, -50f, 150f, "Mask"),
        PropDef("mask.y", "Mask Y %", 50f, -50f, 150f, "Mask"),
        PropDef("mask.w", "Mask width %", 80f, 0f, 200f, "Mask"),
        PropDef("mask.h", "Mask height %", 80f, 0f, 200f, "Mask"),
        PropDef("mask.feather", "Mask feather", 0f, 0f, 200f, "Mask")
    )

    /** Creates every track the layer needs with sensible defaults (never overwrites existing ones). */
    fun ensure(l: Layer, cw: Int, ch: Int) {
        for (d in defs(l, cw, ch)) l.tracks.getOrPut(d.key) { Track(d.def) }
        if (l.mask != null) for (d in maskDefs()) l.tracks.getOrPut(d.key) { Track(d.def) }
    }
}
