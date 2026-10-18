package io.github.korenkrita.simulsub.ui

import androidx.compose.animation.AnimatedContent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import io.github.korenkrita.simulsub.ListeningMode
import io.github.korenkrita.simulsub.ListeningServiceRepository

/** 按所选模式提供真实引导顺序，序号与前后步骤均不依赖枚举排列。 */
internal fun onboardingSteps(mode: ListeningMode): List<OnboardingStep> =
    when (mode) {
        ListeningMode.ONLINE -> listOf(OnboardingStep.PERMISSIONS, OnboardingStep.MODE, OnboardingStep.ONLINE)
        ListeningMode.CLOUD_ASR -> listOf(OnboardingStep.PERMISSIONS, OnboardingStep.MODE, OnboardingStep.ONLINE, OnboardingStep.API)
        ListeningMode.LOCAL -> listOf(OnboardingStep.PERMISSIONS, OnboardingStep.MODE, OnboardingStep.API, OnboardingStep.MODEL)
    }

/** 引导的动画标识只包含步骤和编辑目标，密钥与表单草稿仅保留在内存快照中。 */
private data class OnboardingRoute(
    val step: OnboardingStep,
    val mode: ListeningMode,
    val showingKeys: Boolean,
    val servicePage: OnboardingStep?,
    val translationEditorOpen: Boolean,
    val listeningEditorId: String?,
    val translationEditorId: String?,
) {
    val depth: Int get() = onboardingSteps(mode).indexOf(step).coerceAtLeast(0) * 10 + when {
        showingKeys -> 3
        listeningEditorId != null || translationEditorOpen -> 2
        servicePage != null -> 1
        else -> 0
    }
}

/** 首次引导按模式渲染必要配置，完成记录及步骤切换由控制器负责。 */
@Composable
internal fun OnboardingScreen(state: SimulSubUiState, actions: SimulSubActions) {
    val onboarding = state.onboarding ?: return
    val steps = onboardingSteps(onboarding.listeningMode)
    val step = onboarding.step.takeIf { it in steps } ?: steps.last()
    val pageStateHolder = rememberSaveableStateHolder()
    var showingKeys by rememberSaveable { mutableStateOf(false) }
    var servicePage by rememberSaveable { mutableStateOf<OnboardingStep?>(null) }
    var translationEditorOpen by rememberSaveable { mutableStateOf(false) }
    // 编辑目标随控制器恢复；目标不存在时回到列表，避免误编辑另一套服务。
    LaunchedEffect(Unit) {
        if (translationEditorOpen && state.settings.translationEditorId == null) translationEditorOpen = false
    }
    val hasDirtyKeys = state.settings.platformKeys.any { it.hasUnsavedChanges }
    val editorMode = state.settings.listeningEditor?.mode ?: onboarding.listeningMode
    val asr = editorMode == ListeningMode.CLOUD_ASR
    val onlineDraft = if (asr) state.settings.cloudAsr else state.settings.online
    val showingListening = !showingKeys && ((servicePage == null && step in listOf(OnboardingStep.MODE, OnboardingStep.ONLINE)) || servicePage == OnboardingStep.ONLINE)
    OnlineProbeRouteLifetime(showingListening && (state.settings.listeningEditor != null || step == OnboardingStep.ONLINE),
        { actions.onCancelOnlineTest(); actions.onCancelCloudAsrTest() })
    var pendingStep by remember(step) { mutableStateOf<(() -> Unit)?>(null) }
    var discardOnlyKeys by remember { mutableStateOf(false) }
    val permissions = state.live.permissions.withRequiredPermissions()
    val ready = when (step) {
        OnboardingStep.PERMISSIONS -> permissions.filter { it.required }.all { it.granted }
        OnboardingStep.MODE -> true
        OnboardingStep.API -> onboarding.apiReady && !state.settings.api.hasUnsavedChanges
        OnboardingStep.MODEL -> onboarding.modelReady
        OnboardingStep.ONLINE -> onboarding.onlineReady && !onlineDraft.hasUnsavedChanges && !onlineDraft.testing
    }

    /** 暂存首次离开意图，未保存的接口配置必须由用户明确放弃。 */
    fun requestStep(action: () -> Unit) {
        if (pendingStep != null) return
        discardOnlyKeys = false
        if (state.settings.api.hasUnsavedChanges || state.settings.online.hasUnsavedChanges || state.settings.cloudAsr.hasUnsavedChanges || hasDirtyKeys) pendingStep = action
        else action()
    }

    /** 密钥页是当前引导步骤的子路由，返回不会修改步骤或放弃来源连接草稿。 */
    fun returnFromKeys() {
        if (pendingStep != null) return
        if (hasDirtyKeys) {
            discardOnlyKeys = true
            pendingStep = { showingKeys = false }
        } else showingKeys = false
    }

    /** 离开服务编辑路由必须关闭草稿；仅进入平台密钥子页时继续保留来源编辑器。 */
    fun closeServiceEditors() {
        if (state.settings.listeningEditor != null) actions.onCloseListeningServiceEditor()
        if (translationEditorOpen) {
            actions.onCloseTranslationServiceEditor()
            translationEditorOpen = false
        }
    }

    /** 所有步骤切换先确认未保存输入，再关闭编辑器，避免新建草稿恢复后仍保持脏状态。 */
    fun leaveStep(action: () -> Unit) {
        requestStep { closeServiceEditors(); action() }
    }

    val guardedActions = actions.copy(onNavigate = { destination ->
        leaveStep {
            if (destination == UiDestination.MODELS) servicePage = OnboardingStep.MODEL
            else actions.onNavigate(destination)
        }
    }, onListeningModeChange = { mode ->
        if (!state.live.running && !state.live.busy && !state.settings.online.testing && !state.settings.cloudAsr.testing && mode != onboarding.listeningMode) {
            leaveStep { actions.onListeningModeChange(mode) }
        }
    }, onSelectListeningService = { id ->
        if (!state.live.running && !state.live.busy && !state.settings.online.testing && !state.settings.cloudAsr.testing &&
            !state.settings.api.testing && !state.settings.api.saving) {
            leaveStep { actions.onSelectListeningService(id) }
        }
    }, onChooseOnboardingListeningService = { id ->
        if (!state.live.running && !state.live.busy) leaveStep { actions.onChooseOnboardingListeningService(id) }
    }, onSelectTranslationService = { id ->
        if (!state.live.running && !state.live.busy && !state.settings.api.testing && !state.settings.api.saving &&
            !state.settings.online.testing && !state.settings.cloudAsr.testing) {
            leaveStep { actions.onSelectTranslationService(id) }
        }
    }, onOpenListeningSettings = { leaveStep { servicePage = OnboardingStep.ONLINE } },
        onOpenApiSettings = { leaveStep { servicePage = OnboardingStep.API; translationEditorOpen = false } },
        onOpenPlatformKeys = { showingKeys = true })
    val onlineActions = if (asr) cloudAsrFormActions(guardedActions) else guardedActions

    /** 返回优先关闭服务编辑器，所有草稿继续沿用统一未保存确认。 */
    fun returnToGuide() {
        if (showingKeys) returnFromKeys()
        else if (showingListening && state.settings.listeningEditor != null) requestStep(actions.onCloseListeningServiceEditor)
        else if (servicePage == OnboardingStep.API && translationEditorOpen) requestStep {
            actions.onCloseTranslationServiceEditor()
            translationEditorOpen = false
        }
        else if (servicePage != null) leaveStep { servicePage = null }
        else leaveStep(actions.onOnboardingBack)
    }
    BackHandler(enabled = showingKeys || servicePage != null || step != OnboardingStep.PERMISSIONS, onBack = ::returnToGuide)
    SimulSubTheme(state.settings) {
        Surface(Modifier.fillMaxSize()) {
            BoxWithConstraints(
                Modifier.fillMaxSize()
                    .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout)).imePadding(),
                contentAlignment = Alignment.TopCenter,
            ) {
                val wide = maxWidth >= 840.dp
                val route = OnboardingRoute(step, onboarding.listeningMode, showingKeys, servicePage,
                    translationEditorOpen, state.settings.listeningEditor?.id, state.settings.translationEditorId)
                AnimatedContent(targetState = route, modifier = Modifier.fillMaxSize(),
                    transitionSpec = { routeTransition(forward = targetState.depth >= initialState.depth) },
                    label = "引导页面切换") { page ->
                    val rendered = rememberRouteSnapshot(page == route, state)
                    val pageReady = rememberRouteSnapshot(page == route, ready)
                    val renderedOnboarding = rendered.onboarding ?: onboarding
                    val step = page.step
                    val steps = onboardingSteps(renderedOnboarding.listeningMode)
                    val onboarding = renderedOnboarding
                    val editorMode = rendered.settings.listeningEditor?.mode ?: onboarding.listeningMode
                    val onlineDraft = if (editorMode == ListeningMode.CLOUD_ASR) rendered.settings.cloudAsr else rendered.settings.online
                    val showingListening = !page.showingKeys && ((page.servicePage == null && step in listOf(OnboardingStep.MODE, OnboardingStep.ONLINE)) || page.servicePage == OnboardingStep.ONLINE)
                    Column(Modifier.widthIn(max = 960.dp).fillMaxSize()) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (page.showingKeys) {
                                TextButton(onClick = { returnFromKeys() }, modifier = Modifier.heightIn(min = 48.dp)) {
                                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("返回当前引导步骤")
                                }
                            } else if (page.servicePage != null || (showingListening && rendered.settings.listeningEditor != null)) {
                                TextButton(onClick = ::returnToGuide, modifier = Modifier.heightIn(min = 48.dp)) {
                                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text(if (rendered.settings.listeningEditor != null && showingListening) "返回听写服务列表"
                                        else if (page.translationEditorOpen) "返回翻译服务列表" else "返回服务选择")
                                }
                            } else Text("第 ${steps.indexOf(step).coerceAtLeast(0) + 1}/${steps.size} 步", style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary)
                            Text(if (page.showingKeys) "平台密钥" else if (page.servicePage == OnboardingStep.MODEL) "听写服务"
                            else if (page.servicePage == OnboardingStep.API) "管理翻译服务" else when (step) {
                                OnboardingStep.PERMISSIONS -> "允许必要权限"
                                OnboardingStep.MODE -> "选择听写服务"
                                OnboardingStep.API -> "第二步 · 配置文字翻译"
                                OnboardingStep.MODEL -> "准备本地模型"
                                OnboardingStep.ONLINE -> if (onboarding.listeningMode == ListeningMode.CLOUD_ASR) "配置千问在线听写" else "配置千问语音直译"
                            }, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                        }
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            pageStateHolder.SaveableStateProvider(if (page.showingKeys) "platform-keys"
                                else "${page.servicePage?.let { "service-${it.name}" } ?: step.name}/${page.listeningEditorId ?: page.translationEditorId.takeIf { page.translationEditorOpen } ?: "page"}") {
                                // 滚动按步骤和编辑目标独立保存，密钥子页返回不再复用来源列表的位置。
                                val scrollRoute = if (page.showingKeys) "keys" else {
                                    val source = page.servicePage?.name ?: step.name
                                    val editor = when {
                                        page.servicePage == OnboardingStep.API && page.translationEditorOpen -> "api/${rendered.settings.translationEditorId ?: rendered.settings.api.profileId}"
                                        showingListening && rendered.settings.listeningEditor != null -> "listening/${rendered.settings.listeningEditor.id}"
                                        else -> "page"
                                    }
                                    "$source/$editor"
                                }
                                RouteScrollStateProvider("onboarding/$scrollRoute") {
                                    if (page.showingKeys) PlatformKeysSettings(rendered.settings.platformKeys,
                                        rendered.live.running || rendered.live.busy || rendered.settings.api.testing || rendered.settings.api.saving ||
                                            rendered.settings.online.testing || rendered.settings.cloudAsr.testing, guardedActions, wide)
                                    else if (page.servicePage == OnboardingStep.MODEL) ListeningSettings(rendered.settings,
                                        rendered.live.running || rendered.live.busy, guardedActions, wide, rendered.models)
                                    else if (page.servicePage == OnboardingStep.API && page.translationEditorOpen) TranslationApiSettings(rendered.settings.api,
                                        rendered.live.running || rendered.live.busy, guardedActions, wide, null,
                                        mode = editorMode, platformKeys = rendered.settings.platformKeys, showProfiles = false)
                                    else if (page.servicePage == OnboardingStep.API) TranslationServiceManagement(rendered.settings,
                                        rendered.live.running || rendered.live.busy, guardedActions, wide, { translationEditorOpen = true })
                                    else if (page.servicePage == OnboardingStep.ONLINE) ListeningSettings(rendered.settings,
                                        rendered.live.running || rendered.live.busy, guardedActions, wide, rendered.models)
                                    else when (step) {
                                        OnboardingStep.PERMISSIONS -> OnboardingPermissions(rendered.live.permissions.withRequiredPermissions(), actions)
                                        OnboardingStep.MODE -> OnboardingMode(rendered.settings,
                                            rendered.live.running || rendered.live.busy, guardedActions, wide)
                                        OnboardingStep.API -> TranslationApiSettings(rendered.settings.api,
                                            rendered.live.running || rendered.live.busy, guardedActions, wide, null, onboarding = true,
                                            mode = editorMode, platformKeys = rendered.settings.platformKeys, showProfiles = false)
                                        OnboardingStep.MODEL -> OnboardingModels(rendered.models, actions,
                                            rendered.live.listeningServiceId.removePrefix("local:"))
                                        OnboardingStep.ONLINE -> if (rendered.settings.listeningEditor != null) ListeningSettings(rendered.settings,
                                            rendered.live.running || rendered.live.busy, guardedActions, wide, rendered.models)
                                        else OnlineListeningSettings(onlineDraft,
                                            rendered.live.running || rendered.live.busy, onlineActions, wide, editorMode, platformKeys = rendered.settings.platformKeys)
                                    }
                                }
                            }
                        }
                        if (!page.showingKeys && page.servicePage == null) {
                            HorizontalDivider()
                            OnboardingFooter(step, pageReady, step == steps.last(),
                                onBack = ::returnToGuide,
                                onSkip = { leaveStep(actions.onOnboardingSkip) },
                                onNext = { leaveStep(actions.onOnboardingNext) })
                        }
                    }
                }
            }
        }
        if (pendingStep != null) {
            UnsavedConfigurationDialog(
                onContinueEditing = { pendingStep = null },
                onDiscard = {
                    // 先消费待执行动作，防止重组前重复点击恢复草稿或切换步骤。
                    val action = pendingStep
                    pendingStep = null
                    if (action != null) {
                        if (!discardOnlyKeys) {
                            if (state.settings.api.hasUnsavedChanges) actions.onDiscardApiDraft()
                            if (state.settings.online.hasUnsavedChanges) actions.onDiscardOnlineDraft()
                            if (state.settings.cloudAsr.hasUnsavedChanges) actions.onDiscardCloudAsrDraft()
                        }
                        if (hasDirtyKeys) actions.onDiscardPlatformKeys()
                        action()
                    }
                },
            )
        }
    }
}

/** 引导仅提供两种基础本地模型与三项千问预设；完整目录仍在听写服务中管理。 */
internal fun onboardingListeningChoices(profiles: List<ServiceProfileUiState>): List<ServiceProfileUiState> {
    val ids = listOf("local:reazonspeech", "local:sensevoice") + ListeningServiceRepository.BUILT_INS.map { it.id }
    return ids.mapNotNull { id -> profiles.firstOrNull { it.id == id } }
}

/** 用简短的用途和限制帮助初次选择，不在引导展开模型编号和技术指标。 */
internal fun onboardingListeningDescription(profile: ServiceProfileUiState): String = when {
    profile.id == "local:reazonspeech" -> "日英双语小模型，识别快；英语较弱，需要下载。"
    profile.id == "local:sensevoice" -> "支持中英日韩粤五语，效果较好；体积较大，需要下载。"
    profile.kind == "asr" -> "云端听写后再翻译，费用较低；还需配置文字翻译服务。"
    profile.id == ListeningServiceRepository.BUILT_INS.last().id -> "分段直接翻译，费用较低；字幕等待时间更长。"
    else -> "语音直接译为字幕，配置简单；费用较高。"
}

/** 引导先选择服务，云端密钥在下一步填写；选择不受日常首页就绪过滤影响。 */
@Composable
private fun OnboardingMode(settings: SettingsUiState, sessionBusy: Boolean, actions: SimulSubActions, wide: Boolean) {
    val choices = onboardingListeningChoices(settings.listeningProfiles)
    val first = if (wide) choices.filterIndexed { index, _ -> index % 2 == 0 } else choices
    val second = if (wide) choices.filterIndexed { index, _ -> index % 2 != 0 } else emptyList()
    PageColumns(wide, header = {
        SupportingText("先选择服务，下一步再填写密钥或下载模型。")
    }, first = {
        items(first, key = { it.id }) { OnboardingServiceChoice(it, sessionBusy, actions) }
    }, second = {
        items(second, key = { it.id }) { OnboardingServiceChoice(it, sessionBusy, actions) }
    })
}

/** 每条预设均可选择，未准备好的凭据和下载不会阻止首次配置。 */
@Composable
private fun OnboardingServiceChoice(profile: ServiceProfileUiState, sessionBusy: Boolean, actions: SimulSubActions) {
    val local = profile.kind == "local" || profile.id.startsWith("local:")
    SettingsCard {
        Column(Modifier.testTag("onboarding-service-${profile.id}"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(profile.name, style = MaterialTheme.typography.titleMedium)
            SupportingText(onboardingListeningDescription(profile))
            FilledTonalButton(onClick = { actions.onChooseOnboardingListeningService(
                if (local) profile.id else "profile:${profile.id}") }, enabled = !sessionBusy,
                modifier = Modifier.heightIn(min = 48.dp)) { Text("选择并继续") }
        }
    }
}

/** 必需权限和可选通知分开展示；通知不阻止下一步，屏幕共享留到真正开始时请求。 */
@Composable
private fun OnboardingPermissions(permissions: List<PermissionUiState>, actions: SimulSubActions) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("录音与悬浮显示用于同传，通知权限可选。", style = MaterialTheme.typography.bodyLarge)
        }
        items(permissions, key = { it.id }) { permission ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (permission.required) permission.title else "${permission.title}（可选）", style = MaterialTheme.typography.titleMedium)
                    if (permission.description.isNotBlank()) Text(permission.description, style = MaterialTheme.typography.bodyMedium)
                    FilledTonalButton(onClick = { actions.onPermission(permission.id) }, enabled = !permission.granted,
                        modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(if (permission.granted) "已允许" else "允许")
                    }
                }
            }
        }
        item {
            Text("系统屏幕共享会在每次开始同传时询问，这里无需提前授权。",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 最后一步只准备先前选定的识别模型，其他模型留在听写服务中管理。 */
@Composable
private fun OnboardingModels(models: ModelsUiState, actions: SimulSubActions, selectedId: String = "") {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val selected = models.items.firstOrNull { it.id == selectedId }
        item { Text(selected?.let { "已选择「${it.name}」，下载后启用即可。" } ?: "正在准备已选择的识别模型…", style = MaterialTheme.typography.bodyLarge) }
        if (models.items.isEmpty()) item {
            Text(if (models.loading) "正在读取识别模型…" else "暂无可用的识别模型。", style = MaterialTheme.typography.bodyMedium)
        }
        items(models.items.filter { it.id == selectedId }, key = { it.id }) {
            model -> OnboardingModelCard(model, actions)
        }
    }
}

/** 下载进度来自状态；未知进度与校验使用不定指示，不推测完成比例。 */
@Composable
private fun OnboardingModelCard(model: ModelUiState, actions: SimulSubActions) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(model.name, style = MaterialTheme.typography.titleMedium)
            Text("识别语言：${model.languages}", style = MaterialTheme.typography.bodyMedium)
            Text("模型大小：${model.sizeLabel}", style = MaterialTheme.typography.bodyMedium)
            Text(model.status.title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            model.error?.takeIf { it.isNotBlank() }?.let { error ->
                MessagePanel(UiMessage(error, isError = true))
            }
            if (model.status == ModelUiStatus.DOWNLOADING || model.status == ModelUiStatus.VERIFYING) {
                val progress = model.progress?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
                if (progress == null || model.status == ModelUiStatus.VERIFYING) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    Text("已下载 ${(progress * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium)
                }
            }
            if (model.canDownload) Button(onClick = { actions.onDownloadModel(model.id) },
                modifier = Modifier.heightIn(min = 48.dp)) { Text(if (model.status == ModelUiStatus.FAILED || model.status == ModelUiStatus.CANCELLED) "重试下载" else "下载模型") }
            if (model.canCancel) FilledTonalButton(onClick = { actions.onCancelDownload(model.id) },
                modifier = Modifier.heightIn(min = 48.dp)) { Text("取消下载") }
            if (model.canActivate) Button(onClick = { actions.onActivateModel(model.id) },
                modifier = Modifier.heightIn(min = 48.dp)) { Text("启用模型") }
        }
    }
}

/** 页脚独立于正文滚动；分行按钮在窄屏和大字号下仍有至少四十八单位触控高度。 */
@Composable
private fun OnboardingFooter(step: OnboardingStep, ready: Boolean, lastStep: Boolean,
    onBack: () -> Unit, onSkip: () -> Unit, onNext: () -> Unit) {
    Column(Modifier.fillMaxWidth().testTag("onboarding-footer").padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (step != OnboardingStep.PERMISSIONS) TextButton(onClick = onBack,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("上一步") }
            TextButton(onClick = onSkip, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("跳过这步") }
        }
        Button(onClick = onNext, enabled = ready, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(if (lastStep) "进入羽译" else "下一步")
        }
    }
}
