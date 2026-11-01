package com.r0pw.editor.plugins

import android.content.Context
import com.r0pw.editor.model.EffectInst
import com.r0pw.editor.model.FxCatalog
import com.r0pw.editor.model.FxDef
import com.r0pw.editor.model.FxParam
import com.r0pw.editor.model.PluginStep
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Tiny, safe arithmetic evaluator for plugin parameter expressions: numbers, $vars, + - * / ( ). */
object Expr {
    fun eval(s: String, vars: Map<String, Float>): Float = try {
        Parser(s, vars).expr()
    } catch (e: Exception) {
        0f
    }

    private class Parser(val s: String, val vars: Map<String, Float>) {
        var i = 0

        fun ws() {
            while (i < s.length && s[i] == ' ') i++
        }

        fun expr(): Float {
            var v = term()
            while (true) {
                ws()
                if (i < s.length && s[i] == '+') { i++; v += term() }
                else if (i < s.length && s[i] == '-') { i++; v -= term() }
                else break
            }
            return v
        }

        fun term(): Float {
            var v = factor()
            while (true) {
                ws()
                if (i < s.length && s[i] == '*') { i++; v *= factor() }
                else if (i < s.length && s[i] == '/') {
                    i++
                    val d = factor()
                    v = if (d == 0f) 0f else v / d
                } else break
            }
            return v
        }

        fun factor(): Float {
            ws()
            if (i >= s.length) return 0f
            val c = s[i]
            if (c == '-') { i++; return -factor() }
            if (c == '(') {
                i++
                val v = expr()
                ws()
                if (i < s.length && s[i] == ')') i++
                return v
            }
            if (c == '$') {
                i++
                val st = i
                while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_')) i++
                return vars[s.substring(st, i)] ?: 0f
            }
            val st = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            if (st == i) { i++; return 0f }
            return s.substring(st, i).toFloatOrNull() ?: 0f
        }
    }
}

class PluginInfo(
    val id: String,
    val name: String,
    val version: String,
    val author: String,
    val effects: List<FxDef>,
    val file: File,
    var enabled: Boolean
)

/**
 * Plugins are declarative JSON packs (no code is ever executed):
 * each plugin effect is a named stack of built-in effects whose parameters can be arithmetic
 * expressions of the plugin effect's own sliders. This keeps plugins safe and compatible.
 */
object PluginHost {
    private const val MAX_BYTES = 256 * 1024

    fun dir(ctx: Context): File = File(ctx.filesDir, "plugins").apply { mkdirs() }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("r0pw_plugins", Context.MODE_PRIVATE)

    fun parse(text: String, file: File, enabled: Boolean): PluginInfo {
        if (text.length > MAX_BYTES) throw IllegalArgumentException("Plugin too large")
        val o = JSONObject(text)
        if (o.optInt("apiVersion", 1) != 1) throw IllegalArgumentException("Unsupported apiVersion")
        val id = o.getString("id")
        if (!Regex("[A-Za-z0-9_.\\-]{1,64}").matches(id)) throw IllegalArgumentException("Bad plugin id")
        val effs = o.getJSONArray("effects")
        if (effs.length() > 50) throw IllegalArgumentException("Too many effects")
        val builtinTypes = FxCatalog.builtin.map { it.type }.toSet()
        val defs = ArrayList<FxDef>()
        for (i in 0 until effs.length()) {
            val eo = effs.getJSONObject(i)
            val eid = eo.getString("id")
            if (!Regex("[A-Za-z0-9_\\-]{1,48}").matches(eid)) throw IllegalArgumentException("Bad effect id")
            val params = ArrayList<FxParam>()
            val pa = eo.optJSONArray("params")
            if (pa != null) {
                if (pa.length() > 16) throw IllegalArgumentException("Too many params")
                for (j in 0 until pa.length()) {
                    val po = pa.getJSONObject(j)
                    params.add(
                        FxParam(
                            po.getString("key"), po.optString("label", po.getString("key")),
                            po.optDouble("min", 0.0).toFloat(), po.optDouble("max", 100.0).toFloat(),
                            po.optDouble("def", 0.0).toFloat()
                        )
                    )
                }
            }
            val sa = eo.getJSONArray("stack")
            if (sa.length() == 0 || sa.length() > 12) throw IllegalArgumentException("Bad stack size")
            val steps = ArrayList<PluginStep>()
            for (j in 0 until sa.length()) {
                val so = sa.getJSONObject(j)
                val type = so.getString("type")
                if (type !in builtinTypes) throw IllegalArgumentException("Unknown effect type: $type")
                val pm = HashMap<String, String>()
                val po = so.optJSONObject("params")
                if (po != null) {
                    val keys = po.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val v = po.get(k).toString()
                        if (v.length > 200) throw IllegalArgumentException("Expression too long")
                        pm[k] = v
                    }
                }
                steps.add(PluginStep(type, pm))
            }
            defs.add(
                FxDef(
                    "plugin:$id/$eid", eo.optString("name", eid), eo.optString("category", "Plugins"),
                    params, steps, id
                )
            )
        }
        return PluginInfo(id, o.optString("name", id), o.optString("version", "1.0"), o.optString("author", ""), defs, file, enabled)
    }

    fun loadAll(ctx: Context): List<PluginInfo> {
        val disabled = prefs(ctx).getStringSet("disabled", emptySet()) ?: emptySet()
        val out = ArrayList<PluginInfo>()
        for (f in dir(ctx).listFiles { x -> x.name.endsWith(".json") } ?: emptyArray()) {
            try {
                val raw = f.readText()
                val id = JSONObject(raw).optString("id", "")
                out.add(parse(raw, f, id !in disabled))
            } catch (e: Exception) {
                // ignore invalid plugin file
            }
        }
        FxCatalog.plugins = out.filter { it.enabled }.flatMap { it.effects }
        return out
    }

    /** Validates and installs a plugin. Returns null on success or an error message. */
    fun install(ctx: Context, text: String): String? {
        return try {
            val info = parse(text, File(dir(ctx), "tmp.json"), true)
            File(dir(ctx), "${info.id}.json").writeText(text)
            loadAll(ctx)
            null
        } catch (e: Exception) {
            e.message ?: "Invalid plugin"
        }
    }

    fun remove(ctx: Context, info: PluginInfo) {
        info.file.delete()
        loadAll(ctx)
    }

    fun setEnabled(ctx: Context, id: String, enabled: Boolean) {
        val set = HashSet(prefs(ctx).getStringSet("disabled", emptySet()) ?: emptySet())
        if (enabled) set.remove(id) else set.add(id)
        prefs(ctx).edit().putStringSet("disabled", set).apply()
        loadAll(ctx)
    }

    /** Builds a plugin pack from a layer's current effect stack (so users can create plugins in-app). */
    fun fromStack(name: String, effects: List<EffectInst>): String {
        val slug = name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifBlank { "stack" }
        val stack = JSONArray()
        for (e in effects) {
            if (!e.enabled) continue
            val def = FxCatalog.find(e.type) ?: continue
            if (def.stack != null) continue // only built-in effects can be baked
            val po = JSONObject()
            for ((k, tr) in e.tracks) po.put(k, tr.valueAt(0f).toDouble().toString())
            stack.put(JSONObject().put("type", e.type).put("params", po))
        }
        val eff = JSONObject()
            .put("id", "stack").put("name", name).put("category", "Plugins")
            .put("params", JSONArray()).put("stack", stack)
        return JSONObject()
            .put("apiVersion", 1).put("id", "user.$slug").put("name", name)
            .put("version", "1.0").put("author", "1r0pw user")
            .put("effects", JSONArray().put(eff)).toString(2)
    }

    val SAMPLE = """{
  "apiVersion": 1,
  "id": "sample.neon",
  "name": "Sample Neon Pack",
  "version": "1.0",
  "author": "1r0pw",
  "effects": [
    {
      "id": "neon",
      "name": "Neon Glow",
      "category": "Plugins",
      "params": [
        { "key": "power", "label": "Power", "min": 0, "max": 100, "def": 50 }
      ],
      "stack": [
        { "type": "color", "params": { "saturation": "1 + ${'$'}power / 50", "contrast": "1.1" } },
        { "type": "glow", "params": { "radius": "${'$'}power / 2", "intensity": "80 + ${'$'}power" } }
      ]
    }
  ]
}"""
}
