package com.r0pw.editor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.r0pw.editor.data.AppSettings
import com.r0pw.editor.data.PresetStore
import com.r0pw.editor.data.ProjectStore
import com.r0pw.editor.plugins.PluginHost
import com.r0pw.editor.ui.C
import com.r0pw.editor.ui.HomeScreen
import com.r0pw.editor.ui.PluginScreen
import com.r0pw.editor.ui.R0Theme
import com.r0pw.editor.ui.SettingsScreen
import com.r0pw.editor.ui.SplashScreen
import com.r0pw.editor.ui.editor.EditorScreen
import com.r0pw.editor.ui.editor.EditorState

private sealed class Screen {
    object Splash : Screen()
    object Home : Screen()
    object Settings : Screen()
    object Plugins : Screen()
    class Editor(val state: EditorState) : Screen()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PluginHost.loadAll(this)
        setContent {
            R0Theme { App() }
        }
    }
}

@Composable
private fun App() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { ProjectStore(ctx) }
    val settings = remember { AppSettings(ctx) }
    val presets = remember { PresetStore(ctx) }
    var screen by remember { mutableStateOf<Screen>(Screen.Splash) }
    var homeKey by remember { mutableStateOf(0) }

    Box(Modifier.fillMaxSize().background(C.bg)) {
        when (val s = screen) {
            is Screen.Splash -> SplashScreen { screen = Screen.Home }
            is Screen.Home -> {
                androidx.compose.runtime.key(homeKey) {
                    HomeScreen(
                        store = store,
                        onOpen = { id ->
                            val p = store.load(id)
                            if (p != null) screen = Screen.Editor(EditorState(ctx, p, store, settings, presets, scope))
                        },
                        onSettings = { screen = Screen.Settings },
                        onPlugins = { screen = Screen.Plugins }
                    )
                }
            }
            is Screen.Settings -> {
                BackHandler { screen = Screen.Home }
                SettingsScreen(settings) { screen = Screen.Home }
            }
            is Screen.Plugins -> {
                BackHandler { screen = Screen.Home }
                PluginScreen { screen = Screen.Home }
            }
            is Screen.Editor -> EditorScreen(s.state) {
                homeKey++
                screen = Screen.Home
            }
        }
    }
}
