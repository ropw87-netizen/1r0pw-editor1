package com.r0pw.editor.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.r0pw.editor.model.Easing
import com.r0pw.editor.model.Interp
import com.r0pw.editor.model.Track
import com.r0pw.editor.ui.Btn
import com.r0pw.editor.ui.C
import com.r0pw.editor.ui.Chip
import com.r0pw.editor.ui.DropdownBtn
import com.r0pw.editor.ui.NumberDialog
import com.r0pw.editor.ui.SectionTitle
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private class GMap(val w: Float, val h: Float, val x0: Float, val x1: Float, val y0: Float, val y1: Float) {
    fun px(t: Float): Float = (t - x0) / (x1 - x0) * w
    fun py(v: Float): Float = h - (v - y0) / (y1 - y0) * h
    fun tx(x: Float): Float = x0 + x / w * (x1 - x0)
    fun vy(y: Float): Float = y0 + (h - y) / h * (y1 - y0)
}

private fun valueMap(tr: Track, dur: Float, w: Float, h: Float): GMap {
    var lo = Float.MAX_VALUE
    var hi = -Float.MAX_VALUE
    var tmax = dur
    var tmin = 0f
    for (k in tr.keys) {
        lo = min(lo, k.v); hi = max(hi, k.v)
        tmax = max(tmax, k.t); tmin = min(tmin, k.t)
    }
    for (i in 0..120) {
        val v = tr.valueAt(tmin + (tmax - tmin) * i / 120f)
        lo = min(lo, v); hi = max(hi, v)
    }
    if (hi - lo < 1e-3f) hi = lo + 1f
    val pad = (hi - lo) * 0.15f
    if (tmax - tmin < 0.01f) tmax = tmin + 1f
    return GMap(w, h, tmin, tmax, lo - pad, hi + pad)
}

private fun speedSamples(tr: Track, m: GMap): FloatArray {
    val n = 120
    val out = FloatArray(n + 1)
    val dt = (m.x1 - m.x0) / n
    var prev = tr.valueAt(m.x0)
    for (i in 1..n) {
        val v = tr.valueAt(m.x0 + dt * i)
        out[i] = abs(v - prev) / dt
        prev = v
    }
    out[0] = out[1]
    return out
}

private class Entry(val id: String, val label: String, val track: Track)

@Composable
fun GraphPanel(ed: EditorState) {
    ed.track()
    val l = ed.selected
    if (l == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Select a layer, then animate a property (⏱) to edit its curve", color = C.dim, fontSize = 12.sp)
        }
        return
    }
    val entries = ArrayList<Entry>()
    for ((k, tr) in l.tracks) if (tr.animated) entries.add(Entry("t:$k", k, tr))
    for (e in l.effects) for ((k, tr) in e.tracks) if (tr.animated) entries.add(Entry("e:${e.id}:$k", "fx ${e.type}.$k", tr))
    if (entries.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No animated properties on this layer.\nTap ⏱ next to a property in the Props tab.", color = C.dim, fontSize = 12.sp)
        }
        return
    }
    var selId by remember { mutableStateOf("") }
    val entry = entries.firstOrNull { it.id == selId } ?: entries[0]
    val tr = entry.track
    var selKey by remember(entry.id) { mutableIntStateOf(-1) }
    var speed by remember { mutableStateOf(false) }
    var sz by remember { mutableStateOf(Offset(1f, 1f)) }
    var timeDlg by remember { mutableStateOf(false) }
    var valDlg by remember { mutableStateOf(false) }
    val dur = max(0.5f, l.duration)
    val key = if (selKey in tr.keys.indices) tr.keys[selKey] else null

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            DropdownBtn(entry.label, entries.map { it.label }, { selId = entries[it].id })
            Chip("Value", !speed, { speed = false })
            Chip("Speed", speed, { speed = true })
        }
        Spacer(Modifier.height(6.dp))
        Canvas(
            Modifier.fillMaxWidth().height(150.dp).background(Color(0xFF101014))
                .onSizeChanged { sz = Offset(it.width.toFloat(), it.height.toFloat()) }
                .pointerInput(tr, speed, l.id) {
                    detectTapGestures { off ->
                        val m = valueMap(tr, dur, sz.x, sz.y)
                        var best = -1
                        var bd = 40f
                        for (i in tr.keys.indices) {
                            val d = abs(m.px(tr.keys[i].t) - off.x) + abs(m.py(tr.keys[i].v) - off.y)
                            if (d < bd) { bd = d; best = i }
                        }
                        if (best >= 0) selKey = best else ed.seek(l.start + m.tx(off.x))
                    }
                }
                .pointerInput(tr, speed, l.id) {
                    var dragging = -1
                    var fm = valueMap(tr, dur, sz.x, sz.y)
                    detectDragGestures(
                        onDragStart = { off ->
                            fm = valueMap(tr, dur, sz.x, sz.y)
                            var best = -1
                            var bd = 48f
                            for (i in tr.keys.indices) {
                                val d = abs(fm.px(tr.keys[i].t) - off.x) + abs(fm.py(tr.keys[i].v) - off.y)
                                if (d < bd) { bd = d; best = i }
                            }
                            dragging = best
                            if (best >= 0) selKey = best
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            if (!speed && dragging in tr.keys.indices) {
                                val k = tr.keys[dragging]
                                ed.edit("gk" + System.identityHashCode(k)) {
                                    var nt = fm.tx(change.position.x)
                                    if (ed.settings.snapToFrames) nt = (nt * ed.project.fps).roundToInt() / ed.project.fps.toFloat()
                                    k.t = nt
                                    k.v = fm.vy(change.position.y)
                                    tr.sortKeys()
                                }
                                dragging = tr.keys.indexOf(k)
                                selKey = dragging
                            }
                        }
                    )
                }
        ) {
            val m = valueMap(tr, dur, size.width, size.height)
            // grid
            for (i in 0..4) {
                val y = size.height * i / 4f
                drawLine(Color(0xFF22222A), Offset(0f, y), Offset(size.width, y), 1f)
            }
            if (!speed) {
                val path = Path()
                for (i in 0..160) {
                    val t = m.x0 + (m.x1 - m.x0) * i / 160f
                    val p = Offset(m.px(t), m.py(tr.valueAt(t)))
                    if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                }
                drawPath(path, Color(0xFF7C5CFF), style = Stroke(width = 3f))
            } else {
                val sp = speedSamples(tr, m)
                var smax = 1e-3f
                for (s in sp) smax = max(smax, s)
                val path = Path()
                for (i in sp.indices) {
                    val x = size.width * i / (sp.size - 1f)
                    val y = size.height - sp[i] / smax * size.height * 0.9f - 4f
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, Color(0xFF2DE2E6), style = Stroke(width = 3f))
            }
            // playhead
            val ph = m.px(ed.time - l.start)
            drawLine(Color(0xFF2DE2E6), Offset(ph, 0f), Offset(ph, size.height), 2f)
            // keys
            for (i in tr.keys.indices) {
                val k = tr.keys[i]
                val c = Offset(m.px(k.t), if (speed) size.height - 8f else m.py(k.v))
                drawCircle(if (i == selKey) Color.White else Color(0xFFFFD166), if (i == selKey) 10f else 7f, c)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
            Btn("◀ Key", {
                val lt = ed.time - l.start
                val p = tr.keys.lastOrNull { it.t < lt - 0.004f }
                if (p != null) { ed.seek(l.start + p.t); selKey = tr.keys.indexOf(p) }
            })
            Btn("Key ▶", {
                val lt = ed.time - l.start
                val n = tr.keys.firstOrNull { it.t > lt + 0.004f }
                if (n != null) { ed.seek(l.start + n.t); selKey = tr.keys.indexOf(n) }
            })
            Btn("◆ Add", { ed.toggleKey(tr, l) })
            Btn("Delete key", { if (key != null) { ed.edit { tr.keys.remove(key) }; selKey = -1 } }, enabled = key != null, danger = true)
        }
        if (key != null) {
            SectionTitle("Selected keyframe")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Btn("Time %.2fs".format(key.t), { timeDlg = true })
                Btn("Value %.2f".format(key.v), { valDlg = true })
            }
            Text("Interpolation to next key", color = C.dim, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                Chip("Linear", key.interp == Interp.LINEAR, { ed.edit { key.interp = Interp.LINEAR } })
                Chip("Hold", key.interp == Interp.HOLD, { ed.edit { key.interp = Interp.HOLD } })
                Chip("Bezier", key.interp == Interp.BEZIER, { ed.edit { key.interp = Interp.BEZIER } })
            }
            Text("Easing presets", color = C.dim, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (p in Easing.presets.take(5)) Chip(p.first, false, { ed.edit { key.setEase(p.second) } })
            }
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (p in Easing.presets.drop(5)) Chip(p.first, false, { ed.edit { key.setEase(p.second) } })
            }
            Spacer(Modifier.height(8.dp))
            EaseEditor(ed, key)
        }
        Spacer(Modifier.height(30.dp))
    }
    if (timeDlg && key != null) NumberDialog("Key time (s)", key.t, { timeDlg = false }) { v ->
        ed.edit { key.t = v; tr.sortKeys() }
        selKey = tr.keys.indexOf(key)
        timeDlg = false
    }
    if (valDlg && key != null) NumberDialog("Key value", key.v, { valDlg = false }) { v ->
        ed.edit { key.v = v }
        valDlg = false
    }
}

@Composable
private fun EaseEditor(ed: EditorState, key: com.r0pw.editor.model.Key) {
    val sizeDp = 150.dp
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Canvas(
            Modifier.size(sizeDp).background(Color(0xFF101014))
                .pointerInput(key) {
                    var which = 0
                    detectDragGestures(
                        onDragStart = { off ->
                            val w = size.width.toFloat()
                            val h = size.height.toFloat()
                            fun ex(x: Float) = x * w
                            fun ey(y: Float) = h - (y + 0.5f) / 2f * h
                            val d1 = abs(ex(key.x1) - off.x) + abs(ey(key.y1) - off.y)
                            val d2 = abs(ex(key.x2) - off.x) + abs(ey(key.y2) - off.y)
                            which = if (d1 <= d2) 1 else 2
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            val w = size.width.toFloat()
                            val h = size.height.toFloat()
                            val nx = (change.position.x / w).coerceIn(0f, 1f)
                            val ny = ((h - change.position.y) / h * 2f - 0.5f).coerceIn(-0.5f, 1.5f)
                            ed.edit("ease" + System.identityHashCode(key)) {
                                key.interp = Interp.BEZIER
                                if (which == 1) { key.x1 = nx; key.y1 = ny } else { key.x2 = nx; key.y2 = ny }
                            }
                        }
                    )
                }
        ) {
            val w = size.width
            val h = size.height
            fun ex(x: Float) = x * w
            fun ey(y: Float) = h - (y + 0.5f) / 2f * h
            drawLine(Color(0xFF22222A), Offset(0f, ey(0f)), Offset(w, ey(0f)), 1f)
            drawLine(Color(0xFF22222A), Offset(0f, ey(1f)), Offset(w, ey(1f)), 1f)
            val path = Path()
            for (i in 0..60) {
                val x = i / 60f
                val y = if (key.interp == Interp.BEZIER) Easing.bezier(x, key.x1, key.y1, key.x2, key.y2) else x
                if (i == 0) path.moveTo(ex(x), ey(y)) else path.lineTo(ex(x), ey(y))
            }
            drawPath(path, Color(0xFF7C5CFF), style = Stroke(width = 3f))
            drawLine(Color(0xFF3A3A44), Offset(ex(0f), ey(0f)), Offset(ex(key.x1), ey(key.y1)), 2f)
            drawLine(Color(0xFF3A3A44), Offset(ex(1f), ey(1f)), Offset(ex(key.x2), ey(key.y2)), 2f)
            drawCircle(Color(0xFF2DE2E6), 9f, Offset(ex(key.x1), ey(key.y1)))
            drawCircle(Color(0xFF2DE2E6), 9f, Offset(ex(key.x2), ey(key.y2)))
        }
        Column {
            Text("Drag the handles\nto shape the easing", color = C.dim, fontSize = 11.sp)
            Text("(%.2f, %.2f, %.2f, %.2f)".format(key.x1, key.y1, key.x2, key.y2), color = C.dim, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
}
