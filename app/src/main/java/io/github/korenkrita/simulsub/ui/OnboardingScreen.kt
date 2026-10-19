package io.github.korenkrita.simulsub.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.korenkrita.simulsub.ListeningMode
import io.github.korenkrita.simulsub.ListeningServiceRepository
import kotlin.math.roundToInt

/** 引导固定三页；历史云端听写模式按本地识别加文字翻译的配置页兼容。 */
internal fun onboardingSteps(mode: ListeningMode): List<OnboardingStep> = listOf(
    OnboardingStep.PERMISSIONS, OnboardingStep.MODE,
    if (mode == ListeningMode.ONLINE) OnboardingStep.ONLINE else OnboardingStep.API,
)

/** 动画与滚动标识只含步骤和路径，密钥始终留在控制器和退出动画的内存快照中。 */
private data class OnboardingRoute(val step: OnboardingStep, val mode: ListeningMode) {
    val depth: Int get() = onboardingSteps(mode).indexOf(step).coerceAtLeast(0)
}

/** 三页引导只派发权限、路径与即时配置动作；设置页及其高级表单继续由原路由管理。 */
@Composable
internal fun OnboardingScreen(state: SimulSubUiState, actions: SimulSubActions) {
    val onboarding = state.onboarding ?: return
    val steps = onboardingSteps(onboarding.listeningMode)
    val step = onboarding.step.takeIf { it in steps } ?: steps.last()
    val route = OnboardingRoute(step, onboarding.listeningMode)
    val pageStateHolder = rememberSaveableStateHolder()
    val permissions = state.live.permissions.withRequiredPermissions()
    val ready = when (step) {
        OnboardingStep.PERMISSIONS -> permissions.filter { it.required }.all { it.granted }
        OnboardingStep.MODE -> false
        OnboardingStep.API, OnboardingStep.MODEL -> onboarding.apiReady && onboarding.modelReady
        OnboardingStep.ONLINE -> onboarding.onlineReady
    }
    BackHandler(enabled = step != OnboardingStep.PERMISSIONS, onBack = actions.onOnboardingBack)
    SimulSubTheme(state.settings) {
        Surface(Modifier.fillMaxSize()) {
            BoxWithConstraints(Modifier.fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout)).imePadding(),
                contentAlignment = Alignment.TopCenter) {
                val wide = maxWidth >= maxOf(840.dp, 640.dp * LocalDensity.current.fontScale.coerceAtLeast(1f))
                Column(Modifier.fillMaxSize()) {
                    ModelDownloadIndicator(state.models)
                    state.message?.let { message ->
                        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                            .testTag("onboarding-global-message")) {
                            MessagePanel(message, actions.onDismissMessage)
                        }
                    }
                    AnimatedContent(targetState = route, modifier = Modifier.weight(1f).fillMaxWidth(),
                        transitionSpec = { routeTransition(forward = targetState.depth >= initialState.depth) },
                        label = "引导页面切换") { page ->
                        val rendered = rememberRouteSnapshot(page == route, state)
                        val pageReady = rememberRouteSnapshot(page == route, ready)
                        val guide = rendered.onboarding ?: onboarding
                        val pageSteps = onboardingSteps(page.mode)
                        val sessionBusy = rendered.live.running || rendered.live.busy
                        Column(Modifier.widthIn(max = 960.dp).fillMaxSize()) {
                            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("第 ${pageSteps.indexOf(page.step).coerceAtLeast(0) + 1}/3 步",
                                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                                Text(when (page.step) {
                                    OnboardingStep.PERMISSIONS -> "允许必要权限"
                                    OnboardingStep.MODE -> "选择使用方式"
                                    OnboardingStep.ONLINE -> "配置在线听译"
                                    OnboardingStep.API, OnboardingStep.MODEL -> "配置本地同传"
                                }, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                            }
                            Box(Modifier.weight(1f).fillMaxWidth()) {
                                pageStateHolder.SaveableStateProvider("${page.mode}/${page.step}") {
                                    // 外层仅保存各页滚动位置；提供器屏蔽子控件的业务值保存注册表。
                                    RouteScrollStateProvider("onboarding/${page.mode}/${page.step}") {
                                        when (page.step) {
                                            OnboardingStep.PERMISSIONS -> OnboardingPermissions(
                                                rendered.live.permissions.withRequiredPermissions(), actions, wide)
                                            OnboardingStep.MODE -> OnboardingMode(sessionBusy, actions, wide)
                                            OnboardingStep.ONLINE -> OnboardingSetup(guide.setup, rendered.models,
                                                online = true, sessionBusy = sessionBusy, actions = actions, wide = wide)
                                            OnboardingStep.API, OnboardingStep.MODEL -> OnboardingSetup(guide.setup, rendered.models,
                                                online = false, sessionBusy = sessionBusy, actions = actions, wide = wide)
                                        }
                                    }
                                }
                            }
                            HorizontalDivider()
                            OnboardingFooter(page.step, pageReady, page.step == pageSteps.last(),
                                onBack = actions.onOnboardingBack, onSkip = actions.onOnboardingSkip,
                                onNext = actions.onOnboardingNext)
                        }
                    }
                }
            }
        }
    }
}

/** 方式选择只进入下一步；本地模型的下载由配置页独立控制。 */
@Composable
private fun OnboardingMode(sessionBusy: Boolean, actions: SimulSubActions, wide: Boolean) {
    PageColumns(wide, first = {
        item("local-route") {
            OnboardingModeCard("本地同传", "需下载识别模型并填写翻译 Key，可在下一步配置。",
                "选择本地", "local", !sessionBusy) {
                actions.onChooseOnboardingRoute(ListeningMode.LOCAL)
            }
        }
    }, second = {
        item("online-route") {
            OnboardingModeCard("在线听译", "使用千问 3.5 实时听译，只需千问 Key，无需下载模型。",
                "选择在线", "online", !sessionBusy) {
                actions.onChooseOnboardingRoute(ListeningMode.ONLINE)
            }
        }
    })
}

/** 路径卡片使用简短说明与单个明确按钮，长文字和大字号均可自然换行。 */
@Composable
private fun OnboardingModeCard(title: String, description: String, actionLabel: String,
    tag: String, enabled: Boolean, onClick: () -> Unit) {
    SettingsCard {
        Column(Modifier.fillMaxWidth().testTag("onboarding-route-$tag"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            SupportingText(description)
            Button(onClick = onClick, enabled = enabled,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("onboarding-choose-$tag")) {
                Text(actionLabel)
            }
        }
    }
}

/** 必要与可选权限分组；通知等可选项不参与下一步的就绪判断。 */
@Composable
private fun OnboardingPermissions(permissions: List<PermissionUiState>, actions: SimulSubActions, wide: Boolean) {
    PageColumns(wide, first = {
        item("required-heading") { OnboardingPermissionHeading("必要权限", "onboarding-required-permissions") }
        items(permissions.filter { it.required }, key = { it.id }) { OnboardingPermissionCard(it, actions) }
        item("capture-note") { SupportingText("系统屏幕共享会在每次开始同传时询问，这里无需提前授权。") }
    }, second = {
        item("optional-heading") { OnboardingPermissionHeading("可选权限", "onboarding-optional-permissions") }
        if (permissions.none { !it.required }) item("no-optional") { SupportingText("当前系统没有需要额外申请的可选权限。") }
        items(permissions.filterNot { it.required }, key = { it.id }) { OnboardingPermissionCard(it, actions) }
    })
}

/** 分组标题同时提供标题语义和稳定标识，便于无障碍阅读与定向交互测试。 */
@Composable
private fun OnboardingPermissionHeading(title: String, tag: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag(tag).semantics { heading() })
}

/** 权限按钮仅返回标识，授权弹窗和实际授权结果由控制器回填。 */
@Composable
private fun OnboardingPermissionCard(permission: PermissionUiState, actions: SimulSubActions) {
    OutlinedCard(Modifier.fillMaxWidth().testTag("onboarding-permission-${permission.id}")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(permission.title, style = MaterialTheme.typography.titleMedium)
            if (permission.description.isNotBlank()) SupportingText(permission.description)
            FilledTonalButton(onClick = { actions.onPermission(permission.id) }, enabled = !permission.granted,
                modifier = Modifier.heightIn(min = 48.dp)) { Text(if (permission.granted) "已允许" else "允许") }
        }
    }
}

/** 在线只填千问密钥，本地只选供应商、模型并填密钥；下载与连接测试不共用输入锁。 */
@Composable
private fun OnboardingSetup(setup: OnboardingSetupUiState, models: ModelsUiState, online: Boolean,
    sessionBusy: Boolean, actions: SimulSubActions, wide: Boolean) {
    val enabled = !sessionBusy && !setup.testing
    // 本地配置按识别模型、翻译服务排列；平板同样保持上下阅读顺序。
    PageColumns(if (online) wide else false, first = {
        if (!online) item("reazon-download") { OnboardingReazonProgress(models, sessionBusy, actions) }
        item("simple-setup") {
            SettingsCard {
                Column(Modifier.fillMaxWidth().testTag("onboarding-simple-setup"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (!online) Text("翻译服务", style = MaterialTheme.typography.titleMedium)
                    SupportingText(if (online) "千问 3.5 实时听译会直接生成译文，填写千问 Key 即可。"
                        else "将识别出的文字翻译为字幕。")
                    if (!online) {
                        Box(Modifier.testTag("onboarding-provider")) {
                            OptionPicker("翻译供应商", setup.providerId, setup.providers, enabled = enabled,
                                onSelect = actions.onOnboardingProviderChange)
                        }
                        Box(Modifier.testTag("onboarding-model")) {
                            OptionPicker("翻译模型", setup.model, setup.models, enabled = enabled,
                                onSelect = actions.onOnboardingModelChange)
                        }
                    }
                    OnboardingKeyField(setup.key, online, enabled, actions.onOnboardingKeyChange)
                    SupportingText(if (setup.keySaved) "密钥已自动保存。" else "填写后会自动保存，无需另外点击保存。")
                    OutlinedButton(onClick = actions.onOnboardingTest,
                        enabled = enabled && (setup.key.isNotBlank() || setup.keySaved),
                        modifier = Modifier.heightIn(min = 48.dp).testTag("onboarding-test")) {
                        Text(if (setup.testing) "正在测试…" else "测试连接（可选）")
                    }
                    SupportingText(if (online) "无需测试连接也可完成；你可以稍后在设置中继续调整。"
                        else "测试会获取模型列表，并自动保存第一套可用的连接与模型；也可自行选择。")
                    setup.message?.let { MessagePanel(it) }
                }
            }
        }
    })
}

/** 密钥原文直接回调且不进入可保存状态，显隐仅用内存中的布尔值控制。 */
@Composable
private fun OnboardingKeyField(key: String, online: Boolean, enabled: Boolean, onChange: (String) -> Unit) {
    var visible by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    OutlinedTextField(value = key, onValueChange = onChange, enabled = enabled, singleLine = true,
        label = { Text(if (online) "千问 Key" else "翻译 Key") },
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focus.clearFocus(); keyboard?.hide() }),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }, enabled = enabled) {
                Icon(if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    if (visible) "隐藏密钥" else "显示密钥")
            }
        }, modifier = Modifier.fillMaxWidth().testTag("onboarding-key"))
}

/** 仅展示固定 ReazonSpeech 的真实下载与校验状态，失败后可重试，完成后的启用交给控制器。 */
@Composable
private fun OnboardingReazonProgress(models: ModelsUiState, sessionBusy: Boolean, actions: SimulSubActions) {
    val model = models.items.firstOrNull { it.id == "reazonspeech" }
    SettingsCard {
        Column(Modifier.fillMaxWidth().testTag("onboarding-reazon-progress"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("本地识别模型", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(model?.name ?: "ReazonSpeech", style = MaterialTheme.typography.titleLarge)
            SupportingText("用于在设备上识别声音，识别结果交给翻译服务。")
            if (model == null) {
                SupportingText(if (models.loading) "正在读取模型状态…" else "正在准备识别模型…")
            } else {
                SupportingText(model.status.title)
                if (model.status == ModelUiStatus.DOWNLOADING || model.status == ModelUiStatus.VERIFYING) {
                    val progress = model.progress?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
                    if (progress == null || model.status == ModelUiStatus.VERIFYING) LinearProgressIndicator(Modifier.fillMaxWidth())
                    else {
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                        SupportingText("已下载 ${(progress * 100).roundToInt()}%")
                    }
                    SupportingText("下载期间可以先填写翻译 Key，准备完成后会自动启用。")
                }
                model.error?.takeIf { it.isNotBlank() }?.let { MessagePanel(UiMessage(it, isError = true)) }
                if (model.canDownload) Button(onClick = { actions.onDownloadModel(model.id) }, enabled = !sessionBusy,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("onboarding-retry-model")) {
                    Text(if (model.status in listOf(ModelUiStatus.FAILED, ModelUiStatus.CANCELLED)) "重试下载" else "下载模型 · ${model.sizeLabel}")
                }
            }
            models.message?.let { MessagePanel(it) }
        }
    }
}

/** 页脚留在键盘上方；模式页仅保留返回与跳过，避免绕过卡片的显式路径确认。 */
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
        if (step != OnboardingStep.MODE) Button(onClick = onNext, enabled = ready,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("onboarding-next")) {
            Text(if (lastStep) "进入羽译" else "下一步")
        }
    }
}

/** 保留旧调用的目录筛选兼容函数；三页引导不再通过此目录渲染选择列表。 */
internal fun onboardingListeningChoices(profiles: List<ServiceProfileUiState>): List<ServiceProfileUiState> {
    val ids = listOf("local:reazonspeech", "local:sensevoice") + ListeningServiceRepository.BUILT_INS.map { it.id }
    return ids.mapNotNull { id -> profiles.firstOrNull { it.id == id } }
}
