package com.r0pw.editor.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.r0pw.editor.model.Layer
import com.r0pw.editor.model.LayerType
import com.r0pw.editor.ui.C
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val ROW_H = 34.dp
private val RULER_H = 24.dp

private fun snapT(ed: EditorState, v: Float): Float {
    if (!ed.settings.snapToFrames) return v
    val fps = ed.project.fps
    return (v * fps).roundToInt() / fps.toFloat()
}

@Composable
fun TimelinePanel(ed: EditorState, modifier: Modifier = Modifier) {
    ed.track()
    val density = LocalDensity.current.density
    val pps = ed.pxPerSec
    val project = ed.project
    val totalW = (project.duration * pps).dp
    val hScroll = rememberScrollState()
    val vScroll = rememberScrollState()
    var viewW by remember { mutableIntStateOf(0) }

    LaunchedEffect(ed.time, ed.playing) {
        if (ed.playing) {
            val x = (ed.time * pps * density).toInt()
            if (x > hScroll.value + viewW - 60 || x < hScroll.value) hScroll.scrollTo(max(0, x - 60))
        }
    }

    Row(modifier.background(C.panel)) {
        // ---- names column
        Column(Modifier.width(92.dp)) {
            Box(Modifier.height(RULER_H))
            Column(Modifier.verticalScroll(vScroll)) {
                for (l in project.layers) NameCell(ed, l)
            }
        }
        // ---- tracks
        Column(
            Modifier.weight(1f).onSizeChanged { viewW = it.width }.horizontalScroll(hScroll)
        ) {
            Box(Modifier.width(totalW + 60.dp)) {
                Column {
                    Ruler(ed, totalW, pps)
                    Column(
                        Modifier.verticalScroll(vScroll).width(totalW + 60.dp).pointerInput(pps) {
                            detectTapGestures { off -> ed.seek(off.x / this.density / pps) }
                        }
                    ) {
                        for (l in project.layers) LayerRow(ed, l, pps, totalW)
                    }
                }
                // end of composition marker + playhead
                Box(
                    Modifier.offset(x = (ed.time * pps).dp).width(2.dp).fillMaxHeight().background(C.accent2)
                )
            }
        }
    }
}

@Composable
private fun NameCell(ed: EditorState, l: Layer) {
    val sel = ed.selectedId == l.id
    Row(
        Modifier.height(ROW_H).width(92.dp).background(if (sel) C.elev else C.panel)
            .clickable { ed.selectedId = l.id },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            if (l.visible) "●" else "○", color = if (l.visible) C.accent2 else C.dim, fontSize = 14.sp,
            modifier = Modifier.padding(horizontal = 6.dp).clickable { ed.edit { l.visible = !l.visible } }
        )
        Text(l.name, color = if (sel) C.text else C.dim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Ruler(ed: EditorState, totalW: androidx.compose.ui.unit.Dp, pps: Float) {
    Box(
        Modifier.height(RULER_H).width(totalW + 60.dp).background(C.elev)
            .pointerInput(pps) { detectTapGestures { off -> ed.seek(off.x / this.density / pps) } }
            .pointerInput(pps) {
                detectDragGestures(
                    onDragStart = { off -> ed.seek(off.x / this.density / pps) },
                    onDrag = { change, _ ->
                        change.consume()
                        ed.seek(change.position.x / this.density / pps)
                    }
                )
            }
    ) {
        val labelPaint = remember {
            android.graphics.Paint().apply {
                color = 0xFF8A8A93.toInt()
                isAntiAlias = true
            }
        }
        Canvas(Modifier.fillMaxSize()) {
            labelPaint.textSize = 10.sp.toPx()
            val step = when {
                pps < 30f -> 5f
                pps < 70f -> 2f
                pps < 140f -> 1f
                else -> 0.5f
            }
            var s = 0f
            val dur = ed.project.duration
            while (s <= dur + 0.001f) {
                val x = s * pps * density
                drawLine(Color(0xFF3A3A44), Offset(x, size.height * 0.55f), Offset(x, size.height), 1f)
                val label = if (step < 1f) "%.1f".format(s) else "${s.toInt()}s"
                drawContext.canvas.nativeCanvas.drawText(label, x + 3f, size.height * 0.5f, labelPaint)
                s += step
            }
            // end marker
            val ex = dur * pps * density
            drawRect(Color(0x66FF5C7A), Offset(ex, 0f), Size(size.width - ex, size.height))
        }
    }
}

@Composable
private fun LayerRow(ed: EditorState, l: Layer, pps: Float, totalW: androidx.compose.ui.unit.Dp) {
    val sel = ed.selectedId == l.id
    val isMedia = l.type == LayerType.VIDEO || l.type == LayerType.AUDIO
    Box(Modifier.height(ROW_H).width(totalW + 60.dp)) {
        val x = (l.start * pps).dp
        val w = max(10f, l.duration * pps).dp
        Box(
            Modifier.offset(x = x).width(w).fillMaxHeight().padding(vertical = 3.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(l.color).copy(alpha = if (sel) 1f else 0.62f))
                .let { if (sel) it.border(2.dp, Color.White, RoundedCornerShape(6.dp)) else it }
                .pointerInput(l.id, pps) { detectTapGestures(onTap = { ed.selectedId = l.id }) }
                .pointerInput(l.id, pps) {
                    var acc = 0f
                    var s0 = 0f
                    detectDragGestures(
                        onDragStart = {
                            acc = 0f
                            s0 = ed.project.layer(l.id)?.start ?: 0f
                            ed.selectedId = l.id
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            if (!l.locked) {
                                acc += amount.x / this.density / pps
                                ed.edit("mv" + l.id) {
                                    l.start = snapT(ed, s0 + acc).coerceIn(0f, max(0f, ed.project.duration - 0.1f))
                                }
                            }
                        }
                    )
                }
        ) {
            // keyframe diamonds
            Canvas(Modifier.fillMaxSize()) {
                val times = HashSet<Int>()
                fun addTrackKeys(tr: com.r0pw.editor.model.Track) {
                    for (k in tr.keys) times.add((k.t * 1000f).roundToInt())
                }
                for (tr in l.tracks.values) addTrackKeys(tr)
                for (e in l.effects) for (tr in e.tracks.values) addTrackKeys(tr)
                for (ms in times) {
                    val cx = ms / 1000f * pps * density
                    if (cx < 0f || cx > size.width) continue
                    rotate(45f, Offset(cx, size.height - 6.dp.toPx())) {
                        drawRect(Color.White, Offset(cx - 3.dp.toPx(), size.height - 9.dp.toPx()), Size(6.dp.toPx(), 6.dp.toPx()))
                    }
                }
            }
            Text(
                l.name, color = Color.White, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Clip,
                modifier = Modifier.padding(start = 14.dp, top = 2.dp)
            )
            // left trim handle
            Box(
                Modifier.width(12.dp).fillMaxHeight().background(Color(0x55000000))
                    .pointerInput(l.id, pps) {
                        var acc = 0f
                        var s0 = 0f
                        var d0 = 0f
                        var i0 = 0f
                        detectDragGestures(
                            onDragStart = {
                                acc = 0f
                                s0 = l.start; d0 = l.duration; i0 = l.inPoint
                                ed.selectedId = l.id
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                if (!l.locked) {
                                    acc += amount.x / this.density / pps
                                    var dt = snapT(ed, acc)
                                    dt = max(dt, -s0)
                                    if (isMedia) dt = max(dt, -i0 / max(0.01f, l.speed))
                                    dt = min(dt, d0 - 0.1f)
                                    ed.edit("tl" + l.id) {
                                        val old = l.start
                                        l.start = s0 + dt
                                        l.duration = d0 - dt
                                        if (isMedia || l.type == LayerType.IMAGE) l.inPoint = max(0f, i0 + dt * l.speed)
                                        ed.shiftKeys(l, -(l.start - old))
                                    }
                                }
                            }
                        )
                    }
            )
        }
        // right trim handle (outside the clipped bar so it stays grabbable)
        Box(
            Modifier.offset(x = x + w - 14.dp).width(14.dp).fillMaxHeight().padding(vertical = 3.dp)
                .background(Color(0x55000000))
                .pointerInput(l.id, pps) {
                    var acc = 0f
                    var d0 = 0f
                    detectDragGestures(
                        onDragStart = { acc = 0f; d0 = l.duration; ed.selectedId = l.id },
                        onDrag = { change, amount ->
                            change.consume()
                            if (!l.locked) {
                                acc += amount.x / this.density / pps
                                var nd = snapT(ed, d0 + acc).coerceAtLeast(0.1f)
                                if (isMedia && l.srcDur > 0f) nd = min(nd, max(0.1f, (l.srcDur - l.inPoint) / max(0.01f, l.speed)))
                                ed.edit("tr" + l.id) { l.duration = nd }
                            }
                        }
                    )
                }
        )
    }
}
