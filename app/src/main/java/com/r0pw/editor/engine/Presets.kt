package com.r0pw.editor.engine

import com.r0pw.editor.model.BlendMode
import com.r0pw.editor.model.Easing
import com.r0pw.editor.model.FxCatalog
import com.r0pw.editor.model.Interp
import com.r0pw.editor.model.Layer
import com.r0pw.editor.model.LayerType
import com.r0pw.editor.model.Project
import com.r0pw.editor.model.ShapeKind
import com.r0pw.editor.model.ShapeSpec
import com.r0pw.editor.model.TextAnim
import com.r0pw.editor.model.TextSpec
import com.r0pw.editor.model.Props
import com.r0pw.editor.model.newId

/** Built-in animation / transition / camera / text presets and small project templates. */
object Presets {
    val animNames = listOf(
        "Fade In", "Fade Out", "Pop In", "Slide Up", "Slide In Left", "Spin In",
        "Zoom Punch", "Float", "Pulse", "Shake"
    )
    val transitionNames = listOf("Wipe →", "Wipe ←", "Wipe ↓", "Wipe ↑", "Glitch cut", "Blur cut")
    val cameraNames = listOf("Push In", "Pull Out", "Pan Right", "Pan Left", "Drift", "Tilt Roll")
    val textNames = listOf("Bold Title", "Neon", "Typewriter", "Pop Words", "Fade Up", "Wave")
    val templateNames = listOf("Title Card", "Lower Third")

    private fun key(layer: Layer, prop: String, t: Float, v: Float, ease: FloatArray? = null) {
        val tr = layer.track(prop, v)
        val k = tr.setKey(t, v)
        if (ease != null) k.setEase(ease) else k.interp = Interp.LINEAR
    }

    private val easeOut = floatArrayOf(0f, 0f, 0.58f, 1f)
    private val easeInOut = floatArrayOf(0.42f, 0f, 0.58f, 1f)
    private val backOut = floatArrayOf(0.34f, 1.56f, 0.64f, 1f)

    /** [lt] = current time in layer time. */
    fun applyAnim(name: String, l: Layer, lt: Float) {
        val x = l.track("x").valueAt(lt)
        val y = l.track("y").valueAt(lt)
        val sx = l.track("scaleX", 100f).valueAt(lt)
        val sy = l.track("scaleY", 100f).valueAt(lt)
        val rot = l.track("rotation").valueAt(lt)
        val op = l.track("opacity", 100f).valueAt(lt)
        when (name) {
            "Fade In" -> { key(l, "opacity", lt, 0f, easeOut); key(l, "opacity", lt + 0.5f, op) }
            "Fade Out" -> { key(l, "opacity", lt, op, easeInOut); key(l, "opacity", lt + 0.5f, 0f) }
            "Pop In" -> {
                key(l, "scaleX", lt, 0f, backOut); key(l, "scaleX", lt + 0.5f, sx)
                key(l, "scaleY", lt, 0f, backOut); key(l, "scaleY", lt + 0.5f, sy)
                key(l, "opacity", lt, 0f, easeOut); key(l, "opacity", lt + 0.15f, op)
            }
            "Slide Up" -> {
                key(l, "y", lt, y + 260f, easeOut); key(l, "y", lt + 0.6f, y)
                key(l, "opacity", lt, 0f, easeOut); key(l, "opacity", lt + 0.4f, op)
            }
            "Slide In Left" -> {
                key(l, "x", lt, x - 600f, easeOut); key(l, "x", lt + 0.6f, x)
                key(l, "opacity", lt, 0f, easeOut); key(l, "opacity", lt + 0.3f, op)
            }
            "Spin In" -> {
                key(l, "rotation", lt, rot - 270f, easeOut); key(l, "rotation", lt + 0.8f, rot)
                key(l, "scaleX", lt, 0f, easeOut); key(l, "scaleX", lt + 0.8f, sx)
                key(l, "scaleY", lt, 0f, easeOut); key(l, "scaleY", lt + 0.8f, sy)
            }
            "Zoom Punch" -> {
                key(l, "scaleX", lt, sx, easeOut); key(l, "scaleX", lt + 0.12f, sx * 1.25f, easeInOut); key(l, "scaleX", lt + 0.45f, sx)
                key(l, "scaleY", lt, sy, easeOut); key(l, "scaleY", lt + 0.12f, sy * 1.25f, easeInOut); key(l, "scaleY", lt + 0.45f, sy)
            }
            "Float" -> {
                key(l, "y", lt, y, easeInOut); key(l, "y", lt + 1.2f, y - 30f, easeInOut); key(l, "y", lt + 2.4f, y)
            }
            "Pulse" -> {
                key(l, "scaleX", lt, sx, easeInOut); key(l, "scaleX", lt + 0.5f, sx * 1.08f, easeInOut); key(l, "scaleX", lt + 1f, sx)
                key(l, "scaleY", lt, sy, easeInOut); key(l, "scaleY", lt + 0.5f, sy * 1.08f, easeInOut); key(l, "scaleY", lt + 1f, sy)
            }
            "Shake" -> {
                var i = 0
                while (i < 8) {
                    val s = if (i % 2 == 0) 1f else -1f
                    val fall = 1f - i / 8f
                    key(l, "x", lt + i * 0.05f, x + s * 24f * fall)
                    i++
                }
                key(l, "x", lt + 0.4f, x)
            }
        }
    }

    /** [atEnd] false = transition-in at layer start, true = transition-out at layer end. */
    fun applyTransition(name: String, l: Layer, atEnd: Boolean) {
        val dur = 0.6f.coerceAtMost(l.duration)
        val t0 = if (atEnd) l.duration - dur else 0f
        val t1 = t0 + dur
        val from = if (atEnd) 100f else 0f
        val to = if (atEnd) 0f else 100f
        when (name) {
            "Wipe →", "Wipe ←", "Wipe ↓", "Wipe ↑" -> {
                val e = FxCatalog.create("wipe") ?: return
                e.tracks["direction"]?.base = when (name) { "Wipe →" -> 0f; "Wipe ←" -> 1f; "Wipe ↓" -> 2f; else -> 3f }
                val tr = e.tracks["progress"]!!
                tr.setKey(t0, from).setEase(easeInOut)
                tr.setKey(t1, to)
                l.effects.add(e)
            }
            "Glitch cut" -> {
                val e = FxCatalog.create("glitch") ?: return
                val tr = e.tracks["amount"]!!
                tr.setKey(t0, if (atEnd) 0f else 100f).interp = Interp.LINEAR
                tr.setKey(t1, if (atEnd) 100f else 0f)
                l.effects.add(e)
                val o = l.track("opacity", 100f)
                o.setKey(t0, if (atEnd) 100f else 0f).interp = Interp.HOLD
                o.setKey(t0 + 0.1f, 100f)
                if (atEnd) o.setKey(t1, 0f)
            }
            "Blur cut" -> {
                val e = FxCatalog.create("blur") ?: return
                val tr = e.tracks["radius"]!!
                tr.setKey(t0, if (atEnd) 0f else 50f).setEase(easeInOut)
                tr.setKey(t1, if (atEnd) 50f else 0f)
                l.effects.add(e)
                val o = l.track("opacity", 100f)
                o.setKey(t0, if (atEnd) 100f else 0f).setEase(easeInOut)
                o.setKey(t1, if (atEnd) 0f else 100f)
            }
        }
    }

    fun applyCamera(name: String, l: Layer) {
        if (l.type != LayerType.CAMERA) return
        val d = l.duration
        val x = l.track("x").base
        val y = l.track("y").base
        l.track("zoom", 100f).keys.clear()
        l.track("x").keys.clear()
        l.track("y").keys.clear()
        l.track("rotation").keys.clear()
        when (name) {
            "Push In" -> { key(l, "zoom", 0f, 100f, easeInOut); key(l, "zoom", d, 135f) }
            "Pull Out" -> { key(l, "zoom", 0f, 140f, easeInOut); key(l, "zoom", d, 100f) }
            "Pan Right" -> { key(l, "x", 0f, x - 250f, easeInOut); key(l, "x", d, x + 250f); l.track("zoom").base = 125f }
            "Pan Left" -> { key(l, "x", 0f, x + 250f, easeInOut); key(l, "x", d, x - 250f); l.track("zoom").base = 125f }
            "Drift" -> {
                l.track("zoom").base = 115f
                key(l, "x", 0f, x, easeInOut); key(l, "x", d / 2f, x + 60f, easeInOut); key(l, "x", d, x)
                key(l, "y", 0f, y, easeInOut); key(l, "y", d / 2f, y - 40f, easeInOut); key(l, "y", d, y)
            }
            "Tilt Roll" -> { key(l, "rotation", 0f, -6f, easeInOut); key(l, "rotation", d, 6f); l.track("zoom").base = 115f }
        }
    }

    fun applyText(name: String, l: Layer) {
        val ts = l.text ?: return
        when (name) {
            "Bold Title" -> {
                ts.bold = true; ts.anim = TextAnim.NONE; ts.color = 0xFFFFFFFF.toInt()
                l.track("size", 72f).base = 120f; l.track("stroke").base = 6f
            }
            "Neon" -> {
                ts.color = 0xFFFFFFFF.toInt(); ts.shadowColor = 0xFF2DE2E6.toInt(); ts.bold = true
                l.track("shadowR").base = 28f; l.track("size", 72f).base = 110f
            }
            "Typewriter" -> { ts.anim = TextAnim.TYPEWRITER; ts.stagger = 0.06f }
            "Pop Words" -> { ts.anim = TextAnim.POP; ts.stagger = 0.12f; ts.animDur = 0.5f }
            "Fade Up" -> { ts.anim = TextAnim.FADE_UP; ts.stagger = 0.05f; ts.animDur = 0.6f }
            "Wave" -> { ts.anim = TextAnim.WAVE }
        }
    }

    // ---------------------------------------------------------------- templates

    private fun shapeLayer(p: Project, name: String, color: Int, w: Float, h: Float, x: Float, y: Float, start: Float, dur: Float): Layer {
        val l = Layer(newId(), name, LayerType.SHAPE)
        l.shape = ShapeSpec().also { it.kind = ShapeKind.RECT; it.fill = color }
        l.start = start; l.duration = dur
        Props.ensure(l, p.width, p.height)
        l.track("w").base = w; l.track("h").base = h
        l.track("x").base = x; l.track("y").base = y
        return l
    }

    private fun textLayer(p: Project, text: String, size: Float, x: Float, y: Float, start: Float, dur: Float): Layer {
        val l = Layer(newId(), text.take(14), LayerType.TEXT)
        l.text = TextSpec().also { it.text = text }
        l.start = start; l.duration = dur
        Props.ensure(l, p.width, p.height)
        l.track("size").base = size
        l.track("x").base = x; l.track("y").base = y
        return l
    }

    /** Adds the template layers on top of the project. Returns the new layers. */
    fun applyTemplate(name: String, p: Project, at: Float): List<Layer> {
        val cw = p.width.toFloat()
        val ch = p.height.toFloat()
        val d = (p.duration - at).coerceIn(1f, 5f)
        val out = ArrayList<Layer>()
        when (name) {
            "Title Card" -> {
                val bg = shapeLayer(p, "Card BG", 0xFF15151A.toInt(), cw, ch, cw / 2f, ch / 2f, at, d)
                val bar = shapeLayer(p, "Accent bar", 0xFF7C5CFF.toInt(), cw * 0.5f, 8f, cw / 2f, ch / 2f + 90f, at, d)
                val title = textLayer(p, "YOUR TITLE", ch * 0.12f, cw / 2f, ch / 2f, at, d)
                title.text?.anim = TextAnim.POP
                applyAnim("Fade In", bar, 0f)
                out.add(title); out.add(bar); out.add(bg)
            }
            "Lower Third" -> {
                val box = shapeLayer(p, "Name plate", 0xCC000000.toInt(), cw * 0.45f, ch * 0.12f, cw * 0.28f, ch * 0.82f, at, d)
                val line = shapeLayer(p, "Accent line", 0xFF2DE2E6.toInt(), 6f, ch * 0.12f, cw * 0.28f - cw * 0.225f, ch * 0.82f, at, d)
                val t = textLayer(p, "Name Surname", ch * 0.05f, cw * 0.28f, ch * 0.82f, at, d)
                t.text?.anim = TextAnim.FADE_UP
                applyAnim("Slide In Left", box, 0f)
                applyAnim("Slide In Left", line, 0f)
                out.add(t); out.add(line); out.add(box)
            }
        }
        for (l in out) l.blend = BlendMode.NORMAL
        return out
    }

    /** Copies the "look" (style, keyframes, effects) of a preset layer onto [target]. */
    fun applyLook(preset: Layer, target: Layer) {
        for ((k, v) in preset.tracks) {
            if (k == "x" || k == "y") continue
            target.tracks[k] = v
        }
        target.effects.addAll(preset.effects)
        target.blend = preset.blend
        target.fadeIn = preset.fadeIn
        target.fadeOut = preset.fadeOut
        val pt = preset.text
        if (target.type == LayerType.TEXT && pt != null) {
            pt.text = target.text?.text ?: pt.text
            target.text = pt
        }
        val ps = preset.shape
        if (target.type == LayerType.SHAPE && ps != null) target.shape = ps
        val pm = preset.mask
        if (pm != null) target.mask = pm
    }
}
