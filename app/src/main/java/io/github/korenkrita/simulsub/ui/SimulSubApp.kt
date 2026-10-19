package io.github.korenkrita.simulsub.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
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
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.animation.*
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.github.korenkrita.simulsub.ListeningMode

/** 统一入口仅依赖状态与动作，按当前窗口可用宽度适应旋转和分屏。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimulSubApp(state: SimulSubUiState, actions: SimulSubActions) {
    val routeScrollStateHolder = rememberSaveableStateHolder()
    CompositionLocalProvider(LocalRouteScrollStateHolder provides routeScrollStateHolder) {
        val flowStateHolder = rememberSaveableStateHolder()
        val guiding = state.onboarding != null
        AnimatedContent(targetState = guiding, modifier = Modifier.fillMaxSize(),
            transitionSpec = { routeTransition(forward = !targetState) }, label = "引导与应用切换") { guidePage ->
            val rendered = rememberRouteSnapshot(guidePage == guiding, state)
            flowStateHolder.SaveableStateProvider(if (guidePage) "guide" else "application") {
                SimulSubRoot(rendered, actions)
            }
        }
        state.settings.quickPlatformKey?.let { draft ->
            SimulSubTheme(state.settings) {
                QuickPlatformKeyDialog(draft, actions, !state.live.running && !state.live.busy)
            }
        }
    }
}

/** 导航与确认仍由根页面处理，滚动容器在导航树之外存活，不接收业务草稿。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SimulSubRoot(state: SimulSubUiState, actions: SimulSubActions) {
    val pageStateHolder = rememberSaveableStateHolder()
    if (state.onboarding != null) {
        OnboardingScreen(state, actions)
        return
    }
    val mainDestinations = mainNavigationDestinations
    val layoutDirection = LocalLayoutDirection.current
    var detailReturnDestination by rememberSaveable { mutableStateOf(UiDestination.SETTINGS) }
    var detailReturnSection by rememberSaveable { mutableStateOf<SettingsSection?>(null) }
    var settingsReturnDestination by rememberSaveable { mutableStateOf(UiDestination.SETTINGS) }
    var settingsSection by rememberSaveable { mutableStateOf<SettingsSection?>(null) }
    var translationEditorOpen by rememberSaveable { mutableStateOf(false) }
    // 进程重建只恢复列表；不能把旧编辑路由错误绑定到当前活动服务。
    LaunchedEffect(Unit) {
        if (translationEditorOpen && state.settings.translationEditorId == null) translationEditorOpen = false
    }
    var keysReturnSection by rememberSaveable { mutableStateOf<SettingsSection?>(null) }
    var keysReturnDestination by rememberSaveable { mutableStateOf(UiDestination.SETTINGS) }
    var pendingExit by remember { mutableStateOf<(() -> Unit)?>(null) }
    var discardOnlyKeys by remember { mutableStateOf(false) }
    val editingApi = state.destination == UiDestination.SETTINGS && settingsSection == SettingsSection.API
    val editingOnline = state.destination == UiDestination.SETTINGS && settingsSection == SettingsSection.LISTENING
    val editingKeys = state.destination == UiDestination.SETTINGS && settingsSection == SettingsSection.KEYS
    OnlineProbeRouteLifetime(editingOnline && state.settings.listeningEditor != null,
        { actions.onCancelOnlineTest(); actions.onCancelCloudAsrTest() })
    val hasDirtyKeys = state.settings.platformKeys.any { it.hasUnsavedChanges }
    val hasDirtyConfiguration = state.settings.api.hasUnsavedChanges || state.settings.online.hasUnsavedChanges ||
        state.settings.cloudAsr.hasUnsavedChanges || hasDirtyKeys
    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    var forwardingRootBack by remember { mutableStateOf(false) }

    /** 所有离开入口共用确认，只保留首次意图，取消时不修改控制器草稿。 */
    fun requestExit(exit: () -> Unit) {
        if (pendingExit != null) return
        discardOnlyKeys = false
        if (hasDirtyConfiguration) pendingExit = exit else exit()
    }

    /** 从密钥管理返回来源页只保护密钥草稿，保留来源表单尚未保存的连接信息。 */
    fun returnFromKeys() {
        if (pendingExit != null) return
        val exit: () -> Unit = {
            settingsSection = keysReturnSection
            if (keysReturnDestination != UiDestination.SETTINGS) actions.onNavigate(keysReturnDestination)
        }
        if (hasDirtyKeys) {
            discardOnlyKeys = true
            pendingExit = exit
        } else exit()
    }

    /** 切换主页面前恢复服务编辑的临时选择，进入平台密钥子页时保留当前编辑器。 */
    fun closeServiceEditors() {
        if (state.settings.listeningEditor != null) actions.onCloseListeningServiceEditor()
        if (translationEditorOpen) {
            actions.onCloseTranslationServiceEditor()
            translationEditorOpen = false
        }
    }

    val returnToSettings: () -> Unit = {
        if (editingKeys) returnFromKeys()
        else if (editingOnline && state.settings.listeningEditor != null) requestExit(actions.onCloseListeningServiceEditor)
        else if (editingApi && translationEditorOpen) requestExit {
            actions.onCloseTranslationServiceEditor()
            translationEditorOpen = false
        }
        else requestExit {
            settingsSection = null
            if (settingsReturnDestination == UiDestination.LIVE) actions.onNavigate(UiDestination.LIVE)
            settingsReturnDestination = UiDestination.SETTINGS
        }
    }
    val navigate: (UiDestination) -> Unit = { destination ->
        requestExit {
            closeServiceEditors()
            if (destination == UiDestination.MODELS) {
                settingsSection = SettingsSection.LISTENING
                settingsReturnDestination = if (state.destination == UiDestination.LIVE) UiDestination.LIVE else UiDestination.SETTINGS
                actions.onNavigate(UiDestination.SETTINGS)
                return@requestExit
            }
            if (destination == UiDestination.MODELS || destination == UiDestination.SCENES) {
                detailReturnDestination = if (state.destination in mainDestinations) state.destination else UiDestination.SETTINGS
                detailReturnSection = if (state.destination == UiDestination.SETTINGS) settingsSection else null
            }
            if (destination == UiDestination.SETTINGS) {
                settingsSection = null
                translationEditorOpen = false
                settingsReturnDestination = UiDestination.SETTINGS
            }
            actions.onNavigate(destination)
        }
    }
    val routedActions = actions.copy(onNavigate = { destination ->
        if (destination == UiDestination.SETTINGS && (state.destination == UiDestination.LIVE || editingOnline)) requestExit {
            closeServiceEditors()
            settingsSection = SettingsSection.API
            translationEditorOpen = false
            if (state.destination == UiDestination.LIVE) settingsReturnDestination = UiDestination.LIVE
            actions.onNavigate(destination)
        } else navigate(destination)
    }, onOpenApiSettings = {
        requestExit {
            closeServiceEditors()
            settingsSection = SettingsSection.API
            translationEditorOpen = false
            settingsReturnDestination = if (state.destination == UiDestination.LIVE) UiDestination.LIVE else UiDestination.SETTINGS
            actions.onNavigate(UiDestination.SETTINGS)
        }
    }, onOpenListeningSettings = {
        val open: () -> Unit = {
            closeServiceEditors()
            settingsSection = SettingsSection.LISTENING
            settingsReturnDestination = if (state.destination == UiDestination.LIVE) UiDestination.LIVE else UiDestination.SETTINGS
            actions.onNavigate(UiDestination.SETTINGS)
        }
        requestExit(open)
    }, onOpenPlatformKeys = {
        if (!editingKeys) {
            keysReturnSection = if (state.destination == UiDestination.SETTINGS) settingsSection else null
            keysReturnDestination = state.destination
            settingsSection = SettingsSection.KEYS
            actions.onNavigate(UiDestination.SETTINGS)
        }
    }, onOpenCosts = {
        requestExit {
            closeServiceEditors()
            settingsSection = SettingsSection.COSTS
            settingsReturnDestination = if (state.destination == UiDestination.LIVE) UiDestination.LIVE else UiDestination.SETTINGS
            actions.onNavigate(UiDestination.SETTINGS)
        }
    }, onSelectListeningService = { id ->
        if (!state.live.running && !state.live.busy && !state.settings.online.testing && !state.settings.cloudAsr.testing &&
            !state.settings.api.testing && !state.settings.api.saving) {
            requestExit {
                actions.onSelectListeningService(id)
            }
        }
    }, onSelectTranslationService = { id ->
        if (!state.live.running && !state.live.busy && !state.settings.online.testing && !state.settings.cloudAsr.testing &&
            !state.settings.api.testing && !state.settings.api.saving) {
            requestExit {
                actions.onSelectTranslationService(id)
            }
        }
    }, onListeningModeChange = { mode ->
        if (!state.live.running && !state.live.busy && !state.settings.online.testing && !state.settings.cloudAsr.testing && mode != state.settings.listeningMode) {
            requestExit { actions.onListeningModeChange(mode) }
        }
    }, onRestartOnboarding = { requestExit { closeServiceEditors(); actions.onRestartOnboarding() } })
    val returnFromDetail: () -> Unit = {
        requestExit {
            settingsSection = detailReturnSection
            actions.onNavigate(detailReturnDestination)
        }
    }
    val isDetail = state.destination == UiDestination.MODELS || state.destination == UiDestination.SCENES
    BackHandler(enabled = !isDetail && (state.destination != UiDestination.SETTINGS || settingsSection == null) && hasDirtyConfiguration && !forwardingRootBack) {
        requestExit { forwardingRootBack = true }
    }
    // 下一次组合先关闭本层拦截，再把已确认的根返回交还系统分发器。
    LaunchedEffect(forwardingRootBack) {
        if (forwardingRootBack) {
            backDispatcher?.onBackPressed()
            forwardingRootBack = false
        }
    }
    BackHandler(enabled = isDetail && !(state.destination == UiDestination.SCENES && state.scenes.editor != null), onBack = returnFromDetail)
    BackHandler(enabled = state.destination == UiDestination.SETTINGS && settingsSection != null, onBack = returnToSettings)
    val updatePrompt = state.settings.updates.prompt
    val canShowUpdate = updatePrompt != null && !state.live.running && !state.live.busy &&
        !editingApi && !editingOnline && !editingKeys && pendingExit == null && !hasDirtyConfiguration && state.scenes.editor == null
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    var updateDialogOpened by remember(updatePrompt?.versionName) { mutableStateOf(false) }
    // 已有对话框会让Activity窗口失焦；更新自身取得焦点后保留打开状态，避免反复开关。
    LaunchedEffect(canShowUpdate, windowFocused, updatePrompt?.versionName) {
        if (!canShowUpdate) updateDialogOpened = false
        else if (windowFocused) updateDialogOpened = true
    }
    SimulSubTheme(state.settings) {
        if (canShowUpdate && updateDialogOpened) UpdatePrompt(updatePrompt!!, routedActions)
        if (pendingExit != null) {
            UnsavedConfigurationDialog(
                onContinueEditing = { pendingExit = null },
                onDiscard = {
                    // 先取走待执行意图，避免重组前的多次点击重复恢复和导航。
                    val exit = pendingExit
                    pendingExit = null
                    if (exit != null) {
                        if (!discardOnlyKeys) {
                            if (state.settings.api.hasUnsavedChanges) actions.onDiscardApiDraft()
                            if (state.settings.online.hasUnsavedChanges) actions.onDiscardOnlineDraft()
                            if (state.settings.cloudAsr.hasUnsavedChanges) actions.onDiscardCloudAsrDraft()
                        }
                        if (hasDirtyKeys) actions.onDiscardPlatformKeys()
                        exit()
                    }
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
                                    title = { Text(if (editing) "编辑场景"
                                        else if (editingOnline && state.settings.listeningEditor != null) "编辑听写服务"
                                        else if (editingApi && translationEditorOpen) "编辑翻译服务"
                                        else if (state.destination == UiDestination.SETTINGS) settingsSection?.title ?: "设置" else state.destination.title,
                                        style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = if (wide) 8.dp else 0.dp)) },
                                    navigationIcon = {
                                        if (editing) IconButton(onClick = actions.onCloseSceneEditor, enabled = !editorBusy) {
                                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回场景列表")
                                        }
                                        else if (state.destination == UiDestination.SETTINGS && settingsSection != null) IconButton(onClick = returnToSettings) {
                                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = when {
                                                editingKeys -> "返回${keysReturnSection?.title ?: keysReturnDestination.title}"
                                                editingOnline && state.settings.listeningEditor != null -> "返回听写服务列表"
                                                editingApi && translationEditorOpen -> "返回翻译服务列表"
                                                else -> "返回${settingsReturnDestination.title}"
                                            })
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
                            ModelDownloadIndicator(state.models)
                            state.message?.let { message ->
                                Box(Modifier.fillMaxWidth().heightIn(max = messageMaxHeight).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
                                    MessagePanel(message, actions.onDismissMessage)
                                }
                            }
                            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                                AnimatedContent(targetState = state.destination,
                                    transitionSpec = { mainPageTransition(vertical = useRail, layoutDirection = layoutDirection) }, label = "主页面切换") { destination ->
                                    val page = rememberRouteSnapshot(destination == state.destination, state)
                                    val settingsRoute = rememberRouteSnapshot(destination == state.destination, settingsSection to translationEditorOpen)
                                    // 页面退出动画组合后仍按稳定路由保留位置，业务状态继续仅由控制器持有。
                                    pageStateHolder.SaveableStateProvider(destination.name) {
                                      Box(Modifier.fillMaxSize().testTag("main-page-${destination.name}")) {
                                        when (destination) {
                                            UiDestination.LIVE -> RouteScrollStateProvider("main/live") { LiveScreen(page.live, routedActions, wide) }
                                            UiDestination.SUBTITLE -> RouteScrollStateProvider("main/subtitle") { SubtitleSettings(page.settings.subtitle, routedActions, wide, null, page.live.finalOnly) }
                                            UiDestination.MODELS -> RouteScrollStateProvider("main/models") { ModelsScreen(page.models, routedActions, wide) }
                                            UiDestination.SCENES -> ScenesScreen(page.scenes, routedActions, wide)
                                            UiDestination.SETTINGS -> SettingsScreen(page.settings, page.live.running || page.live.busy, routedActions, wide,
                                                models = page.models,
                                                selected = settingsRoute.first, finalOnly = page.live.finalOnly,
                                                translationEditorOpen = settingsRoute.second,
                                                onOpenTranslationEditor = { translationEditorOpen = true }, onSelect = { section ->
                                                    if (section == SettingsSection.KEYS) routedActions.onOpenPlatformKeys()
                                                    else if (section == SettingsSection.COSTS) routedActions.onOpenCosts()
                                                    else requestExit { settingsSection = section; translationEditorOpen = false }
                                                })
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
