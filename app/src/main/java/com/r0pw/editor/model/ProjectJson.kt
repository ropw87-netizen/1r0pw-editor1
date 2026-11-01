package com.r0pw.editor.model

import org.json.JSONArray
import org.json.JSONObject

private fun JSONObject.f(k: String, d: Float): Float = optDouble(k, d.toDouble()).toFloat()
private fun JSONObject.s(k: String): String? = if (has(k) && !isNull(k)) getString(k) else null

object ProjectJson {

    private inline fun <reified E : Enum<E>> enumOf(name: String?, def: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: def

    // ---------- tracks ----------
    fun trackToJson(t: Track): JSONObject {
        val o = JSONObject()
        o.put("base", t.base.toDouble())
        val ks = JSONArray()
        for (k in t.keys) {
            val ko = JSONObject()
            ko.put("t", k.t.toDouble())
            ko.put("v", k.v.toDouble())
            ko.put("i", k.interp.name)
            ko.put("x1", k.x1.toDouble()); ko.put("y1", k.y1.toDouble())
            ko.put("x2", k.x2.toDouble()); ko.put("y2", k.y2.toDouble())
            ks.put(ko)
        }
        o.put("keys", ks)
        return o
    }

    fun trackFromJson(o: JSONObject): Track {
        val t = Track(o.f("base", 0f))
        val ks = o.optJSONArray("keys")
        if (ks != null) {
            for (i in 0 until ks.length()) {
                val ko = ks.getJSONObject(i)
                val k = Key(ko.f("t", 0f), ko.f("v", 0f))
                k.interp = enumOf(ko.s("i"), Interp.LINEAR)
                k.x1 = ko.f("x1", 0.42f); k.y1 = ko.f("y1", 0f)
                k.x2 = ko.f("x2", 0.58f); k.y2 = ko.f("y2", 1f)
                t.keys.add(k)
            }
            t.sortKeys()
        }
        return t
    }

    private fun tracksToJson(m: Map<String, Track>): JSONObject {
        val o = JSONObject()
        for ((k, v) in m) o.put(k, trackToJson(v))
        return o
    }

    private fun tracksFromJson(o: JSONObject?, into: MutableMap<String, Track>) {
        if (o == null) return
        val it = o.keys()
        while (it.hasNext()) {
            val key = it.next()
            into[key] = trackFromJson(o.getJSONObject(key))
        }
    }

    // ---------- layers ----------
    fun layerToJson(l: Layer): JSONObject {
        val o = JSONObject()
        o.put("id", l.id); o.put("name", l.name); o.put("type", l.type.name)
        o.put("start", l.start.toDouble()); o.put("duration", l.duration.toDouble())
        o.put("inPoint", l.inPoint.toDouble()); o.put("speed", l.speed.toDouble())
        l.source?.let { o.put("source", it) }
        l.mediaId?.let { o.put("mediaId", it) }
        o.put("srcW", l.srcW); o.put("srcH", l.srcH); o.put("srcDur", l.srcDur.toDouble())
        l.parentId?.let { o.put("parent", it) }
        o.put("visible", l.visible); o.put("locked", l.locked); o.put("threeD", l.threeD)
        o.put("blend", l.blend.name)
        o.put("fadeIn", l.fadeIn.toDouble()); o.put("fadeOut", l.fadeOut.toDouble())
        o.put("color", l.color)
        l.text?.let { t ->
            val to = JSONObject()
            to.put("text", t.text); to.put("color", t.color); to.put("strokeColor", t.strokeColor)
            to.put("shadowColor", t.shadowColor)
            t.fontPath?.let { to.put("font", it) }
            to.put("family", t.family); to.put("bold", t.bold); to.put("italic", t.italic)
            to.put("align", t.align); to.put("anim", t.anim.name)
            to.put("stagger", t.stagger.toDouble()); to.put("animDur", t.animDur.toDouble())
            o.put("textSpec", to)
        }
        l.shape?.let { s ->
            val so = JSONObject()
            so.put("kind", s.kind.name); so.put("fill", s.fill); so.put("fill2", s.fill2)
            so.put("gradient", s.gradient); so.put("gradAngle", s.gradAngle.toDouble())
            so.put("strokeColor", s.strokeColor); so.put("points", s.points)
            so.put("inner", s.inner.toDouble())
            o.put("shapeSpec", so)
        }
        l.mask?.let { m ->
            val mo = JSONObject()
            mo.put("kind", m.kind); mo.put("inverted", m.inverted)
            o.put("maskSpec", mo)
        }
        o.put("tracks", tracksToJson(l.tracks))
        val fx = JSONArray()
        for (e in l.effects) {
            val eo = JSONObject()
            eo.put("id", e.id); eo.put("type", e.type); eo.put("enabled", e.enabled)
            eo.put("tracks", tracksToJson(e.tracks))
            fx.put(eo)
        }
        o.put("effects", fx)
        return o
    }

    fun layerFromJson(o: JSONObject): Layer {
        val l = Layer(o.optString("id", newId()), o.optString("name", "Layer"), enumOf(o.s("type"), LayerType.SHAPE))
        l.start = o.f("start", 0f); l.duration = o.f("duration", 5f)
        l.inPoint = o.f("inPoint", 0f); l.speed = o.f("speed", 1f)
        l.source = o.s("source"); l.mediaId = o.s("mediaId")
        l.srcW = o.optInt("srcW", 0); l.srcH = o.optInt("srcH", 0); l.srcDur = o.f("srcDur", 0f)
        l.parentId = o.s("parent")
        l.visible = o.optBoolean("visible", true); l.locked = o.optBoolean("locked", false)
        l.threeD = o.optBoolean("threeD", false)
        l.blend = enumOf(o.s("blend"), BlendMode.NORMAL)
        l.fadeIn = o.f("fadeIn", 0f); l.fadeOut = o.f("fadeOut", 0f)
        l.color = o.optInt("color", l.color)
        o.optJSONObject("textSpec")?.let { to ->
            val t = TextSpec()
            t.text = to.optString("text", "")
            t.color = to.optInt("color", t.color); t.strokeColor = to.optInt("strokeColor", t.strokeColor)
            t.shadowColor = to.optInt("shadowColor", t.shadowColor)
            t.fontPath = to.s("font")
            t.family = to.optInt("family", 0); t.bold = to.optBoolean("bold", true)
            t.italic = to.optBoolean("italic", false); t.align = to.optInt("align", 1)
            t.anim = enumOf(to.s("anim"), TextAnim.NONE)
            t.stagger = to.f("stagger", 0.05f); t.animDur = to.f("animDur", 0.45f)
            l.text = t
        }
        o.optJSONObject("shapeSpec")?.let { so ->
            val s = ShapeSpec()
            s.kind = enumOf(so.s("kind"), ShapeKind.ROUNDRECT)
            s.fill = so.optInt("fill", s.fill); s.fill2 = so.optInt("fill2", s.fill2)
            s.gradient = so.optBoolean("gradient", false); s.gradAngle = so.f("gradAngle", 45f)
            s.strokeColor = so.optInt("strokeColor", s.strokeColor)
            s.points = so.optInt("points", 5); s.inner = so.f("inner", 0.5f)
            l.shape = s
        }
        o.optJSONObject("maskSpec")?.let { mo ->
            val m = MaskSpec()
            m.kind = mo.optInt("kind", 0); m.inverted = mo.optBoolean("inverted", false)
            l.mask = m
        }
        tracksFromJson(o.optJSONObject("tracks"), l.tracks)
        val fx = o.optJSONArray("effects")
        if (fx != null) {
            for (i in 0 until fx.length()) {
                val eo = fx.getJSONObject(i)
                val e = EffectInst(eo.optString("id", newId()), eo.optString("type", ""))
                e.enabled = eo.optBoolean("enabled", true)
                tracksFromJson(eo.optJSONObject("tracks"), e.tracks)
                l.effects.add(e)
            }
        }
        return l
    }

    // ---------- project ----------
    fun toJson(p: Project): JSONObject {
        val o = JSONObject()
        o.put("v", 2)
        o.put("id", p.id); o.put("name", p.name)
        o.put("width", p.width); o.put("height", p.height); o.put("fps", p.fps)
        o.put("duration", p.duration.toDouble()); o.put("bg", p.bg)
        o.put("modified", p.modified)
        val ls = JSONArray()
        for (l in p.layers) ls.put(layerToJson(l))
        o.put("layers", ls)
        val ms = JSONArray()
        for (m in p.media) {
            val mo = JSONObject()
            mo.put("id", m.id); mo.put("uri", m.uri); mo.put("name", m.name); mo.put("type", m.type.name)
            mo.put("duration", m.duration.toDouble()); mo.put("w", m.width); mo.put("h", m.height)
            mo.put("audio", m.hasAudio)
            ms.put(mo)
        }
        o.put("media", ms)
        return o
    }

    fun fromJson(o: JSONObject): Project {
        val p = Project(o.optString("id", newId()), o.optString("name", "Untitled"))
        p.width = o.optInt("width", 1920); p.height = o.optInt("height", 1080)
        p.fps = o.optInt("fps", 30); p.duration = o.f("duration", 10f)
        p.bg = o.optInt("bg", 0xFF000000.toInt())
        p.modified = o.optLong("modified", System.currentTimeMillis())
        val ls = o.optJSONArray("layers")
        if (ls != null) for (i in 0 until ls.length()) p.layers.add(layerFromJson(ls.getJSONObject(i)))
        val ms = o.optJSONArray("media")
        if (ms != null) {
            for (i in 0 until ms.length()) {
                val mo = ms.getJSONObject(i)
                val m = MediaItem(
                    mo.optString("id", newId()), mo.optString("uri", ""),
                    mo.optString("name", "media"), enumOf(mo.s("type"), LayerType.VIDEO)
                )
                m.duration = mo.f("duration", 0f); m.width = mo.optInt("w", 0); m.height = mo.optInt("h", 0)
                m.hasAudio = mo.optBoolean("audio", false)
                p.media.add(m)
            }
        }
        for (l in p.layers) Props.ensure(l, p.width, p.height)
        return p
    }

    fun parse(text: String): Project = fromJson(JSONObject(text))
}
