package com.r0pw.editor.data

import android.content.Context
import com.r0pw.editor.model.Layer
import com.r0pw.editor.model.Project
import com.r0pw.editor.model.ProjectJson
import com.r0pw.editor.model.newId
import org.json.JSONObject
import java.io.File

class ProjectInfo(
    val id: String,
    val name: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val duration: Float,
    val layers: Int,
    val modified: Long
)

private fun writeAtomic(f: File, text: String) {
    f.parentFile?.mkdirs()
    val tmp = File(f.parentFile, f.name + ".tmp")
    tmp.writeText(text)
    if (!tmp.renameTo(f)) {
        f.writeText(text)
        tmp.delete()
    }
}

class ProjectStore(private val ctx: Context) {
    private val dir = File(ctx.filesDir, "projects").apply { mkdirs() }

    private fun file(id: String) = File(dir, "$id.json")

    fun list(): List<ProjectInfo> {
        val out = ArrayList<ProjectInfo>()
        for (f in dir.listFiles { x -> x.name.endsWith(".json") } ?: emptyArray()) {
            try {
                val o = JSONObject(f.readText())
                out.add(
                    ProjectInfo(
                        o.optString("id", f.nameWithoutExtension),
                        o.optString("name", "Untitled"),
                        o.optInt("width", 1920), o.optInt("height", 1080), o.optInt("fps", 30),
                        o.optDouble("duration", 10.0).toFloat(),
                        o.optJSONArray("layers")?.length() ?: 0,
                        o.optLong("modified", f.lastModified())
                    )
                )
            } catch (e: Exception) {
                // skip corrupt file
            }
        }
        return out.sortedByDescending { it.modified }
    }

    fun load(id: String): Project? = try {
        ProjectJson.parse(file(id).readText())
    } catch (e: Exception) {
        null
    }

    fun save(p: Project) {
        p.modified = System.currentTimeMillis()
        writeAtomic(file(p.id), ProjectJson.toJson(p).toString())
    }

    /** Writes an already-serialized project (so serialization can happen on the UI thread safely). */
    fun writeJson(id: String, text: String) {
        writeAtomic(file(id), text)
    }

    fun create(name: String, w: Int, h: Int, fps: Int, duration: Float): Project {
        val p = Project(newId() + newId(), name.ifBlank { "Untitled" })
        p.width = w; p.height = h; p.fps = fps; p.duration = duration
        save(p)
        return p
    }

    fun delete(id: String) {
        file(id).delete()
    }

    fun duplicate(id: String): Project? {
        val src = load(id) ?: return null
        val copy = ProjectJson.parse(ProjectJson.toJson(src).put("id", newId() + newId()).put("name", src.name + " copy").toString())
        save(copy)
        return copy
    }

    fun rename(id: String, name: String) {
        val p = load(id) ?: return
        p.name = name
        save(p)
    }
}

class AppSettings(ctx: Context) {
    private val sp = ctx.getSharedPreferences("r0pw_settings", Context.MODE_PRIVATE)

    /** 0 = low (480px), 1 = medium (720px), 2 = high (1080px) */
    var previewQuality: Int
        get() = sp.getInt("previewQuality", 1)
        set(v) { sp.edit().putInt("previewQuality", v).apply() }

    var loopPlayback: Boolean
        get() = sp.getBoolean("loop", true)
        set(v) { sp.edit().putBoolean("loop", v).apply() }

    var showGizmos: Boolean
        get() = sp.getBoolean("gizmos", true)
        set(v) { sp.edit().putBoolean("gizmos", v).apply() }

    var snapToFrames: Boolean
        get() = sp.getBoolean("snap", true)
        set(v) { sp.edit().putBoolean("snap", v).apply() }

    fun previewWidth(): Int = when (previewQuality) {
        0 -> 480
        2 -> 1080
        else -> 720
    }
}

/** User presets: a saved layer "look" (keyframes + effects + style) stored as a JSON file. */
class PresetStore(private val ctx: Context) {
    private val dir = File(ctx.filesDir, "presets").apply { mkdirs() }

    fun list(): List<String> =
        (dir.listFiles { x -> x.name.endsWith(".json") } ?: emptyArray()).map { it.nameWithoutExtension }.sorted()

    fun save(name: String, layer: Layer) {
        val safe = name.replace(Regex("[^A-Za-z0-9 _\\-\\u0600-\\u06FF]"), "_").ifBlank { "preset" }
        writeAtomic(File(dir, "$safe.json"), ProjectJson.layerToJson(layer).toString())
    }

    fun load(name: String): Layer? = try {
        ProjectJson.layerFromJson(JSONObject(File(dir, "$name.json").readText()))
    } catch (e: Exception) {
        null
    }

    fun rawJson(name: String): String? = try {
        File(dir, "$name.json").readText()
    } catch (e: Exception) {
        null
    }

    fun importRaw(name: String, text: String): Boolean = try {
        ProjectJson.layerFromJson(JSONObject(text)) // validate
        writeAtomic(File(dir, "${name.replace(Regex("[^A-Za-z0-9 _\\-]"), "_")}.json"), text)
        true
    } catch (e: Exception) {
        false
    }

    fun delete(name: String) {
        File(dir, "$name.json").delete()
    }
}
