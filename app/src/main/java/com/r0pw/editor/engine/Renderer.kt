package com.r0pw.editor.engine

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Camera
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import com.r0pw.editor.model.BlendMode
import com.r0pw.editor.model.Easing
import com.r0pw.editor.model.Layer
import com.r0pw.editor.model.LayerType
import com.r0pw.editor.model.Project
import com.r0pw.editor.model.ShapeKind
import com.r0pw.editor.model.TextAnim
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Composites a [Project] at a given time into a bitmap.
 * One Renderer must only be used from one thread at a time.
 */
class Renderer(private val media: MediaCache) {
    private var fx: Fx? = null
    private var out: Bitmap? = null
    private var bufA: Bitmap? = null
    private var bufB: Bitmap? = null
    private var bufM: Bitmap? = null
    private var bw = 0
    private var bh = 0

    private val cam3d = Camera()
    private val tmp3 = Matrix()
    private val memo = HashMap<String, Matrix>()
    private val view = Matrix()
    private var persp = 1000f
    private var project: Project? = null
    private val tp = Paint()

    private fun ensureBuffers(w: Int, h: Int) {
        if (out != null && bw == w && bh == h) return
        release()
        bw = w
        bh = h
        out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bufA = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bufB = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bufM = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        fx = Fx(w, h)
    }

    fun release() {
        out?.recycle(); bufA?.recycle(); bufB?.recycle(); bufM?.recycle(); fx?.release()
        out = null; bufA = null; bufB = null; bufM = null; fx = null
        bw = 0; bh = 0
    }

    /** Returns an internal bitmap that is reused on the next call: copy it if you keep it. */
    fun render(
        p: Project, t: Float, outW: Int, outH: Int,
        exact: Boolean, selectedId: String?, gizmos: Boolean, useCache: Boolean = true
    ): Bitmap {
        ensureBuffers(outW, outH)
        project = p
        memo.clear()
        val o = out!!
        o.eraseColor(p.bg)
        val canvas = Canvas(o)
        val rs = outW.toFloat() / p.width

        val cam = p.layers.firstOrNull { it.type == LayerType.CAMERA && it.visible && t >= it.start && t < it.end }
        persp = if (cam != null) cam.v("persp", t - cam.start, 1000f) else 1000f
        view.reset()
        if (cam != null) {
            val inv = Matrix()
            worldOf(cam, t, 0).invert(inv)
            val zoom = max(0.05f, cam.v("zoom", t - cam.start, 100f) / 100f)
            view.setTranslate(p.width / 2f, p.height / 2f)
            view.preScale(zoom, zoom)
            view.preConcat(inv)
        }

        for (i in p.layers.indices.reversed()) {
            val l = p.layers[i]
            if (!l.visible) continue
            if (t < l.start || t >= l.end) continue
            if (l.type == LayerType.AUDIO || l.type == LayerType.CAMERA || l.type == LayerType.NULL) continue
            renderLayer(canvas, l, t, rs, exact, useCache)
        }
        if (gizmos) drawGizmos(canvas, p, t, rs, selectedId)
        return o
    }

    // ------------------------------------------------------------------ transforms

    private fun localMatrix(l: Layer, lt: Float, m: Matrix) {
        m.reset()
        m.postTranslate(-l.v("anchorX", lt, 0f), -l.v("anchorY", lt, 0f))
        m.postScale(l.v("scaleX", lt, 100f) / 100f, l.v("scaleY", lt, 100f) / 100f)
        if (l.threeD) {
            cam3d.save()
            cam3d.setLocation(0f, 0f, -persp / 72f)
            cam3d.rotateX(l.v("rotX", lt, 0f))
            cam3d.rotateY(l.v("rotY", lt, 0f))
            cam3d.translate(0f, 0f, l.v("z", lt, 0f))
            cam3d.getMatrix(tmp3)
            cam3d.restore()
            m.postConcat(tmp3)
        }
        m.postRotate(l.v("rotation", lt, 0f))
        val p = project!!
        m.postTranslate(l.v("x", lt, p.width / 2f), l.v("y", lt, p.height / 2f))
    }

    private fun worldOf(l: Layer, t: Float, depth: Int): Matrix {
        memo[l.id]?.let { return it }
        val m = Matrix()
        localMatrix(l, t - l.start, m)
        val par = project!!.layer(l.parentId)
        val res = if (par != null && par !== l && depth < 16) {
            val r = Matrix(worldOf(par, t, depth + 1))
            r.preConcat(m)
            r
        } else m
        memo[l.id] = res
        return res
    }

    private fun fullMatrix(l: Layer, t: Float, rs: Float): Matrix {
        val m = Matrix()
        m.setScale(rs, rs)
        m.preConcat(view)
        m.preConcat(worldOf(l, t, 0))
        return m
    }

    // ------------------------------------------------------------------ layers

    private fun withAlpha(color: Int, a: Float): Int {
        val ca = ((color ushr 24) * a).roundToInt().coerceIn(0, 255)
        return (ca shl 24) or (color and 0xFFFFFF)
    }

    private fun blendMode(b: BlendMode): PorterDuff.Mode? = when (b) {
        BlendMode.NORMAL -> null
        BlendMode.ADD -> PorterDuff.Mode.ADD
        BlendMode.SCREEN -> PorterDuff.Mode.SCREEN
        BlendMode.MULTIPLY -> PorterDuff.Mode.MULTIPLY
        BlendMode.OVERLAY -> PorterDuff.Mode.OVERLAY
        BlendMode.DARKEN -> PorterDuff.Mode.DARKEN
        BlendMode.LIGHTEN -> PorterDuff.Mode.LIGHTEN
    }

    private fun renderLayer(canvas: Canvas, l: Layer, t: Float, rs: Float, exact: Boolean, useCache: Boolean) {
        val lt = t - l.start
        var alpha = l.v("opacity", lt, 100f) / 100f
        if (l.fadeIn > 0.001f && lt < l.fadeIn) alpha *= lt / l.fadeIn
        val remaining = l.duration - lt
        if (l.fadeOut > 0.001f && remaining < l.fadeOut) alpha *= remaining / l.fadeOut
        alpha = alpha.coerceIn(0f, 1f)
        if (alpha <= 0.002f) return

        val m = fullMatrix(l, t, rs)
        val active = l.effects.filter { it.enabled }
        val needBuf = active.isNotEmpty() || l.mask != null || l.blend != BlendMode.NORMAL

        if (!needBuf) {
            canvas.save()
            canvas.concat(m)
            drawContent(canvas, l, lt, alpha, exact, useCache)
            canvas.restore()
            return
        }

        val a = bufA!!
        a.eraseColor(0)
        val ac = Canvas(a)
        ac.concat(m)
        drawContent(ac, l, lt, 1f, exact, useCache)
        if (l.mask != null) applyMask(a, l, lt, m)

        var cur = a
        var other = bufB!!
        val f = fx!!
        for (e in active) {
            val res = f.apply(e, cur, other, lt, rs)
            if (res === other) {
                other = cur
                cur = res
            }
        }
        val pp = Paint(Paint.FILTER_BITMAP_FLAG)
        pp.alpha = (alpha * 255f).roundToInt().coerceIn(0, 255)
        val bm = blendMode(l.blend)
        if (bm != null) pp.xfermode = PorterDuffXfermode(bm)
        canvas.drawBitmap(cur, 0f, 0f, pp)
    }

    private fun applyMask(target: Bitmap, l: Layer, lt: Float, m: Matrix) {
        val mk = l.mask ?: return
        val size = contentSize(l, lt)
        val cw = size.first
        val ch = size.second
        val cx = (l.v("mask.x", lt, 50f) / 100f - 0.5f) * cw
        val cy = (l.v("mask.y", lt, 50f) / 100f - 0.5f) * ch
        val mw = l.v("mask.w", lt, 80f) / 100f * cw
        val mh = l.v("mask.h", lt, 80f) / 100f * ch
        val feather = l.v("mask.feather", lt, 0f)
        val mb = bufM!!
        mb.eraseColor(0)
        val mc = Canvas(mb)
        mc.concat(m)
        val mp = Paint(Paint.ANTI_ALIAS_FLAG)
        mp.color = Color.WHITE
        if (feather > 0.3f) mp.maskFilter = BlurMaskFilter(feather, BlurMaskFilter.Blur.NORMAL)
        val rect = RectF(cx - mw / 2f, cy - mh / 2f, cx + mw / 2f, cy + mh / 2f)
        if (mk.kind == 1) mc.drawOval(rect, mp) else mc.drawRect(rect, mp)
        val dp = Paint()
        dp.xfermode = PorterDuffXfermode(if (mk.inverted) PorterDuff.Mode.DST_OUT else PorterDuff.Mode.DST_IN)
        Canvas(target).drawBitmap(mb, 0f, 0f, dp)
    }

    // ------------------------------------------------------------------ content

    fun contentSize(l: Layer, lt: Float): Pair<Float, Float> = when (l.type) {
        LayerType.VIDEO, LayerType.IMAGE ->
            if (l.srcW > 0 && l.srcH > 0) Pair(l.srcW.toFloat(), l.srcH.toFloat()) else Pair(100f, 100f)
        LayerType.TEXT -> textBounds(l, lt)
        LayerType.SHAPE -> Pair(l.v("w", lt, 400f), l.v("h", lt, 300f))
        else -> Pair(100f, 100f)
    }

    private fun drawContent(c: Canvas, l: Layer, lt: Float, alpha: Float, exact: Boolean, useCache: Boolean) {
        when (l.type) {
            LayerType.VIDEO -> {
                val src = l.source ?: return
                if (l.srcW <= 0 || l.srcH <= 0) return
                val srcT = l.inPoint + lt * l.speed
                val limit = (max(bw, bh) * (if (exact) 1.5f else 1.1f)).toInt().coerceIn(240, 3840)
                val sz = MediaImporter.fitSize(l.srcW, l.srcH, limit)
                val bmp = media.videoFrame(src, srcT, sz.first, sz.second, exact, useCache) ?: return
                val pp = Paint(Paint.FILTER_BITMAP_FLAG)
                pp.alpha = (alpha * 255f).roundToInt().coerceIn(0, 255)
                val w = l.srcW.toFloat()
                val h = l.srcH.toFloat()
                c.drawBitmap(bmp, null, RectF(-w / 2f, -h / 2f, w / 2f, h / 2f), pp)
            }
            LayerType.IMAGE -> {
                val src = l.source ?: return
                val bmp = media.image(src) ?: return
                val pp = Paint(Paint.FILTER_BITMAP_FLAG)
                pp.alpha = (alpha * 255f).roundToInt().coerceIn(0, 255)
                val w = if (l.srcW > 0) l.srcW.toFloat() else bmp.width.toFloat()
                val h = if (l.srcH > 0) l.srcH.toFloat() else bmp.height.toFloat()
                c.drawBitmap(bmp, null, RectF(-w / 2f, -h / 2f, w / 2f, h / 2f), pp)
            }
            LayerType.TEXT -> drawText(c, l, lt, alpha)
            LayerType.SHAPE -> drawShape(c, l, lt, alpha)
            else -> {}
        }
    }

    // ------------------------------------------------------------------ text

    private fun setupText(l: Layer, lt: Float): Float {
        val ts = l.text!!
        val size = max(1f, l.v("size", lt, 72f))
        tp.reset()
        tp.isAntiAlias = true
        tp.isSubpixelText = true
        tp.typeface = media.typeface(ts)
        tp.textSize = size
        tp.letterSpacing = l.v("spacing", lt, 0f) / size
        return size
    }

    private fun textBounds(l: Layer, lt: Float): Pair<Float, Float> {
        val ts = l.text ?: return Pair(100f, 100f)
        val size = setupText(l, lt)
        val lines = ts.text.split("\n")
        var mw = 1f
        for (ln in lines) mw = max(mw, tp.measureText(ln))
        return Pair(mw, lines.size * size * 1.25f)
    }

    private fun isRtl(s: String): Boolean = s.any { it.code in 0x0590..0x08FF }

    private fun textPass(c: Canvas, l: Layer, lt: Float, s: String, x: Float, y: Float, align: Paint.Align, a: Float, stroke: Boolean) {
        val ts = l.text!!
        tp.textAlign = align
        if (stroke) {
            tp.style = Paint.Style.STROKE
            tp.strokeWidth = l.v("stroke", lt, 0f) * 2f
            tp.strokeJoin = Paint.Join.ROUND
            tp.color = withAlpha(ts.strokeColor, a)
            tp.clearShadowLayer()
        } else {
            tp.style = Paint.Style.FILL
            tp.color = withAlpha(ts.color, a)
            val shR = l.v("shadowR", lt, 0f)
            val shX = l.v("shadowX", lt, 0f)
            val shY = l.v("shadowY", lt, 0f)
            if (shR > 0.01f || shX != 0f || shY != 0f) tp.setShadowLayer(max(0.01f, shR), shX, shY, withAlpha(ts.shadowColor, a))
            else tp.clearShadowLayer()
        }
        c.drawText(s, x, y, tp)
    }

    private fun drawText(c: Canvas, l: Layer, lt: Float, alpha: Float) {
        val ts = l.text ?: return
        if (ts.text.isEmpty()) return
        val size = setupText(l, lt)
        val strokeW = l.v("stroke", lt, 0f)
        val lines = ts.text.split("\n")
        val lh = size * 1.25f
        val totalH = lines.size * lh
        val fm = tp.fontMetrics
        val firstBase = -totalH / 2f + (lh - (fm.descent - fm.ascent)) / 2f - fm.ascent
        val widths = FloatArray(lines.size) { tp.measureText(lines[it]) }
        var maxW = 1f
        for (w in widths) maxW = max(maxW, w)

        if (ts.anim == TextAnim.NONE) {
            val align = when (ts.align) {
                0 -> Paint.Align.LEFT
                2 -> Paint.Align.RIGHT
                else -> Paint.Align.CENTER
            }
            val x = when (ts.align) {
                0 -> -maxW / 2f
                2 -> maxW / 2f
                else -> 0f
            }
            for (pass in 0..1) {
                if (pass == 0 && strokeW <= 0f) continue
                for (i in lines.indices) {
                    textPass(c, l, lt, lines[i], x, firstBase + i * lh, align, alpha, pass == 0)
                }
            }
            return
        }

        // Animated per-character (or per-word for Arabic/Hebrew) typography
        var idx = 0
        for (i in lines.indices) {
            val line = lines[i]
            val by = firstBase + i * lh
            val rtl = isRtl(line)
            val lineW = widths[i]
            val left = when (ts.align) {
                0 -> -maxW / 2f
                2 -> maxW / 2f - lineW
                else -> -lineW / 2f
            }
            val units: List<String> = if (rtl) line.split(" ") else line.map { it.toString() }
            val spaceW = tp.measureText(" ")
            var prefix = ""
            var consumedW = 0f
            for (unit in units) {
                val uw = tp.measureText(unit)
                val ux: Float
                val align: Paint.Align
                if (rtl) {
                    ux = left + lineW - consumedW
                    align = Paint.Align.RIGHT
                    consumedW += uw + spaceW
                } else {
                    ux = left + tp.measureText(prefix)
                    align = Paint.Align.LEFT
                    prefix += unit
                }
                val u = ((lt - idx * ts.stagger) / max(0.01f, ts.animDur)).coerceIn(0f, 1f)
                var a = alpha
                var dy = 0f
                var sc = 1f
                var skip = false
                when (ts.anim) {
                    TextAnim.TYPEWRITER -> if (u <= 0f) skip = true
                    TextAnim.FADE_UP -> {
                        if (u <= 0f) skip = true
                        val e = Easing.smooth(u)
                        a *= e
                        dy = (1f - e) * size * 0.6f
                    }
                    TextAnim.POP -> {
                        if (u <= 0f) skip = true
                        sc = max(0f, Easing.backOut(u))
                        a *= min(1f, u * 3f)
                    }
                    TextAnim.WAVE -> dy = (sin(lt * 6f + idx * 0.6f) * size * 0.15f)
                    else -> {}
                }
                idx++
                if (skip || a <= 0.002f) continue
                c.save()
                c.translate(ux, by + dy)
                if (sc != 1f) {
                    val pivotX = if (rtl) -uw / 2f else uw / 2f
                    c.scale(sc, sc, pivotX, -size * 0.3f)
                }
                if (strokeW > 0f) textPass(c, l, lt, unit, 0f, 0f, align, a, true)
                textPass(c, l, lt, unit, 0f, 0f, align, a, false)
                c.restore()
            }
        }
    }

    // ------------------------------------------------------------------ shapes

    private fun shapePath(sp: com.r0pw.editor.model.ShapeSpec, w: Float, h: Float): Path {
        val path = Path()
        val n = sp.points.coerceIn(3, 24)
        val rx = w / 2f
        val ry = h / 2f
        val count = if (sp.kind == ShapeKind.STAR) n * 2 else n
        for (i in 0 until count) {
            val ang = -PI / 2.0 + i * 2.0 * PI / count
            val k = if (sp.kind == ShapeKind.STAR && i % 2 == 1) sp.inner else 1f
            val x = (cos(ang) * rx * k).toFloat()
            val y = (sin(ang) * ry * k).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        return path
    }

    private fun drawGeometry(c: Canvas, sp: com.r0pw.editor.model.ShapeSpec, w: Float, h: Float, corner: Float, p: Paint) {
        val r = RectF(-w / 2f, -h / 2f, w / 2f, h / 2f)
        when (sp.kind) {
            ShapeKind.RECT -> c.drawRect(r, p)
            ShapeKind.ROUNDRECT -> c.drawRoundRect(r, corner, corner, p)
            ShapeKind.ELLIPSE -> c.drawOval(r, p)
            else -> c.drawPath(shapePath(sp, w, h), p)
        }
    }

    private fun drawShape(c: Canvas, l: Layer, lt: Float, alpha: Float) {
        val sp = l.shape ?: return
        val w = max(1f, l.v("w", lt, 400f))
        val h = max(1f, l.v("h", lt, 300f))
        val stroke = l.v("stroke", lt, 0f)
        val corner = l.v("corner", lt, 40f)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        fill.style = Paint.Style.FILL
        if (sp.gradient) {
            val rad = hypot(w, h) / 2f
            val a = Math.toRadians(sp.gradAngle.toDouble())
            fill.shader = LinearGradient(
                (-cos(a) * rad).toFloat(), (-sin(a) * rad).toFloat(),
                (cos(a) * rad).toFloat(), (sin(a) * rad).toFloat(),
                sp.fill, sp.fill2, Shader.TileMode.CLAMP
            )
            fill.alpha = (alpha * 255f).roundToInt().coerceIn(0, 255)
        } else {
            fill.color = withAlpha(sp.fill, alpha)
        }
        drawGeometry(c, sp, w, h, corner, fill)
        if (stroke > 0.01f) {
            val sk = Paint(Paint.ANTI_ALIAS_FLAG)
            sk.style = Paint.Style.STROKE
            sk.strokeWidth = stroke
            sk.strokeJoin = Paint.Join.ROUND
            sk.color = withAlpha(sp.strokeColor, alpha)
            drawGeometry(c, sp, w, h, corner, sk)
        }
    }

    // ------------------------------------------------------------------ gizmos (preview only)

    private fun drawGizmos(canvas: Canvas, p: Project, t: Float, rs: Float, selectedId: String?) {
        val line = Paint(Paint.ANTI_ALIAS_FLAG)
        line.style = Paint.Style.STROKE
        line.strokeWidth = max(1.5f, 2f * rs * 1.2f)
        val fillP = Paint(Paint.ANTI_ALIAS_FLAG)
        fillP.style = Paint.Style.FILL
        for (l in p.layers) {
            if (!l.visible) continue
            val sel = l.id == selectedId
            if (!sel && l.type != LayerType.NULL) continue
            if (l.type == LayerType.AUDIO || l.type == LayerType.CAMERA) continue
            if (t < l.start || t >= l.end) continue
            val lt = t - l.start
            val sz = contentSize(l, lt)
            val hw = sz.first / 2f
            val hh = sz.second / 2f
            val pts = floatArrayOf(-hw, -hh, hw, -hh, hw, hh, -hw, hh)
            val m = fullMatrix(l, t, rs)
            m.mapPoints(pts)
            val col = if (sel) 0xFF7C5CFF.toInt() else 0xFF2DE2E6.toInt()
            line.color = col
            fillP.color = col
            for (i in 0 until 4) {
                val j = (i + 1) % 4
                canvas.drawLine(pts[i * 2], pts[i * 2 + 1], pts[j * 2], pts[j * 2 + 1], line)
            }
            if (sel) {
                val s = max(3f, 4f * rs * 1.2f)
                for (i in 0 until 4) canvas.drawRect(pts[i * 2] - s, pts[i * 2 + 1] - s, pts[i * 2] + s, pts[i * 2 + 1] + s, fillP)
            }
            val a = floatArrayOf(l.v("anchorX", lt, 0f), l.v("anchorY", lt, 0f))
            m.mapPoints(a)
            val k = max(6f, 8f * rs * 1.2f)
            canvas.drawLine(a[0] - k, a[1], a[0] + k, a[1], line)
            canvas.drawLine(a[0], a[1] - k, a[0], a[1] + k, line)
        }
    }
}
