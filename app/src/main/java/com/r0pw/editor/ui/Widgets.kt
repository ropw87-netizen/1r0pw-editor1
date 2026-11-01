package com.r0pw.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object C {
    val bg = Color(0xFF0B0B0E)
    val panel = Color(0xFF15151A)
    val elev = Color(0xFF1D1D24)
    val line = Color(0xFF2A2A33)
    val accent = Color(0xFF7C5CFF)
    val accent2 = Color(0xFF2DE2E6)
    val text = Color(0xFFECECF1)
    val dim = Color(0xFF8A8A93)
    val danger = Color(0xFFFF5C7A)
}

@Composable
fun R0Theme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = C.accent, onPrimary = Color.White, secondary = C.accent2,
            background = C.bg, onBackground = C.text, surface = C.panel, onSurface = C.text,
            surfaceVariant = C.elev, onSurfaceVariant = C.dim
        ),
        content = content
    )
}

@Composable
fun Btn(
    text: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    accent: Boolean = false, enabled: Boolean = true, danger: Boolean = false
) {
    val bg = when {
        !enabled -> C.line
        danger -> C.danger
        accent -> C.accent
        else -> C.elev
    }
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = if (enabled) C.text else C.dim, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) C.accent else C.elev)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = C.text, fontSize = 12.sp)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(), color = C.accent2, fontSize = 11.sp, fontWeight = FontWeight.Bold,
        modifier = modifier.padding(top = 10.dp, bottom = 4.dp)
    )
}

@Composable
fun HLine(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(C.line))
}

@Composable
fun ColorDot(color: Int, size: Int = 24, onClick: (() -> Unit)? = null) {
    var m = Modifier.size(size.dp).clip(CircleShape).background(Color(color)).border(1.dp, C.line, CircleShape)
    if (onClick != null) m = m.clickable { onClick() }
    Box(m)
}

@Composable
fun TextDialog(title: String, initial: String, singleLine: Boolean = true, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var v by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = C.panel,
        title = { Text(title, color = C.text) },
        text = {
            OutlinedTextField(value = v, onValueChange = { v = it }, singleLine = singleLine, modifier = Modifier.fillMaxWidth())
        },
        confirmButton = { TextButton(onClick = { onOk(v) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun NumberDialog(title: String, initial: Float, onDismiss: () -> Unit, onOk: (Float) -> Unit) {
    var v by remember { mutableStateOf(if (initial == initial.toInt().toFloat()) initial.toInt().toString() else "%.2f".format(initial)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = C.panel,
        title = { Text(title, color = C.text) },
        text = {
            OutlinedTextField(
                value = v, onValueChange = { v = it }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = {
                val f = v.replace(',', '.').toFloatOrNull()
                if (f != null) onOk(f) else onDismiss()
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private val palette = listOf(
    0xFFFFFFFF, 0xFFD9D9D9, 0xFF8A8A93, 0xFF3A3A44, 0xFF000000, 0xFFFF5C7A, 0xFFFF2D55, 0xFFFF9500,
    0xFFFFCC00, 0xFFA3E635, 0xFF34C759, 0xFF2DE2E6, 0xFF0A84FF, 0xFF5E5CE6, 0xFF7C5CFF, 0xFFBF5AF2,
    0xFFFF6FD8, 0xFF8B5E3C, 0xFFFFD6A5, 0xFFB5E48C, 0xFF99D9EA, 0xFF1E3A8A, 0xFF7F1D1D, 0xFF064E3B
)

@Composable
fun ColorPickerDialog(initial: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    var hex by remember { mutableStateOf("%06X".format(initial and 0xFFFFFF)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = C.panel,
        title = { Text("Color", color = C.text) },
        text = {
            Column {
                for (r in 0 until 3) {
                    Row(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (c in 0 until 8) {
                            val col = palette[r * 8 + c].toInt()
                            ColorDot(col, 30) { hex = "%06X".format(col and 0xFFFFFF) }
                        }
                    }
                }
                OutlinedTextField(
                    value = hex, onValueChange = { hex = it.uppercase().take(6) }, singleLine = true,
                    label = { Text("HEX") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val v = hex.toLongOrNull(16)
                if (v != null) onPick((0xFF000000 or v).toInt()) else onDismiss()
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** A small button that opens a dropdown of options. */
@Composable
fun DropdownBtn(label: String, options: List<String>, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Btn(label + "  ▾", onClick = { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (i in options.indices) {
                DropdownMenuItem(text = { Text(options[i]) }, onClick = { open = false; onSelect(i) })
            }
        }
    }
}

@Composable
fun ScrollColumn(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.verticalScroll(rememberScrollState())) { content() }
}

@Composable
fun LabelValue(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = C.dim, fontSize = 12.sp, modifier = Modifier.width(110.dp))
        Text(value, color = C.text, fontSize = 12.sp)
    }
}
