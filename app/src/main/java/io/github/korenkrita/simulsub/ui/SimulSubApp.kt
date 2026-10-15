package io.github.korenkrita.simulsub.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/** 统一入口仅依赖状态与动作，按当前窗口可用宽度适应旋转和分屏。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimulSubApp(state: SimulSubUiState, actions: SimulSubActions) {
    if (state.onboarding != null) {
        OnboardingScreen(state, actions)
        return
    }
    val mainDestinations = listOf(UiDestination.LIVE, UiDestination.SUBTITLE, UiDestination.SETTINGS)
    var detailReturnDestination by rememberSaveable { mutableStateOf(UiDestination.SETTINGS) }
    var settingsReturnDestination by rememberSaveable { mutableStateOf(UiDestination.SETTINGS) }
    var settingsSection by rememberSaveable { mutableStateOf<SettingsSection?>(null) }
    var pendingApiExit by remember { mutableStateOf<(() -> Unit)?>(null) }
    val editingApi = state.destination == UiDestination.SETTINGS && settingsSection == SettingsSection.API

    /** 所有离开入口共用确认，只保留首次意图，取消时不修改控制器草稿。 */
    fun requestExit(exit: () -> Unit) {
        if (pendingApiExit != null) return
        if (editingApi && state.settings.api.hasUnsavedChanges) pendingApiExit = exit else exit()
    }

    val returnToSettings: () -> Unit = {
        requestExit {
            settingsSection = null
            if (settingsReturnDestination == UiDestination.LIVE) actions.onNavigate(UiDestination.LIVE)
            settingsReturnDestination = UiDestination.SETTINGS
        }
    }
    val navigate: (UiDestination) -> Unit = { destination ->
        requestExit {
            if (destination == UiDestination.MODELS || destination == UiDestination.SCENES) {
                detailReturnDestination = if (state.destination in mainDestinations) state.destination else UiDestination.SETTINGS
            }
            if (destination == UiDestination.SETTINGS) {
                settingsSection = null
                settingsReturnDestination = UiDestination.SETTINGS
            }
            actions.onNavigate(destination)
        }
    }
    val routedActions = actions.copy(onNavigate = { destination ->
        if (destination == UiDestination.SETTINGS && state.destination == UiDestination.LIVE) {
            settingsSection = SettingsSection.API
            settingsReturnDestination = UiDestination.LIVE
            actions.onNavigate(destination)
        } else navigate(destination)
    })
    val returnFromDetail: () -> Unit = { navigate(detailReturnDestination) }
    val isDetail = state.destination == UiDestination.MODELS || state.destination == UiDestination.SCENES
    BackHandler(enabled = isDetail && !(state.destination == UiDestination.SCENES && state.scenes.editor != null), onBack = returnFromDetail)
    BackHandler(enabled = state.destination == UiDestination.SETTINGS && settingsSection != null, onBack = returnToSettings)
    val updatePrompt = state.settings.updates.prompt
    val canShowUpdate = updatePrompt != null && !state.live.running && !state.live.busy &&
        !editingApi && pendingApiExit == null && state.scenes.editor == null
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    var updateDialogOpened by remember(updatePrompt?.versionName) { mutableStateOf(false) }
    // 已有对话框会让Activity窗口失焦；更新自身取得焦点后保留打开状态，避免反复开关。
    LaunchedEffect(canShowUpdate, windowFocused, updatePrompt?.versionName) {
        if (!canShowUpdate) updateDialogOpened = false
        else if (windowFocused) updateDialogOpened = true
    }
    SimulSubTheme(state.settings) {
        if (canShowUpdate && updateDialogOpened) UpdatePrompt(updatePrompt!!, routedActions)
        if (pendingApiExit != null) {
            AlertDialog(
                onDismissRequest = { pendingApiExit = null },
                title = { Text("配置尚未保存") },
                text = { Text("离开将放弃本次修改，是否继续编辑？") },
                confirmButton = {
                    TextButton(onClick = {
                        // 先取走待执行意图，避免重组前的多次点击重复恢复和导航。
                        val exit = pendingApiExit
                        pendingApiExit = null
                        if (exit != null) {
                            if (state.settings.api.hasUnsavedChanges) actions.onDiscardApiDraft()
                            exit()
                        }
                    }) { Text("放弃修改") }
                },
                dismissButton = {
                    TextButton(onClick = { pendingApiExit = null }) { Text("继续编辑") }
                },
            )
        }
        Surface(Modifier.fillMaxSize()) {
            // 根布局只避让系统栏和显示缺口，底部导航保持原位，由键盘覆盖。
            BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))) {
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
                            header = { if (showRailHeader) Text("羽译", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 12.dp, vertical = 20.dp)) },
                        ) {
                            mainDestinations.forEach { destination ->
                                NavigationRailItem(
                                    selected = state.destination == destination || (isDetail && destination == UiDestination.SETTINGS), onClick = { navigate(destination) },
                                    icon = { Icon(destination.icon(), contentDescription = null) },
                                    label = { Text(destination.title) }, modifier = Modifier.padding(vertical = 4.dp).heightIn(min = 64.dp),
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
                                    title = { Text(if (editing) "编辑场景" else if (state.destination == UiDestination.SETTINGS) settingsSection?.title ?: "设置" else state.destination.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = if (wide) 8.dp else 0.dp)) },
                                    navigationIcon = {
                                        if (editing) IconButton(onClick = actions.onCloseSceneEditor, enabled = !editorBusy) {
                                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回场景列表")
                                        }
                                        else if (state.destination == UiDestination.SETTINGS && settingsSection != null) IconButton(onClick = returnToSettings) {
                                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回${settingsReturnDestination.title}")
                                        }
                                        else if (isDetail) IconButton(onClick = returnFromDetail) {
                                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回${detailReturnDestination.title}")
                                        }
                                    },
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
                                mainDestinations.forEach { destination ->
                                    NavigationBarItem(
                                        selected = state.destination == destination || (isDetail && destination == UiDestination.SETTINGS), onClick = { navigate(destination) },
                                        icon = { Icon(destination.icon(), contentDescription = null) },
                                        label = { Text(destination.title) },
                                    )
                                }
                            }
                        },
                    ) { padding ->
                        // 表单单独避让键盘；先消费导航占位，避免再多留一段导航栏高度。
                        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
                            state.message?.let { message ->
                                Box(Modifier.fillMaxWidth().heightIn(max = messageMaxHeight).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
                                    MessagePanel(message, actions.onDismissMessage)
                                }
                            }
                            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                                AnimatedContent(targetState = state.destination,
                                    transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(100)) }, label = "主页面切换") { destination ->
                                    when (destination) {
                                        UiDestination.LIVE -> LiveScreen(state.live, routedActions, wide)
                                        UiDestination.SUBTITLE -> SubtitleSettings(state.settings.subtitle, routedActions, wide, null, state.live.finalOnly)
                                        UiDestination.MODELS -> ModelsScreen(state.models, routedActions, wide)
                                        UiDestination.SCENES -> ScenesScreen(state.scenes, routedActions, wide)
                                        UiDestination.SETTINGS -> SettingsScreen(state.settings, state.live.running || state.live.busy, routedActions, wide,
                                            selected = settingsSection, finalOnly = state.live.finalOnly, onSelect = { settingsSection = it })
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
    UiDestination.SUBTITLE -> Icons.Outlined.Subtitles
    UiDestination.MODELS -> Icons.AutoMirrored.Outlined.LibraryBooks
    UiDestination.SCENES -> Icons.Outlined.Tune
    UiDestination.SETTINGS -> Icons.Outlined.Settings
}
