package com.r0pw.editor.engine

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.r0pw.editor.model.LayerType
import com.r0pw.editor.model.Project
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class ExportOptions(val width: Int, val height: Int, val fps: Int, val bitrate: Int, val audio: Boolean)

class ExportResult(val ok: Boolean, val message: String, val audioIncluded: Boolean)

object Exporter {

    /** Output size for a "short side" preset (720 / 1080 / 2160), keeping the project's aspect ratio. */
    fun sizeFor(p: Project, shortSide: Int): Pair<Int, Int> {
        val aspect = p.width.toFloat() / p.height
        var w: Int
        var h: Int
        if (aspect >= 1f) {
            h = shortSide
            w = (shortSide * aspect).roundToInt()
        } else {
            w = shortSide
            h = (shortSide / aspect).roundToInt()
        }
        w = w and 1.inv()
        h = h and 1.inv()
        return Pair(max(2, w), max(2, h))
    }

    fun defaultBitrate(w: Int, h: Int, fps: Int): Int =
        (w.toLong() * h * fps * 0.12).toLong().coerceIn(2_000_000L, 80_000_000L).toInt()

    private class Writer(val codec: MediaCodec, val muxer: MediaMuxer, val audio: AudioMixer.Encoded?) {
        var vTrack = -1
        var aTrack = -1
        var started = false
        val info = MediaCodec.BufferInfo()

        fun drain(eos: Boolean) {
            var spins = 0
            while (true) {
                val idx = codec.dequeueOutputBuffer(info, if (eos) 10_000L else 0L)
                if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (!eos) return
                    spins++
                    if (spins > 800) return
                    continue
                }
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    vTrack = muxer.addTrack(codec.outputFormat)
                    if (audio != null) aTrack = muxer.addTrack(audio.format)
                    muxer.start()
                    started = true
                    if (audio != null) {
                        for (s in audio.samples) muxer.writeSampleData(aTrack, ByteBuffer.wrap(s.first), s.second)
                    }
                    continue
                }
                if (idx < 0) continue
                val buf = codec.getOutputBuffer(idx)
                if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0
                if (info.size > 0 && buf != null && started) {
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    muxer.writeSampleData(vTrack, buf, info)
                }
                codec.releaseOutputBuffer(idx, false)
                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
            }
        }
    }

    /** Blocking. Call from a background thread. */
    fun export(
        ctx: Context, project: Project, opts: ExportOptions, media: MediaCache,
        onProgress: (Float) -> Unit, cancelled: () -> Boolean
    ): ExportResult {
        val outDir = ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: ctx.filesDir
        outDir.mkdirs()
        val safe = project.name.replace(Regex("[^A-Za-z0-9_\\-]"), "_").ifBlank { "project" }
        val file = File(outDir, "${safe}_${System.currentTimeMillis() / 1000}.mp4")

        var audio: AudioMixer.Encoded? = null
        var audioNote = ""
        if (opts.audio && project.duration <= 600f && project.layers.any { it.visible && it.hasAudioSource() }) {
            try {
                audio = AudioMixer.mixAndEncode(ctx, project, cancelled)
            } catch (t: Throwable) {
                audio = null
                audioNote = " (audio skipped: ${t.message})"
            }
        }
        if (cancelled()) return ExportResult(false, "Cancelled", false)

        val w = opts.width
        val h = opts.height
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        val renderer = Renderer(media)
        var writer: Writer? = null
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            format.setInteger(MediaFormat.KEY_BIT_RATE, opts.bitrate)
            format.setInteger(MediaFormat.KEY_FRAME_RATE, opts.fps)
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            format.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
            format.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec = c
            c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            c.start()
            val m = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = m
            val wr = Writer(c, m, audio)
            writer = wr

            val px = IntArray(w * h)
            val total = max(1, (project.duration * opts.fps).roundToInt())
            for (i in 0 until total) {
                if (cancelled()) {
                    c.stop(); c.release(); codec = null
                    try { if (wr.started) m.stop() } catch (e: Exception) { }
                    m.release(); muxer = null
                    file.delete()
                    return ExportResult(false, "Cancelled", false)
                }
                val t = i / opts.fps.toFloat()
                val bmp = renderer.render(project, t, w, h, true, null, false, false)
                var inIdx = c.dequeueInputBuffer(10_000L)
                var guard = 0
                while (inIdx < 0) {
                    wr.drain(false)
                    inIdx = c.dequeueInputBuffer(10_000L)
                    guard++
                    if (guard > 2000) throw IllegalStateException("Encoder stalled")
                }
                val img = c.getInputImage(inIdx) ?: throw IllegalStateException("Encoder gave no input image")
                fillImage(img, bmp, px)
                val pts = i * 1_000_000L / opts.fps
                c.queueInputBuffer(inIdx, 0, w * h * 3 / 2, pts, 0)
                wr.drain(false)
                onProgress((i + 1f) / total)
            }
            var eosIdx = c.dequeueInputBuffer(10_000L)
            var g2 = 0
            while (eosIdx < 0) {
                wr.drain(false)
                eosIdx = c.dequeueInputBuffer(10_000L)
                g2++
                if (g2 > 2000) throw IllegalStateException("Encoder stalled at end")
            }
            c.queueInputBuffer(eosIdx, 0, 0, total * 1_000_000L / opts.fps, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            wr.drain(true)
            c.stop(); c.release(); codec = null
            if (wr.started) m.stop()
            m.release(); muxer = null
        } catch (t: Throwable) {
            try { codec?.stop() } catch (e: Exception) { }
            try { codec?.release() } catch (e: Exception) { }
            try { muxer?.release() } catch (e: Exception) { }
            file.delete()
            return ExportResult(false, "Export failed: ${t.message ?: t.javaClass.simpleName}", false)
        } finally {
            renderer.release()
        }

        val where = publish(ctx, file)
        return ExportResult(true, "Saved: $where$audioNote", audio != null)
    }

    private fun publish(ctx: Context, file: File): String {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                val rv = ctx.contentResolver
                val values = ContentValues()
                values.put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
                values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                values.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/1r0pw")
                values.put(MediaStore.Video.Media.IS_PENDING, 1)
                val uri = rv.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    rv.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                    values.clear()
                    values.put(MediaStore.Video.Media.IS_PENDING, 0)
                    rv.update(uri, values, null, null)
                    file.delete()
                    return "Movies/1r0pw/${file.name}"
                }
            } catch (e: Exception) {
                // fall through
            }
        }
        return file.absolutePath
    }

    private fun fillImage(img: Image, bmp: Bitmap, px: IntArray) {
        val w = bmp.width
        val h = bmp.height
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val yP = img.planes[0]
        val uP = img.planes[1]
        val vP = img.planes[2]
        val yBuf = yP.buffer
        val uBuf = uP.buffer
        val vBuf = vP.buffer
        val yRow = yP.rowStride
        val uRow = uP.rowStride
        val vRow = vP.rowStride
        val uPix = uP.pixelStride
        val vPix = vP.pixelStride
        for (j in 0 until h) {
            val rowBase = j * w
            val yBase = j * yRow
            val evenRow = (j and 1) == 0
            for (i in 0 until w) {
                val c = px[rowBase + i]
                val r = (c shr 16) and 255
                val g = (c shr 8) and 255
                val b = c and 255
                val y = ((47 * r + 157 * g + 16 * b + 128) shr 8) + 16
                yBuf.put(yBase + i, (if (y > 255) 255 else y).toByte())
                if (evenRow && (i and 1) == 0) {
                    var u = ((-26 * r - 87 * g + 112 * b + 128) shr 8) + 128
                    var v = ((112 * r - 102 * g - 10 * b + 128) shr 8) + 128
                    if (u < 0) u = 0 else if (u > 255) u = 255
                    if (v < 0) v = 0 else if (v > 255) v = 255
                    uBuf.put((j shr 1) * uRow + (i shr 1) * uPix, u.toByte())
                    vBuf.put((j shr 1) * vRow + (i shr 1) * vPix, v.toByte())
                }
            }
        }
    }
}

/** Decodes every audio-carrying layer, mixes them into one stereo 44.1 kHz track and encodes it as AAC. */
object AudioMixer {
    const val SR = 44100

    class Encoded(val format: MediaFormat, val samples: List<Pair<ByteArray, MediaCodec.BufferInfo>>)

    fun mixAndEncode(ctx: Context, project: Project, cancelled: () -> Boolean): Encoded? {
        val totalFrames = (project.duration * SR).toInt()
        if (totalFrames <= 0) return null
        val mix = ShortArray(totalFrames * 2)
        var any = false
        for (l in project.layers.reversed()) {
            if (!l.visible || !l.hasAudioSource()) continue
            if (l.type != LayerType.AUDIO && l.type != LayerType.VIDEO) continue
            if (cancelled()) return null
            if (decodeInto(ctx, l, mix, totalFrames, cancelled)) any = true
        }
        if (!any) return null
        return encodeAac(mix)
    }

    private fun decodeInto(
        ctx: Context, l: com.r0pw.editor.model.Layer, mix: ShortArray, totalFrames: Int, cancelled: () -> Boolean
    ): Boolean {
        val ex = MediaExtractor()
        var dec: MediaCodec? = null
        try {
            ex.setDataSource(ctx, Uri.parse(l.source), null)
            var track = -1
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) {
                    track = i
                    break
                }
            }
            if (track < 0) return false
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: return false
            var sr = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val startUs = (l.inPoint * 1_000_000.0).toLong()
            val endUs = ((l.inPoint + l.duration) * 1_000_000.0).toLong()
            ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val d = MediaCodec.createDecoderByType(mime)
            dec = d
            d.configure(fmt, null, null, 0)
            d.start()

            val startIdx = (l.start * SR).toInt()
            val endIdx = min(totalFrames, ((l.start + l.duration) * SR).toInt())
            val info = MediaCodec.BufferInfo()
            var inDone = false
            var outDone = false
            var posInit = false
            var destPos = 0.0
            var gain = 1f
            var gainUntil = -1
            var spins = 0
            while (!outDone) {
                if (cancelled()) return false
                if (!inDone) {
                    val i = d.dequeueInputBuffer(5000)
                    if (i >= 0) {
                        val b = d.getInputBuffer(i)!!
                        val n = ex.readSampleData(b, 0)
                        if (n < 0 || ex.sampleTime > endUs) {
                            d.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inDone = true
                        } else {
                            d.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val o = d.dequeueOutputBuffer(info, 5000)
                if (o >= 0) {
                    spins = 0
                    val ob = d.getOutputBuffer(o)
                    if (ob != null && info.size > 0) {
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        val sb = ob.order(ByteOrder.nativeOrder()).asShortBuffer()
                        val cnt = sb.remaining()
                        val shorts = ShortArray(cnt)
                        sb.get(shorts)
                        if (!posInit) {
                            destPos = (l.start + (info.presentationTimeUs - startUs) / 1_000_000.0) * SR
                            posInit = true
                        }
                        val step = SR.toDouble() / sr
                        val frames = cnt / max(1, ch)
                        for (k in 0 until frames) {
                            val l0 = shorts[k * ch].toInt()
                            val r0 = if (ch > 1) shorts[k * ch + 1].toInt() else l0
                            val d0 = floor(destPos).toInt()
                            destPos += step
                            val d1 = floor(destPos).toInt()
                            if (d1 > d0) {
                                for (di in d0 until d1) {
                                    if (di < startIdx || di >= endIdx) continue
                                    if (di > gainUntil) {
                                        gain = gainAt(l, di.toFloat() / SR)
                                        gainUntil = di + 441
                                    }
                                    val mi = di * 2
                                    mix[mi] = clamp16(mix[mi] + (l0 * gain).toInt())
                                    mix[mi + 1] = clamp16(mix[mi + 1] + (r0 * gain).toInt())
                                }
                            }
                        }
                    }
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0 || info.presentationTimeUs > endUs) outDone = true
                    d.releaseOutputBuffer(o, false)
                } else if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val nf = d.outputFormat
                    if (nf.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sr = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    if (nf.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) ch = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                } else if (o == MediaCodec.INFO_TRY_AGAIN_LATER && inDone) {
                    spins++
                    if (spins > 400) outDone = true
                }
            }
            return true
        } finally {
            try { dec?.stop() } catch (e: Exception) { }
            try { dec?.release() } catch (e: Exception) { }
            try { ex.release() } catch (e: Exception) { }
        }
    }

    private fun gainAt(l: com.r0pw.editor.model.Layer, compSec: Float): Float {
        val lt = compSec - l.start
        var g = l.v("volume", lt, 100f) / 100f
        if (l.fadeIn > 0.001f && lt < l.fadeIn) g *= lt / l.fadeIn
        val rem = l.duration - lt
        if (l.fadeOut > 0.001f && rem < l.fadeOut) g *= rem / l.fadeOut
        return max(0f, g)
    }

    private fun clamp16(v: Int): Short = (if (v > 32767) 32767 else if (v < -32768) -32768 else v).toShort()

    private fun encodeAac(mix: ShortArray): Encoded {
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SR, 2)
        fmt.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, 160_000)
        fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 32768)
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        enc.start()
        val samples = ArrayList<Pair<ByteArray, MediaCodec.BufferInfo>>()
        var outFormat: MediaFormat? = null
        val info = MediaCodec.BufferInfo()
        var inPos = 0
        val chunk = 4096 * 2
        var eosSent = false
        var done = false
        var spins = 0
        try {
            while (!done) {
                if (!eosSent) {
                    val i = enc.dequeueInputBuffer(5000)
                    if (i >= 0) {
                        val b = enc.getInputBuffer(i)!!
                        b.clear()
                        val remain = mix.size - inPos
                        val pts = (inPos / 2) * 1_000_000L / SR
                        if (remain <= 0) {
                            enc.queueInputBuffer(i, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            eosSent = true
                        } else {
                            val cnt = min(remain, chunk)
                            b.order(ByteOrder.nativeOrder())
                            b.asShortBuffer().put(mix, inPos, cnt)
                            enc.queueInputBuffer(i, 0, cnt * 2, pts, 0)
                            inPos += cnt
                        }
                    }
                }
                val o = enc.dequeueOutputBuffer(info, 5000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    outFormat = enc.outputFormat
                } else if (o >= 0) {
                    spins = 0
                    val ob = enc.getOutputBuffer(o)
                    if (ob != null && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0) {
                        val arr = ByteArray(info.size)
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        ob.get(arr)
                        val bi = MediaCodec.BufferInfo()
                        bi.set(0, info.size, info.presentationTimeUs, info.flags)
                        samples.add(Pair(arr, bi))
                    }
                    enc.releaseOutputBuffer(o, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) done = true
                } else if (eosSent) {
                    spins++
                    if (spins > 600) done = true
                }
            }
        } finally {
            try { enc.stop() } catch (e: Exception) { }
            try { enc.release() } catch (e: Exception) { }
        }
        return Encoded(outFormat ?: fmt, samples)
    }
}
