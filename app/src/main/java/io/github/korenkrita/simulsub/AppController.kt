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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.File
import java.util.UUID
import io.github.korenkrita.simulsub.online.*

/** 连接 Compose 页面与原有业务；草稿保存在 ViewModel，旋转和窗口调整不会丢失输入。 */
class AppController internal constructor(application: Application,
    private val releaseSource: ReleaseUpdateSource,
    private val updateClock: () -> Long,
    private val jevProbeFactory: (JevConnection, Lang?, Target) -> Judge,
) : AndroidViewModel(application) {
    /** 生产连接测试使用完整快照，并设置独立的十秒时限。 */
    internal constructor(application: Application, releaseSource: ReleaseUpdateSource, updateClock: () -> Long) :
        this(application, releaseSource, updateClock, { connection, source, target ->
            Jev(connection.provider, connection.key.orEmpty(), url = connection.url, model = connection.model, deadlineMs = 10_000, source = source, target = target,
                clock = android.os.SystemClock::elapsedRealtime,
                onUsage = { usage -> UsageCostRepository.get(application).recordObserved(null, CostCategory.TEST,
                    connection.url, connection.model, usage) })
        })
    /** 生产入口保持AndroidViewModel所需的单参数构造器，测试可以注入网络与时钟。 */
    constructor(application: Application) : this(application, GithubReleaseUpdateSource(), System::currentTimeMillis)
    private val prefs = Prefs(application)
    private val services = ListeningServiceRepository(application)
    private var listeningEditor: ListeningEditorUiState? = null
    private var listeningEditorBaseline: ListeningServiceProfile? = null
    private var translationTargetProfileId: String? = null
    private val models = ModelRepository.get(application)
    private val costs = UsageCostRepository.get(application)
    private val launcherIcons = LauncherIcons(application)
    private val appearance = application.getSharedPreferences("appearance", 0)
    private var preferredLocalModelId = appearance.getString("preferred_local_model", null)
    private val updatePreferences = application.getSharedPreferences("updates", 0)
    private var updateJob: Job? = null
    private var updateGeneration = 0
    private var updateManual = false
    private var appEntered = false
    private var startupUpdateChecked = false
    private val mutableState = MutableStateFlow(SimulSubUiState())
    val state = mutableState.asStateFlow()
    private var jevProbe: Judge? = null
    private var jevProbeJob: Job? = null
    private var jevProbeGeneration = 0
    private var probe: ByokTranslator? = null
    private var onlineProbe: AutoCloseable? = null
    private var onlineProbeGeneration = 0
    private var onlineProbeJob: Job? = null
    private var probeJob: Job? = null
    private var probeService: ByokService? = null
    private var probeActiveProfileId: String? = null
    /** 待确认结果绑定整套已测试配置，编辑、保存或重新测试都会使其失效。 */
    private data class TestedEndpoint(val service: ByokService, val draft: ApiDraftSnapshot,
        val previouslySaved: ByokService?, val activeProfileId: String, val url: String, val variantName: String?)
    /** 只比较可编辑输入，测试进度与提示变化不使快照失效。 */
    private data class ApiDraftSnapshot(val url: String, val model: String, val key: String?, val hasSavedKey: Boolean,
        val userAgent: String, val headers: List<Pair<String, String>>, val profileId: String)
    private var testedEndpoint: TestedEndpoint? = null
    private var deletingModelId: String? = null
    private var onboardingStep: OnboardingStep? = if (appearance.getBoolean("onboarding_completed", false)) null else OnboardingStep.PERMISSIONS
    private var onboardingProviderId = appearance.getString("onboarding_provider", "deepseek").orEmpty()
        .takeIf { id -> TextServiceProviders.entries.any { it.id == id } } ?: "deepseek"
    private var onboardingSetupProbe: TranslationSetupDiscovery? = null
    internal var onboardingProbeFactory: (ByokService, String, Int, (ByokService, MtResult) -> Unit) -> TranslationSetupDiscovery =
        { service, system, tokens, usage -> TranslationSetupProbe(service, system, tokens, usage) }
    private var onboardingKeyDraft: String? = null
    private var onboardingSaveError: UiMessage? = null
    private val pendingModelDownloads = mutableSetOf<String>()
    private var generation: Job? = null
    private var refreshJob: Job? = null
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        // 拖动落点不参与页面显示，抬手保存坐标不能触发整份服务配置重建。
        if (key !in placementPreferenceKeys)
            requestRefresh()
    }
    private var permissions = emptyList<PermissionUiState>().withRequiredPermissions()

    init {
        prefs.ensureDefaultTranslationServices()
        services.migrateLegacy(prefs)
        prefs.listen(prefListener)
        mutableState.update { it.copy(settings = it.settings.copy(api = savedApi(), online = savedOnline(), cloudAsr = savedCloudAsr(), platformKeys = savedPlatformKeys())) }
        refreshPermissions()
        viewModelScope.launch { models.state.collect {
            announceModelDownloadChanges(it)
            activateOnboardingModelWhenReady()
            requestRefresh()
        } }
        // 首页已取消逐句字幕预览：正文变化只绘制悬浮窗，不重建整份服务与密钥设置。
        viewModelScope.launch {
            CaptionService.state.map { it.running to it.status }.distinctUntilChanged().collect { (running, status) ->
                if (running != state.value.live.running) refresh()
                else if (running) mutableState.update { it.copy(live = it.live.copy(statusDetail = status.ifBlank { "正在听取目标应用播放的声音" })) }
            }
        }
        viewModelScope.launch { costs.state.collect { summary ->
            val presentation = costUiState(summary)
            mutableState.update { it.copy(live = it.live.copy(costs = presentation), settings = it.settings.copy(costs = presentation)) }
        } }
        viewModelScope.launch {
            while (isActive) {
                delay(60_000)
                withContext(Dispatchers.IO) { costs.refreshToday() }
            }
        }
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
        onNavigate = { destination -> cancelJevProbe(); mutableState.update { it.copy(destination = destination) } },
        onOnboardingNext = { advanceOnboarding(false) },
        onOnboardingSkip = { advanceOnboarding(true) },
        onOnboardingBack = {
            val steps = onboardingSteps(prefs.listeningMode)
            val index = steps.indexOf(onboardingStep)
            if (index > 0) onboardingStep = steps[index - 1]
            cancelProbe(); cancelOnlineProbe(); refresh()
        },
        onRestartOnboarding = {
            if (CaptionService.running) message("请先停止同传，再重新配置", true)
            else { clearModelOperationNotice(); onboardingStep = OnboardingStep.PERMISSIONS; refresh() }
        },
        onStart = { cancelProbe(); cancelOnlineProbe(); cancelJevProbe(); start() },
        onStop = { cancelProbe(); CaptionService.stop(getApplication()) },
        onPermission = permission,
        onSourceLanguage = { id ->
            if (!(CaptionService.running && prefs.listeningMode != ListeningMode.LOCAL)) {
                val allowed = state.value.live.sourceOptions.any { it.id == id }
                if (allowed) prefs.sourceLang = if (id == "auto") null else Lang.of(id)
                refresh()
            }
        },
        onTargetLanguage = { id ->
            if (!(CaptionService.running && prefs.listeningMode != ListeningMode.LOCAL)) {
                Target.parse(id)?.takeIf { prefs.listeningMode != ListeningMode.ONLINE || it.lang != null }?.let { prefs.targetLang = it }
                refresh()
            }
        },
        onCustomTargetChange = { value -> if (!(CaptionService.running && prefs.listeningMode != ListeningMode.LOCAL)) mutableState.update { it.copy(live = it.live.copy(customTarget = value)) } },
        onApplyCustomTarget = {
            if (!(CaptionService.running && prefs.listeningMode != ListeningMode.LOCAL)) Target.custom(state.value.live.customTarget)?.let { prefs.targetLang = it; refresh() }
                ?: message("请输入 1～40 字的目标语言名称", true)
        },
        onSelectScene = { id -> if (!(CaptionService.running && prefs.listeningMode == ListeningMode.CLOUD_ASR)) {
            prefs.scenes.firstOrNull { it.id == id }?.let { prefs.scene = it }; refresh()
        } },
        onFinalOnly = { prefs.finalOnly = it; refresh() },
        onPlatformKeyChange = ::editPlatformKey,
        onSavePlatformKey = ::savePlatformKey,
        onAddPlatformKey = ::addPlatformKey,
        onDeletePlatformKey = ::deletePlatformKey,
        onDiscardPlatformKey = ::discardPlatformKey,
        onPlatformKeyLabelChange = { platform, id, label -> editPlatformCredential(platform, id, label = label) },
        onPlatformCredentialChange = { platform, id, key -> editPlatformCredential(platform, id, key = key) },
        onSavePlatformCredential = ::savePlatformCredential,
        onServiceCredentialChange = ::selectServiceCredential,
        onSetDefaultPlatformKey = ::setDefaultPlatformKey,
        onOpenQuickPlatformKey = ::openQuickPlatformKey,
        onQuickPlatformKeyPlatformChange = ::changeQuickPlatform,
        onQuickPlatformKeyNameChange = { name -> updateQuickPlatformKey { it.copy(name = name, message = null) } },
        onQuickPlatformKeyValueChange = { key -> updateQuickPlatformKey { it.copy(key = key, message = null) } },
        onSaveQuickPlatformKey = ::saveQuickPlatformKey,
        onDismissQuickPlatformKey = { mutableState.update { it.copy(settings = it.settings.copy(quickPlatformKey = null)) } },
        onDiscardPlatformKeys = { mutableState.update { it.copy(settings = it.settings.copy(platformKeys = savedPlatformKeys())) } },
        onListeningModeChange = ::changeListeningMode,
        onCreateListeningService = ::createListeningService,
        onEditListeningService = ::editListeningService,
        onListeningServiceNameChange = { name ->
            if (!CaptionService.running) { listeningEditor = listeningEditor?.copy(name = name); refresh() }
        },
        onSaveListeningService = ::saveListeningService,
        onCloseListeningServiceEditor = ::closeListeningServiceEditor,
        onSelectListeningService = ::selectListeningService,
        onChooseOnboardingListeningService = ::chooseOnboardingListeningService,
        onChooseOnboardingRoute = ::chooseOnboardingRoute,
        onOnboardingProviderChange = ::changeOnboardingProvider,
        onOnboardingModelChange = ::changeOnboardingModel,
        onOnboardingKeyChange = ::saveOnboardingKey,
        onOnboardingTest = {
            if (onboardingSaveError == null) {
                if (prefs.listeningMode == ListeningMode.ONLINE) testOnline() else testOnboardingTranslation()
            }
        },
        onSelectTranslationService = { id ->
            if (!CaptionService.running) prefs.byokProfiles.firstOrNull { it.id == id }?.let { selected ->
                changeApiProfile { prefs.useByokProfile(selected) }
            }
        },
        onOnlineDraftChange = ::editOnlineDraft,
        onSaveOnline = ::saveOnline,
        onTestOnline = ::testOnline,
        onDiscardOnlineDraft = { discardListeningDraft(ListeningMode.ONLINE) },
        onCancelOnlineTest = ::cancelOnlineProbe,
        onCloudAsrDraftChange = ::editCloudAsrDraft,
        onSaveCloudAsr = ::saveCloudAsr,
        onTestCloudAsr = ::testCloudAsr,
        onCancelCloudAsrTest = ::cancelOnlineProbe,
        onDiscardCloudAsrDraft = { discardListeningDraft(ListeningMode.CLOUD_ASR) },
        onOpenCloudAsrHelp = { openUrl("https://help.aliyun.com/zh/model-studio/qwen-asr-api-reference") },
        onOpenOnlineHelp = { openUrl(if (LiveTranslateModels.find(state.value.settings.online.model)?.realtime != false)
            "https://help.aliyun.com/zh/model-studio/qwen3-5-livetranslate-flash-realtime"
            else "https://help.aliyun.com/zh/model-studio/qwen3-livetranslate-flash") },
        onRefreshModels = { models.refresh() },
        onDownloadModel = ::startModelDownload,
        onCancelDownload = { models.cancel(it) },
        onRequestDeleteModel = ::requestDeleteModel,
        onDismissDeleteModel = {
            if (deletingModelId == null) mutableState.update { it.copy(models = it.models.copy(deleteConfirmationId = null)) }
        },
        onConfirmDeleteModel = ::deleteModel,
        onActivateModel = { id ->
            if (CaptionService.running) message("请先停止同传，再切换识别模型", true)
            else {
                clearModelOperationNotice()
                models.select(id)
                preferredLocalModelId = id
                appearance.edit().putString("preferred_local_model", id).apply()
                modelSpec(id)?.let { spec ->
                    if (prefs.listeningMode == ListeningMode.LOCAL && prefs.sourceLang !in spec.sourceLanguages &&
                        !(prefs.sourceLang == null && spec.supportsAutoLanguage)) prefs.sourceLang = spec.sourceLanguages.first()
                }
                refresh()
            }
        },
        onModelSource = { id -> modelSpec(id)?.files?.firstOrNull()?.sources?.firstOrNull()?.let(openUrl) },
        onModelLicense = { id -> modelSpec(id)?.licenseUrl?.takeIf { it.isNotBlank() }?.let(openUrl) },
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
            if (!cloudSessionLocked() && !state.value.settings.api.testing && !state.value.settings.api.saving) {
                cancelProbe()
                updateApi { bindApiPlatform(TranslationPresets.apply(it, id)) }
            }
        },
        onApiDraftChange = ::editApiDraft,
        onAddHeader = { if (!cloudSessionLocked()) { cancelProbe(); updateApi { it.copy(headers = it.headers + HeaderUiState(UUID.randomUUID().toString())) } } },
        onRemoveHeader = { id -> if (!cloudSessionLocked()) { cancelProbe(); updateApi { it.copy(headers = it.headers.filterNot { row -> row.id == id }) } } },
        onSelectApiProfile = { id -> changeApiProfile { prefs.useByokProfile(prefs.byokProfiles.firstOrNull { it.id == id } ?: error("翻译配置不存在")) } },
        onEditTranslationService = { id -> openTranslationService(id, null) },
        onCreateTranslationService = { name -> openTranslationService(null, name) },
        onCreateTranslationProvider = ::createTranslationProvider,
        onCloseTranslationServiceEditor = ::closeTranslationServiceEditor,
        onAddApiProfile = { name -> changeApiProfile { prefs.addByokProfile(name) } },
        onRenameApiProfile = { name -> changeApiProfile { prefs.renameByokProfile(requireNotNull(apiProfile()), name) } },
        onDeleteTranslationService = ::deleteTranslationService,
        onDeleteApiProfile = { changeApiProfile {
            val profile = requireNotNull(apiProfile())
            prefs.deleteByokProfile(profile)
            if (translationTargetProfileId == profile.id) translationTargetProfileId = null
        } },
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
        onSaveJevCustom = { config ->
            if (!CaptionService.running) {
                cancelJevProbe()
                prefs.setJevCustom(config)
                refresh()
            }
        },
        onSaveJevKey = { saveJevKey() },
        onTestJevConnection = ::testJevConnection,
        onCancelJevTest = { cancelJevProbe(showMessage = true) },
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

    /** 前台恢复时核对真实图标入口，仅更新图标状态，不重建整页服务配置。 */
    fun restoreLauncherIcon() {
        runCatching { launcherIcons.reconcile() }
            .onFailure { message("桌面图标暂时无法恢复，可在外观中重新选择", true) }
        mutableState.update { it.copy(settings = it.settings.copy(launcherIcon = launcherIcons.current)) }
    }

    /** 主题操作才同步自动纯色，字幕刷新不触发系统组件切换。 */
    private fun updateLauncherTheme() {
        runCatching { launcherIcons.syncTheme() }
            .onFailure { message("桌面图标颜色暂未更新，可在外观中重新保存", true) }
    }

    /** 系统实值没有改变时复用当前页面，避免初始化与首次onResume重复重建整份服务配置。 */
    fun setPermissions(value: List<PermissionUiState>) {
        val actual = value.withRequiredPermissions()
        if (permissions == actual && state.value.live.permissions == actual) return
        permissions = actual
        refresh()
    }

    /** 冷启动与授权返回共用系统实值；不缓存本次会话的屏幕捕获授权。 */
    fun refreshPermissions() {
        viewModelScope.launch(Dispatchers.IO) { costs.refreshToday() }
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

    /** 引导按已保存配置判断就绪；下载须主动确认，各步可跳过，完成后不再自动出现。 */
    private fun advanceOnboarding(skip: Boolean) {
        val step = onboardingStep ?: return
        val ready = when (step) {
            OnboardingStep.PERMISSIONS -> permissions.filter { it.required }.all { it.granted }
            OnboardingStep.MODE -> true
            OnboardingStep.ONLINE -> prefs.onlineReady && onboardingSaveError == null
            OnboardingStep.API -> prefs.byokReady && onboardingLocalReady() && onboardingSaveError == null
            OnboardingStep.MODEL -> models.selectedPaths() != null
        }
        if (!skip && !ready) return
        if (step == OnboardingStep.MODE) {
            // 跳过路线选择不启动下载；仍可在下一页显式下载或继续跳过。
            if (prefs.listeningMode != ListeningMode.ONLINE) {
                runCatching { prepareOnboardingLocal() }.onFailure {
                    onboardingSaveError = UiMessage(it.message ?: "配置暂未完成，请重试", true)
                    refresh()
                    return
                }
            }
        }
        val steps = onboardingSteps(prefs.listeningMode)
        onboardingStep = steps.getOrNull(steps.indexOf(step) + 1)
        cancelProbe(); cancelOnlineProbe()
        if (onboardingStep == null) {
            appearance.edit().putBoolean("onboarding_completed", true).putBoolean("onboarding_local_requested", false).apply()
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

    /** 同一次偏好事务逐键通知时只安排一次重建，避免主线程连续重复解析所有配置。 */
    private fun requestRefresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            yield()
            refresh()
        }
    }

    /** 更新持久化状态，保持用户尚未保存的 API 和场景草稿。 */
    private fun refresh() {
        val jevConnection = prefs.jevConnection()
        val library = models.state.value
        val modelWorkBusy = library.entries.any { it.status in listOf(ModelStatus.QUEUED, ModelStatus.DOWNLOADING, ModelStatus.VERIFYING) }
        val session = CaptionService.state.value
        if ((probe != null || onboardingSetupProbe != null) && (session.running || prefs.byokProfile.id != probeActiveProfileId ||
                apiProfile()?.let { prefs.byokService(it) } != probeService)) cancelProbe()
        if (testedEndpoint != null && (session.running || prefs.byokProfile.id != testedEndpoint?.activeProfileId ||
                apiProfile()?.let { prefs.byokService(it) } != testedEndpoint?.previouslySaved)) cancelProbe()
        val desiredLocalId = preferredLocalModelId ?: library.selectedId ?: ModelCatalog.entries.first().id
        val selected = library.entries.firstOrNull { it.spec.id == desiredLocalId && it.status == ModelStatus.READY && it.spec.id == library.selectedId }
        val localServices = library.entries
        val savedServices = services.profiles
        val activeService = savedServices.firstOrNull { it.id == services.activeId && matchesActive(it) }
            ?: savedServices.firstOrNull(::matchesActive)
        val listeningTitles = distinctServiceTitles(savedServices.map { it.id to listeningServiceTitle(it.mode, it.model) })
        val listeningOptions = localServices.filter { it.status == ModelStatus.READY }.map {
            UiOption("local:${it.spec.id}", "本地 · ${it.spec.name}", "模型已安装")
        } + savedServices.filter { profileHasKey(it) || !isBuiltInListening(it) }.map {
            val platform = PlatformCredentials.forUrl(it.endpoint)
            val slot = listeningCredentialSlot(it.mode, it.endpoint, it.model)
            UiOption("profile:${it.id}", listeningTitles.getValue(it.id), platform?.let { "密钥：${credentialLabel(it.id, slot)}" }.orEmpty())
        }
        val listeningId = when (prefs.listeningMode) {
            ListeningMode.LOCAL -> "local:$desiredLocalId"
            else -> activeService?.let { "profile:${it.id}" }.orEmpty()
        }
        val translationProfiles = services.translationServices(prefs)
        val activeTranslationId = prefs.byokProfile.id
        val translationTitles = distinctServiceTitles(translationProfiles.map { it.id to translationServiceTitle(it.url, it.model) })
        val translationOptions = translationProfiles.filter { summary ->
            val profile = prefs.byokProfiles.firstOrNull { it.id == summary.id }
            summary.configured && (profile?.let { prefs.byokReady(it) } == true || !isBuiltInTranslation(summary.url, summary.model, summary.id))
        }.map { summary ->
            val platform = PlatformCredentials.forUrl(summary.url)
            UiOption(summary.id, translationTitles.getValue(summary.id),
                platform?.let { "密钥：${credentialLabel(it.id, "translation:${summary.id}")}" }.orEmpty())
        }
        val costState = costUiState(costs.state.value)
        val scenes = prefs.scenes
        val online = prefs.listeningMode == ListeningMode.ONLINE
        val cloudAsr = prefs.listeningMode == ListeningMode.CLOUD_ASR
        val sourceLanguages = if (online || cloudAsr) Lang.entries.toList() else selected?.spec?.sourceLanguages ?: listOf(Lang.JA, Lang.EN)
        val ready = when (prefs.listeningMode) {
            ListeningMode.ONLINE -> prefs.onlineReady
            ListeningMode.CLOUD_ASR -> prefs.cloudAsrReady && prefs.byokReady
            ListeningMode.LOCAL -> selected != null && prefs.byokReady
        }
        val status = when {
            session.running -> "正在同传"
            online && !prefs.onlineReady -> "还差一步：配置在线听译"
            cloudAsr && !prefs.cloudAsrReady -> "还差一步：配置在线听写"
            cloudAsr && !prefs.byokReady -> "还差一步：配置文字翻译"
            (online || cloudAsr) && permissions.none { it.required && !it.granted } -> "准备就绪"
            online || cloudAsr -> "开启权限后即可开始"
            selected == null -> "先选择一个识别模型"
            !prefs.byokReady -> "还差一步：配置翻译服务"
            permissions.any { it.required && !it.granted } -> "开启权限后即可开始"
            else -> "准备就绪"
        }
        mutableState.update { old ->
            old.copy(
                onboarding = onboardingStep?.let { OnboardingUiState(it, prefs.byokReady && onboardingSaveError == null,
                    onboardingLocalReady(), prefs.listeningMode, prefs.onlineReady && onboardingSaveError == null, onboardingSetup()) },
                live = old.live.copy(
                    running = session.running,
                    listeningMode = prefs.listeningMode,
                    listeningServiceId = listeningId, listeningServices = listeningOptions,
                    translationServiceId = activeTranslationId, translationServices = translationOptions,
                    costs = costState,
                    cloudReady = savedListeningReady(),
                    translationReady = prefs.byokReady,
                    canStart = ready && permissions.none { it.required && !it.granted },
                    statusTitle = status,
                    statusDetail = if (session.running) session.status.ifBlank { "正在听取目标应用播放的声音" } else when {
                        online && !prefs.onlineReady -> "在听写服务中填写千问密钥，无需另配翻译服务。"
                        online -> "${LiveTranslateModels.find(prefs.onlineModel)?.label ?: "在线听译"} · 播放音频将发送给所选云端服务"
                        cloudAsr && !prefs.cloudAsrReady && !prefs.byokReady -> "配置千问听写与文字翻译服务（推荐 DeepSeek）；平台密钥各填写一次。"
                        cloudAsr && !prefs.cloudAsrReady -> "文字翻译已保存，请在听写服务中填写千问密钥。"
                        cloudAsr && !prefs.byokReady -> "在线听写已保存，还需保存文字翻译 API（推荐 DeepSeek）。"
                        cloudAsr -> "千问 3.1 Streaming 识别音频，文字交给已配置的翻译服务；所选场景同时用于识别背景与翻译。"
                        selected == null -> "请在听写服务中下载并选择识别模型。"
                        !prefs.byokReady -> "语音识别在本地完成，识别文本交给你配置的服务翻译。"
                        else -> "${selected.spec.name} · 音频仅在设备上识别"
                    },
                    modelLabel = if (cloudAsr) { if (prefs.cloudAsrReady) "千问 3.1 Streaming" else "未配置在线听写" } else if (online) "千问 LiveTranslate" else selected?.spec?.name ?: "未启用模型",
                    translationLabel = if (online) { if (prefs.onlineReady) LiveTranslateModels.find(prefs.onlineModel)?.label ?: prefs.onlineModel else "未配置" } else prefs.byokModel.let { model ->
                        if (model.isNotBlank()) translationServiceTitle(prefs.byokUrl, model)
                        else if (session.running) "继续使用会话中的服务" else "未配置"
                    },
                    sourceId = prefs.sourceLang?.code ?: "auto",
                    targetId = prefs.targetLang.stored,
                    sourceOptions = sourceLanguages.map { UiOption(it.code, it.zh) } +
                        if (online || cloudAsr || selected?.spec?.supportsAutoLanguage != false) listOf(UiOption("auto", "自动识别")) else emptyList(),
                    targetOptions = Target.PRESETS.map { UiOption(it.stored, it.zh) } +
                        (if (!online && prefs.targetLang.lang == null) listOf(UiOption(prefs.targetLang.stored, prefs.targetLang.zh)) else emptyList()),
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
                    listeningEditor = listeningEditor,
                    translationEditorId = translationTargetProfileId,
                    listeningProfiles = localServices.map { ServiceProfileUiState("local:${it.spec.id}", "本地 · ${it.spec.name}",
                        "${it.spec.languages.joinToString("、")} · ${if (it.status == ModelStatus.READY) "已安装" else "需下载模型"}",
                        "local", listeningId == "local:${it.spec.id}", it.status == ModelStatus.READY) } +
                        savedServices.map { profile ->
                            val slot = listeningCredentialSlot(profile.mode, profile.endpoint, profile.model)
                            val platform = PlatformCredentials.forUrl(profile.endpoint)
                            ServiceProfileUiState(profile.id, listeningTitles.getValue(profile.id), profile.model,
                                if (profile.mode == ListeningMode.CLOUD_ASR) "asr" else "direct", profile.id == activeService?.id,
                                configured = profileHasKey(profile), platformId = platform?.id.orEmpty(),
                                credentialOptions = platform?.let { credentialOptions(it.id) }.orEmpty(),
                                credentialId = platform?.let { uiCredentialSelection(it.id, slot) }.orEmpty(),
                                credentialLabel = platform?.let { credentialLabel(it.id, slot) }.orEmpty(),
                                builtIn = isBuiltInListening(profile))
                        },
                    translationProfiles = translationProfiles.map { summary ->
                        val platform = PlatformCredentials.forUrl(summary.url)
                        val slot = "translation:${summary.id}"
                        val profile = prefs.byokProfiles.firstOrNull { it.id == summary.id }
                        ServiceProfileUiState(summary.id, translationTitles.getValue(summary.id), summary.model,
                            "translation", activeTranslationId == summary.id, profile?.let { prefs.byokReady(it) } == true,
                            platformId = platform?.id.orEmpty(), credentialOptions = platform?.let { credentialOptions(it.id) }.orEmpty(),
                            credentialId = platform?.let { uiCredentialSelection(it.id, slot) }.orEmpty(),
                            credentialLabel = platform?.let { credentialLabel(it.id, slot) }.orEmpty(),
                            builtIn = isBuiltInTranslation(summary.url, summary.model, summary.id))
                    },
                    listeningMode = prefs.listeningMode,
                    listeningServiceId = listeningId, listeningServices = listeningOptions,
                    translationServiceId = activeTranslationId, translationServices = translationOptions,
                    costs = costState,
                    platformKeys = savedPlatformKeys(old.settings.platformKeys),
                    online = bindListeningCredentials(ListeningMode.ONLINE, old.settings.online.copy(
                        key = prefs.keyForService(old.settings.online.endpoint, listeningCredentialSlot(ListeningMode.ONLINE, old.settings.online.endpoint, old.settings.online.model)),
                        hasUnsavedChanges = isOnlineDirty(old.settings.online), hasSavedConfiguration = savedOnline().hasSavedConfiguration)),
                    cloudAsr = bindListeningCredentials(ListeningMode.CLOUD_ASR, old.settings.cloudAsr.copy(
                        key = prefs.keyForService(old.settings.cloudAsr.endpoint, listeningCredentialSlot(ListeningMode.CLOUD_ASR, old.settings.cloudAsr.endpoint, old.settings.cloudAsr.model)),
                        hasUnsavedChanges = isCloudAsrDirty(old.settings.cloudAsr), hasSavedConfiguration = savedCloudAsr().hasSavedConfiguration)),
                    api = bindApiPlatform(old.settings.api).copy(hasSavedConfiguration = apiProfile()?.let { prefs.byokReady(it) } ?: false, canTest = !session.running && !old.settings.api.testing,
                        hasUnsavedChanges = isApiDirty(bindApiPlatform(old.settings.api))),
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
                        jevNeedsKey = prefs.jevProvider.needsKey || prefs.jevProvider == JevProvider.CUSTOM,
                        jevCustom = prefs.jevCustom, jevEndpoint = jevConnection.url,
                        jevCredentialOptions = PlatformCredentials.forUrl(jevConnection.url)?.let { credentialOptions(it.id) }.orEmpty(),
                        jevCredentialId = PlatformCredentials.forUrl(jevConnection.url)?.let { uiCredentialSelection(it.id, "jev:${prefs.jevProvider.name}") }.orEmpty(),
                        jevCredentialLabel = PlatformCredentials.forUrl(jevConnection.url)?.let { credentialLabel(it.id, "jev:${prefs.jevProvider.name}") }.orEmpty(),
                        jevHasSavedKey = !jevConnection.key.isNullOrEmpty(),
                        jevKey = jevConnection.key,
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
            id = entry.spec.id, name = entry.spec.name, description = entry.spec.introduction,
            languages = entry.spec.languages.joinToString("、"), sizeLabel = size(entry.spec.sizeBytes),
            license = entry.spec.license, licenseUrl = entry.spec.licenseUrl, sourceLabel = entry.source?.let { android.net.Uri.parse(it).host }.orEmpty().ifBlank { if (entry.spec.id == "moonshine-tiny-en") "Hugging Face 官方源，国内网络可能无法直连" else "自动尝试已校验的下载源" },
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

    /** 当前表单的目标配置；目标消失时回退到首页活动配置。 */
    private fun apiProfile(): LlmProfile? = translationTargetProfileId?.let { id ->
        prefs.byokProfiles.firstOrNull { it.id == id }
    } ?: if (translationTargetProfileId == null) prefs.byokProfile else null

    /** 从目标配置加载编辑表单，首页活动翻译配置保持独立。 */
    private fun savedApi(): TranslationApiUiState {
        val profile = requireNotNull(apiProfile())
        val url = prefs.byokUrl(profile)
        val key = prefs.byokKey(profile)
        return bindApiPlatform(TranslationApiUiState(
            profileId = profile.id, profiles = prefs.byokProfiles.map { UiOption(it.id, it.name) },
            presetId = TranslationPresets.match(url),
            url = url, model = prefs.byokModel(profile), key = key, hasSavedKey = !key.isNullOrBlank(),
            hasSavedConfiguration = prefs.byokReady(profile),
            userAgent = prefs.byokUserAgent(profile),
            headers = prefs.byokHeaders(profile).map { HeaderUiState(UUID.randomUUID().toString(), it.first, it.second) },
            resolvedUrl = ByokTranslator.chatUrl(url).second.orEmpty(),
        ))
    }

    /** 按地址来源绑定共享密钥，切换模型或配置不会携带上一平台的凭据。 */
    private fun bindApiPlatform(draft: TranslationApiUiState): TranslationApiUiState {
        val platform = PlatformCredentials.forUrl(draft.url)
        val key = prefs.keyForService(draft.url, "translation:${draft.profileId}")
        return draft.copy(platformId = platform?.id.orEmpty(), platformLabel = platform?.label.orEmpty(),
            key = key, hasSavedKey = key.isNotEmpty(),
            credentialOptions = platform?.let { credentialOptions(it.id) }.orEmpty(),
            credentialId = platform?.let { uiCredentialSelection(it.id, "translation:${draft.profileId}") }.orEmpty(),
            credentialLabel = platform?.let { credentialLabel(it.id, "translation:${draft.profileId}") }.orEmpty())
    }

    /** 一个平台的多份凭据按稳定标识合并；保留未保存草稿，不将秘密写入页面状态包。 */
    private fun savedPlatformKeys(previous: List<PlatformKeyUiState> = emptyList(), apiUrl: String = state.value.settings.api.url): List<PlatformKeyUiState> {
        val platforms = (prefs.credentialPlatforms + listOf(apiUrl, state.value.settings.online.endpoint,
            state.value.settings.cloudAsr.endpoint).mapNotNull(PlatformCredentials::forUrl)).distinctBy { it.id }
        return platforms.flatMap { platform ->
            val saved = prefs.platformCredentials(platform.id).ifEmpty { listOf(PlatformCredential("default", "默认密钥", "")) }
            saved.map { credential ->
                val old = previous.firstOrNull { it.id == platform.id && it.credentialId == credential.id }
                if (old?.hasUnsavedChanges == true) old.copy(hasSavedKey = credential.key.isNotEmpty(),
                    isDefault = credential.id == runCatching { prefs.defaultPlatformCredentialId(platform.id) }.getOrNull(),
                    isPlaceholder = !prefs.hasStoredPlatformCredential(platform.id, credential.id),
                    usedBy = credentialUsage(platform.id, credential.id))
                else PlatformKeyUiState(platform.id, platform.label, credential.key, credential.key.isNotEmpty(),
                    message = old?.message, credentialId = credential.id, credentialLabel = credential.label,
                    isDefault = credential.id == runCatching { prefs.defaultPlatformCredentialId(platform.id) }.getOrNull(),
                    isPlaceholder = !prefs.hasStoredPlatformCredential(platform.id, credential.id),
                    usedBy = credentialUsage(platform.id, credential.id))
            } + previous.filter { it.id == platform.id && it.isNew && saved.none { saved -> saved.id == it.credentialId } }
        }
    }

    /** 仅在删除确认中列出真正读取该凭据的连接，默认跟随也按当前指针计算。 */
    private fun credentialUsage(platformId: String, credentialId: String): List<String> {
        val targets = prefs.byokProfiles.map { Triple(it.name, prefs.byokUrl(it), translationCredentialSlot(it.id)) } +
            services.profiles.filter { it.mode != ListeningMode.LOCAL }.map {
                Triple(it.name, it.endpoint, listeningCredentialSlot(it.mode, it.endpoint, it.model))
            } + JevProvider.entries.filter { it.needsKey || it == JevProvider.CUSTOM }.map {
                Triple("Jev · ${it.label}", prefs.jevConnection(it).url, jevCredentialSlot(it)) }
        return targets.filter { (_, endpoint, slot) -> PlatformCredentials.forUrl(endpoint)?.id == platformId &&
            prefs.credentialFor(platformId, slot)?.id == credentialId }.map { it.first }.distinct()
    }

    /** 单条弹窗放弃草稿时，不影响其他凭据的未保存修改。 */
    private fun discardPlatformKey(platformId: String, credentialId: String) {
        val previous = state.value.settings.platformKeys.filterNot { it.id == platformId && it.credentialId == credentialId }
        mutableState.update { it.copy(settings = it.settings.copy(platformKeys = savedPlatformKeys(previous))) }
    }

    /** 界面二次确认后才删除；删除失败仍保留钥匙扣和错误提示。 */
    private fun deletePlatformKey(platformId: String, credentialId: String) {
        if (CaptionService.running || state.value.live.busy) return
        cancelProbe(); cancelOnlineProbe(); cancelJevProbe()
        val result = runCatching { prefs.deletePlatformCredential(platformId, credentialId) }
        if (result.isSuccess) {
            discardPlatformKey(platformId, credentialId)
            refresh()
        } else message(result.exceptionOrNull()?.message ?: "密钥删除失败，请重试", true)
    }

    /** 新密钥仅创建独立草稿，保存前不进入服务下拉。 */
    private fun addPlatformKey(platformId: String) {
        if (CaptionService.running) return
        val platform = state.value.settings.platformKeys.firstOrNull { it.id == platformId }?.let { CredentialPlatform(it.id, it.label) } ?: return
        mutableState.update { root ->
            val number = root.settings.platformKeys.count { it.id == platformId } + 1
            root.copy(settings = root.settings.copy(platformKeys = root.settings.platformKeys +
                PlatformKeyUiState(platform.id, platform.label, credentialId = UUID.randomUUID().toString(),
                    credentialLabel = "密钥 $number", isNew = true, hasUnsavedChanges = true)))
        }
    }

    /** 编辑指定凭据的名称或完整原文，空串也保留为明确的清空操作。 */
    private fun editPlatformCredential(platformId: String, credentialId: String, key: String? = null, label: String? = null) {
        if (CaptionService.running) return
        cancelProbe(); cancelOnlineProbe(); cancelJevProbe()
        val saved = prefs.platformCredentials(platformId).firstOrNull { it.id == credentialId }
        mutableState.update { root -> root.copy(settings = root.settings.copy(platformKeys = root.settings.platformKeys.map {
            if (it.id != platformId || it.credentialId != credentialId) it else {
                val updated = it.copy(key = key ?: it.key, credentialLabel = label ?: it.credentialLabel, message = null)
                updated.copy(hasUnsavedChanges = updated.isNew || updated.key != saved?.key.orEmpty() ||
                    updated.credentialLabel != (saved?.label ?: "默认密钥"))
            }
        })) }
    }

    /** 兼容入口继续编辑平台当前默认，切换默认引用后不误改旧中央项。 */
    private fun editPlatformKey(id: String, key: String) {
        runCatching { prefs.defaultPlatformCredentialId(id) }.getOrNull()?.let { editPlatformCredential(id, it, key = key) }
    }

    /** 旧保存动作不会覆盖平台中的备用密钥。 */
    private fun savePlatformKey(id: String) {
        runCatching { prefs.defaultPlatformCredentialId(id) }.getOrNull()?.let { savePlatformCredential(id, it) }
    }

    /** 单独加密保存一条命名凭据，立即同步就绪状态和使用该凭据的连接测试。 */
    private fun savePlatformCredential(platformId: String, credentialId: String) {
        if (CaptionService.running) return
        val draft = state.value.settings.platformKeys.firstOrNull { it.id == platformId && it.credentialId == credentialId } ?: return
        cancelProbe(); cancelOnlineProbe(); cancelJevProbe()
        val result = runCatching { prefs.savePlatformCredential(platformId, credentialId, draft.credentialLabel, draft.key) }
        mutableState.update { root -> root.copy(settings = root.settings.copy(platformKeys = root.settings.platformKeys.map {
            if (it.id != platformId || it.credentialId != credentialId) it else if (result.isSuccess)
                it.copy(hasUnsavedChanges = false, isNew = false, hasSavedKey = draft.key.isNotEmpty(),
                    message = UiMessage("密钥已保存"))
            else it.copy(message = UiMessage(result.exceptionOrNull()?.message ?: "密钥保存失败，请重试", true))
        })) }
        refresh()
    }

    /** 每个基础服务只选择凭据引用；不复制密钥，也不改变当前使用的服务。 */
    private fun selectServiceCredential(serviceId: String, credentialId: String) {
        if (CaptionService.running) return
        val pair = serviceCredentialTarget(serviceId) ?: return
        val platform = pair.first ?: return
        cancelProbe(); cancelOnlineProbe(); cancelJevProbe()
        runCatching { prefs.selectCredential(platform.id, pair.second, credentialId.ifEmpty { null }) }
            .onFailure { message(it.message ?: "密钥选择失败", true) }
        refresh()
    }

    /** 编辑中的音频连接与保存的基础服务共用同一凭据槽位，平台按当前地址确定。 */
    private fun serviceCredentialTarget(serviceId: String): Pair<CredentialPlatform?, String>? {
        return when {
            serviceId.startsWith("audio:") -> {
                val mode = ListeningMode.entries.firstOrNull { it.name == serviceId.removePrefix("audio:") && it != ListeningMode.LOCAL }
                    ?: return null
                val draft = if (mode == ListeningMode.CLOUD_ASR) state.value.settings.cloudAsr else state.value.settings.online
                PlatformCredentials.forUrl(draft.endpoint) to listeningCredentialSlot(mode, draft.endpoint, draft.model)
            }
            serviceId.startsWith("translation:") -> {
                val profile = prefs.byokProfiles.firstOrNull { it.id == serviceId.removePrefix("translation:") } ?: return null
                val endpoint = state.value.settings.api.takeIf { it.profileId == profile.id }?.url ?: prefs.byokUrl(profile)
                PlatformCredentials.forUrl(endpoint) to "translation:${profile.id}"
            }
            serviceId.startsWith("jev:") -> {
                val provider = JevProvider.entries.firstOrNull { it.name == serviceId.removePrefix("jev:") } ?: return null
                PlatformCredentials.forUrl(prefs.jevConnection(provider).url) to "jev:${provider.name}"
            }
            else -> {
                val profile = services.profiles.firstOrNull { it.id == serviceId.removePrefix("profile:") } ?: return null
                PlatformCredentials.forUrl(profile.endpoint) to listeningCredentialSlot(profile.mode, profile.endpoint, profile.model)
            }
        }
    }

    /** 更换默认只修改平台引用；显式绑定和正在运行的会话快照不改变。 */
    private fun setDefaultPlatformKey(platformId: String, credentialId: String) {
        if (CaptionService.running || state.value.live.busy) return
        cancelProbe(); cancelOnlineProbe(); cancelJevProbe()
        runCatching { prefs.setDefaultPlatformCredential(platformId, credentialId) }
            .onFailure { message(it.message ?: "默认密钥设置失败", true) }
        refresh()
    }

    /** 无密钥时直接填写当前默认项；已有凭据则新建一条，避免覆盖原密钥。 */
    private fun openQuickPlatformKey(platformId: String, serviceId: String) {
        if (CaptionService.running || state.value.live.busy || state.value.settings.quickPlatformKey != null) return
        val platforms = (PlatformCredentials.defaults + prefs.credentialPlatforms).distinctBy { it.id }
        if (platformId.isEmpty() && serviceId.isEmpty()) {
            mutableState.update { it.copy(settings = it.settings.copy(quickPlatformKey = QuickPlatformKeyUiState(
                "", "", UUID.randomUUID().toString(), name = "默认密钥", platformEditable = true,
                platforms = platforms.map { option -> UiOption(option.id, option.label) },
            ))) }
            return
        }
        val platform = platforms.firstOrNull { it.id == platformId } ?: return
        if (serviceId.isNotBlank() && serviceCredentialTarget(serviceId)?.first?.id != platformId) return
        val credentials = prefs.platformCredentials(platformId)
        val first = credentials.none { it.key.isNotEmpty() }
        val defaultId = runCatching { prefs.defaultPlatformCredentialId(platformId) }.getOrElse {
            message("请先在平台密钥中重新指定默认项", true)
            return
        }
        val default = credentials.firstOrNull { it.id == defaultId }
        mutableState.update { it.copy(settings = it.settings.copy(quickPlatformKey = QuickPlatformKeyUiState(
            platformId, platform.label, if (first && defaultId.isNotEmpty()) defaultId else UUID.randomUUID().toString(), serviceId,
            name = if (first) default?.label ?: "默认密钥" else "密钥 ${credentials.size + 1}",
            platformEditable = serviceId.isEmpty(), platforms = platforms.map { option -> UiOption(option.id, option.label) },
        ))) }
    }

    /** 在同一表单切换预设或输入自定义来源；只改所属平台，不清除备注与密钥草稿。 */
    private fun changeQuickPlatform(input: String) {
        val draft = state.value.settings.quickPlatformKey ?: return
        if (!draft.platformEditable || draft.serviceId.isNotEmpty()) return
        val preset = draft.platforms.firstOrNull { it.id == input || it.label == input }
        val platform = preset?.let { CredentialPlatform(it.id, it.label) } ?: PlatformCredentials.forUrl(input)
        updateQuickPlatformKey { it.copy(platformInput = preset?.label ?: input,
            platformId = platform?.id.orEmpty(), platformLabel = platform?.label.orEmpty(),
            credentialId = if (it.platformId == platform?.id) it.credentialId else UUID.randomUUID().toString(), message = null) }
    }

    /** 快捷输入只改内存草稿，不复制到连接表单或可恢复页面状态。 */
    private fun updateQuickPlatformKey(change: (QuickPlatformKeyUiState) -> QuickPlatformKeyUiState) {
        if (CaptionService.running || state.value.live.busy) return
        mutableState.update { root -> root.copy(settings = root.settings.copy(
            quickPlatformKey = root.settings.quickPlatformKey?.let(change))) }
    }

    /** 保存并为来源服务绑定这条凭据；连接的地址、模型和未保存草稿保持不变。 */
    private fun saveQuickPlatformKey() {
        if (CaptionService.running || state.value.live.busy) return
        val draft = state.value.settings.quickPlatformKey ?: return
        if (draft.key.isEmpty()) {
            updateQuickPlatformKey { it.copy(message = UiMessage("请输入密钥", true)) }
            return
        }
        if (draft.platformId.isEmpty()) {
            updateQuickPlatformKey { it.copy(message = UiMessage("请选择平台，或填写完整的服务地址", true)) }
            return
        }
        val target = draft.serviceId.takeIf { it.isNotBlank() }?.let(::serviceCredentialTarget)
        if (draft.serviceId.isNotBlank() && target?.first?.id != draft.platformId) {
            updateQuickPlatformKey { it.copy(message = UiMessage("服务地址已改变，请重新添加密钥", true)) }
            return
        }
        cancelProbe(); cancelOnlineProbe(); cancelJevProbe()
        val result = runCatching {
            prefs.savePlatformCredential(draft.platformId, draft.credentialId, draft.name, draft.key)
            if (target != null) {
                // 首次填入平台默认项时保留“跟随默认”，之后切换默认仍对该服务生效。
                val alreadyFollowsDefault = runCatching { prefs.selectedCredentialId(draft.platformId, target.second) == null }.getOrDefault(false) &&
                    prefs.defaultPlatformCredentialId(draft.platformId) == draft.credentialId
                if (!alreadyFollowsDefault) prefs.selectCredential(draft.platformId, target.second, draft.credentialId)
            }
        }
        if (result.isSuccess) mutableState.update { it.copy(settings = it.settings.copy(quickPlatformKey = null)) }
        else updateQuickPlatformKey { it.copy(message = UiMessage(result.exceptionOrNull()?.message ?: "密钥保存失败，请重试", true)) }
        refresh()
    }

    /** 内置音频接口由受支持的标准端点与模型识别，用户已有其他连接仍保留。 */
    private fun isBuiltInListening(profile: ListeningServiceProfile): Boolean = when (profile.mode) {
        ListeningMode.LOCAL -> true
        ListeningMode.CLOUD_ASR -> profile.endpoint == CloudAsrConfig.DEFAULT_ENDPOINT && profile.model in CloudAsrConfig.SUPPORTED_MODELS
        ListeningMode.ONLINE -> LiveTranslateModels.find(profile.model)?.defaultEndpoint == profile.endpoint
    }

    /** 默认文字接口以真实连接识别，不依赖用户可修改的名称。 */
    private fun isBuiltInTranslation(url: String, model: String, profileId: String): Boolean =
        !prefs.isCustomTranslation(profileId) && TranslationPresets.entries.any {
        it.id in setOf("deepseek", "qwen") && it.model == model &&
            ByokTranslator.chatUrl(it.url).second == ByokTranslator.chatUrl(url).second
    }

    /** 下拉显示名称与状态，绝不把密钥值放进服务摘要。 */
    private fun credentialLabel(platformId: String, slot: String): String =
        prefs.credentialFor(platformId, slot)?.let { if (it.key.isEmpty()) "${it.label} · 未填写" else it.label } ?: "未配置密钥"

    /** 损坏绑定只在界面标为未配置，底层取钥仍拒绝回退，避免读取旧偏好时崩溃。 */
    private fun uiCredentialSelection(platformId: String, slot: String): String? =
        runCatching { prefs.selectedCredentialId(platformId, slot) }.getOrNull()

    /** 编辑器可选择平台中的所有命名密钥，并支持跟随平台默认。 */
    private fun credentialOptions(platformId: String): List<UiOption> = listOf(UiOption("", "跟随默认密钥")) +
        prefs.platformCredentials(platformId).filter { prefs.hasStoredPlatformCredential(platformId, it.id) }.map { UiOption(it.id, it.label, if (it.key.isEmpty()) "未填写" else "已保存") }

    /** 云端表单按自己的地址和模型展示凭据绑定，缺密钥时仍能就地新增。 */
    private fun bindListeningCredentials(mode: ListeningMode, draft: OnlineListeningUiState): OnlineListeningUiState {
        val platform = PlatformCredentials.forUrl(draft.endpoint)
        val slot = listeningCredentialSlot(mode, draft.endpoint, draft.model)
        return draft.copy(credentialOptions = platform?.let { credentialOptions(it.id) }.orEmpty(),
            credentialId = platform?.let { uiCredentialSelection(it.id, slot) }.orEmpty(),
            credentialLabel = platform?.let { credentialLabel(it.id, slot) }.orEmpty())
    }

    /** 第三模式使用会话启动时的翻译和上下文快照，运行期间必须停止后再编辑。 */
    private fun cloudSessionLocked(): Boolean = CaptionService.running && prefs.listeningMode == ListeningMode.CLOUD_ASR

    /** 按平台预设添加基础调用连接，内部名称自动生成，不要求用户起用途名称。 */
    private fun createTranslationProvider(presetId: String) {
        val preset = TranslationPresets.entries.firstOrNull { it.id == presetId }
        if (preset == null && presetId != "custom") return
        if (CaptionService.running || state.value.settings.api.hasUnsavedChanges) return
        val base = (preset?.title ?: "自定义接口").take(LlmProfile.MAX_NAME - 4)
        val existing = prefs.byokProfiles.map { it.name }.toSet()
        var name = base
        var suffix = 2
        while (name in existing) { name = "$base $suffix"; suffix++ }
        openTranslationService(null, name)
        if (translationTargetProfileId != null) updateApi { bindApiPlatform(TranslationPresets.apply(it, presetId)) }
    }

    /** 翻译编辑只绑定目标标识，不切换运行配置。 */
    private fun openTranslationService(id: String?, newName: String?) {
        if (CaptionService.running) { message("请先停止同传，再编辑服务", true); return }
        if (state.value.settings.api.hasUnsavedChanges) {
            updateApi { it.copy(message = UiMessage("请先保存或放弃当前修改", true)) }; return
        }
        cancelProbe()
        val target = runCatching {
            if (newName != null) prefs.addByokProfile(newName, select = false, userCreated = true)
            else prefs.byokProfiles.firstOrNull { it.id == id } ?: error("翻译配置不存在")
        }.getOrElse { error ->
            updateApi { it.copy(message = UiMessage(error.message ?: "配置操作失败", true)) }
            return
        }
        translationTargetProfileId = target.id
        updateApi { savedApi() }
        refresh()
    }

    /** 关闭编辑器只清目标标识；活动翻译服务从未被编辑器改变。 */
    private fun closeTranslationServiceEditor() {
        cancelProbe()
        translationTargetProfileId = null
        updateApi { savedApi() }
        refresh()
    }

    /** 列表确认后按稳定标识删除服务；平台密钥保留，运行与未保存草稿均阻止删除。 */
    private fun deleteTranslationService(id: String) {
        if (CaptionService.running || state.value.live.busy || state.value.settings.api.testing || state.value.settings.api.saving) return
        changeApiProfile {
            val profile = prefs.byokProfiles.firstOrNull { it.id == id } ?: error("翻译服务不存在")
            prefs.deleteByokProfile(profile)
            if (translationTargetProfileId == profile.id) translationTargetProfileId = null
        }
    }

    /** 配置管理先处理未保存草稿并取消旧测试，之后回填所选配置自己的字段与凭据。 */
    private fun changeApiProfile(change: () -> Unit) {
        if (cloudSessionLocked()) return
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
            val draft = bindApiPlatform(change(it.settings.api))
            it.copy(settings = it.settings.copy(api = draft.copy(hasUnsavedChanges = isApiDirty(draft)),
                platformKeys = savedPlatformKeys(it.settings.platformKeys, draft.url)))
        }
        refreshOnboardingSetup()
    }

    /** 连接草稿只比较连接字段，中央密钥的修改由独立草稿保护。 */
    private fun isApiDirty(draft: TranslationApiUiState): Boolean {
        val profile = apiProfile() ?: return true
        return draft.profileId != profile.id || draft.url.trim() != prefs.byokUrl(profile).trim() ||
            draft.model.trim() != prefs.byokModel(profile).trim() || draft.userAgent.trim() != prefs.byokUserAgent(profile) ||
            draft.headers.map { it.name.trim() to it.value } != prefs.byokHeaders(profile)
    }

    /** 地址变化重新绑定对应平台密钥，同时清空旧连接的附加请求头。 */
    private fun editApiDraft(draft: TranslationApiUiState) {
        if (cloudSessionLocked()) return
        val previous = state.value.settings.api
        if (draft.profileId != previous.profileId) return
        val endpointChanged = previous.url.isNotBlank() &&
            ByokTranslator.chatUrl(previous.url).second != ByokTranslator.chatUrl(draft.url).second
        cancelProbe()
        updateApi {
            draft.copy(
                testing = false, canTest = !CaptionService.running,
                key = null,
                hasSavedKey = false,
                headers = if (endpointChanged) emptyList() else draft.headers,
                userAgent = if (endpointChanged) "" else draft.userAgent,
                suggestedUrl = "",
                message = if (endpointChanged) UiMessage("服务地址已变更，将使用对应平台已保存的密钥。")
                    else draft.message.takeUnless { it == previous.message },
            )
        }
    }

    /** 校验整套翻译配置并一次保存，防止运行会话读到半套配置。 */
    private fun saveApi(): Boolean {
        if (cloudSessionLocked()) return false
        val draft = state.value.settings.api
        val profile = apiProfile() ?: return false
        if (draft.profileId != profile.id) return false
        val (error, url) = ByokTranslator.chatUrl(draft.url)
        val headers = draft.headers.map { it.name.trim() to it.value }
        val headerError = ByokService.badAdvanced(draft.userAgent, headers)
        val problem = error ?: if (url == null) "翻译地址无效" else if (draft.model.isBlank()) "请填写模型 ID" else headerError
        if (problem != null) { updateApi { it.copy(message = UiMessage(problem, true)) }; return false }
        cancelProbe()
        // 连接表单不再写密钥，平台凭据只由集中配置页保存。
        val key: String? = null
        runCatching { prefs.saveByok(draft.url, draft.model, key, draft.userAgent, headers, profile) }
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
        if (CaptionService.running || probe != null || onboardingSetupProbe != null) return
        val draft = apiSnapshot(state.value.settings.api)
        if (draft.profileId != apiProfile()?.id) return
        val (error, url) = ByokTranslator.chatUrl(draft.url)
        val problem = error ?: if (draft.model.isBlank()) "请填写模型 ID" else ByokService.badAdvanced(draft.userAgent, draft.headers)
        if (problem != null || url == null) { updateApi { it.copy(message = UiMessage(problem ?: "翻译地址无效", true)) }; return }
        cancelProbe()
        val previous = apiProfile()?.let { prefs.byokService(it) }
        val key = prefs.keyForService(draft.url, "translation:${draft.profileId}")
        val service = ByokService(url, draft.model, key, draft.userAgent, draft.headers)
        val translator = ByokTranslator(service, byokSystem(prefs.scene, prefs.sourceLang, prefs.targetLang), null, maxTokens = mtMaxTokens(prefs.targetLang),
            onUsage = { costs.recordTranslation(null, CostCategory.TEST, service, it) })
        probe = translator
        probeService = previous
        probeActiveProfileId = prefs.byokProfile.id
        updateApi { it.copy(testing = true, canTest = false, suggestedUrl = "", message = UiMessage(
            if (onboardingStep == OnboardingStep.API) "正在测试连接；密钥已自动保存。"
            else "正在测试草稿地址与关闭思考参数，最多 2 个同源地址、12 次请求，服务商可能计费；不会自动保存。")) }
        probeJob = viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) { runCatching { translator.probeConnection(draft.url) }.getOrNull() }
            if (probe === translator) {
                if (CaptionService.running || prefs.byokProfile.id != probeActiveProfileId ||
                    apiProfile()?.let { prefs.byokService(it) } != previous || apiSnapshot(state.value.settings.api) != draft) {
                    cancelProbe(); return@launch
                }
                probe = null
                probeService = null
                probeActiveProfileId = null
                probeJob = null
                val testedUrl = outcome?.url
                if (testedUrl == null) {
                    updateApi { it.copy(testing = false, canTest = true, suggestedUrl = "", message = UiMessage(
                        outcome?.lastResult?.let { result -> ByokTranslator.why(result) }
                            ?: outcome?.error ?: "连接测试失败，请检查服务配置后重试", true)) }
                } else {
                    testedEndpoint = TestedEndpoint(service, draft, previous, prefs.byokProfile.id, testedUrl, outcome.variantName)
                    val notice = if (outcome.variantName == ByokTranslator.NONE_WORKED)
                        "连接可用，但未能关闭思考，字幕延迟可能较大" else "连接测试通过"
                    updateApi { it.copy(testing = false, canTest = true, suggestedUrl = testedUrl,
                        message = UiMessage(if (onboardingStep == OnboardingStep.API) "$notice；密钥已自动保存。"
                            else "$notice；可在下方替换并保存，不操作即保持原配置。")) }
                }
            }
        }
    }

    /** 只保存仍对应当前草稿的成功结果，保留本次真实测试的凭据，不重新请求网络。 */
    private fun applyTestedApiUrl() {
        val tested = testedEndpoint ?: return
        if (CaptionService.running || probe != null || prefs.byokProfile.id != tested.activeProfileId ||
            apiProfile()?.id != tested.draft.profileId ||
            apiProfile()?.let { prefs.byokService(it) } != tested.previouslySaved ||
            apiSnapshot(state.value.settings.api) != tested.draft || state.value.settings.api.suggestedUrl != tested.url) {
            cancelProbe()
            updateApi { it.copy(message = UiMessage("配置已变更或同传正在运行，请停止后重新测试", true)) }
            return
        }
        testedEndpoint = null
        val target = apiProfile() ?: return
        prefs.saveByok(tested.url, tested.service.model, null, tested.service.userAgent, tested.service.headers, target)
        tested.variantName?.let { prefs.setByokVariant(tested.url, tested.service.model, it) }
        updateApi { savedApi().copy(message = UiMessage("已替换并保存测试通过的地址")) }
        refresh()
    }

    /** 先使旧结果失效并复位界面，再终止网络和协程；已发送的请求无法撤回。 */
    private fun cancelProbe() {
        testedEndpoint = null
        val translator = probe
        val setupProbe = onboardingSetupProbe
        onboardingSetupProbe = null
        probe = null
        probeService = null
        probeActiveProfileId = null
        val job = probeJob
        probeJob = null
        updateApi { it.copy(testing = false, canTest = !CaptionService.running, suggestedUrl = "", message = if (it.testing) UiMessage("连接测试已取消") else it.message) }
        translator?.cancel()
        setupProbe?.cancel()
        job?.cancel()
    }

    /** 模式切换只改变下次会话，已有模型与三套接口及各自草稿均保留。 */
    private fun matchesActive(profile: ListeningServiceProfile): Boolean = profile.mode == prefs.listeningMode &&
        when (profile.mode) {
            ListeningMode.CLOUD_ASR -> profile.endpoint == prefs.cloudAsrEndpoint && profile.model == prefs.cloudAsrModel
            ListeningMode.ONLINE -> profile.endpoint == prefs.onlineEndpoint && profile.model == prefs.onlineModel
            else -> false
        }

    /** 目录保存连接参数，密钥完整性按当前中央平台存储实时计算。 */
    private fun profileHasKey(profile: ListeningServiceProfile): Boolean = profileKey(profile).isNotEmpty()

    /** 运行和测试引用同一模型对应的密钥选择。 */
    private fun profileKey(profile: ListeningServiceProfile): String = prefs.keyForService(profile.endpoint,
        listeningCredentialSlot(profile.mode, profile.endpoint, profile.model))

    /** 创建临时编辑草稿，保存前不把预设放入首页服务列表。 */
    private fun createListeningService(kind: String, name: String) {
        if (CaptionService.running) { message("请先停止同传，再创建服务", true); return }
        val mode = if (kind == "asr") ListeningMode.CLOUD_ASR else ListeningMode.ONLINE
        val model = when (kind) {
            "asr" -> CloudAsrConfig.DEFAULT_MODEL
            "realtime" -> LiveTranslateModels.REALTIME_ID
            else -> return
        }
        val endpoint = if (mode == ListeningMode.CLOUD_ASR) CloudAsrConfig.DEFAULT_ENDPOINT
            else requireNotNull(LiveTranslateModels.find(model)).defaultEndpoint
        openListeningEditor(ListeningServiceProfile(UUID.randomUUID().toString(),
            name.trim().ifBlank { listeningServiceTitle(mode, model) }.take(ListeningServiceProfile.MAX_NAME), mode, endpoint, model), true)
    }

    /** 编辑目录中的指定服务，不切换首页当前使用的服务。 */
    private fun editListeningService(id: String) {
        if (CaptionService.running) { message("请先停止同传，再编辑服务", true); return }
        services.profiles.firstOrNull { it.id == id }?.let { openListeningEditor(it, false) }
    }

    /** 绑定编辑基线与独立连接草稿，测试与取消仍复用现有客户端。 */
    private fun openListeningEditor(profile: ListeningServiceProfile, isNew: Boolean) {
        cancelOnlineProbe()
        listeningEditorBaseline = profile
        listeningEditor = ListeningEditorUiState(profile.id, profile.name, profile.mode, isNew)
        val draft = OnlineListeningUiState(endpoint = profile.endpoint, model = profile.model,
            key = profileKey(profile), hasSavedConfiguration = !isNew && profileHasKey(profile))
        if (profile.mode == ListeningMode.CLOUD_ASR) updateCloudAsr { draft } else updateOnline { draft }
        refresh()
    }

    /** 放弃当前草稿时一并恢复名称，避免连接恢复后仍被名称修改阻止返回。 */
    private fun discardListeningDraft(mode: ListeningMode) {
        cancelOnlineProbe()
        if (listeningEditor?.mode == mode) listeningEditor = listeningEditor?.copy(name = listeningEditorBaseline?.name.orEmpty())
        if (mode == ListeningMode.CLOUD_ASR) updateCloudAsr { savedCloudAsr() }
        else updateOnline { savedOnline() }
        refresh()
    }

    /** 离开编辑后恢复当前使用的连接，已保存的其他服务留在目录。 */
    private fun closeListeningServiceEditor() {
        cancelOnlineProbe()
        listeningEditor = null; listeningEditorBaseline = null
        updateOnline { savedOnline() }; updateCloudAsr { savedCloudAsr() }
        refresh()
    }

    /** 保存具名服务；仅同步正在使用的配置，新服务由用户明确选择后使用。 */
    private fun saveListeningService() {
        if (CaptionService.running) return
        val editor = listeningEditor ?: return
        val draft = if (editor.mode == ListeningMode.CLOUD_ASR) state.value.settings.cloudAsr else state.value.settings.online
        val profile = ListeningServiceProfile(editor.id, editor.name.trim(), editor.mode, draft.endpoint.trim(), draft.model.trim())
        val wasActive = services.activeId == profile.id
        runCatching { services.save(profile) }.onSuccess {
            cancelOnlineProbe()
            listeningEditorBaseline = profile
            listeningEditor = editor.copy(name = profile.name, isNew = false)
            if (wasActive) activateListeningProfile(profile)
            val notice = UiMessage(if (profileHasKey(profile)) "服务已保存，可在首页选择" else "服务已保存，请选择或添加平台密钥")
            if (editor.mode == ListeningMode.CLOUD_ASR) updateCloudAsr { savedCloudAsr().copy(message = notice) }
            else updateOnline { savedOnline().copy(message = notice) }
            refresh()
        }.onFailure { error ->
            val notice = UiMessage(error.message ?: "服务保存失败，请检查连接设置", true)
            if (editor.mode == ListeningMode.CLOUD_ASR) updateCloudAsr { it.copy(message = notice) }
            else updateOnline { it.copy(message = notice) }
        }
    }

    /** 运行引擎仍读取原有偏好快照；选择服务时同步完整连接与对应平台密钥。 */
    private fun rememberLegacyService(mode: ListeningMode, endpoint: String, model: String) {
        val previous = services.profiles.firstOrNull { it.mode == mode && it.id == services.activeId }
            ?: services.profiles.firstOrNull { it.mode == mode && it.endpoint == endpoint && it.model == model }
        val profile = ListeningServiceProfile(previous?.id ?: UUID.randomUUID().toString(),
            previous?.name ?: if (mode == ListeningMode.CLOUD_ASR) "千问在线听写" else LiveTranslateModels.find(model)?.label ?: "千问语音直译",
            mode, endpoint, model)
        services.save(profile)
        if (mode == prefs.listeningMode) services.select(profile.id)
    }

    /** 选择服务只更新该路径，不覆写另外一种云端协议的保存值。 */
    private fun activateListeningProfile(profile: ListeningServiceProfile) {
        if (profile.mode == ListeningMode.CLOUD_ASR) prefs.saveCloudAsr(profile.endpoint, profile.model, profileKey(profile))
        else prefs.saveOnline(profile.endpoint, profile.model, profileKey(profile))
        services.select(profile.id)
        changeListeningMode(profile.mode)
        if (listeningEditor == null) {
            updateOnline { savedOnline() }; updateCloudAsr { savedCloudAsr() }
        }
    }

    /** 首页仅接收已保存服务或已安装模型标识，不含下载与管理动作。 */
    private fun selectListeningService(id: String) {
        if (CaptionService.running) { message("请先停止同传，再切换听写服务", true); return }
        val settings = state.value.settings
        if (settings.api.hasUnsavedChanges || settings.online.hasUnsavedChanges || settings.cloudAsr.hasUnsavedChanges) {
            message("请先保存或放弃当前连接修改，再切换听写服务", true)
            return
        }
        when {
            id.startsWith("profile:") -> {
                val profile = services.profiles.firstOrNull { it.id == id.removePrefix("profile:") } ?: return
                activateListeningProfile(profile)
            }
            id.startsWith("local:") -> {
                val modelId = id.removePrefix("local:")
                val entry = models.state.value.entries.firstOrNull { it.spec.id == modelId } ?: return
                preferredLocalModelId = modelId
                appearance.edit().putString("preferred_local_model", modelId).apply()
                if (entry.status == ModelStatus.READY) models.select(modelId)
                services.select(null)
                changeListeningMode(ListeningMode.LOCAL)
                modelSpec(modelId)?.let { spec ->
                    if (prefs.sourceLang !in spec.sourceLanguages && !(prefs.sourceLang == null && spec.supportsAutoLanguage))
                        prefs.sourceLang = spec.sourceLanguages.first()
                }
            }
            id.startsWith("asr:") && id.removePrefix("asr:") in CloudAsrConfig.SUPPORTED_MODELS -> {
                changeListeningMode(ListeningMode.CLOUD_ASR)
                rememberLegacyService(ListeningMode.CLOUD_ASR, prefs.cloudAsrEndpoint, prefs.cloudAsrModel)
            }
            id.startsWith("direct:") -> {
                val modelId = id.removePrefix("direct:")
                if (LiveTranslateModels.find(modelId) == null) return
                val endpoint = LiveTranslateModels.endpointAfterSelection(prefs.onlineEndpoint, prefs.onlineModel, modelId)
                val key = PlatformCredentials.forUrl(endpoint)?.let { prefs.platformKey(it.id) }.orEmpty()
                prefs.saveOnline(endpoint, modelId, key)
                updateOnline { savedOnline() }
                changeListeningMode(ListeningMode.ONLINE)
                rememberLegacyService(ListeningMode.ONLINE, endpoint, modelId)
            }
        }
        refresh()
    }

    /** 引导先选择基础服务再填写凭据，未配密钥不妨碍进入下一配置步骤。 */
    private fun chooseOnboardingListeningService(id: String) {
        if (onboardingStep != OnboardingStep.MODE || CaptionService.running || state.value.live.busy) return
        selectListeningService(id)
        if (state.value.live.listeningServiceId == id) advanceOnboarding(false)
    }

    /** 引导固定两条基础路线，只有明确点击本地下载按钮才允许联网下载。 */
    private fun chooseOnboardingRoute(mode: ListeningMode) {
        if (onboardingStep != OnboardingStep.MODE || CaptionService.running || state.value.live.busy) return
        if (mode !in listOf(ListeningMode.LOCAL, ListeningMode.ONLINE)) return
        cancelProbe(); cancelOnlineProbe()
        onboardingKeyDraft = null; onboardingSaveError = null
        runCatching {
            if (mode == ListeningMode.LOCAL) prepareOnboardingLocal()
            else {
                val endpoint = LiveTranslateModels.REALTIME_ENDPOINT
                val model = LiveTranslateModels.REALTIME_ID
                val existing = services.profiles.firstOrNull { it.mode == ListeningMode.ONLINE && it.endpoint == endpoint && it.model == model }
                    ?: ListeningServiceProfile(ListeningServiceProfile.newId(), "千问实时听译", ListeningMode.ONLINE, endpoint, model).also(services::save)
                activateListeningProfile(existing)
                updateOnline { savedOnline() }
            }
            onboardingStep = if (mode == ListeningMode.ONLINE) OnboardingStep.ONLINE else OnboardingStep.API
        }.onFailure { onboardingSaveError = UiMessage(it.message ?: "配置暂未完成，请重试", true) }
        refresh()
    }

    /** 选择本地只预填配置并启用已安装模型；下载必须在配置页由用户主动点击。 */
    private fun prepareOnboardingLocal() {
        clearModelOperationNotice()
        preferredLocalModelId = "reazonspeech"
        appearance.edit().putString("preferred_local_model", preferredLocalModelId).apply()
        services.select(null)
        changeListeningMode(ListeningMode.LOCAL)
        prepareOnboardingTranslation()
        val entry = models.state.value.entries.firstOrNull { it.spec.id == "reazonspeech" }
        if (entry?.status == ModelStatus.READY) models.select("reazonspeech")
    }

    /** 下载完成即启用引导确认的模型，不影响离开引导后用户选择的其他模型。 */
    private fun activateOnboardingModelWhenReady() {
        if (onboardingStep !in listOf(OnboardingStep.API, OnboardingStep.MODEL) || prefs.listeningMode != ListeningMode.LOCAL ||
            !appearance.getBoolean("onboarding_local_requested", false)) return
        val library = models.state.value
        if (library.selectedId != "reazonspeech" && library.entries.any { it.spec.id == "reazonspeech" && it.status == ModelStatus.READY })
            models.select("reazonspeech")
    }

    /** 只提示本次观察到的下载终态，已安装模型的普通扫描不重复报下载完成。 */
    private fun announceModelDownloadChanges(library: ModelLibraryState) {
        val active = setOf(ModelStatus.QUEUED, ModelStatus.DOWNLOADING, ModelStatus.VERIFYING)
        pendingModelDownloads += library.entries.filter { it.status in active }.map { it.spec.id }
        val completed = library.entries.filter { it.spec.id in pendingModelDownloads && it.status == ModelStatus.READY }
        val failed = library.entries.firstOrNull { it.spec.id in pendingModelDownloads && it.status == ModelStatus.FAILED }
        val cancelled = library.entries.firstOrNull { it.spec.id in pendingModelDownloads && it.status == ModelStatus.CANCELLED }
        pendingModelDownloads.removeAll(library.entries.filter { it.status in setOf(ModelStatus.READY, ModelStatus.FAILED, ModelStatus.CANCELLED) }.map { it.spec.id }.toSet())
        when {
            completed.isNotEmpty() -> message("${completed.joinToString("、") { it.spec.name }} 模型下载完成")
            failed != null -> message("${failed.spec.name} 下载失败：${failed.error ?: "请重试"}", true)
            cancelled != null -> message("${cancelled.spec.name} 下载已取消")
        }
    }

    /** 新操作或重新配置结束旧操作提示的展示周期，模型自身的实际错误仍由仓库状态保留。 */
    private fun clearModelOperationNotice() {
        mutableState.update { root -> root.copy(models = root.models.copy(message = null)) }
    }

    /** 先记录用户确认的下载，再交给持久任务，避免缓存命中太快而漏掉完成提示。 */
    private fun startModelDownload(id: String) {
        clearModelOperationNotice()
        // 跳过方式选择后仍可在配置页主动下载，此时也需要在下载完成后自动启用。
        if (id == "reazonspeech" && prefs.listeningMode == ListeningMode.LOCAL &&
            onboardingStep in listOf(OnboardingStep.API, OnboardingStep.MODEL))
            appearance.edit().putBoolean("onboarding_local_requested", true).apply()
        if (!models.isReady(id)) pendingModelDownloads += id
        models.download(id)
    }

    /** 本地完成条件明确要求初始识别模型已校验且已选中，不能借用别的模型的就绪状态。 */
    private fun onboardingLocalReady(): Boolean = models.state.value.selectedId == "reazonspeech" &&
        models.state.value.entries.any { it.spec.id == "reazonspeech" && it.status == ModelStatus.READY } && models.selectedPaths() != null

    /** 引导中的供应商模板提供全部连接字段；用户只接触供应商、模型和密钥。 */
    private fun onboardingProvider(): TextServiceProvider = TextServiceProviders.entries.first { it.id == onboardingProviderId }

    /** 探测地址只允许保留模板的协议、主机与端口，不能将共享凭据发往其他来源。 */
    private fun onboardingEndpoint(): String {
        val provider = onboardingProvider()
        val candidate = appearance.getString("onboarding_endpoint:${provider.id}", provider.url).orEmpty()
        return candidate.takeIf { sameOrigin(it, provider.url) && ByokTranslator.chatUrl(it).first == null } ?: provider.url
    }

    /** 自动配置仅更换同源接口路径，不扫描端口或接受其他站点地址。 */
    private fun sameOrigin(first: String, second: String): Boolean = runCatching {
        val a = java.net.URI(first); val b = java.net.URI(second)
        a.scheme.equals(b.scheme, true) && a.host != null && a.host.equals(b.host, true) && a.port == b.port
    }.getOrDefault(false)

    /** 成功获取的账户模型目录优先于预设；未获取目录时仍可使用内置建议。 */
    private fun onboardingTranslationModels(): List<String> {
        val provider = onboardingProvider()
        val stored = appearance.getString("onboarding_models:${provider.id}", null)
        val discovered = runCatching { org.json.JSONArray(stored ?: "[]") }
            .getOrNull()?.let { data -> (0 until minOf(data.length(), 256)).mapNotNull { data.opt(it) as? String } }.orEmpty()
            .filter { it.isNotBlank() && it.length <= 256 && it.none(Char::isISOControl) }.distinct()
        val choices = if (stored != null) discovered else TextModelCatalog.forProvider(provider.id)
        val selected = appearance.getString("onboarding_model:${provider.id}", null)
            ?.takeIf { it.isNotBlank() && it.length <= 256 && it.none(Char::isISOControl) }
        return if (selected != null && selected !in choices) listOf(selected) + choices else choices
    }

    /** 每个平台记住自己的选择，探测返回的新模型同样能在引导中选择。 */
    private fun onboardingTranslationModel(): String {
        val provider = onboardingProvider()
        val models = onboardingTranslationModels()
        val default = provider.model.takeIf { it.isNotBlank() && it in models } ?: models.firstOrNull().orEmpty()
        return appearance.getString("onboarding_model:${provider.id}", default).orEmpty().takeIf { it in models } ?: default
    }

    /** 复用完全相同的已存服务，否则只复用引导自建项，避免覆盖用户其他接口。 */
    private fun prepareOnboardingTranslation() {
        val provider = onboardingProvider()
        val model = onboardingTranslationModel()
        val exact = prefs.byokProfiles.firstOrNull { prefs.byokUrl(it) == onboardingEndpoint() && prefs.byokModel(it) == model }
        val ownedId = appearance.getString("onboarding_profile:${provider.id}", null)
        val owned = prefs.byokProfiles.firstOrNull { it.id == ownedId }
        val profile = exact ?: owned ?: run {
            val base = "初次配置 · ${provider.title}"
            var name = base
            var suffix = 2
            while (prefs.byokProfiles.any { it.name == name }) name = "$base ${suffix++}"
            prefs.addByokProfile(name, select = false).also {
                appearance.edit().putString("onboarding_profile:${provider.id}", it.id).apply()
            }
        }
        prefs.saveByok(onboardingEndpoint(), model, null, profile = profile)
        prefs.useByokProfile(profile)
        translationTargetProfileId = null
        updateApi { savedApi() }
    }

    /** 极简引导测试自动保存真实通过的地址与模型，普通设置页仍保留原有人工确认行为。 */
    private fun testOnboardingTranslation() {
        if (onboardingStep != OnboardingStep.API || prefs.listeningMode != ListeningMode.LOCAL ||
            CaptionService.running || probe != null || onboardingSetupProbe != null) return
        val draft = apiSnapshot(state.value.settings.api)
        val target = apiProfile() ?: return
        val previous = prefs.byokService(target) ?: return
        if (draft.profileId != target.id || previous.key.isEmpty()) return
        cancelProbe()
        val provider = onboardingProviderId
        val selectedProfile = prefs.byokProfile.id
        val client = onboardingProbeFactory(previous, byokSystem(prefs.scene, prefs.sourceLang, prefs.targetLang),
            mtMaxTokens(prefs.targetLang)) { service, usage -> costs.recordTranslation(null, CostCategory.TEST, service, usage) }
        onboardingSetupProbe = client
        probeService = previous; probeActiveProfileId = selectedProfile
        val candidates = onboardingTranslationModels()
        updateApi { it.copy(testing = true, canTest = false, message = UiMessage("正在获取模型并测试连接…")) }
        probeJob = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { client.probe(candidates) }
                if (onboardingSetupProbe !== client) return@launch
                if (onboardingStep != OnboardingStep.API || provider != onboardingProviderId || CaptionService.running ||
                    selectedProfile != prefs.byokProfile.id || previous != prefs.byokService(target) ||
                    draft != apiSnapshot(state.value.settings.api)) { cancelProbe(); return@launch }
                onboardingSetupProbe = null; probeService = null; probeActiveProfileId = null; probeJob = null
                val url = result.url
                val model = result.model
                // 即使前几项未通过，也保留供应商目录供用户选择；当前配置不因列表刷新而偷偷变更。
                if (result.catalogAvailable && (url == null || sameOrigin(url, previous.url))) appearance.edit()
                    .putString("onboarding_models:$provider", org.json.JSONArray(result.models).toString())
                    .putString("onboarding_model:$provider", draft.model).apply()
                if (url == null || model == null || !sameOrigin(url, previous.url) || ByokTranslator.chatUrl(url).first != null) {
                    updateApi { it.copy(testing = false, canTest = true,
                        message = UiMessage(result.error ?: "没有找到可用的翻译配置，请选择模型后重试", true)) }
                    refresh()
                    return@launch
                }
                runCatching {
                    prefs.saveByok(url, model, null, previous.userAgent, previous.headers, target)
                    result.variantName?.let { prefs.setByokVariant(url, model, it) }
                    appearance.edit().apply {
                        putString("onboarding_endpoint:$provider", url)
                        putString("onboarding_model:$provider", model)
                        if (result.catalogAvailable && result.models.isNotEmpty())
                            putString("onboarding_models:$provider", org.json.JSONArray(result.models).toString())
                    }.apply()
                }.onSuccess {
                    val note = if (result.catalogAvailable) "模型列表已更新" else "未获取模型列表，保留已有选项"
                    val thinking = if (result.variantName == ByokTranslator.NONE_WORKED) "；该模型思考未关闭，字幕可能较慢" else ""
                    updateApi { savedApi().copy(message = UiMessage("连接测试通过，已自动选择并保存 $model；$note$thinking")) }
                }.onFailure {
                    updateApi { it.copy(testing = false, canTest = true, message = UiMessage("配置未能保存，请重试", true)) }
                }
                refresh()
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) {
                if (onboardingSetupProbe === client) {
                    onboardingSetupProbe = null; probeService = null; probeActiveProfileId = null; probeJob = null
                    updateApi { it.copy(testing = false, canTest = true, message = UiMessage("连接测试失败，请稍后重试", true)) }
                }
            } finally { client.cancel() }
        }
    }

    /** 供应商切换重新读取该平台自己的密钥，不把上个平台输入带到新地址。 */
    private fun changeOnboardingProvider(id: String) {
        if (onboardingStep != OnboardingStep.API || CaptionService.running || TextServiceProviders.entries.none { it.id == id }) return
        cancelProbe()
        onboardingProviderId = id
        onboardingKeyDraft = null; onboardingSaveError = null
        appearance.edit().putString("onboarding_provider", id).apply()
        runCatching { prepareOnboardingTranslation() }
            .onFailure { onboardingSaveError = UiMessage(it.message ?: "供应商配置保存失败", true) }
        refresh()
    }

    /** 模型只能从该平台的预制项选取，连接地址始终由模板自动提供。 */
    private fun changeOnboardingModel(model: String) {
        if (onboardingStep != OnboardingStep.API || CaptionService.running || model !in onboardingTranslationModels()) return
        cancelProbe()
        onboardingKeyDraft = null; onboardingSaveError = null
        appearance.edit().putString("onboarding_model:$onboardingProviderId", model).apply()
        runCatching { prepareOnboardingTranslation() }
            .onFailure { onboardingSaveError = UiMessage(it.message ?: "模型配置保存失败", true) }
        refresh()
    }

    /** 原样保存每次输入到共用凭据库；成功不清空输入，失败保留本次输入供重试。 */
    private fun saveOnboardingKey(value: String) {
        if (onboardingStep !in listOf(OnboardingStep.API, OnboardingStep.ONLINE) || CaptionService.running) return
        cancelProbe(); cancelOnlineProbe()
        onboardingKeyDraft = value; onboardingSaveError = null
        // 账户改变后需重新获取目录，旧账户的模型不能冒充新密钥可用项。
        appearance.edit().remove("onboarding_models:$onboardingProviderId").apply()
        runCatching {
            if (prefs.listeningMode == ListeningMode.ONLINE) {
                prefs.saveOnline(LiveTranslateModels.REALTIME_ENDPOINT, LiveTranslateModels.REALTIME_ID, value)
                updateOnline { savedOnline() }
            } else {
                prefs.saveByok(onboardingEndpoint(), onboardingTranslationModel(), value)
                updateApi { savedApi() }
            }
            onboardingKeyDraft = null
        }.onFailure { onboardingSaveError = UiMessage(it.message ?: "密钥未能保存，请重试", true) }
        refresh()
    }

    /** 引导快照只包含简化表单，成功测试不是就绪前提，错误提示不包含密钥。 */
    private fun onboardingSetup(): OnboardingSetupUiState {
        val online = prefs.listeningMode == ListeningMode.ONLINE
        val draft = state.value.settings
        val key = onboardingKeyDraft ?: if (online) prefs.onlineKey else
            prefs.byokKey.orEmpty().takeIf { prefs.byokUrl == onboardingEndpoint() }.orEmpty()
        return OnboardingSetupUiState(onboardingProviderId, onboardingTranslationModel(),
            TextServiceProviders.entries.map { UiOption(it.id, it.title) },
            onboardingTranslationModels().map { UiOption(it, it) }, key,
            keySaved = onboardingKeyDraft == null && onboardingSaveError == null && key.isNotEmpty(),
            testing = if (online) draft.online.testing else draft.api.testing,
            message = onboardingSaveError ?: if (online) draft.online.message else draft.api.message)
    }

    /** 异步测试只刷新引导表单，避免为一条测试消息重建整个服务目录。 */
    private fun refreshOnboardingSetup() {
        if (onboardingStep != null) mutableState.update { root -> root.copy(onboarding = root.onboarding?.copy(setup = onboardingSetup())) }
    }

    /** 内部运行路径仍兼容旧配置，界面通过听写与翻译两步选择。 */
    private fun changeListeningMode(mode: ListeningMode) {
        if (mode == prefs.listeningMode) return
        if (CaptionService.running) { message("请先停止同传，再切换听译模式", true); return }
        cancelProbe(); cancelOnlineProbe(); cancelJevProbe()
        val source = prefs.modeSource(mode) ?: (prefs.sourceLang?.code ?: "auto")
        val target = prefs.modeTarget(mode) ?: prefs.targetLang
        prefs.saveModeLanguages()
        prefs.listeningMode = mode
        prefs.sourceLang = if (source == "auto") null else Lang.of(source)
        prefs.targetLang = if (mode == ListeningMode.ONLINE && target.lang == null) Target.ZH else target
        if (mode == ListeningMode.LOCAL) {
            modelSpec(models.state.value.selectedId.orEmpty())?.let { spec ->
                if (prefs.sourceLang !in spec.sourceLanguages && !(prefs.sourceLang == null && spec.supportsAutoLanguage)) {
                    prefs.sourceLang = spec.sourceLanguages.first()
                }
            }
        }
        refresh()
    }

    /** 直接听译连接与听写连接均引用同一个千问平台密钥。 */
    private fun savedOnline(): OnlineListeningUiState {
        val editing = listeningEditorBaseline?.takeIf { it.mode == ListeningMode.ONLINE }
        return if (editing != null) OnlineListeningUiState(endpoint = editing.endpoint, model = editing.model,
            key = profileKey(editing), hasSavedConfiguration = listeningEditor?.isNew != true && profileHasKey(editing))
        else OnlineListeningUiState(endpoint = prefs.onlineEndpoint, model = prefs.onlineModel,
            key = prefs.onlineKey, hasSavedConfiguration = prefs.onlineReady)
    }

    /** 听写草稿始终读取自己的配置，与当前听译模式无关。 */
    private fun savedCloudAsr(): OnlineListeningUiState {
        val editing = listeningEditorBaseline?.takeIf { it.mode == ListeningMode.CLOUD_ASR }
        return if (editing != null) OnlineListeningUiState(endpoint = editing.endpoint, model = editing.model,
            key = profileKey(editing), hasSavedConfiguration = listeningEditor?.isNew != true && profileHasKey(editing))
        else OnlineListeningUiState(endpoint = prefs.cloudAsrEndpoint, model = prefs.cloudAsrModel,
            key = prefs.cloudAsrKey, hasSavedConfiguration = prefs.cloudAsrReady)
    }

    /** 引导读取当前分支草稿；设置内的两个表单仍各自独立。 */
    private fun listeningDraft(): OnlineListeningUiState = if (prefs.listeningMode == ListeningMode.CLOUD_ASR)
        state.value.settings.cloudAsr else state.value.settings.online

    /** 听写未保存状态只与自己的持久化字段比较。 */
    private fun isCloudAsrDirty(draft: OnlineListeningUiState): Boolean {
        val saved = savedCloudAsr()
        return draft.endpoint.trim() != saved.endpoint || draft.model.trim() != saved.model ||
            (listeningEditor?.takeIf { it.mode == ListeningMode.CLOUD_ASR }?.let { it.isNew || it.name.trim() != listeningEditorBaseline?.name } == true)
    }

    /** 更新独立听写草稿，同时计算离开保护所需的状态。 */
    private fun updateCloudAsr(change: (OnlineListeningUiState) -> OnlineListeningUiState) {
        mutableState.update { root ->
            val draft = change(root.settings.cloudAsr)
            root.copy(settings = root.settings.copy(cloudAsr = draft.copy(hasUnsavedChanges = isCloudAsrDirty(draft))))
        }
    }

    /** 停止旧测试再编辑听写配置，运行时拒绝写入。 */
    private fun editCloudAsrDraft(value: OnlineListeningUiState) {
        if (CaptionService.running) return
        cancelOnlineProbe()
        updateCloudAsr { it.copy(endpoint = value.endpoint, model = value.model, key = prefs.cloudAsrKey, testing = false, message = null) }
        refresh()
    }

    /** 按当前模式判断已保存的音频服务，不把文字翻译就绪混入听写配置。 */
    private fun savedListeningReady(): Boolean =
        if (prefs.listeningMode == ListeningMode.CLOUD_ASR) prefs.cloudAsrReady else prefs.onlineReady

    /** 仅比较当前模式对应的持久化连接，测试结果不属于输入。 */
    private fun isOnlineDirty(draft: OnlineListeningUiState): Boolean {
        val saved = savedOnline()
        return draft.endpoint.trim() != saved.endpoint || draft.model.trim() != saved.model ||
            (listeningEditor?.takeIf { it.mode == ListeningMode.ONLINE }?.let { it.isNew || it.name.trim() != listeningEditorBaseline?.name } == true)
    }

    /** 统一修改云端草稿，不把用户输入隐式写入偏好。 */
    private fun updateOnline(change: (OnlineListeningUiState) -> OnlineListeningUiState) {
        mutableState.update { root -> root.copy(settings = root.settings.copy(online = change(root.settings.online))) }
        refreshOnboardingSetup()
    }

    /** 编辑使旧测试失效；运行中的会话始终使用启动时的配置快照。 */
    private fun editOnlineDraft(value: OnlineListeningUiState) {
        if (CaptionService.running) return
        cancelOnlineProbe()
        updateOnline { current -> current.copy(endpoint = value.endpoint, model = value.model, key = prefs.onlineKey,
            testing = false, message = null, hasUnsavedChanges = isOnlineDirty(value)) }
        refresh()
    }

    /** 连接测试使用中央千问密钥，文字翻译只按自身平台取密钥。 */
    private fun onlineDraftConfig(draft: OnlineListeningUiState) = LiveTranslateConfig(
        endpoint = draft.endpoint.trim(), model = draft.model.trim(), apiKey = prefs.keyForService(draft.endpoint, listeningCredentialSlot(ListeningMode.ONLINE, draft.endpoint.trim(), draft.model.trim())),
        sourceLang = prefs.sourceLang?.code, targetLang = prefs.targetLang.lang?.code ?: "zh",
    )

    /** 听写连接读取中央千问密钥，场景背景与源语种取当前选择。 */
    private fun cloudAsrDraftConfig(draft: OnlineListeningUiState) = prefs.cloudAsrConfig().copy(
        endpoint = draft.endpoint.trim(), model = draft.model.trim(), apiKey = prefs.keyForService(draft.endpoint, listeningCredentialSlot(ListeningMode.CLOUD_ASR, draft.endpoint.trim(), draft.model.trim())),
    )

    /** 整套保存云端配置，失败不清空输入，成功重新读取持久化值。 */
    private fun saveOnline() {
        if (listeningEditor?.mode == ListeningMode.ONLINE) { saveListeningService(); return }
        if (CaptionService.running) return
        val draft = state.value.settings.online
        val config = onlineDraftConfig(draft)
        config.copy(apiKey = "待配置").validationError()?.let { issue -> updateOnline { it.copy(message = UiMessage(issue, true)) }; return }
        cancelOnlineProbe()
        runCatching { prefs.saveOnline(config.endpoint, config.model, config.apiKey) }
            .onSuccess { rememberLegacyService(ListeningMode.ONLINE, config.endpoint, config.model)
                updateOnline { savedOnline().copy(message = UiMessage("在线听译配置已保存")) }; refresh() }
            .onFailure { updateOnline { it.copy(message = UiMessage("在线听译配置保存失败，请重试", true)) } }
    }

    /** 保存独立听写配置，模式不影响写入目标，也不覆盖直接听译配置。 */
    private fun saveCloudAsr() {
        if (listeningEditor?.mode == ListeningMode.CLOUD_ASR) { saveListeningService(); return }
        if (CaptionService.running) return
        val config = cloudAsrDraftConfig(state.value.settings.cloudAsr)
        config.copy(apiKey = "待配置").validationErrors().firstOrNull()?.let { issue -> updateCloudAsr { it.copy(message = UiMessage(issue, true)) }; return }
        cancelOnlineProbe()
        runCatching { prefs.saveCloudAsr(config.endpoint, config.model, config.apiKey) }
            .onSuccess { rememberLegacyService(ListeningMode.CLOUD_ASR, config.endpoint, config.model)
                updateCloudAsr { savedCloudAsr().copy(message = UiMessage("在线听写配置已保存；文字翻译需单独配置")) }; refresh() }
            .onFailure { updateCloudAsr { it.copy(message = UiMessage("在线听写配置保存失败，请重试", true)) } }
    }

    /** 显式测试只握手与确认配置，不采集音频、不自动保存，也不自动重连。 */
    private fun testOnline() {
        if (CaptionService.running || onlineProbe != null) return
        val config = onlineDraftConfig(state.value.settings.online)
        config.validationError()?.let { issue -> updateOnline { it.copy(message = UiMessage(issue, true)) }; return }
        cancelOnlineProbe()
        val token = onlineProbeGeneration
        val error = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val streamId = UUID.randomUUID().toString()
        val listener = object : LiveTranslateListener {
            /** 只保留客户端生成的错误说明，不把服务端原始正文写入界面或日志。 */
            override fun onError(value: LiveTranslateError) { error.set(value.message) }
            /** 即使测试失败，已返回的计量也属于本次显式测试。 */
            override fun onCumulativeUsage(eventId: String, usage: MeteredUsage) {
                costs.recordObserved(null, CostCategory.TEST, config.endpoint, config.model, usage,
                    eventId = "$streamId:usage", cumulative = true)
            }
        }
        val client = QwenLiveTranslateClient(config, listener)
        val verify = { try { client.start() && client.awaitReady() } finally { client.finish(); client.close() } }
        onlineProbe = client
        updateOnline { it.copy(testing = true, message = UiMessage("正在验证连接与会话配置，不发送播放音频…")) }
        onlineProbeJob = viewModelScope.launch {
            val ready = withContext(Dispatchers.IO) {
                verify()
            }
            if (token != onlineProbeGeneration || onlineProbe !== client) return@launch
            onlineProbe = null; onlineProbeJob = null
            updateOnline { it.copy(testing = false, message = UiMessage(
                if (ready) if (onboardingStep == OnboardingStep.ONLINE) "连接可用，密钥已自动保存。"
                    else "连接与会话配置可用；尚未测试音频听译，配置仍需点击保存。"
                else error.get() ?: "连接测试未通过，请检查地址、密钥、模型和网络", !ready)) }
        }
    }

    /** 听写测试只等待任务握手成功，不发送音频；取消和切换后丢弃旧结果。 */
    private fun testCloudAsr() {
        if (CaptionService.running || onlineProbe != null) return
        val config = cloudAsrDraftConfig(state.value.settings.cloudAsr)
        config.validationErrors().firstOrNull()?.let { issue -> updateCloudAsr { it.copy(message = UiMessage(issue, true)) }; return }
        cancelOnlineProbe()
        val token = onlineProbeGeneration
        val error = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val streamId = UUID.randomUUID().toString()
        val client = QwenStreamingAsrClient(config, onText = {}, onStatus = {}, onFailure = { error.set(it) },
            onUsage = { usage -> costs.recordObserved(null, CostCategory.TEST, config.endpoint, config.model,
                MeteredUsage(inputTokens = usage.inputTokens, outputTokens = usage.outputTokens,
                    audioSeconds = usage.duration?.toDouble()), eventId = streamId, cumulative = true) })
        onlineProbe = client
        updateCloudAsr { it.copy(testing = true, message = UiMessage("正在验证在线听写握手，不发送音频…")) }
        onlineProbeJob = viewModelScope.launch {
            val ready = withContext(Dispatchers.IO) {
                try { client.start() && client.awaitReady(15_000) } finally { client.close() }
            }
            if (token != onlineProbeGeneration || onlineProbe !== client) return@launch
            onlineProbe = null; onlineProbeJob = null
            updateCloudAsr { it.copy(testing = false, message = UiMessage(
                if (ready) "在线听写握手成功；未发送音频、未测试文字翻译，配置仍需点击保存。"
                else error.get() ?: "在线听写连接失败，请检查配置与网络", !ready)) }
        }
    }

    /** 取消只结束网络测试，不丢弃草稿；世代号防止旧结果覆盖新编辑。 */
    private fun cancelOnlineProbe() {
        onlineProbeGeneration++
        val client = onlineProbe
        onlineProbe = null
        client?.close()
        onlineProbeJob?.cancel(); onlineProbeJob = null
        updateOnline { it.copy(testing = false, message = if (it.testing) UiMessage("连接测试已取消") else it.message) }
        updateCloudAsr { it.copy(testing = false, message = if (it.testing) UiMessage("连接测试已取消") else it.message) }
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
        prefs.widthFrac = style.widthFraction.coerceIn(0f, 1f)
        prefs.heightFrac = style.heightFraction.coerceIn(0f, CaptionService.MAX_CAPTION_FRAC)
        prefs.setCaptionRetention(style.limitMode, style.maxSentences, style.keepChars)
        prefs.showJa = style.showOriginal; prefs.showStats = style.showStats; prefs.palette = style.paletteId
        prefs.reserveDraftSpace = style.reserveDraftSpace
        refresh()
    }

    /** 保存高级翻译参数，运行中固定的 Jev 开关保持原会话值。 */
    private fun saveAdvanced(value: AdvancedUiState) {
        if (cloudSessionLocked()) return
        val provider = runCatching { JevProvider.valueOf(value.jevProviderId) }.getOrNull() ?: prefs.jevProvider
        val switched = provider.name != state.value.settings.advanced.jevProviderId || provider != prefs.jevProvider
        if (switched) cancelJevProbe()
        prefs.contextOn = value.contextOn; prefs.contextSentences = value.contextSentences; prefs.dedupeJoin = value.dedupeJoin
        if (!CaptionService.running) { prefs.jevOn = value.jevOn; prefs.jevCheck = value.jevCheck }
        prefs.jevProvider = provider
        val draft = if (switched) value.copy(jevKey = null, jevMessage = null, jevTesting = false) else
            value.copy(jevTesting = state.value.settings.advanced.jevTesting)
        mutableState.update { it.copy(settings = it.settings.copy(advanced = draft)) }; refresh()
    }

    /** 发送一次短文字判断验证真实模型能力；不依赖辅助开关，不保存或采集音频。 */
    private fun testJevConnection() {
        if (CaptionService.running || jevProbe != null || state.value.settings.api.testing ||
            state.value.settings.online.testing || state.value.settings.cloudAsr.testing) return
        val connection = prefs.jevConnection()
        val provider = connection.provider
        val platform = PlatformCredentials.forUrl(connection.url)
        if (connection.url.isEmpty() || connection.model.isBlank()) {
            updateJevProbe(false, UiMessage("请先配置自定义服务的地址与模型", true)); return
        }
        if (connection.key == null) {
            updateJevProbe(false, UiMessage("请先为${provider.label}选择或添加平台密钥", true))
            return
        }
        if (state.value.settings.platformKeys.any { it.id == platform?.id && it.hasUnsavedChanges }) {
            updateJevProbe(false, UiMessage("平台密钥有未保存的修改，请先保存再测试", true))
            return
        }
        cancelJevProbe()
        val token = jevProbeGeneration
        val client = jevProbeFactory(connection, prefs.sourceLang, prefs.targetLang)
        jevProbe = client
        updateJevProbe(true, UiMessage("正在验证${provider.label}的连接、鉴权与模型响应…"))
        jevProbeJob = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    runCatching { client.unfinished("このV何者気になるV、他とは違うVだから") }.getOrNull()
                }
                if (token != jevProbeGeneration || jevProbe !== client || !connection.sameAs(prefs.jevConnection())) return@launch
                jevProbe = null; jevProbeJob = null
                val valid = result?.status == 200 && result.error == null && result.noul?.let { it in 0.0..1.0 } == true
                val detail = when {
                    valid -> "连接测试通过 · HTTP 200 · ${result!!.ms} 毫秒 · ${connection.model}"
                    result?.status == 401 || result?.status == 403 -> "鉴权失败，请检查平台密钥、账户余额与模型权限"
                    result?.status == 429 || result?.error == "paused" -> "服务限流或额度不足，请稍后再试或检查账户配额"
                    result?.status == 404 -> "服务地址或模型不可用，请检查供应商支持情况"
                    result?.status == 200 -> "服务已连接，但模型未返回有效的 Jev 判断结果"
                    result?.error == "bad key" -> "已保存的密钥无法用于 HTTP 鉴权，请核对平台提供的原文"
                    result == null || result.status == 0 -> "连接失败或超时，请检查网络与服务状态"
                    else -> "连接测试未通过 · HTTP ${result.status}，请检查服务状态"
                }
                updateJevProbe(false, UiMessage(detail, !valid))
            } finally {
                client.cancel()
            }
        }
    }

    /** 测试状态只更新 Jev 区域，不改动用户开关、平台密钥或其他连接草稿。 */
    private fun updateJevProbe(testing: Boolean, message: UiMessage?) {
        mutableState.update { root -> root.copy(settings = root.settings.copy(advanced =
            root.settings.advanced.copy(jevTesting = testing, jevMessage = message))) }
    }

    /** 取消及切换使迟到结果失效，离开页面后不继续显示旧服务测试结果。 */
    private fun cancelJevProbe(showMessage: Boolean = false) {
        jevProbeGeneration++
        val client = jevProbe
        jevProbe = null
        jevProbeJob?.cancel(); jevProbeJob = null
        client?.cancel()
        updateJevProbe(false, if (showMessage && client != null) UiMessage("Jev 连接测试已取消") else null)
    }

    /** 仅在用户点保存时更新Jev密钥，保存后回填本服务的值，避免空框造成提交失败的误解。 */
    private fun saveJevKey() {
        if (cloudSessionLocked()) return
        val draft = state.value.settings.advanced
        val provider = prefs.jevProvider
        if (draft.jevProviderId != provider.name || (!provider.needsKey && provider != JevProvider.CUSTOM)) return
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
                runCatching { generateTerms(service, prefs.byokVariant(service.url, service.model), { prefs.setByokVariant(service.url, service.model, it) }, editor.name, editor.background, editor.terms.map { it.original }.toSet(), count, prefs.sourceLang, Target.parse(editor.termsLanguageId) ?: prefs.targetLang,
                    onUsage = { costs.recordTranslation(null, CostCategory.TRANSLATION, service, it) }) }
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
    override fun onCleared() { prefs.unlisten(prefListener); cancelProbe(); cancelOnlineProbe(); cancelJevProbe(); generation?.cancel(); updateJob?.cancel(); super.onCleared() }

    companion object {
        const val REPOSITORY = "https://github.com/ZOUYIQAQ/yuyi"
        private val placementPreferenceKeys = setOf("overlayX", "overlayY", "overlayBaselineY",
            "overlayBaselineAreaWidth", "overlayBaselineAreaHeight")
    }
}
