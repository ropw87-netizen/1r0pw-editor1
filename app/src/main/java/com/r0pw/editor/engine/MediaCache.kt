package com.r0pw.editor.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import com.r0pw.editor.model.LayerType
import com.r0pw.editor.model.MediaItem
import com.r0pw.editor.model.TextSpec
import com.r0pw.editor.model.newId
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Reads and caches video frames, images and fonts. Thread-safe (calls are synchronized). */
class MediaCache(private val ctx: Context) {
    private val retrievers = HashMap<String, MediaMetadataRetriever?>()
    private val frames = LinkedHashMap<String, Bitmap>(16, 0.75f, true)
    private var frameBytes = 0L
    private val images = HashMap<String, Bitmap?>()
    private val fonts = HashMap<String, Typeface?>()

    private fun retriever(uri: String): MediaMetadataRetriever? {
        if (retrievers.containsKey(uri)) return retrievers[uri]
        val r = try {
            MediaMetadataRetriever().also { it.setDataSource(ctx, Uri.parse(uri)) }
        } catch (e: Exception) {
            null
        }
        retrievers[uri] = r
        return r
    }

    private fun soft(b: Bitmap?): Bitmap? {
        if (b == null) return null
        return if (b.config == Bitmap.Config.HARDWARE) b.copy(Bitmap.Config.ARGB_8888, false) else b
    }

    /**
     * A video frame at [timeSec]. [tw]/[th] is the wanted decode size (display orientation).
     * [exact] = false uses nearest keyframe seeking, which is much faster for preview.
     */
    @Synchronized
    fun videoFrame(uri: String, timeSec: Float, tw: Int, th: Int, exact: Boolean, useCache: Boolean): Bitmap? {
        val ms = (timeSec * 1000f).roundToInt().coerceAtLeast(0)
        val key = "$uri@${if (exact) ms else ms / 40 * 40}@$tw@${if (exact) 1 else 0}"
        if (useCache) frames[key]?.let { return it }
        val r = retriever(uri) ?: return null
        val us = ms * 1000L
        val opt = if (exact) MediaMetadataRetriever.OPTION_CLOSEST else MediaMetadataRetriever.OPTION_CLOSEST_SYNC
        val bmp = try {
            if (Build.VERSION.SDK_INT >= 27 && tw > 0 && th > 0) r.getScaledFrameAtTime(us, opt, tw, th)
            else r.getFrameAtTime(us, opt)
        } catch (e: Exception) {
            null
        }
        val sb = soft(bmp) ?: return null
        if (useCache) {
            frames[key] = sb
            frameBytes += sb.byteCount.toLong()
            val it = frames.entries.iterator()
            while (frameBytes > 64L * 1024 * 1024 && it.hasNext()) {
                val e = it.next()
                frameBytes -= e.value.byteCount.toLong()
                it.remove()
            }
        }
        return sb
    }

    @Synchronized
    fun image(uri: String, maxDim: Int = 2048): Bitmap? {
        if (images.containsKey(uri)) return images[uri]
        val bmp = decodeImage(ctx, uri, maxDim)
        images[uri] = bmp
        return bmp
    }

    @Synchronized
    fun typeface(ts: TextSpec): Typeface {
        val path = ts.fontPath
        if (path != null) {
            val cached = fonts[path]
            if (cached != null) return cached
            if (!fonts.containsKey(path)) {
                fonts[path] = try {
                    Typeface.createFromFile(path)
                } catch (e: Exception) {
                    null
                }
                fonts[path]?.let { return it }
            }
        }
        val base = when (ts.family) {
            1 -> Typeface.SERIF
            2 -> Typeface.MONOSPACE
            else -> Typeface.DEFAULT
        }
        val style = when {
            ts.bold && ts.italic -> Typeface.BOLD_ITALIC
            ts.bold -> Typeface.BOLD
            ts.italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        return Typeface.create(base, style)
    }

    @Synchronized
    fun clearFrames() {
        frames.clear()
        frameBytes = 0
    }

    @Synchronized
    fun release() {
        for (r in retrievers.values) try { r?.release() } catch (e: Exception) { }
        retrievers.clear()
        frames.clear()
        frameBytes = 0
        images.clear()
    }

    companion object {
        fun decodeImage(ctx: Context, uri: String, maxDim: Int): Bitmap? {
            return try {
                val u = Uri.parse(uri)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                ctx.contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (max(bounds.outWidth, bounds.outHeight) / sample > maxDim) sample *= 2
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                var bmp = ctx.contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, opts) }
                    ?: return null
                val orient = try {
                    ctx.contentResolver.openInputStream(u)?.use {
                        ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                    } ?: ExifInterface.ORIENTATION_NORMAL
                } catch (e: Exception) {
                    ExifInterface.ORIENTATION_NORMAL
                }
                val m = Matrix()
                when (orient) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
                    ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
                }
                if (!m.isIdentity) {
                    val rot = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                    if (rot !== bmp) bmp = rot
                }
                bmp
            } catch (e: Exception) {
                null
            }
        }
    }
}

/** Reads metadata of a picked file and turns it into a [MediaItem]. */
object MediaImporter {
    fun analyze(ctx: Context, uri: Uri): MediaItem? {
        val mime = ctx.contentResolver.getType(uri) ?: ""
        var name = "media"
        try {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) name = c.getString(i) ?: name
            }
        } catch (e: Exception) {
        }
        val type = when {
            mime.startsWith("video") -> LayerType.VIDEO
            mime.startsWith("image") -> LayerType.IMAGE
            mime.startsWith("audio") -> LayerType.AUDIO
            else -> return null
        }
        val item = MediaItem(newId(), uri.toString(), name, type)
        try {
            when (type) {
                LayerType.IMAGE -> {
                    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
                    var w = o.outWidth
                    var h = o.outHeight
                    val orient = try {
                        ctx.contentResolver.openInputStream(uri)?.use {
                            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)
                        } ?: 1
                    } catch (e: Exception) {
                        1
                    }
                    if (orient == ExifInterface.ORIENTATION_ROTATE_90 || orient == ExifInterface.ORIENTATION_ROTATE_270 ||
                        orient == ExifInterface.ORIENTATION_TRANSPOSE || orient == ExifInterface.ORIENTATION_TRANSVERSE
                    ) {
                        val t = w; w = h; h = t
                    }
                    item.width = w; item.height = h
                }
                else -> {
                    val r = MediaMetadataRetriever()
                    r.setDataSource(ctx, uri)
                    val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    item.duration = dur / 1000f
                    if (type == LayerType.VIDEO) {
                        var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                        var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                        val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                        if (rot == 90 || rot == 270) {
                            val t = w; w = h; h = t
                        }
                        item.width = w; item.height = h
                        item.hasAudio = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
                    } else {
                        item.hasAudio = true
                    }
                    r.release()
                }
            }
        } catch (e: Exception) {
            if (type != LayerType.IMAGE && item.duration <= 0f) return null
        }
        return item
    }

    fun takePermission(ctx: Context, uri: Uri) {
        try {
            ctx.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) {
        }
    }

    /** Copies a picked font into app storage and returns its path. */
    fun importFont(ctx: Context, uri: Uri): String? {
        return try {
            val dir = java.io.File(ctx.filesDir, "fonts").apply { mkdirs() }
            var name = "font_${newId()}.ttf"
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) name = c.getString(i) ?: name
            }
            val out = java.io.File(dir, name)
            ctx.contentResolver.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it) } }
            // validate
            Typeface.createFromFile(out)
            out.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    fun fitSize(w: Int, h: Int, maxDim: Int): Pair<Int, Int> {
        val s = min(1f, maxDim.toFloat() / max(w, h).coerceAtLeast(1))
        return Pair(max(2, (w * s).roundToInt()), max(2, (h * s).roundToInt()))
    }
}
