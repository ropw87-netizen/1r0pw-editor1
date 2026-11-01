package com.r0pw.editor.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.r0pw.editor.Brand
import com.r0pw.editor.data.ProjectInfo
import com.r0pw.editor.data.ProjectStore
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

fun openUrl(ctx: Context, url: String) {
    try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        // no browser installed
    }
}

@Composable
fun SocialRow(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Btn("TikTok  @${Brand.TIKTOK_HANDLE}", { openUrl(ctx, Brand.TIKTOK_URL) })
        Btn("Instagram  @${Brand.INSTAGRAM_HANDLE}", { openUrl(ctx, Brand.INSTAGRAM_URL) })
    }
}

@Composable
fun SplashScreen(onDone: () -> Unit) {
    var go by remember { mutableStateOf(false) }
    val a by animateFloatAsState(if (go) 1f else 0f, tween(900), label = "splash")
    LaunchedEffect(Unit) {
        go = true
        delay(2200)
        onDone()
    }
    Box(Modifier.fillMaxSize().background(C.bg), contentAlignment = Alignment.Center) {
        Column(Modifier.alpha(a), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(Brand.NAME, color = C.text, fontSize = 56.sp, fontWeight = FontWeight.Black)
            Text("STUDIO", color = C.accent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text(Brand.TAGLINE, color = C.dim, fontSize = 13.sp)
            Spacer(Modifier.height(36.dp))
            Text("TikTok · Instagram  @${Brand.TIKTOK_HANDLE}", color = C.accent2, fontSize = 13.sp)
        }
    }
}

private val CANVASES = listOf(
    "16:9 · 1920×1080" to Pair(1920, 1080),
    "9:16 · 1080×1920" to Pair(1080, 1920),
    "1:1 · 1080×1080" to Pair(1080, 1080),
    "16:9 · 1280×720" to Pair(1280, 720),
    "4K · 3840×2160" to Pair(3840, 2160)
)

@Composable
fun HomeScreen(
    store: ProjectStore,
    onOpen: (String) -> Unit,
    onSettings: () -> Unit,
    onPlugins: () -> Unit
) {
    var projects by remember { mutableStateOf(store.list()) }
    var showNew by remember { mutableStateOf(false) }
    var renameId by remember { mutableStateOf<String?>(null) }
    var renameOld by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().background(C.bg).padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(Brand.NAME, color = C.text, fontSize = 30.sp, fontWeight = FontWeight.Black)
                Text("Studio · v${Brand.VERSION}", color = C.accent, fontSize = 12.sp)
            }
            Btn("Plugins", onPlugins)
            Spacer(Modifier.padding(3.dp))
            Btn("Settings", onSettings)
        }
        Spacer(Modifier.height(16.dp))
        Btn("+  New project", { showNew = true }, accent = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        Text("PROJECTS", color = C.accent2, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        if (projects.isEmpty()) {
            Text("No projects yet. Tap “New project” to start.", color = C.dim, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
        }
        LazyColumn(Modifier.weight(1f).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(projects, key = { it.id }) { p -> ProjectCard(p, onOpen, {
                renameId = p.id; renameOld = p.name
            }, {
                store.duplicate(p.id); projects = store.list()
            }, {
                store.delete(p.id); projects = store.list()
            }) }
        }
        SocialRow(Modifier.padding(vertical = 10.dp).horizontalScroll(rememberScrollState()))
    }

    if (showNew) NewProjectDialog(onDismiss = { showNew = false }) { name, w, h, fps, dur ->
        val p = store.create(name, w, h, fps, dur)
        showNew = false
        projects = store.list()
        onOpen(p.id)
    }
    val rid = renameId
    if (rid != null) TextDialog("Rename project", renameOld, onDismiss = { renameId = null }) { v ->
        if (v.isNotBlank()) store.rename(rid, v)
        projects = store.list()
        renameId = null
    }
}

@Composable
private fun ProjectCard(p: ProjectInfo, onOpen: (String) -> Unit, onRename: () -> Unit, onDup: () -> Unit, onDelete: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.panel).clickable { onOpen(p.id) }.padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(p.name, color = C.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            Text(
                "${p.width}×${p.height} · ${p.fps}fps · %.0fs · ${p.layers} layers".format(p.duration),
                color = C.dim, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp)
            )
            Text(
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(p.modified)),
                color = C.dim, fontSize = 10.sp
            )
        }
        DropdownBtn("⋮", listOf("Rename", "Duplicate", "Delete"), { i ->
            when (i) {
                0 -> onRename()
                1 -> onDup()
                else -> confirm = true
            }
        })
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        containerColor = C.panel,
        title = { Text("Delete “${p.name}”?", color = C.text) },
        text = { Text("This cannot be undone.", color = C.dim) },
        confirmButton = { TextButton(onClick = { confirm = false; onDelete() }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } }
    )
}

@Composable
private fun NewProjectDialog(onDismiss: () -> Unit, onCreate: (String, Int, Int, Int, Float) -> Unit) {
    var name by remember { mutableStateOf("My project") }
    var canvas by remember { mutableStateOf(0) }
    var fps by remember { mutableStateOf(30) }
    var dur by remember { mutableStateOf(10f) }
    var nameDlg by remember { mutableStateOf(false) }
    var durDlg by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = C.panel,
        title = { Text("New project", color = C.text) },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Btn(name, { nameDlg = true })
                    Btn("%.0f s".format(dur), { durDlg = true })
                }
                SectionTitle("Canvas")
                for (i in CANVASES.indices) {
                    Chip(CANVASES[i].first, canvas == i, { canvas = i }, Modifier.padding(vertical = 2.dp))
                }
                SectionTitle("Frame rate")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (f in listOf(24, 30, 60)) Chip("$f fps", fps == f, { fps = f })
                }
                Text("The first video you import can also match the canvas to its size.", color = C.dim, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val c = CANVASES[canvas].second
                onCreate(name, c.first, c.second, fps, dur)
            }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
    if (nameDlg) TextDialog("Project name", name, onDismiss = { nameDlg = false }) { name = it; nameDlg = false }
    if (durDlg) NumberDialog("Length (seconds)", dur, { durDlg = false }) { dur = it.coerceIn(1f, 600f); durDlg = false }
}
