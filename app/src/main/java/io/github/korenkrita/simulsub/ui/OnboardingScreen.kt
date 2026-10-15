package io.github.korenkrita.simulsub.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** 首次引导只渲染权限、翻译接口和识别模型，完成记录及步骤切换由控制器负责。 */
@Composable
internal fun OnboardingScreen(state: SimulSubUiState, actions: SimulSubActions) {
    val onboarding = state.onboarding ?: return
    val step = onboarding.step
    var pendingStep by remember(step) { mutableStateOf<(() -> Unit)?>(null) }
    val permissions = state.live.permissions.withRequiredPermissions().filter { it.required }
    val ready = when (step) {
        OnboardingStep.PERMISSIONS -> permissions.all { it.granted }
        OnboardingStep.API -> onboarding.apiReady && !state.settings.api.hasUnsavedChanges
        OnboardingStep.MODEL -> onboarding.modelReady
    }

    /** 暂存首次离开意图，未保存的接口配置必须由用户明确放弃。 */
    fun requestStep(action: () -> Unit) {
        if (pendingStep != null) return
        if (step == OnboardingStep.API && state.settings.api.hasUnsavedChanges) pendingStep = action
        else action()
    }

    BackHandler(enabled = step != OnboardingStep.PERMISSIONS) { requestStep(actions.onOnboardingBack) }
    SimulSubTheme(state.settings) {
        Surface(Modifier.fillMaxSize()) {
            BoxWithConstraints(
                Modifier.fillMaxSize()
                    .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout)).imePadding(),
                contentAlignment = Alignment.TopCenter,
            ) {
                val wide = maxWidth >= 840.dp
                Column(Modifier.widthIn(max = 960.dp).fillMaxSize()) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("第 ${step.ordinal + 1}/3 步", style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary)
                        Text(when (step) {
                            OnboardingStep.PERMISSIONS -> "允许必要权限"
                            OnboardingStep.API -> "配置翻译 API"
                            OnboardingStep.MODEL -> "下载并启用识别模型"
                        }, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                    }
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        when (step) {
                            OnboardingStep.PERMISSIONS -> OnboardingPermissions(permissions, actions)
                            OnboardingStep.API -> TranslationApiSettings(state.settings.api,
                                state.live.running || state.live.busy, actions, wide, null, onboarding = true)
                            OnboardingStep.MODEL -> OnboardingModels(state.models, actions)
                        }
                    }
                    HorizontalDivider()
                    OnboardingFooter(step, ready,
                        onBack = { requestStep(actions.onOnboardingBack) },
                        onSkip = { requestStep(actions.onOnboardingSkip) },
                        onNext = { requestStep(actions.onOnboardingNext) })
                }
            }
        }
        if (pendingStep != null) {
            AlertDialog(
                onDismissRequest = { pendingStep = null },
                title = { Text("配置尚未保存") },
                text = { Text("离开这一步将放弃本次修改，是否继续编辑？") },
                confirmButton = {
                    TextButton(onClick = {
                        // 先消费待执行动作，防止重组前重复点击恢复草稿或切换步骤。
                        val action = pendingStep
                        pendingStep = null
                        if (action != null) {
                            if (state.settings.api.hasUnsavedChanges) actions.onDiscardApiDraft()
                            action()
                        }
                    }) { Text("放弃修改") }
                },
                dismissButton = { TextButton(onClick = { pendingStep = null }) { Text("继续编辑") } },
            )
        }
    }
}

/** 权限只保留必需项；屏幕共享由系统在每次开始同传时询问，不在引导提前请求。 */
@Composable
private fun OnboardingPermissions(permissions: List<PermissionUiState>, actions: SimulSubActions) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("允许以下权限后即可继续，也可以先跳过。", style = MaterialTheme.typography.bodyLarge)
        }
        items(permissions, key = { it.id }) { permission ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(permission.title, style = MaterialTheme.typography.titleMedium)
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

/** 模型仅展示两张实际目录卡片，避免引导混入评测、删除和来源等后续管理功能。 */
@Composable
private fun OnboardingModels(models: ModelsUiState, actions: SimulSubActions) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("选择一个识别模型，下载完成后点击启用。", style = MaterialTheme.typography.bodyLarge) }
        if (models.items.isEmpty()) item {
            Text(if (models.loading) "正在读取识别模型…" else "暂无可用的识别模型。", style = MaterialTheme.typography.bodyMedium)
        }
        items(models.items.take(2), key = { it.id }) { model -> OnboardingModelCard(model, actions) }
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
private fun OnboardingFooter(step: OnboardingStep, ready: Boolean,
    onBack: () -> Unit, onSkip: () -> Unit, onNext: () -> Unit) {
    Column(Modifier.fillMaxWidth().testTag("onboarding-footer").padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (step != OnboardingStep.PERMISSIONS) TextButton(onClick = onBack,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("上一步") }
            TextButton(onClick = onSkip, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("跳过这步") }
        }
        Button(onClick = onNext, enabled = ready, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(if (step == OnboardingStep.MODEL) "进入羽译" else "下一步")
        }
    }
}
