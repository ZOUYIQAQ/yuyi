package io.github.korenkrita.simulsub

import android.app.Application
import android.Manifest
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.korenkrita.simulsub.models.*
import io.github.korenkrita.simulsub.updates.*
import kotlinx.coroutines.CancellationException
import io.github.korenkrita.simulsub.icons.LauncherIcons
import io.github.korenkrita.simulsub.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** 连接 Compose 页面与原有业务；草稿保存在 ViewModel，旋转和窗口调整不会丢失输入。 */
class AppController internal constructor(application: Application,
    private val releaseSource: ReleaseUpdateSource,
    private val updateClock: () -> Long,
) : AndroidViewModel(application) {
    /** 生产入口保持AndroidViewModel所需的单参数构造器，测试可以注入网络与时钟。 */
    constructor(application: Application) : this(application, GithubReleaseUpdateSource(), System::currentTimeMillis)
    private val prefs = Prefs(application)
    private val models = ModelRepository.get(application)
    private val launcherIcons = LauncherIcons(application)
    private val appearance = application.getSharedPreferences("appearance", 0)
    private val updatePreferences = application.getSharedPreferences("updates", 0)
    private var updateJob: Job? = null
    private var updateGeneration = 0
    private var updateManual = false
    private var appEntered = false
    private var startupUpdateChecked = false
    private val mutableState = MutableStateFlow(SimulSubUiState())
    val state = mutableState.asStateFlow()
    private var probe: ByokTranslator? = null
    private var probeJob: Job? = null
    private var probeService: ByokService? = null
    /** 待确认结果绑定整套已测试配置，编辑、保存或重新测试都会使其失效。 */
    private data class TestedEndpoint(val service: ByokService, val draft: ApiDraftSnapshot,
        val previouslySaved: ByokService?, val url: String, val variantName: String?)
    /** 只比较可编辑输入，测试进度与提示变化不使快照失效。 */
    private data class ApiDraftSnapshot(val url: String, val model: String, val key: String?, val hasSavedKey: Boolean,
        val userAgent: String, val headers: List<Pair<String, String>>, val profileId: String)
    private var testedEndpoint: TestedEndpoint? = null
    private var deletingModelId: String? = null
    private var onboardingStep: OnboardingStep? = if (appearance.getBoolean("onboarding_completed", false)) null else OnboardingStep.PERMISSIONS
    private var generation: Job? = null
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        viewModelScope.launch { refresh() }
    }
    private var permissions = emptyList<PermissionUiState>().withRequiredPermissions()

    init {
        prefs.listen(prefListener)
        mutableState.update { it.copy(settings = it.settings.copy(api = savedApi())) }
        refreshPermissions()
        viewModelScope.launch { models.state.collect { refresh() } }
        viewModelScope.launch { CaptionService.state.collect { refresh() } }
        refreshRecords()
    }

    /** 构建 Activity 无关的动作，系统授权与分享动作由 Activity 注入。 */
    fun actions(
        start: () -> Unit,
        permission: (String) -> Unit,
        share: (File) -> Unit,
        openUrl: (String) -> Unit,
        openUpdateUrl: (String) -> Unit = openUrl,
    ) = SimulSubActions(
        onNavigate = { destination -> mutableState.update { it.copy(destination = destination) } },
        onOnboardingNext = { advanceOnboarding(false) },
        onOnboardingSkip = { advanceOnboarding(true) },
        onOnboardingBack = { onboardingStep = when (onboardingStep) {
            OnboardingStep.MODEL -> OnboardingStep.API
            OnboardingStep.API -> OnboardingStep.PERMISSIONS
            else -> onboardingStep
        }; refresh() },
        onRestartOnboarding = {
            if (CaptionService.running) message("请先停止同传，再重新配置", true)
            else { onboardingStep = OnboardingStep.PERMISSIONS; refresh() }
        },
        onStart = { cancelProbe(); start() },
        onStop = { cancelProbe(); CaptionService.stop(getApplication()) },
        onPermission = permission,
        onSourceLanguage = { id -> prefs.sourceLang = if (id == "auto") null else Lang.of(id); refresh() },
        onTargetLanguage = { id -> Target.parse(id)?.let { prefs.targetLang = it }; refresh() },
        onCustomTargetChange = { value -> mutableState.update { it.copy(live = it.live.copy(customTarget = value)) } },
        onApplyCustomTarget = {
            Target.custom(state.value.live.customTarget)?.let { prefs.targetLang = it; refresh() }
                ?: message("请输入 1～40 字的目标语言名称", true)
        },
        onSelectScene = { id -> prefs.scenes.firstOrNull { it.id == id }?.let { prefs.scene = it }; refresh() },
        onFinalOnly = { prefs.finalOnly = it; refresh() },
        onRefreshModels = { models.refresh() },
        onDownloadModel = { models.download(it) },
        onCancelDownload = { models.cancel(it) },
        onRequestDeleteModel = ::requestDeleteModel,
        onDismissDeleteModel = {
            if (deletingModelId == null) mutableState.update { it.copy(models = it.models.copy(deleteConfirmationId = null)) }
        },
        onConfirmDeleteModel = ::deleteModel,
        onActivateModel = { id ->
            if (CaptionService.running) message("请先停止同传，再切换识别模型", true)
            else {
                models.select(id)
                if (modelSpec(id)?.kind == ModelKind.REAZON && prefs.sourceLang !in listOf(null, Lang.JA, Lang.EN)) prefs.sourceLang = Lang.JA
                refresh()
            }
        },
        onModelSource = { id -> modelSpec(id)?.files?.firstOrNull()?.sources?.firstOrNull()?.let(openUrl) },
        onModelLicense = { id -> openUrl(if (id.contains("sense")) "https://github.com/QwenAudio/SenseVoice" else "https://github.com/reazon-research/ReazonSpeech") },
        onOpenModelBenchmark = { openUrl("https://github.com/KorenKrita/simul-subtitle/blob/97e0a49954fc01029468775d3d93e195adbf146f/docs/asr.md") },
        onCreateScene = { editScene(null) },
        onEditScene = { editScene(it) },
        onSceneDraftChange = { editor -> mutableState.update { it.copy(scenes = it.scenes.copy(editor = editor)) } },
        onAddTerm = { updateEditor { it.copy(terms = it.terms + TermUiState(UUID.randomUUID().toString())) } },
        onRemoveTerm = { id -> updateEditor { it.copy(terms = it.terms.filterNot { row -> row.id == id }) } },
        onGenerateTerms = ::generateSceneTerms,
        onSaveScene = ::saveScene,
        onCloseSceneEditor = { mutableState.update { it.copy(scenes = it.scenes.copy(editor = null)) } },
        onResetScene = { id ->
            Scene.DEFAULTS.firstOrNull { it.id == id }?.let { original ->
                prefs.scenes = prefs.scenes.map { if (it.id == id) original else it }
                editScene(id); refresh()
            }
        },
        onDeleteScene = { id ->
            if (prefs.scenes.any { it.id == id && !it.builtin }) {
                prefs.scenes = prefs.scenes.filterNot { it.id == id }
                mutableState.update { it.copy(scenes = it.scenes.copy(editor = null)) }; refresh()
            }
        },
        onApplyApiPreset = { id ->
            if (!state.value.settings.api.testing && !state.value.settings.api.saving) {
                cancelProbe()
                updateApi { TranslationPresets.apply(it, id) }
            }
        },
        onApiDraftChange = ::editApiDraft,
        onAddHeader = { cancelProbe(); updateApi { it.copy(headers = it.headers + HeaderUiState(UUID.randomUUID().toString())) } },
        onRemoveHeader = { id -> cancelProbe(); updateApi { it.copy(headers = it.headers.filterNot { row -> row.id == id }) } },
        onSelectApiProfile = { id -> changeApiProfile { prefs.useByokProfile(prefs.byokProfiles.firstOrNull { it.id == id } ?: error("翻译配置不存在")) } },
        onAddApiProfile = { name -> changeApiProfile { prefs.addByokProfile(name) } },
        onRenameApiProfile = { name -> changeApiProfile { prefs.renameByokProfile(prefs.byokProfile, name) } },
        onDeleteApiProfile = { changeApiProfile { prefs.deleteByokProfile(prefs.byokProfile) } },
        onSaveApi = { saveApi() },
        onTestApi = ::testApi,
        onApplyTestedApiUrl = ::applyTestedApiUrl,
        onKeepApiUrl = {
            testedEndpoint = null
            updateApi { it.copy(suggestedUrl = "", message = UiMessage("已保留原服务地址")) }
        },
        onDiscardApiDraft = { cancelProbe(); updateApi { savedApi() } },
        onSubtitleStyleChange = ::saveStyle,
        onResetOverlayPosition = { prefs.overlayX = -1; prefs.overlayY = -1; prefs.overlayBaselineY = -1; message("字幕位置将在下次启动同传时恢复默认") },
        onThemeColorChange = { id ->
            if (id == "custom" || ThemePalettes.entries.any { it.id == id }) appearance.edit().putString("color", id).putBoolean("dynamic", false).apply()
            updateLauncherTheme(); refresh()
        },
        onCustomThemeColorChange = { seed ->
            appearance.edit().putInt("custom_color", seed or 0xFF000000.toInt())
                .putString("color", "custom").putBoolean("dynamic", false).apply()
            updateLauncherTheme(); refresh()
        },
        onLauncherIconChange = { choice ->
            runCatching { launcherIcons.apply(choice) }
                .onSuccess { refresh(); message("桌面图标已更新，启动器可能需要片刻刷新") }
                .onFailure { message("图标未能切换，已保留原设置", true) }
        },
        onThemeChange = { prefs.theme = when (it) { UiThemeMode.SYSTEM -> "system"; UiThemeMode.LIGHT -> "light"; UiThemeMode.DARK -> "dark" }; updateLauncherTheme(); refresh() },
        onDynamicColorsChange = { appearance.edit().putBoolean("dynamic", it).apply(); updateLauncherTheme(); refresh() },
        onAdvancedChange = ::saveAdvanced,
        onSaveJevKey = { saveJevKey() },
        onKeepLogsChange = { prefs.keepLogs = it; refresh() },
        onRefreshRecords = { refreshRecords() },
        onShareRecord = { id ->
            val root = getApplication<Application>().getExternalFilesDir(null)
            val file = root?.let { SessionLog.list(it).firstOrNull { f -> f.name == id } }
            if (file == null || SessionLog.isActive(file)) message("这条记录仍在写入或已不可用", true) else share(file)
        },
        onDeleteRecord = ::deleteRecord,
        onDeleteAllRecords = ::deleteAllRecords,
        onCheckUpdate = { checkUpdate(true) },
        onAutoUpdateChange = ::setAutomaticUpdates,
        onOpenUpdate = {
            val release = state.value.settings.updates.let { it.prompt ?: it.latest }
            if (release != null && !state.value.settings.checkingUpdate) {
                laterUpdate()
                openUpdateUrl(release.downloadUrl ?: release.releaseUrl)
            }
        },
        onOpenReleasePage = { openUpdateUrl("$REPOSITORY/releases") },
        onLaterUpdate = ::laterUpdate,
        onSkipUpdate = ::skipUpdate,
        onRestoreUpdateReminders = {
            updatePreferences.edit().remove("skipped_version").remove("later_version").remove("later_until").apply()
            refreshUpdatePreferences()
        },
        onOpenRepository = { openUrl(REPOSITORY) },
        onOpenUpstreamRepository = { openUrl("https://github.com/KorenKrita/simul-subtitle") },
        onDismissMessage = { mutableState.update { it.copy(message = null) } },
    )

    /** 开屏和返回前台时恢复真实入口，同时更新界面中的图标偏好。 */
    fun restoreLauncherIcon() {
        runCatching { launcherIcons.reconcile() }
            .onFailure { message("桌面图标暂时无法恢复，可在外观中重新选择", true) }
        refresh()
    }

    /** 主题操作才同步自动纯色，字幕刷新不触发系统组件切换。 */
    private fun updateLauncherTheme() {
        runCatching { launcherIcons.syncTheme() }
            .onFailure { message("桌面图标颜色暂未更新，可在外观中重新保存", true) }
    }

    /** 接收系统授权状态，只展示系统实际授予的结果。 */
    fun setPermissions(value: List<PermissionUiState>) { permissions = value.withRequiredPermissions(); refresh() }

    /** 冷启动与授权返回共用系统实值；不缓存本次会话的屏幕捕获授权。 */
    fun refreshPermissions() {
        val context = getApplication<Application>().applicationContext
        setPermissions(emptyList<PermissionUiState>().withRequiredPermissions().map { permission ->
            permission.copy(granted = when (permission.id) {
                "audio" -> context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                "overlay" -> Settings.canDrawOverlays(context)
                else -> false
            })
        } + PermissionUiState("notifications", "下载与运行通知", "显示模型下载进度和字幕服务控制。",
            Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
            required = false))
    }

    /** 引导不会隐式保存或下载；未准备好可明确跳过，最后一步结束后不再自动出现。 */
    private fun advanceOnboarding(skip: Boolean) {
        val step = onboardingStep ?: return
        val ready = when (step) {
            OnboardingStep.PERMISSIONS -> permissions.filter { it.required }.all { it.granted }
            OnboardingStep.API -> prefs.byokReady && !state.value.settings.api.hasUnsavedChanges
            OnboardingStep.MODEL -> models.selectedPaths() != null
        }
        if (!skip && !ready) return
        onboardingStep = when (step) {
            OnboardingStep.PERMISSIONS -> OnboardingStep.API
            OnboardingStep.API -> OnboardingStep.MODEL
            OnboardingStep.MODEL -> null
        }
        if (onboardingStep == null) {
            appearance.edit().putBoolean("onboarding_completed", true).apply()
            mutableState.update { it.copy(destination = UiDestination.LIVE) }
        }
        refresh()
        if (onboardingStep == null && appEntered) checkStartupUpdate()
    }

    /** 给界面发布具体操作结果。 */
    fun message(text: String, error: Boolean = false) { mutableState.update { it.copy(message = UiMessage(text, error)) } }

    /** 取得固定目录内的模型，拒绝来自页面的未知标识。 */
    private fun modelSpec(id: String): ModelSpec? = models.state.value.entries.firstOrNull { it.spec.id == id }?.spec

    /** 先展示真实模型范围与后果，未经页面确认不派发磁盘删除。 */
    private fun requestDeleteModel(id: String) {
        val item = state.value.models.items.firstOrNull { it.id == id } ?: return
        if (!item.canDelete) { message(item.deleteBlockedReason ?: "当前模型不可删除", true); return }
        mutableState.update { it.copy(models = it.models.copy(deleteConfirmationId = id, message = null)) }
    }

    /** 用户确认后异步删除，仓库再次核对运行与下载状态，结果始终重新映射真实磁盘状态。 */
    private fun deleteModel(id: String) {
        if (deletingModelId != null || state.value.models.deleteConfirmationId != id) return
        deletingModelId = id
        refresh()
        viewModelScope.launch {
            try {
                val result = models.delete(id)
                mutableState.update { it.copy(models = it.models.copy(
                    deleteConfirmationId = if (result.deleted) null else id,
                    message = UiMessage(if (result.deleted) "模型已删除，需要时可重新下载并启用" else result.error ?: "模型删除失败", !result.deleted),
                )) }
            } finally {
                deletingModelId = null
                refresh()
            }
        }
    }

    /** 更新持久化状态，保持用户尚未保存的 API 和场景草稿。 */
    private fun refresh() {
        val library = models.state.value
        val modelWorkBusy = library.entries.any { it.status in listOf(ModelStatus.QUEUED, ModelStatus.DOWNLOADING, ModelStatus.VERIFYING) }
        val session = CaptionService.state.value
        if (probe != null && (session.running || prefs.byokService() != probeService)) cancelProbe()
        if (testedEndpoint != null && (session.running || prefs.byokService() != testedEndpoint?.previouslySaved)) cancelProbe()
        val selected = library.entries.firstOrNull { it.spec.id == library.selectedId && it.status == ModelStatus.READY }
        val scenes = prefs.scenes
        val sourceLanguages = if (selected?.spec?.kind == ModelKind.SENSEVOICE) Lang.entries.toList() else listOf(Lang.JA, Lang.EN)
        val ready = selected != null && prefs.byokReady
        val status = when {
            session.running -> "正在同传"
            selected == null -> "先选择一个识别模型"
            !prefs.byokReady -> "还差一步：配置翻译服务"
            permissions.any { it.required && !it.granted } -> "开启权限后即可开始"
            else -> "准备就绪"
        }
        mutableState.update { old ->
            old.copy(
                onboarding = onboardingStep?.let { OnboardingUiState(it, prefs.byokReady, selected != null) },
                live = old.live.copy(
                    running = session.running,
                    canStart = ready && permissions.none { it.required && !it.granted },
                    statusTitle = status,
                    statusDetail = if (session.running) session.status.ifBlank { "正在听取目标应用播放的声音" } else when {
                        selected == null -> "模型不会随安装包下载。前往模型库，选择适合你的语言和设备的模型。"
                        !prefs.byokReady -> "语音识别在本地完成，识别文本交给你配置的服务翻译。"
                        else -> "${selected.spec.name} · 音频仅在设备上识别"
                    },
                    modelLabel = selected?.spec?.name ?: "未启用模型",
                    translationLabel = prefs.byokModel.let { model ->
                        if (model.isNotBlank()) "${prefs.byokProfile.name} · $model"
                        else if (session.running) "继续使用会话中的服务" else "未配置"
                    },
                    sourceId = prefs.sourceLang?.code ?: "auto",
                    targetId = prefs.targetLang.stored,
                    sourceOptions = sourceLanguages.map { UiOption(it.code, it.zh) } + UiOption("auto", "自动识别"),
                    targetOptions = Target.PRESETS.map { UiOption(it.stored, it.zh) } +
                        (if (prefs.targetLang.lang == null) listOf(UiOption(prefs.targetLang.stored, prefs.targetLang.zh)) else emptyList()),
                    sceneId = prefs.scene.id,
                    sceneOptions = scenes.map { UiOption(it.id, it.label) },
                    finalOnly = prefs.finalOnly,
                    showOriginal = prefs.showJa,
                    showStats = prefs.showStats,
                    permissions = permissions,
                    captions = session.captions.map { CaptionUiState("${it.segmentId}", it.ja, it.zh, !it.isFinal, "识别 ${it.timing.asrMs} 毫秒 · 翻译 ${it.timing.mtMs} 毫秒") },
                ),
                models = old.models.copy(items = library.entries.map { entry -> modelUi(entry, library.selectedId, session.running, modelWorkBusy, deletingModelId ?: library.deletingId) },
                    deletingId = deletingModelId ?: library.deletingId),
                scenes = old.scenes.copy(items = scenes.map { SceneUiState(it.id, it.label, it.background.orEmpty(), it.terms.size, it.termsLang.zh, it.builtin, it.summary(prefs.targetLang)) }, selectedId = prefs.scene.id),
                settings = old.settings.copy(
                    api = old.settings.api.copy(canTest = !session.running && !old.settings.api.testing,
                        hasUnsavedChanges = isApiDirty(old.settings.api)),
                    theme = when (prefs.theme) { "dark" -> UiThemeMode.DARK; "light" -> UiThemeMode.LIGHT; else -> UiThemeMode.SYSTEM },
                    dynamicColors = appearance.getBoolean("dynamic", false),
                    themeColorId = appearance.getString("color", ThemePalettes.DEFAULT_ID) ?: ThemePalettes.DEFAULT_ID,
                    customThemeSeed = appearance.getInt("custom_color", ThemePalettes.DEFAULT_CUSTOM_SEED),
                    launcherIcon = launcherIcons.current,
                    updates = updatePreferenceState(old.settings.updates),
                    permissions = permissions,
                    subtitle = styleState(),
                    keepLogs = prefs.keepLogs,
                    advanced = old.settings.advanced.copy(
                        contextOn = prefs.contextOn, contextSentences = prefs.contextSentences,
                        maxContextSentences = Pipeline.MAX_PREVIOUS_SENTENCES, dedupeJoin = prefs.dedupeJoin,
                        jevOn = prefs.jevOn, jevCheck = prefs.jevCheck, jevProviderId = prefs.jevProvider.name,
                        jevProviders = JevProvider.entries.map { UiOption(it.name, it.label) },
                        jevNeedsKey = prefs.jevProvider.needsKey,
                        jevHasSavedKey = !prefs.jevKey(prefs.jevProvider).isNullOrBlank(),
                        jevKey = old.settings.advanced.jevKey.takeIf { old.settings.advanced.jevProviderId == prefs.jevProvider.name }
                            ?: prefs.jevKey(prefs.jevProvider),
                        jevMessage = old.settings.advanced.jevMessage.takeIf { old.settings.advanced.jevProviderId == prefs.jevProvider.name },
                    ), version = BuildConfig.VERSION_NAME,
                ),
            )
        }
    }

    /** 将后台状态映射为明确的安装、校验、错误和可执行操作。 */
    private fun modelUi(entry: ModelEntry, selectedId: String?, running: Boolean, workBusy: Boolean, deletingId: String?): ModelUiState {
        val status = when (entry.status) {
            ModelStatus.NOT_INSTALLED -> ModelUiStatus.NOT_INSTALLED
            ModelStatus.QUEUED, ModelStatus.DOWNLOADING -> ModelUiStatus.DOWNLOADING
            ModelStatus.VERIFYING -> ModelUiStatus.VERIFYING
            ModelStatus.READY -> if (entry.spec.id == selectedId) ModelUiStatus.ACTIVE else ModelUiStatus.READY
            ModelStatus.FAILED -> ModelUiStatus.FAILED
            ModelStatus.CANCELLED -> ModelUiStatus.CANCELLED
        }
        return ModelUiState(
            id = entry.spec.id, name = entry.spec.name, description = if (entry.spec.kind == ModelKind.REAZON) "日英双语，下载体积较小，适合日语视频与直播。" else "中英日韩粤五语言，支持标点与数字规整，下载体积较大。",
            languages = entry.spec.languages.joinToString("、"), sizeLabel = size(entry.spec.sizeBytes),
            license = (if (entry.spec.kind == ModelKind.SENSEVOICE) "FunASR 模型协议 v1.1" else "ReazonSpeech 上游 Apache-2.0") + " · VAD：MIT", sourceLabel = entry.source?.let { android.net.Uri.parse(it).host }.orEmpty().ifBlank { "优先国内源，失败时切换备用源" },
            status = status, progress = if (entry.status == ModelStatus.QUEUED) null else entry.progress,
            downloadedLabel = "${size(entry.downloadedBytes)} / ${size(entry.totalBytes)}", error = entry.error,
            canDownload = entry.status in listOf(ModelStatus.NOT_INSTALLED, ModelStatus.FAILED, ModelStatus.CANCELLED),
            canCancel = entry.status in listOf(ModelStatus.QUEUED, ModelStatus.DOWNLOADING, ModelStatus.VERIFYING),
            canActivate = entry.status == ModelStatus.READY && entry.spec.id != selectedId && !running,
            verificationDetail = "逐文件核对完整大小与 SHA-256，校验通过后才能启用。" + if (running) "切换模型前请停止同传。" else "",
            sourceUrl = entry.spec.files.firstOrNull()?.sources?.firstOrNull().orEmpty(),
            canDelete = entry.hasLocalFiles && !running && !workBusy && deletingId == null,
            deletionScope = if (entry.hasLocalFiles) models.deletionScope(entry.spec.id) else "",
            deleteBlockedReason = when {
                running -> "请先停止同传再删除模型"
                workBusy -> "下载或校验结束后可删除模型"
                deletingId != null -> "正在删除模型，请稍候"
                else -> null
            },
        )
    }

    /** 格式化真实字节数，使用二进制单位。 */
    private fun size(bytes: Long): String = "%.1f MiB".format(bytes / 1048576.0)

    /** 从加密存储恢复已保存值，保存与页面重建后仍能核对密钥，避免空白被误认为保存失败。 */
    private fun savedApi() = TranslationApiUiState(
        profileId = prefs.byokProfile.id, profiles = prefs.byokProfiles.map { UiOption(it.id, it.name) },
        presetId = TranslationPresets.match(prefs.byokUrl),
        url = prefs.byokUrl, model = prefs.byokModel, key = prefs.byokKey, hasSavedKey = !prefs.byokKey.isNullOrBlank(),
        hasSavedConfiguration = prefs.byokReady,
        userAgent = prefs.byokUserAgent,
        headers = prefs.byokHeaders.map { HeaderUiState(UUID.randomUUID().toString(), it.first, it.second) },
        resolvedUrl = ByokTranslator.chatUrl(prefs.byokUrl).second.orEmpty(),
    )

    /** 配置管理先处理未保存草稿并取消旧测试，之后回填所选配置自己的字段与凭据。 */
    private fun changeApiProfile(change: () -> Unit) {
        if (state.value.settings.api.hasUnsavedChanges) {
            updateApi { it.copy(message = UiMessage("请先保存或放弃当前修改", true)) }
            return
        }
        cancelProbe()
        runCatching(change).onFailure { error ->
            updateApi { it.copy(message = UiMessage(error.message ?: "配置操作失败", true)) }
            return
        }
        updateApi { savedApi() }
        refresh()
    }

    /** 更新 API 草稿，不触发网络或改动已保存配置。 */
    private fun updateApi(change: (TranslationApiUiState) -> TranslationApiUiState) {
        mutableState.update {
            val draft = change(it.settings.api)
            it.copy(settings = it.settings.copy(api = draft.copy(hasUnsavedChanges = isApiDirty(draft))))
        }
    }

    /** 只比较真正会保存的字段，输入框的空密钥与保留已存密钥遵循各自语义。 */
    private fun isApiDirty(draft: TranslationApiUiState): Boolean {
        val effectiveKey = draft.key?.trim() ?: if (draft.hasSavedKey) prefs.byokKey.orEmpty() else ""
        return draft.profileId != prefs.byokProfile.id || draft.url.trim() != prefs.byokUrl.trim() || draft.model.trim() != prefs.byokModel.trim() ||
            effectiveKey != prefs.byokKey.orEmpty() || draft.userAgent.trim() != prefs.byokUserAgent ||
            draft.headers.map { it.name.trim() to it.value } != prefs.byokHeaders
    }

    /** 按预设使用的完整聊天端点比较地址，换路径清空旧凭据，等价地址保留。 */
    private fun editApiDraft(draft: TranslationApiUiState) {
        val previous = state.value.settings.api
        if (draft.profileId != previous.profileId) return
        val endpointChanged = previous.url.isNotBlank() &&
            ByokTranslator.chatUrl(previous.url).second != ByokTranslator.chatUrl(draft.url).second
        cancelProbe()
        updateApi {
            draft.copy(
                testing = false, canTest = !CaptionService.running,
                key = if (endpointChanged) "" else draft.key,
                hasSavedKey = if (endpointChanged) false else draft.hasSavedKey,
                headers = if (endpointChanged) emptyList() else draft.headers,
                userAgent = if (endpointChanged) "" else draft.userAgent,
                suggestedUrl = "",
                message = if (endpointChanged) UiMessage("服务地址已变更，请填写对应密钥；旧密钥和请求头不会带入。")
                    else draft.message.takeUnless { it == previous.message },
            )
        }
    }

    /** 校验整套翻译配置并一次保存，防止运行会话读到半套配置。 */
    private fun saveApi(): Boolean {
        val draft = state.value.settings.api
        if (draft.profileId != prefs.byokProfile.id) return false
        val (error, url) = ByokTranslator.chatUrl(draft.url)
        val headers = draft.headers.map { it.name.trim() to it.value }
        val headerError = ByokService.badAdvanced(draft.userAgent, headers)
        val problem = error ?: if (url == null) "翻译地址无效" else if (draft.model.isBlank()) "请填写模型 ID" else headerError
        if (problem != null) { updateApi { it.copy(message = UiMessage(problem, true)) }; return false }
        cancelProbe()
        // 新端点没有可保留的密钥时，即使输入框留空也必须清除旧端点凭据。
        val key = draft.key ?: if (draft.hasSavedKey) null else ""
        runCatching { prefs.saveByok(draft.url, draft.model, key, draft.userAgent, headers) }
            .onFailure { error ->
                updateApi { it.copy(message = UiMessage("翻译配置保存失败：${error.message ?: "请重试"}", true)) }
                return false
            }
        updateApi { savedApi().copy(message = UiMessage("翻译配置已保存")) }
        refresh()
        return true
    }

    /** 提取本次草稿快照，保留密钥的明确填写、清空和保留三种状态。 */
    private fun apiSnapshot(draft: TranslationApiUiState) = ApiDraftSnapshot(draft.url.trim(), draft.model.trim(),
        draft.key, draft.hasSavedKey, draft.userAgent.trim(), draft.headers.map { it.name.trim() to it.value }, draft.profileId)

    /** 用临时配置测试，不保存草稿或探测结果；只有用户确认才写入设置。 */
    private fun testApi() {
        if (CaptionService.running || probe != null) return
        val draft = apiSnapshot(state.value.settings.api)
        val (error, url) = ByokTranslator.chatUrl(draft.url)
        val problem = error ?: if (draft.model.isBlank()) "请填写模型 ID" else ByokService.badAdvanced(draft.userAgent, draft.headers)
        if (problem != null || url == null) { updateApi { it.copy(message = UiMessage(problem ?: "翻译地址无效", true)) }; return }
        cancelProbe()
        val previous = prefs.byokService()
        val key = draft.key?.trim() ?: if (draft.hasSavedKey) prefs.byokKey.orEmpty() else ""
        val service = ByokService(url, draft.model, key, draft.userAgent, draft.headers)
        val translator = ByokTranslator(service, byokSystem(prefs.scene, prefs.sourceLang, prefs.targetLang), null, maxTokens = mtMaxTokens(prefs.targetLang))
        probe = translator
        probeService = previous
        updateApi { it.copy(testing = true, canTest = false, suggestedUrl = "", message = UiMessage("正在测试草稿地址与关闭思考参数，最多 2 个同源地址、12 次请求，服务商可能计费；不会自动保存。")) }
        probeJob = viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) { runCatching { translator.probeConnection(draft.url) }.getOrNull() }
            if (probe === translator) {
                if (CaptionService.running || prefs.byokService() != previous || apiSnapshot(state.value.settings.api) != draft) {
                    cancelProbe(); return@launch
                }
                probe = null
                probeService = null
                probeJob = null
                val testedUrl = outcome?.url
                if (testedUrl == null) {
                    updateApi { it.copy(testing = false, canTest = true, suggestedUrl = "", message = UiMessage(
                        outcome?.lastResult?.let { result -> ByokTranslator.why(result) }
                            ?: outcome?.error ?: "连接测试失败，请检查服务配置后重试", true)) }
                } else {
                    testedEndpoint = TestedEndpoint(service, draft, previous, testedUrl, outcome.variantName)
                    val notice = if (outcome.variantName == ByokTranslator.NONE_WORKED)
                        "连接可用，但未能关闭思考，字幕延迟可能较大" else "连接测试通过"
                    updateApi { it.copy(testing = false, canTest = true, suggestedUrl = testedUrl,
                        message = UiMessage("$notice；可在下方替换并保存，不操作即保持原配置。")) }
                }
            }
        }
    }

    /** 只保存仍对应当前草稿的成功结果，保留本次真实测试的凭据，不重新请求网络。 */
    private fun applyTestedApiUrl() {
        val tested = testedEndpoint ?: return
        if (CaptionService.running || probe != null || prefs.byokService() != tested.previouslySaved ||
            apiSnapshot(state.value.settings.api) != tested.draft || state.value.settings.api.suggestedUrl != tested.url) {
            cancelProbe()
            updateApi { it.copy(message = UiMessage("配置已变更或同传正在运行，请停止后重新测试", true)) }
            return
        }
        testedEndpoint = null
        prefs.saveByok(tested.url, tested.service.model, tested.service.key, tested.service.userAgent, tested.service.headers)
        tested.variantName?.let { prefs.setByokVariant(tested.url, tested.service.model, it) }
        updateApi { savedApi().copy(message = UiMessage("已替换并保存测试通过的地址")) }
        refresh()
    }

    /** 先使旧结果失效并复位界面，再终止网络和协程；已发送的请求无法撤回。 */
    private fun cancelProbe() {
        testedEndpoint = null
        val translator = probe
        probe = null
        probeService = null
        val job = probeJob
        probeJob = null
        updateApi { it.copy(testing = false, canTest = !CaptionService.running, suggestedUrl = "", message = if (it.testing) UiMessage("连接测试已取消") else it.message) }
        translator?.cancel()
        job?.cancel()
    }

    /** 提取与悬浮字幕相同的样式配置。 */
    private fun styleState(): SubtitleStyleUiState {
        val range = prefs.sentenceRange()
        return SubtitleStyleUiState(
            textSp = prefs.textSp, backgroundAlpha = prefs.bgAlpha, widthFraction = prefs.widthFrac,
            heightFraction = prefs.heightFrac, keepSentences = range.first, maxSentences = prefs.captionSentenceCount,
            keepChars = prefs.keepChars.coerceIn(10, 300), maxLines = 0, limitMode = prefs.captionLimitMode,
            showOriginal = prefs.showJa, showStats = prefs.showStats, paletteId = prefs.palette,
            reserveDraftSpace = prefs.reserveDraftSpace,
            minTextSp = CaptionOverlayView.MIN_SP, maxTextSp = CaptionOverlayView.MAX_SP,
            palettes = CaptionOverlayView.PALETTES.map { CaptionPaletteUiState(it.id, it.label, it.zh, it.bg, it.ja) },
        )
    }

    /** 修改字幕样式立即持久化，服务通过原有监听同步变化。 */
    private fun saveStyle(style: SubtitleStyleUiState) {
        prefs.setStyle(style.textSp.coerceIn(CaptionOverlayView.MIN_SP, CaptionOverlayView.MAX_SP), style.backgroundAlpha.coerceIn(0f, 1f))
        prefs.widthFrac = style.widthFraction.coerceIn(Prefs.MIN_WIDTH, 1f)
        prefs.heightFrac = style.heightFraction.coerceIn(0f, CaptionService.MAX_CAPTION_FRAC)
        prefs.setCaptionRetention(style.limitMode, style.maxSentences, style.keepChars)
        prefs.showJa = style.showOriginal; prefs.showStats = style.showStats; prefs.palette = style.paletteId
        prefs.reserveDraftSpace = style.reserveDraftSpace
        refresh()
    }

    /** 保存高级翻译参数，运行中固定的 Jev 开关保持原会话值。 */
    private fun saveAdvanced(value: AdvancedUiState) {
        val provider = runCatching { JevProvider.valueOf(value.jevProviderId) }.getOrNull() ?: prefs.jevProvider
        val switched = provider.name != state.value.settings.advanced.jevProviderId || provider != prefs.jevProvider
        prefs.contextOn = value.contextOn; prefs.contextSentences = value.contextSentences; prefs.dedupeJoin = value.dedupeJoin
        if (!CaptionService.running) { prefs.jevOn = value.jevOn; prefs.jevCheck = value.jevCheck }
        prefs.jevProvider = provider
        val draft = if (switched) value.copy(jevKey = null, jevMessage = null) else value
        mutableState.update { it.copy(settings = it.settings.copy(advanced = draft)) }; refresh()
    }

    /** 仅在用户点保存时更新Jev密钥，保存后回填本服务的值，避免空框造成提交失败的误解。 */
    private fun saveJevKey() {
        val draft = state.value.settings.advanced
        val provider = prefs.jevProvider
        if (draft.jevProviderId != provider.name || !provider.needsKey) return
        draft.jevKey?.let { prefs.setJevKey(provider, it) } ?: return
        mutableState.update { it.copy(settings = it.settings.copy(advanced = it.settings.advanced.copy(
            jevKey = prefs.jevKey(provider).orEmpty(),
            jevMessage = UiMessage(if (draft.jevKey.isNullOrBlank()) "辅助服务密钥已清空" else "辅助服务密钥已保存")))) }
        refresh()
    }

    /** 打开持久化场景的可编辑草稿。 */
    private fun editScene(id: String?) {
        val scene = prefs.scenes.firstOrNull { it.id == id }
        val editor = SceneEditorUiState(
            id = scene?.id ?: UUID.randomUUID().toString(), name = scene?.label.orEmpty(), background = scene?.background.orEmpty(),
            termsLanguageId = scene?.termsLang?.stored ?: prefs.targetLang.stored,
            languageOptions = Target.PRESETS.map { UiOption(it.stored, it.zh) } +
                (if (prefs.targetLang.lang == null) listOf(UiOption(prefs.targetLang.stored, prefs.targetLang.zh)) else emptyList()),
            terms = scene?.terms?.map { TermUiState(UUID.randomUUID().toString(), it.first, it.second) }.orEmpty(),
            builtin = scene?.builtin == true, canGenerate = prefs.byokReady,
        )
        mutableState.update { it.copy(scenes = it.scenes.copy(editor = editor)) }
    }

    /** 更新场景草稿，不影响当前同传。 */
    private fun updateEditor(change: (SceneEditorUiState) -> SceneEditorUiState) {
        mutableState.update { root -> root.copy(scenes = root.scenes.copy(editor = root.scenes.editor?.let(change))) }
    }

    /** 通过原有严格字段校验保存场景，失败时保留完整草稿。 */
    private fun saveScene() {
        val editor = state.value.scenes.editor ?: return
        val target = Target.parse(editor.termsLanguageId) ?: prefs.targetLang
        val (scene, error) = Scene.edit(editor.id ?: UUID.randomUUID().toString(), editor.name, editor.background, editor.terms.map { it.original to it.translated }, target)
        if (scene == null) { updateEditor { it.copy(message = UiMessage(error ?: "场景保存失败", true)) }; return }
        prefs.scenes = prefs.scenes.filterNot { it.id == scene.id } + scene
        mutableState.update { it.copy(scenes = it.scenes.copy(editor = null)) }; refresh(); message("场景与术语已保存")
    }

    /** 使用用户自己的翻译服务生成术语，结果仅写入仍打开的同一份草稿。 */
    private fun generateSceneTerms() {
        val editor = state.value.scenes.editor ?: return
        val service = prefs.byokService() ?: return message("请先保存翻译配置", true)
        if (generation?.isActive == true) return
        val count = editor.generationCount.toIntOrNull()?.coerceIn(1, MAX_GENERATE) ?: return message("请输入有效的生成数量", true)
        updateEditor { it.copy(generating = true, message = UiMessage("正在生成术语，服务商可能计费…")) }
        generation = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { generateTerms(service, prefs.byokVariant(service.url, service.model), { prefs.setByokVariant(service.url, service.model, it) }, editor.name, editor.background, editor.terms.map { it.original }.toSet(), count, prefs.sourceLang, Target.parse(editor.termsLanguageId) ?: prefs.targetLang) }
            }
            if (state.value.scenes.editor?.id != editor.id) return@launch
            result.fold(onSuccess = { (terms, error) ->
                updateEditor { current -> current.copy(generating = false, terms = current.terms + terms.orEmpty().map { TermUiState(UUID.randomUUID().toString(), it.first, it.second) }, message = UiMessage(error ?: "已生成 ${terms.orEmpty().size} 条术语，请检查后保存", error != null)) }
            }, onFailure = { error -> updateEditor { it.copy(generating = false, message = UiMessage(error.message ?: "生成失败", true)) } })
        }
    }

    /** 在后台读取真实日志索引，不阻塞页面滚动。 */
    private fun refreshRecords(result: UiMessage? = state.value.settings.recordsMessage) {
        mutableState.update { it.copy(settings = it.settings.copy(recordsLoading = true)) }
        viewModelScope.launch {
            val records = withContext(Dispatchers.IO) {
                val root = getApplication<Application>().getExternalFilesDir(null)
                root?.let { SessionLog.list(it) }.orEmpty().map {
                    val active = SessionLog.isActive(it)
                    UsageRecordUiState(it.name, it.name.removePrefix("session-").removeSuffix(".jsonl"), java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it.lastModified())), "${size(it.length())} · ${if (active) "正在记录，稍后可分享或删除" else if (SessionLog.complete(it)) "完整记录" else "未完整结束"}", active = active)
                }
            }
            mutableState.update { it.copy(settings = it.settings.copy(records = records, recordsLoading = false, recordsMessage = result)) }
        }
    }

    /** 仅删除固定日志索引中的记录，活动记录由现有存储接口再次保护。 */
    private fun deleteRecord(id: String) {
        changeRecords { root ->
            val file = SessionLog.list(root).firstOrNull { it.name == id }
            when {
                file == null -> UiMessage("这条记录已不存在，请刷新列表", true)
                SessionLog.delete(file) -> UiMessage("记录已删除")
                SessionLog.isActive(file) -> UiMessage("这条记录仍在写入，请在会话结束后删除", true)
                else -> UiMessage("记录删除失败，请稍后重试", true)
            }
        }
    }

    /** 清空非活动记录，告知仍被写入或删除失败而保留的记录数。 */
    private fun deleteAllRecords() {
        changeRecords { root ->
            val left = SessionLog.deleteAll(root)
            if (left == 0) UiMessage("使用记录已清空")
            else UiMessage("已清理可删除的记录，保留 $left 条正在写入或暂时无法删除的记录")
        }
    }

    /** 串行处理记录删除与刷新，在后台执行磁盘操作并反馈真实结果。 */
    private fun changeRecords(change: (File) -> UiMessage) {
        if (state.value.settings.recordsLoading) return
        mutableState.update { it.copy(settings = it.settings.copy(recordsLoading = true)) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().getExternalFilesDir(null)?.let(change)
                        ?: UiMessage("无法访问使用记录目录，请稍后重试", true)
                }.getOrElse { UiMessage("记录清理失败，请稍后重试", true) }
            }
            refreshRecords(result)
        }
    }

    /** 每次Activity会话只检查一次；旋转与系统授权返回会复用同一个控制器。 */
    fun onAppStarted() { appEntered = true; checkStartupUpdate() }

    /** 首次引导结束后才检查，关闭开关时不发网络请求。 */
    private fun checkStartupUpdate() {
        if (startupUpdateChecked || onboardingStep != null) return
        startupUpdateChecked = true
        if (updatePreferences.getBoolean("auto", true)) checkUpdate(false)
    }

    /** 把提醒偏好叠加到已有结果，字幕刷新不会清除检查状态。 */
    private fun updatePreferenceState(old: AppUpdateUiState): AppUpdateUiState {
        val checked = updatePreferences.getLong("last_checked", 0L)
        return old.copy(autoCheck = updatePreferences.getBoolean("auto", true),
            skippedVersion = updatePreferences.getString("skipped_version", "").orEmpty(),
            laterVersion = updatePreferences.getString("later_version", "").orEmpty(),
            laterUntil = updatePreferences.getLong("later_until", 0L),
            lastChecked = if (checked > 0) java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(checked)) else "")
    }

    /** 只刷新更新分区，不改翻译草稿或导航位置。 */
    private fun refreshUpdatePreferences() {
        mutableState.update { it.copy(settings = it.settings.copy(updates = updatePreferenceState(it.settings.updates))) }
    }

    /** 停用自动检查同时作废其请求；手动发起的检查不受自动开关约束。 */
    private fun setAutomaticUpdates(enabled: Boolean) {
        updatePreferences.edit().putBoolean("auto", enabled).apply()
        if (!enabled && !updateManual) {
            updateGeneration++
            updateJob?.cancel(); updateJob = null
            mutableState.update { it.copy(settings = it.settings.copy(checkingUpdate = false)) }
        }
        mutableState.update { it.copy(settings = it.settings.copy(
            updates = updatePreferenceState(it.settings.updates).copy(prompt = if (enabled) it.settings.updates.prompt else null))) }
    }

    /** 稍后提醒以版本为单位，新的发行版不会被旧版的延期隐藏。 */
    private fun laterUpdate() {
        val release = state.value.settings.updates.let { it.prompt ?: it.latest } ?: return
        updatePreferences.edit().putString("later_version", release.versionName)
            .putLong("later_until", updateClock() + 24 * 60 * 60 * 1000L).apply()
        mutableState.update { it.copy(settings = it.settings.copy(updates = updatePreferenceState(it.settings.updates).copy(prompt = null))) }
    }

    /** 跳过仅作用于当前版本，手动检查仍可再次查看。 */
    private fun skipUpdate() {
        val release = state.value.settings.updates.let { it.prompt ?: it.latest } ?: return
        updatePreferences.edit().putString("skipped_version", release.versionName).apply()
        mutableState.update { it.copy(settings = it.settings.copy(updates = updatePreferenceState(it.settings.updates).copy(prompt = null))) }
    }

    /** 检查本Fork正式发行版，自动失败不打断使用，手动结果在更新页明确展示。 */
    private fun checkUpdate(manual: Boolean) {
        if (updateJob?.isActive == true) { if (manual) updateManual = true; return }
        if (!manual && !updatePreferences.getBoolean("auto", true)) return
        val generation = ++updateGeneration
        updateManual = manual
        mutableState.update { it.copy(settings = it.settings.copy(checkingUpdate = true,
            updateMessage = "正在检查更新…", updates = it.settings.updates.copy(latest = null, prompt = null, checkFailed = false))) }
        updateJob = viewModelScope.launch {
            try {
                val release = releaseSource.latest()
                if (generation != updateGeneration) return@launch
                val newer = release != null && ReleaseVersions.isNewer(release.versionName, BuildConfig.VERSION_NAME)
                val ui = release?.takeIf { newer }?.let { UpdateReleaseUiState(it.versionName, it.notes, it.releaseUrl, it.downloadUrl) }
                val current = updatePreferenceState(state.value.settings.updates)
                val offer = newer && (updateManual || (current.autoCheck && current.skippedVersion != release!!.versionName &&
                    (current.laterVersion != release.versionName || updateClock() >= current.laterUntil)))
                updatePreferences.edit().putLong("last_checked", updateClock()).apply()
                val text = when {
                    release == null -> "此 Fork 尚未发布正式版本，当前版本 ${BuildConfig.VERSION_NAME}"
                    !newer -> "已是最新版本（${BuildConfig.VERSION_NAME}）"
                    else -> "发现新版本 ${release.versionName}"
                }
                mutableState.update { it.copy(settings = it.settings.copy(updateMessage = text,
                    updates = updatePreferenceState(it.settings.updates).copy(latest = ui, prompt = if (offer) ui else null, checkFailed = false))) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (generation == updateGeneration) mutableState.update { it.copy(settings = it.settings.copy(
                    updateMessage = "暂时无法检查更新，请稍后重试，或打开 GitHub 发布页查看。",
                    updates = it.settings.updates.copy(checkFailed = true))) }
            } finally {
                if (generation == updateGeneration) {
                    updateManual = false
                    mutableState.update { it.copy(settings = it.settings.copy(checkingUpdate = false)) }
                }
            }
        }
    }

    /** 销毁控制器时取消探测，避免离开应用后继续发送连接测试请求。 */
    override fun onCleared() { prefs.unlisten(prefListener); cancelProbe(); generation?.cancel(); updateJob?.cancel(); super.onCleared() }

    companion object { const val REPOSITORY = "https://github.com/ZOUYIQAQ/yuyi" }
}
