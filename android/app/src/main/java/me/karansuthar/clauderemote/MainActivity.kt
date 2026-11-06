package me.karansuthar.clauderemote

import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.karansuthar.clauderemote.data.Device
import me.karansuthar.clauderemote.ui.AddDeviceDialog
import me.karansuthar.clauderemote.ui.AppTheme
import me.karansuthar.clauderemote.ui.ChatScreen
import me.karansuthar.clauderemote.ui.DeviceDrawer
import me.karansuthar.clauderemote.ui.DeviceHome
import me.karansuthar.clauderemote.ui.FolderPicker
import me.karansuthar.clauderemote.ui.SettingsDialog
import me.karansuthar.clauderemote.ui.SetupScreen

private sealed class Screen {
    data object Device : Screen()
    data object Picker : Screen()
    data class Chat(val key: String) : Screen()
}

/** Keeps the current screen across rotation and process death. */
private val ScreenSaver = Saver<Screen, String>(
    save = {
        when (it) {
            Screen.Device -> "device"
            Screen.Picker -> "picker"
            is Screen.Chat -> "chat:" + it.key
        }
    },
    restore = {
        when {
            it == "picker" -> Screen.Picker
            it.startsWith("chat:") && it.length > 5 -> Screen.Chat(it.substring(5))
            else -> Screen.Device
        }
    },
)

class MainActivity : ComponentActivity() {
    private val app by lazy { application as App }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Transparent bars whose icons follow the system light/dark setting, like AppTheme.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        setContent { AppTheme { Root() } }
    }

    override fun onResume() {
        super.onResume()
        app.devices.get(app.devices.lastSelectedId)?.let { app.client(it).wake() }
    }

    @Composable
    private fun Root() {
        val scope = rememberCoroutineScope()
        val devices by app.devices.devices.collectAsStateWithLifecycle()
        val drawerState = rememberDrawerState(DrawerValue.Closed)

        var selectedId by rememberSaveable {
            mutableStateOf(app.devices.get(app.devices.lastSelectedId)?.id ?: app.devices.list().firstOrNull()?.id)
        }
        var screen by rememberSaveable(stateSaver = ScreenSaver) { mutableStateOf<Screen>(Screen.Device) }
        var showAdd by rememberSaveable { mutableStateOf(false) }
        var showSettings by rememberSaveable { mutableStateOf(false) }

        // The selected device, falling back to the first one if it was removed.
        val device: Device? = devices.find { it.id == selectedId } ?: devices.firstOrNull()
        LaunchedEffect(device?.id) {
            if (device?.id != selectedId) {
                selectedId = device?.id
                screen = Screen.Device
            }
            app.devices.lastSelectedId = device?.id
        }

        fun select(d: Device) {
            if (d.id != selectedId) {
                selectedId = d.id
                screen = Screen.Device
            }
            scope.launch { drawerState.close() }
        }

        BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }
        BackHandler(enabled = !drawerState.isOpen && screen != Screen.Device) { screen = Screen.Device }

        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = screen == Screen.Device || drawerState.isOpen,
            drawerContent = {
                DeviceDrawer(
                    app = app,
                    devices = devices,
                    selectedId = device?.id,
                    onSelect = ::select,
                    onRemove = { d ->
                        app.devices.remove(d.id)
                        app.dropClient(d.id)
                    },
                    onRename = { d, name -> app.devices.rename(d.id, name) },
                    onAdd = {
                        scope.launch { drawerState.close() }
                        showAdd = true
                    },
                    onSettings = {
                        scope.launch { drawerState.close() }
                        showSettings = true
                    },
                )
            },
        ) {
            val current = device
            if (current == null) {
                SetupScreen(onAdd = { showAdd = true })
            } else {
                when (val s = screen) {
                    Screen.Device -> DeviceHome(
                        app = app,
                        device = current,
                        onMenu = { scope.launch { drawerState.open() } },
                        onNewSession = { screen = Screen.Picker },
                        onOpenSession = { key -> screen = Screen.Chat(key) },
                    )
                    Screen.Picker -> FolderPicker(
                        client = app.client(current),
                        onBack = { screen = Screen.Device },
                        onOpened = { key -> screen = Screen.Chat(key) },
                    )
                    is Screen.Chat -> ChatScreen(
                        app = app,
                        device = current,
                        key = s.key,
                        onBack = { screen = Screen.Device },
                    )
                }
            }
        }

        if (showAdd) {
            AddDeviceDialog(
                onDismiss = { showAdd = false },
                onAdd = { url, name ->
                    val added = app.devices.add(url, name)
                    showAdd = false
                    selectedId = added.id
                    screen = Screen.Device
                },
            )
        }
        if (showSettings) {
            SettingsDialog(app = app, onDismiss = { showSettings = false })
        }
    }
}
