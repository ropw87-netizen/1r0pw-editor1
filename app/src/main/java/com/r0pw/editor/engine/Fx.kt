package com.r0pw.editor.engine

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import com.r0pw.editor.model.EffectInst
import com.r0pw.editor.model.FxCatalog
import com.r0pw.editor.plugins.Expr
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The effects engine. Every effect takes a source bitmap and a destination scratch bitmap
 * (same size) and returns whichever one holds the result.
 * All pixel based parameters are multiplied by [rs] (render scale) so preview and export look the same.
 */
class Fx(val w: Int, val h: Int) {
    private val n = w * h
    private val px = IntArray(n)
    private val ca = IntArray(n)
    private val cr = IntArray(n)
    private val cg = IntArray(n)
    private val cb = IntArray(n)
    private val ct = IntArray(n)
    private val tmp: Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    private var noiseTile: Bitmap? = null

    private fun paint(): Paint = Paint(Paint.FILTER_BITMAP_FLAG)

    // ------------------------------------------------------------------ public entry

    fun apply(inst: EffectInst, src: Bitmap, dst: Bitmap, lt: Float, rs: Float): Bitmap {
        val def = FxCatalog.find(inst.type) ?: return src
        val values = HashMap<String, Float>()
        for (pd in def.params) values[pd.key] = inst.tracks[pd.key]?.valueAt(lt) ?: pd.def
        val stack = def.stack
        if (stack == null) {
            return builtin(inst.type, { k -> values[k] ?: (FxCatalog.paramDef(inst.type, k)?.def ?: 0f) }, src, dst, lt, rs)
        }
        var cur = src
        var other = dst
        for (step in stack) {
            val bd = FxCatalog.builtin.firstOrNull { it.type == step.type } ?: continue
            val sv = HashMap<String, Float>()
            for (pd in bd.params) {
                val expr = step.params[pd.key]
                sv[pd.key] = if (expr != null) Expr.eval(expr, values) else pd.def
            }
            val res = builtin(step.type, { k -> sv[k] ?: 0f }, cur, other, lt, rs)
            if (res === other) {
                val o = other
                other = cur
                cur = o
            }
        }
        return cur
    }

    private fun builtin(type: String, p: (String) -> Float, src: Bitmap, dst: Bitmap, t: Float, rs: Float): Bitmap {
        return when (type) {
            "color" -> fxColor(src, dst, p)
            "colorize" -> fxColorize(src, dst, p)
            "invert" -> fxInvert(src, dst, p("amount") / 100f)
            "chromakey" -> fxChromaKey(src, p)
            "blur" -> { blurBitmap(src, p("radius") * rs); src }
            "dirblur" -> fxDirBlur(src, dst, p("length") * rs, p("angle"))
            "zoomblur" -> fxZoomBlur(src, dst, p("amount"), p("cx"), p("cy"))
            "glow" -> fxGlow(src, p("radius") * rs, p("intensity") / 100f)
            "dropshadow" -> fxShadow(src, dst, p("distance") * rs, p("angle"), p("blur") * rs, p("opacity") / 100f)
            "vignette" -> fxVignette(src, p("amount") / 100f, p("size"), p("softness"))
            "chroma" -> fxChroma(src, dst, p("amount") * rs, p("angle"))
            "glitch" -> fxGlitch(src, dst, t, p("amount") / 100f, p("slices").roundToInt(), p("rate"), p("rgb") * rs, rs)
            "shake" -> fxShake(src, dst, t, p("amplitude") * rs, p("freq"), p("rotation"))
            "wave" -> fxWave(src, dst, t, p("amplitude") * rs, p("frequency"), p("speed"), rs)
            "pixelate" -> fxPixelate(src, dst, max(1f, p("size") * rs))
            "mirror" -> fxMirror(src, dst, p("mode").roundToInt())
            "noise" -> fxNoise(src, t, p("amount") / 100f)
            "sharpen" -> fxSharpen(src, p("amount") / 100f)
            "wipe" -> fxWipe(src, dst, p("progress") / 100f, p("direction").roundToInt())
            else -> src
        }
    }

    // ------------------------------------------------------------------ colour

    private fun hueMatrix(deg: Float): ColorMatrix {
        val r = deg * PI.toFloat() / 180f
        val c = cos(r)
        val s = sin(r)
        val lr = 0.213f
        val lg = 0.715f
        val lb = 0.072f
        return ColorMatrix(
            floatArrayOf(
                lr + c * (1f - lr) + s * (-lr), lg + c * (-lg) + s * (-lg), lb + c * (-lb) + s * (1f - lb), 0f, 0f,
                lr + c * (-lr) + s * 0.143f, lg + c * (1f - lg) + s * 0.140f, lb + c * (-lb) + s * (-0.283f), 0f, 0f,
                lr + c * (-lr) + s * (-(1f - lr)), lg + c * (-lg) + s * lg, lb + c * (1f - lb) + s * lb, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )
        )
    }

    private fun drawWithMatrix(src: Bitmap, dst: Bitmap, cm: ColorMatrix): Bitmap {
        dst.eraseColor(0)
        val pp = paint()
        pp.colorFilter = ColorMatrixColorFilter(cm)
        Canvas(dst).drawBitmap(src, 0f, 0f, pp)
        return dst
    }

    private fun fxColor(src: Bitmap, dst: Bitmap, p: (String) -> Float): Bitmap {
        val br = p("brightness") * 2.55f
        val ct = p("contrast")
        val off = (1f - ct) * 127.5f + br
        val m = ColorMatrix()
        m.setSaturation(p("saturation"))
        m.postConcat(hueMatrix(p("hue")))
        m.postConcat(
            ColorMatrix(
                floatArrayOf(
                    ct, 0f, 0f, 0f, off,
                    0f, ct, 0f, 0f, off,
                    0f, 0f, ct, 0f, off,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )
        return drawWithMatrix(src, dst, m)
    }

    private fun fxInvert(src: Bitmap, dst: Bitmap, a: Float): Bitmap {
        val d = 1f - 2f * a
        val o = 255f * a
        return drawWithMatrix(
            src, dst, ColorMatrix(
                floatArrayOf(
                    d, 0f, 0f, 0f, o,
                    0f, d, 0f, 0f, o,
                    0f, 0f, d, 0f, o,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )
    }

    private fun fxColorize(src: Bitmap, dst: Bitmap, p: (String) -> Float): Bitmap {
        val col = Color.HSVToColor(floatArrayOf(p("hue"), 1f, 1f))
        val r = Color.red(col) / 255f * 1.6f
        val g = Color.green(col) / 255f * 1.6f
        val b = Color.blue(col) / 255f * 1.6f
        val m = ColorMatrix()
        m.setSaturation(0f)
        m.postConcat(
            ColorMatrix(
                floatArrayOf(
                    r, 0f, 0f, 0f, 0f,
                    0f, g, 0f, 0f, 0f,
                    0f, 0f, b, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )
        dst.eraseColor(0)
        val c = Canvas(dst)
        c.drawBitmap(src, 0f, 0f, null)
        val pp = paint()
        pp.colorFilter = ColorMatrixColorFilter(m)
        pp.alpha = (p("amount") / 100f * 255f).roundToInt().coerceIn(0, 255)
        c.drawBitmap(src, 0f, 0f, pp)
        return dst
    }

    private fun fxChromaKey(src: Bitmap, p: (String) -> Float): Bitmap {
        src.getPixels(px, 0, w, 0, 0, w, h)
        val keyH = p("hue")
        val tol = p("tolerance")
        val soft = p("softness")
        val minSat = p("minsat") / 100f
        for (i in 0 until n) {
            val c = px[i]
            val a = c ushr 24
            if (a == 0) continue
            val r = (c shr 16) and 255
            val g = (c shr 8) and 255
            val b = c and 255
            val mx = max(r, max(g, b))
            val mn = min(r, min(g, b))
            val delta = mx - mn
            if (delta == 0 || mx == 0) continue
            val s = delta.toFloat() / mx
            if (s < minSat) continue
            var hh = when (mx) {
                r -> 60f * (((g - b).toFloat() / delta) % 6f)
                g -> 60f * ((b - r).toFloat() / delta + 2f)
                else -> 60f * ((r - g).toFloat() / delta + 4f)
            }
            if (hh < 0f) hh += 360f
            var d = abs(hh - keyH)
            if (d > 180f) d = 360f - d
            var na = a
            if (d <= tol) na = 0
            else if (soft > 0f && d < tol + soft) na = (a * (d - tol) / soft).toInt()
            if (na != a) px[i] = (na shl 24) or (c and 0xFFFFFF)
        }
        src.setPixels(px, 0, w, 0, 0, w, h)
        return src
    }

    // ------------------------------------------------------------------ blur family

    fun blurBitmap(bmp: Bitmap, radius: Float) {
        if (radius < 0.6f) return
        val f = (radius / 5f).toInt().coerceAtLeast(1)
        if (f > 1) {
            val sw = max(2, bmp.width / f)
            val sh = max(2, bmp.height / f)
            val small = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
            Canvas(small).drawBitmap(bmp, null, Rect(0, 0, sw, sh), paint())
            boxBlur3(small, radius / f)
            bmp.eraseColor(0)
            Canvas(bmp).drawBitmap(small, null, Rect(0, 0, bmp.width, bmp.height), paint())
            small.recycle()
        } else {
            boxBlur3(bmp, radius)
        }
    }

    private fun boxBlur3(bmp: Bitmap, radius: Float) {
        val bw = bmp.width
        val bh = bmp.height
        val cnt = bw * bh
        bmp.getPixels(px, 0, bw, 0, 0, bw, bh)
        for (i in 0 until cnt) {
            val c = px[i]
            val a = c ushr 24
            ca[i] = a
            cr[i] = ((c shr 16) and 255) * a / 255
            cg[i] = ((c shr 8) and 255) * a / 255
            cb[i] = (c and 255) * a / 255
        }
        val r = max(1, radius.roundToInt())
        val chans = arrayOf(ca, cr, cg, cb)
        for (ch in chans) {
            for (pass in 0 until 3) {
                boxH(ch, ct, bw, bh, r)
                boxV(ct, ch, bw, bh, r)
            }
        }
        for (i in 0 until cnt) {
            val a = ca[i]
            if (a <= 0) {
                px[i] = 0
            } else {
                val rr = min(255, cr[i] * 255 / a)
                val gg = min(255, cg[i] * 255 / a)
                val bb = min(255, cb[i] * 255 / a)
                px[i] = (a shl 24) or (rr shl 16) or (gg shl 8) or bb
            }
        }
        bmp.setPixels(px, 0, bw, 0, 0, bw, bh)
    }

    private fun boxH(s: IntArray, d: IntArray, bw: Int, bh: Int, r: Int) {
        val div = 2 * r + 1
        for (y in 0 until bh) {
            val base = y * bw
            var sum = 0
            val lim = min(r, bw - 1)
            for (x in 0..lim) sum += s[base + x]
            for (x in 0 until bw) {
                d[base + x] = sum / div
                val add = x + r + 1
                if (add < bw) sum += s[base + add]
                val sub = x - r
                if (sub >= 0) sum -= s[base + sub]
            }
        }
    }

    private fun boxV(s: IntArray, d: IntArray, bw: Int, bh: Int, r: Int) {
        val div = 2 * r + 1
        for (x in 0 until bw) {
            var sum = 0
            val lim = min(r, bh - 1)
            for (y in 0..lim) sum += s[y * bw + x]
            for (y in 0 until bh) {
                d[y * bw + x] = sum / div
                val add = y + r + 1
                if (add < bh) sum += s[add * bw + x]
                val sub = y - r
                if (sub >= 0) sum -= s[sub * bw + x]
            }
        }
    }

    private fun fxDirBlur(src: Bitmap, dst: Bitmap, len: Float, angle: Float): Bitmap {
        if (len < 0.5f) return src
        val samples = 12
        dst.eraseColor(0)
        val c = Canvas(dst)
        val a = Math.toRadians(angle.toDouble())
        val dx = (cos(a) * len).toFloat()
        val dy = (sin(a) * len).toFloat()
        val pp = paint()
        for (i in 0 until samples) {
            val s = i / (samples - 1f) - 0.5f
            pp.alpha = (255f / (i + 1)).toInt().coerceIn(1, 255)
            c.drawBitmap(src, dx * s, dy * s, pp)
        }
        return dst
    }

    private fun fxZoomBlur(src: Bitmap, dst: Bitmap, amount: Float, cxp: Float, cyp: Float): Bitmap {
        if (amount < 0.5f) return src
        val samples = 12
        dst.eraseColor(0)
        val c = Canvas(dst)
        val k = amount / 100f * 0.4f
        val cx = w * cxp / 100f
        val cy = h * cyp / 100f
        val pp = paint()
        for (i in 0 until samples) {
            val s = 1f + k * (i / (samples - 1f) - 0.5f)
            pp.alpha = (255f / (i + 1)).toInt().coerceIn(1, 255)
            c.save()
            c.scale(s, s, cx, cy)
            c.drawBitmap(src, 0f, 0f, pp)
            c.restore()
        }
        return dst
    }

    private fun fxGlow(src: Bitmap, radius: Float, intensity: Float): Bitmap {
        tmp.eraseColor(0)
        Canvas(tmp).drawBitmap(src, 0f, 0f, null)
        blurBitmap(tmp, max(1f, radius))
        val pp = paint()
        pp.xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
        val c = Canvas(src)
        var k = intensity
        while (k > 0.01f) {
            pp.alpha = (min(1f, k) * 255f).roundToInt().coerceIn(0, 255)
            c.drawBitmap(tmp, 0f, 0f, pp)
            k -= 1f
        }
        return src
    }

    private fun fxShadow(src: Bitmap, dst: Bitmap, dist: Float, angle: Float, blur: Float, opacity: Float): Bitmap {
        tmp.eraseColor(0)
        val sp = paint()
        sp.colorFilter = ColorMatrixColorFilter(
            ColorMatrix(
                floatArrayOf(
                    0f, 0f, 0f, 0f, 0f,
                    0f, 0f, 0f, 0f, 0f,
                    0f, 0f, 0f, 0f, 0f,
                    0f, 0f, 0f, opacity, 0f
                )
            )
        )
        Canvas(tmp).drawBitmap(src, 0f, 0f, sp)
        if (blur > 0.6f) blurBitmap(tmp, blur)
        dst.eraseColor(0)
        val c = Canvas(dst)
        val a = Math.toRadians(angle.toDouble())
        c.drawBitmap(tmp, (cos(a) * dist).toFloat(), (sin(a) * dist).toFloat(), null)
        c.drawBitmap(src, 0f, 0f, null)
        return dst
    }

    // ------------------------------------------------------------------ light

    private fun fxVignette(src: Bitmap, amount: Float, size: Float, softness: Float): Bitmap {
        val rad = hypot(w.toFloat(), h.toFloat()) / 2f * max(0.1f, size)
        val start = (1f - softness).coerceIn(0f, 0.99f)
        val g = RadialGradient(
            w / 2f, h / 2f, rad,
            intArrayOf(Color.TRANSPARENT, Color.argb((amount * 255f).roundToInt().coerceIn(0, 255), 0, 0, 0)),
            floatArrayOf(start, 1f), Shader.TileMode.CLAMP
        )
        val pp = Paint()
        pp.shader = g
        pp.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
        Canvas(src).drawRect(0f, 0f, w.toFloat(), h.toFloat(), pp)
        return src
    }

    // ------------------------------------------------------------------ distortion

    private fun fxChroma(src: Bitmap, dst: Bitmap, amount: Float, angle: Float): Bitmap {
        if (amount < 0.3f) return src
        dst.eraseColor(0)
        val c = Canvas(dst)
        val a = Math.toRadians(angle.toDouble())
        val dx = (cos(a) * amount).toFloat()
        val dy = (sin(a) * amount).toFloat()
        val chans = arrayOf(floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 1f, 0f), floatArrayOf(0f, 0f, 1f))
        val sign = floatArrayOf(1f, 0f, -1f)
        for (i in 0..2) {
            val ch = chans[i]
            val m = ColorMatrix(
                floatArrayOf(
                    ch[0], 0f, 0f, 0f, 0f,
                    0f, ch[1], 0f, 0f, 0f,
                    0f, 0f, ch[2], 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            val pp = paint()
            pp.colorFilter = ColorMatrixColorFilter(m)
            pp.xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
            c.drawBitmap(src, dx * sign[i], dy * sign[i], pp)
        }
        return dst
    }

    private fun fxGlitch(src: Bitmap, dst: Bitmap, t: Float, amt: Float, slices: Int, rate: Float, rgb: Float, rs: Float): Bitmap {
        val seed = floor(t * max(1f, rate)).toLong()
        val rnd = Random(seed * 7919L + 17L)
        dst.eraseColor(0)
        val c = Canvas(dst)
        c.drawBitmap(src, 0f, 0f, null)
        val clear = Paint()
        clear.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        val fh = h.toFloat()
        for (i in 0 until max(1, slices)) {
            if (rnd.nextFloat() < amt) {
                val y0 = rnd.nextFloat() * fh
                val hh = rnd.nextFloat() * 0.12f * fh + 2f * rs
                val dx = (rnd.nextFloat() - 0.5f) * w * 0.25f * amt
                c.save()
                c.clipRect(0f, y0, w.toFloat(), min(fh, y0 + hh))
                c.drawPaint(clear)
                c.drawBitmap(src, dx, 0f, null)
                c.restore()
            }
        }
        return if (rgb > 0.5f) fxChroma(dst, src, rgb, 0f) else dst
    }

    private fun wob(x: Float, s: Float): Float =
        (sin(x + s) + 0.6f * sin(x * 2.3f + s * 1.7f + 1f) + 0.3f * sin(x * 4.1f + s * 0.7f + 2f)) / 1.9f

    private fun fxShake(src: Bitmap, dst: Bitmap, t: Float, amp: Float, freq: Float, rot: Float): Bitmap {
        val tt = t * freq * 6.2831f
        dst.eraseColor(0)
        val c = Canvas(dst)
        c.save()
        c.translate(wob(tt, 0f) * amp, wob(tt, 10f) * amp)
        c.rotate(wob(tt, 20f) * rot, w / 2f, h / 2f)
        c.drawBitmap(src, 0f, 0f, paint())
        c.restore()
        return dst
    }

    private fun fxWave(src: Bitmap, dst: Bitmap, t: Float, amp: Float, freq: Float, speed: Float, rs: Float): Bitmap {
        dst.eraseColor(0)
        val c = Canvas(dst)
        val band = max(2, (3f * rs).roundToInt())
        val pp = paint()
        var y = 0
        while (y < h) {
            val y2 = min(h, y + band)
            val dx = (amp * sin(freq * (y.toFloat() / h) * 6.2831f + t * speed)).toFloat()
            c.drawBitmap(src, Rect(0, y, w, y2), android.graphics.RectF(dx, y.toFloat(), w + dx, y2.toFloat()), pp)
            y = y2
        }
        return dst
    }

    // ------------------------------------------------------------------ stylize

    private fun fxPixelate(src: Bitmap, dst: Bitmap, size: Float): Bitmap {
        val sw = max(1, (w / size).toInt())
        val sh = max(1, (h / size).toInt())
        val small = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
        Canvas(small).drawBitmap(src, null, Rect(0, 0, sw, sh), paint())
        dst.eraseColor(0)
        val pp = Paint()
        pp.isFilterBitmap = false
        Canvas(dst).drawBitmap(small, null, Rect(0, 0, w, h), pp)
        small.recycle()
        return dst
    }

    private fun fxMirror(src: Bitmap, dst: Bitmap, mode: Int): Bitmap {
        dst.eraseColor(0)
        val c = Canvas(dst)
        c.drawBitmap(src, 0f, 0f, null)
        val clear = Paint()
        clear.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        c.save()
        if (mode == 0) {
            c.clipRect(w / 2f, 0f, w.toFloat(), h.toFloat())
            c.drawPaint(clear)
            c.scale(-1f, 1f, w / 2f, h / 2f)
        } else {
            c.clipRect(0f, h / 2f, w.toFloat(), h.toFloat())
            c.drawPaint(clear)
            c.scale(1f, -1f, w / 2f, h / 2f)
        }
        c.drawBitmap(src, 0f, 0f, null)
        c.restore()
        return dst
    }

    private fun fxNoise(src: Bitmap, t: Float, amount: Float): Bitmap {
        var tile = noiseTile
        if (tile == null) {
            val rnd = Random(1234L)
            val ts = 256
            val arr = IntArray(ts * ts)
            for (i in arr.indices) {
                val v = rnd.nextInt(256)
                arr[i] = (255 shl 24) or (v shl 16) or (v shl 8) or v
            }
            tile = Bitmap.createBitmap(arr, ts, ts, Bitmap.Config.ARGB_8888)
            noiseTile = tile
        }
        val rnd = Random(floor(t * 24f).toLong() * 31L + 5L)
        val sh = BitmapShader(tile!!, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        val m = Matrix()
        m.setTranslate(rnd.nextInt(256).toFloat(), rnd.nextInt(256).toFloat())
        sh.setLocalMatrix(m)
        val pp = Paint()
        pp.shader = sh
        pp.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
        pp.alpha = (amount * 0.5f * 255f).roundToInt().coerceIn(0, 255)
        Canvas(src).drawRect(0f, 0f, w.toFloat(), h.toFloat(), pp)
        return src
    }

    private fun fxSharpen(src: Bitmap, amount: Float): Bitmap {
        if (amount < 0.01f) return src
        src.getPixels(px, 0, w, 0, 0, w, h)
        System.arraycopy(px, 0, ca, 0, n)
        val k = 4f * amount + 1f
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                val c = px[i]
                val l = px[i - 1]
                val r = px[i + 1]
                val u = px[i - w]
                val d = px[i + w]
                var out = c and (0xFF shl 24)
                var shift = 0
                while (shift <= 16) {
                    val cc = (c shr shift) and 255
                    val nn = ((l shr shift) and 255) + ((r shr shift) and 255) + ((u shr shift) and 255) + ((d shr shift) and 255)
                    val v = (cc * k - amount * nn).toInt().coerceIn(0, 255)
                    out = out or (v shl shift)
                    shift += 8
                }
                ca[i] = out
            }
        }
        src.setPixels(ca, 0, w, 0, 0, w, h)
        return src
    }

    private fun fxWipe(src: Bitmap, dst: Bitmap, progress: Float, dir: Int): Bitmap {
        if (progress >= 0.999f) return src
        dst.eraseColor(0)
        if (progress <= 0.001f) return dst
        val c = Canvas(dst)
        val fw = w.toFloat()
        val fh = h.toFloat()
        c.save()
        when (dir) {
            0 -> c.clipRect(0f, 0f, fw * progress, fh)
            1 -> c.clipRect(fw * (1f - progress), 0f, fw, fh)
            2 -> c.clipRect(0f, 0f, fw, fh * progress)
            else -> c.clipRect(0f, fh * (1f - progress), fw, fh)
        }
        c.drawBitmap(src, 0f, 0f, null)
        c.restore()
        return dst
    }

    fun release() {
        tmp.recycle()
        noiseTile?.recycle()
        noiseTile = null
    }
}
