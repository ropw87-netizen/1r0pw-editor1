package com.r0pw.editor.ui.editor

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.r0pw.editor.data.AppSettings
import com.r0pw.editor.data.PresetStore
import com.r0pw.editor.data.ProjectStore
import com.r0pw.editor.engine.AudioPreview
import com.r0pw.editor.engine.ExportOptions
import com.r0pw.editor.engine.Exporter
import com.r0pw.editor.engine.MediaCache
import com.r0pw.editor.engine.MediaImporter
import com.r0pw.editor.engine.Presets
import com.r0pw.editor.engine.Renderer
import com.r0pw.editor.model.BlendMode
import com.r0pw.editor.model.FxCatalog
import com.r0pw.editor.model.Layer
import com.r0pw.editor.model.LayerType
import com.r0pw.editor.model.MaskSpec
import com.r0pw.editor.model.MediaItem
import com.r0pw.editor.model.Project
import com.r0pw.editor.model.ProjectJson
import com.r0pw.editor.model.Props
import com.r0pw.editor.model.ShapeKind
import com.r0pw.editor.model.ShapeSpec
import com.r0pw.editor.model.TextSpec
import com.r0pw.editor.model.Track
import com.r0pw.editor.model.newId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class EditorState(
    val ctx: Context,
    val project: Project,
    val store: ProjectStore,
    val settings: AppSettings,
    val presets: PresetStore,
    private val scope: CoroutineScope
) {
    // ---- observable state
    var rev by mutableIntStateOf(0)
        private set
    var time by mutableFloatStateOf(0f)
    var playing by mutableStateOf(false)
    var selectedId by mutableStateOf<String?>(null)
    var tab by mutableIntStateOf(0)
    var pxPerSec by mutableFloatStateOf(80f)
    var preview by mutableStateOf<ImageBitmap?>(null)
    var message by mutableStateOf("")
    var exporting by mutableStateOf(false)
    var exportProgress by mutableFloatStateOf(0f)
    var canUndo by mutableStateOf(false)
    var canRedo by mutableStateOf(false)

    val media = MediaCache(ctx)
    val audio = AudioPreview(ctx)
    private val renderer = Renderer(media)
    private val renderDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    @Volatile
    private var cancelExport = false

    fun track(): Int = rev

    val selected: Layer? get() = project.layer(selectedId)

    // ------------------------------------------------------------------ undo / redo / save

    private val undoStack = ArrayDeque<String>()
    private val redoStack = ArrayDeque<String>()
    private var lastTag = ""
    private var lastTime = 0L
    private var saveJob: Job? = null

    private fun snapshot(): String = ProjectJson.toJson(project).toString()

    fun edit(tag: String = "", block: () -> Unit) {
        val now = System.currentTimeMillis()
        if (tag.isEmpty() || tag != lastTag || now - lastTime > 700L) {
            undoStack.addLast(snapshot())
            if (undoStack.size > 60) undoStack.removeFirst()
            redoStack.clear()
        }
        lastTag = tag
        lastTime = now
        block()
        touch()
    }

    fun touch() {
        rev++
        canUndo = undoStack.isNotEmpty()
        canRedo = redoStack.isNotEmpty()
        scheduleSave()
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(900)
            saveNow()
        }
    }

    suspend fun saveNow() {
        project.modified = System.currentTimeMillis()
        val text = snapshot()
        withContext(Dispatchers.IO) { store.writeJson(project.id, text) }
    }

    private fun adopt(p: Project) {
        project.name = p.name
        project.width = p.width; project.height = p.height; project.fps = p.fps
        project.duration = p.duration; project.bg = p.bg
        project.layers.clear(); project.layers.addAll(p.layers)
        project.media.clear(); project.media.addAll(p.media)
        if (project.layer(selectedId) == null) selectedId = null
        time = time.coerceIn(0f, project.duration)
        lastTag = ""
        touch()
    }

    fun undo() {
        if (undoStack.isEmpty()) return
        redoStack.addLast(snapshot())
        adopt(ProjectJson.parse(undoStack.removeLast()))
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        undoStack.addLast(snapshot())
        adopt(ProjectJson.parse(redoStack.removeLast()))
    }

    // ------------------------------------------------------------------ preview rendering

    suspend fun renderFrame() {
        val t = time
        val isPlaying = playing
        val sel = selectedId
        val gizmos = settings.showGizmos && !isPlaying
        val w = min(settings.previewWidth(), project.width).coerceAtLeast(2)
        val h = (w * project.height.toFloat() / project.width).roundToInt().coerceAtLeast(2)
        try {
            val bmp = withContext(renderDispatcher) {
                val b = renderer.render(project, t, w, h, !isPlaying, sel, gizmos)
                b.copy(Bitmap.Config.ARGB_8888, false)
            }
            preview = bmp.asImageBitmap()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // The project changed while rendering; the next edit triggers a fresh render.
        }
    }

    // ------------------------------------------------------------------ playback

    fun togglePlay() {
        if (playing) {
            playing = false
            audio.pauseAll()
        } else {
            if (time >= project.duration - 0.02f) time = 0f
            playing = true
        }
    }

    suspend fun playLoop() {
        var baseT = time
        var baseNs = System.nanoTime()
        var tick = 0
        while (playing) {
            androidx.compose.runtime.withFrameNanos { }
            var t = baseT + (System.nanoTime() - baseNs) / 1e9f
            if (t >= project.duration) {
                if (settings.loopPlayback) {
                    baseT = 0f
                    baseNs = System.nanoTime()
                    t = 0f
                } else {
                    time = project.duration
                    playing = false
                    break
                }
            }
            time = t
            if (tick % 5 == 0) audio.sync(project, t, true)
            tick++
        }
        audio.pauseAll()
    }

    fun seek(t: Float) {
        var v = t.coerceIn(0f, project.duration)
        if (settings.snapToFrames) v = (v * project.fps).roundToInt() / project.fps.toFloat()
        time = v
    }

    fun stepFrames(n: Int) {
        playing = false
        seek(time + n / project.fps.toFloat())
    }

    // ------------------------------------------------------------------ layers

    private fun fit(l: Layer) {
        Props.ensure(l, project.width, project.height)
    }

    private fun insertTop(l: Layer) {
        fit(l)
        project.layers.add(0, l)
        selectedId = l.id
    }

    fun importUris(uris: List<Uri>) {
        scope.launch {
            val items = withContext(Dispatchers.IO) {
                uris.mapNotNull { u ->
                    MediaImporter.takePermission(ctx, u)
                    MediaImporter.analyze(ctx, u)
                }
            }
            if (items.isEmpty()) {
                message = "Could not read the selected files"
                return@launch
            }
            edit("import") {
                for (m in items) {
                    project.media.add(m)
                    addLayerForMedia(m)
                }
            }
        }
    }

    fun addLayerForMedia(m: MediaItem) {
        if (project.layers.isEmpty() && m.type == LayerType.VIDEO && m.width > 0 && m.height > 0) {
            val s = min(1f, 3840f / max(m.width, m.height))
            project.width = max(2, (m.width * s).roundToInt()) and 1.inv()
            project.height = max(2, (m.height * s).roundToInt()) and 1.inv()
            project.duration = max(1f, m.duration)
        }
        val l = Layer(newId(), m.name.substringBeforeLast('.').take(24), m.type)
        l.source = m.uri
        l.mediaId = m.id
        l.srcW = m.width
        l.srcH = m.height
        l.srcDur = m.duration
        l.start = min(time, max(0f, project.duration - 0.5f))
        l.duration = when (m.type) {
            LayerType.IMAGE -> 5f
            else -> max(0.5f, m.duration)
        }
        l.color = when (m.type) {
            LayerType.VIDEO -> 0xFF3B6EF5.toInt()
            LayerType.IMAGE -> 0xFF2DB87A.toInt()
            else -> 0xFFE58A2D.toInt()
        }
        fit(l)
        if (m.width > 0 && m.height > 0) {
            val s = min(project.width.toFloat() / m.width, project.height.toFloat() / m.height) * 100f
            l.track("scaleX").base = s
            l.track("scaleY").base = s
        }
        project.layers.add(0, l)
        selectedId = l.id
        if (l.end > project.duration && project.layers.size == 1) project.duration = l.end
    }

    private fun baseLayer(name: String, type: LayerType, color: Int): Layer {
        val l = Layer(newId(), name, type)
        l.start = min(time, max(0f, project.duration - 0.5f))
        l.duration = max(0.5f, min(5f, project.duration - l.start))
        l.color = color
        return l
    }

    fun addText() = edit {
        val l = baseLayer("Text", LayerType.TEXT, 0xFFE0B341.toInt())
        l.text = TextSpec()
        insertTop(l)
        l.track("size", 72f).base = project.height * 0.1f
    }

    fun addShape(kind: ShapeKind) = edit {
        val l = baseLayer(kind.label, LayerType.SHAPE, 0xFF7C5CFF.toInt())
        l.shape = ShapeSpec().also { it.kind = kind }
        insertTop(l)
        val s = project.height * 0.3f
        l.track("w").base = s
        l.track("h").base = s
        l.track("corner").base = s * 0.15f
    }

    fun addNull() = edit {
        val l = baseLayer("Null", LayerType.NULL, 0xFF2DE2E6.toInt())
        insertTop(l)
    }

    fun addCamera() = edit {
        val l = baseLayer("Camera", LayerType.CAMERA, 0xFFFF6FD8.toInt())
        l.start = 0f
        l.duration = project.duration
        insertTop(l)
    }

    fun deleteLayer(id: String) = edit {
        val l = project.layer(id) ?: return@edit
        project.layers.remove(l)
        for (o in project.layers) if (o.parentId == id) o.parentId = null
        if (selectedId == id) selectedId = null
    }

    fun duplicateLayer(id: String) = edit {
        val l = project.layer(id) ?: return@edit
        val copy = ProjectJson.layerFromJson(ProjectJson.layerToJson(l))
        val c = Layer(newId(), l.name + " copy", l.type)
        // rebuild with new id by re-reading JSON into the fresh layer shell
        c.start = copy.start; c.duration = copy.duration; c.inPoint = copy.inPoint; c.speed = copy.speed
        c.source = copy.source; c.mediaId = copy.mediaId; c.srcW = copy.srcW; c.srcH = copy.srcH; c.srcDur = copy.srcDur
        c.parentId = copy.parentId; c.visible = copy.visible; c.threeD = copy.threeD; c.blend = copy.blend
        c.fadeIn = copy.fadeIn; c.fadeOut = copy.fadeOut; c.color = copy.color
        c.text = copy.text; c.shape = copy.shape; c.mask = copy.mask
        c.tracks.putAll(copy.tracks)
        c.effects.addAll(copy.effects)
        val idx = project.layers.indexOf(l)
        project.layers.add(max(0, idx), c)
        selectedId = c.id
    }

    fun moveLayer(id: String, dir: Int) = edit {
        val i = project.layers.indexOfFirst { it.id == id }
        val j = i + dir
        if (i < 0 || j < 0 || j >= project.layers.size) return@edit
        val l = project.layers.removeAt(i)
        project.layers.add(j, l)
    }

    fun splitSelected() {
        val l = selected ?: return
        val lt = time - l.start
        if (lt <= 0.05f || lt >= l.duration - 0.05f) {
            message = "Move the playhead inside the layer to split it"
            return
        }
        edit {
            val src = ProjectJson.layerFromJson(ProjectJson.layerToJson(l))
            val c = Layer(newId(), l.name + " B", l.type)
            c.source = src.source; c.mediaId = src.mediaId; c.srcW = src.srcW; c.srcH = src.srcH; c.srcDur = src.srcDur
            c.speed = src.speed; c.visible = src.visible; c.threeD = src.threeD; c.blend = src.blend
            c.parentId = src.parentId; c.color = src.color
            c.text = src.text; c.shape = src.shape; c.mask = src.mask
            c.tracks.putAll(src.tracks); c.effects.addAll(src.effects)
            c.start = time
            c.duration = l.duration - lt
            c.inPoint = l.inPoint + lt * l.speed
            shiftKeys(c, -lt)
            c.fadeOut = l.fadeOut
            l.fadeOut = 0f
            l.duration = lt
            project.layers.add(max(0, project.layers.indexOf(l)), c)
            selectedId = c.id
        }
    }

    fun shiftKeys(l: Layer, dt: Float) {
        for (tr in l.tracks.values) for (k in tr.keys) k.t += dt
        for (e in l.effects) for (tr in e.tracks.values) for (k in tr.keys) k.t += dt
    }

    fun setParent(l: Layer, parentId: String?) = edit {
        val par = project.layer(parentId)
        if (par != null && project.isDescendantOrSelf(par, l)) {
            message = "Cannot parent a layer to itself or its child"
            return@edit
        }
        l.parentId = par?.id
    }

    // ------------------------------------------------------------------ properties

    fun setValue(tr: Track, layer: Layer, v: Float, tag: String) {
        edit(tag) {
            if (tr.animated) {
                tr.setKey(time - layer.start, v)
            } else {
                tr.base = v
            }
        }
    }

    fun toggleAnimated(tr: Track, layer: Layer) = edit {
        val lt = time - layer.start
        if (tr.animated) tr.collapse(lt) else tr.setKey(lt, tr.base)
    }

    fun toggleKey(tr: Track, layer: Layer) = edit {
        val lt = time - layer.start
        val k = tr.keyNear(lt)
        if (k != null) tr.keys.remove(k) else tr.setKey(lt, tr.valueAt(lt))
    }

    fun dragSelected(dx: Float, dy: Float, scale: Float, rotDeg: Float) {
        val l = selected ?: return
        if (l.locked) return
        edit("drag" + l.id) {
            val lt = time - l.start
            val tx = l.track("x")
            val ty = l.track("y")
            setTrack(tx, lt, tx.valueAt(lt) + dx)
            setTrack(ty, lt, ty.valueAt(lt) + dy)
            if (scale != 1f && l.type != LayerType.CAMERA) {
                for (key in listOf("scaleX", "scaleY")) {
                    val tr = l.track(key, 100f)
                    setTrack(tr, lt, max(0f, tr.valueAt(lt) * scale))
                }
            }
            if (rotDeg != 0f) {
                val tr = l.track("rotation")
                setTrack(tr, lt, tr.valueAt(lt) + rotDeg)
            }
        }
    }

    private fun setTrack(tr: Track, lt: Float, v: Float) {
        if (tr.animated) tr.setKey(lt, v) else tr.base = v
    }

    // ------------------------------------------------------------------ effects / masks

    fun addEffect(l: Layer, type: String) = edit {
        val e = FxCatalog.create(type) ?: return@edit
        l.effects.add(e)
    }

    fun removeEffect(l: Layer, id: String) = edit { l.effects.removeAll { it.id == id } }

    fun moveEffect(l: Layer, id: String, dir: Int) = edit {
        val i = l.effects.indexOfFirst { it.id == id }
        val j = i + dir
        if (i < 0 || j < 0 || j >= l.effects.size) return@edit
        val e = l.effects.removeAt(i)
        l.effects.add(j, e)
    }

    fun toggleMask(l: Layer) = edit {
        if (l.mask == null) {
            l.mask = MaskSpec()
            Props.ensure(l, project.width, project.height)
        } else {
            l.mask = null
        }
    }

    fun setBlend(l: Layer, b: BlendMode) = edit { l.blend = b }

    // ------------------------------------------------------------------ export

    fun cancelExport() {
        cancelExport = true
    }

    fun startExport(shortSide: Int, fps: Int, withAudio: Boolean) {
        if (exporting) return
        playing = false
        audio.pauseAll()
        cancelExport = false
        exporting = true
        exportProgress = 0f
        val snap = ProjectJson.parse(snapshot())
        scope.launch {
            val size = Exporter.sizeFor(snap, shortSide)
            val opts = ExportOptions(size.first, size.second, fps, Exporter.defaultBitrate(size.first, size.second, fps), withAudio)
            val res = withContext(Dispatchers.Default) {
                Exporter.export(ctx, snap, opts, media, { p -> exportProgress = p }, { cancelExport })
            }
            exporting = false
            message = res.message
        }
    }

    fun closeAndSave(done: () -> Unit) {
        playing = false
        audio.pauseAll()
        saveJob?.cancel()
        scope.launch {
            saveNow()
            done()
        }
    }

    fun release() {
        playing = false
        audio.release()
        media.release()
        renderDispatcher.close()
    }
}
