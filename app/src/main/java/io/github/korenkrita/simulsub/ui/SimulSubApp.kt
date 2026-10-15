package io.github.korenkrita.simulsub.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/** 统一入口仅依赖状态与动作，按当前窗口可用宽度适应旋转和分屏。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimulSubApp(state: SimulSubUiState, actions: SimulSubActions) {
    SimulSubTheme(state.settings) {
        Surface(Modifier.fillMaxSize()) {
            BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                val fontScale = LocalDensity.current.fontScale
                val useRail = maxWidth >= 600.dp
                val showRailHeader = maxHeight >= 480.dp
                val railWidth = if (fontScale >= 1.5f) 144.dp else 88.dp
                val contentWidth = maxWidth - if (useRail) railWidth else 0.dp
                val wide = contentWidth >= if (fontScale >= 1.5f) 960.dp else 760.dp
                val messageMaxHeight = minOf(160.dp, maxHeight * 0.25f)
                val editor = state.scenes.editor
                val editing = state.destination == UiDestination.SCENES && editor != null
                val editorBusy = editor?.let { it.saving || it.generating } == true
                BackHandler(enabled = editing && !editorBusy, onBack = actions.onCloseSceneEditor)
                Row(Modifier.fillMaxSize()) {
                    if (useRail) {
                        NavigationRail(
                            modifier = Modifier.width(railWidth).fillMaxHeight().verticalScroll(rememberScrollState()),
                            windowInsets = WindowInsets(0, 0, 0, 0),
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            header = { if (showRailHeader) Text("同传字幕", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 12.dp, vertical = 20.dp)) },
                        ) {
                            UiDestination.entries.forEach { destination ->
                                NavigationRailItem(
                                    selected = state.destination == destination, onClick = { actions.onNavigate(destination) },
                                    icon = { Icon(destination.icon(), contentDescription = null) },
                                    label = { Text(if (destination == UiDestination.SCENES) "场景" else destination.title) }, modifier = Modifier.padding(vertical = 4.dp).heightIn(min = 64.dp),
                                )
                            }
                        }
                    }
                    Scaffold(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        contentWindowInsets = WindowInsets(0, 0, 0, 0),
                        topBar = {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                                TopAppBar(
                                    modifier = Modifier.widthIn(max = if (wide) 1120.dp else 640.dp).fillMaxWidth(),
                                    title = { Text(state.destination.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = if (wide) 8.dp else 0.dp)) },
                                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                                    windowInsets = WindowInsets(0, 0, 0, 0),
                                )
                            }
                        },
                        bottomBar = {
                            if (!useRail) NavigationBar(
                                windowInsets = WindowInsets(0, 0, 0, 0),
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                tonalElevation = 0.dp,
                            ) {
                                UiDestination.entries.forEach { destination ->
                                    NavigationBarItem(
                                        selected = state.destination == destination, onClick = { actions.onNavigate(destination) },
                                        icon = { Icon(destination.icon(), contentDescription = null) },
                                        label = { Text(if (destination == UiDestination.SCENES) "场景" else destination.title) },
                                    )
                                }
                            }
                        },
                    ) { padding ->
                        Column(Modifier.fillMaxSize().padding(padding)) {
                            state.message?.let { message ->
                                Box(Modifier.fillMaxWidth().heightIn(max = messageMaxHeight).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
                                    MessagePanel(message, actions.onDismissMessage)
                                }
                            }
                            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                                key(state.destination) {
                                    when (state.destination) {
                                        UiDestination.LIVE -> LiveScreen(state.live, actions, wide)
                                        UiDestination.MODELS -> ModelsScreen(state.models, actions, wide)
                                        UiDestination.SCENES -> ScenesScreen(state.scenes, actions, wide)
                                        UiDestination.SETTINGS -> SettingsScreen(state.settings, state.live.running || state.live.busy, actions, wide)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 导航图标只表达页面含义，完整中文名称由组件标签提供。 */
private fun UiDestination.icon(): ImageVector = when (this) {
    UiDestination.LIVE -> Icons.Outlined.GraphicEq
    UiDestination.MODELS -> Icons.AutoMirrored.Outlined.LibraryBooks
    UiDestination.SCENES -> Icons.Outlined.Tune
    UiDestination.SETTINGS -> Icons.Outlined.Settings
}
