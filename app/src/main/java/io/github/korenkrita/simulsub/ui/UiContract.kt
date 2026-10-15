package io.github.korenkrita.simulsub.ui

import io.github.korenkrita.simulsub.icons.LauncherIconChoice

/** 字幕显示策略直接复用核心，预览与实际服务使用同一裁剪规则。 */
typealias CaptionLimitMode = io.github.korenkrita.simulsub.CaptionLimitMode

/** 页面标识由控制器持有，重建后可恢复当前入口。 */
enum class UiDestination(val title: String) { LIVE("同传"), SUBTITLE("字幕"), MODELS("模型库"), SCENES("场景术语"), SETTINGS("设置") }

/** 首次配置只包含不可缺少的权限、翻译服务和识别模型，各步均可跳过。 */
enum class OnboardingStep { PERMISSIONS, API, MODEL }

/** 引导完成状态来自真实持久化配置，不将草稿或下载中模型视为可用。 */
data class OnboardingUiState(val step: OnboardingStep = OnboardingStep.PERMISSIONS,
    val apiReady: Boolean = false, val modelReady: Boolean = false)

/** 主题仅影响应用界面，悬浮字幕的配色独立设置。 */
enum class UiThemeMode(val title: String) { SYSTEM("跟随系统"), LIGHT("浅色"), DARK("深色") }

/** 列表选项的标识直接交还控制器，不在界面解析后台枚举。 */
data class UiOption(val id: String, val label: String, val description: String = "")

/** 消息来自实际操作结果；错误通过文字与语义同时表达。 */
data class UiMessage(val text: String, val isError: Boolean = false)

/** 根状态不依赖 Activity、Prefs、下载器或识别模型，可直接用于 Compose 渲染。 */
data class SimulSubUiState(
    val destination: UiDestination = UiDestination.LIVE,
    val live: LiveUiState = LiveUiState(),
    val models: ModelsUiState = ModelsUiState(),
    val scenes: ScenesUiState = ScenesUiState(),
    val settings: SettingsUiState = SettingsUiState(),
    val message: UiMessage? = null,
    val onboarding: OnboardingUiState? = null,
)

/** 权限按钮只派发标识；授权结果由系统回到控制器后重新传入。 */
data class PermissionUiState(val id: String, val title: String, val description: String, val granted: Boolean, val required: Boolean = true)

/** 每句字幕保留原文和译文；未定稿状态与真实计时信息由服务提供。 */
data class CaptionUiState(val id: String, val original: String, val translated: String, val isDraft: Boolean = false, val detail: String = "")

/** 同传页：canStart 必须同时反映模型完整、翻译配置和必要权限的检查结果。 */
data class LiveUiState(
    val running: Boolean = false,
    val busy: Boolean = false,
    val canStart: Boolean = false,
    val statusTitle: String = "准备同传字幕",
    val statusDetail: String = "先在模型库安装识别模型，再配置翻译服务。",
    val modelLabel: String = "未选择模型",
    val translationLabel: String = "未配置翻译服务",
    val sourceId: String = "ja",
    val targetId: String = "zh",
    val sourceOptions: List<UiOption> = emptyList(),
    val targetOptions: List<UiOption> = emptyList(),
    val customTarget: String = "",
    val sceneId: String = "",
    val sceneOptions: List<UiOption> = emptyList(),
    val finalOnly: Boolean = false,
    val showOriginal: Boolean = false,
    val showStats: Boolean = false,
    val permissions: List<PermissionUiState> = emptyList(),
    val captions: List<CaptionUiState> = emptyList(),
    val elapsed: String = "",
)

/** 状态与可执行动作分开：下载器必须明确给出取消、重试、启用能力。 */
enum class ModelUiStatus(val title: String) {
    NOT_INSTALLED("未安装"), DOWNLOADING("下载中"), VERIFYING("正在校验"),
    READY("已安装"), ACTIVE("使用中"), FAILED("下载失败"), CANCELLED("已取消")
}

/** 模型卡片只展示真实清单；progress 为 null 时显示不定进度，不捏造百分比。 */
data class ModelUiState(
    val id: String,
    val name: String,
    val description: String,
    val languages: String,
    val sizeLabel: String,
    val license: String,
    val sourceLabel: String,
    val status: ModelUiStatus = ModelUiStatus.NOT_INSTALLED,
    val progress: Float? = null,
    val downloadedLabel: String = "",
    val error: String? = null,
    val canDownload: Boolean = false,
    val canCancel: Boolean = false,
    val canActivate: Boolean = false,
    val verificationDetail: String = "",
    val sourceUrl: String = "",
    val licenseUrl: String = "",
    val canDelete: Boolean = false,
    val deletionScope: String = "",
    val deleteBlockedReason: String? = null,
)

/** 模型目录加载失败与目录为空分别呈现，刷新通过回调触发。 */
data class ModelsUiState(val items: List<ModelUiState> = emptyList(), val loading: Boolean = false, val message: UiMessage? = null,
    val storageLabel: String = "", val deleteConfirmationId: String? = null, val deletingId: String? = null)

/** 场景摘要由真实持久化内容转换，不把预览示例写回场景。 */
data class SceneUiState(val id: String, val name: String, val background: String, val termCount: Int, val languageLabel: String, val builtin: Boolean = false, val summary: String = "")

/** 术语编辑行保留稳定标识，输入时不会因增添其他行而失去焦点。 */
data class TermUiState(val id: String, val original: String = "", val translated: String = "")

/** 场景草稿由控制器持有；生成仅追加，保存校验及恢复默认均交给控制器。 */
data class SceneEditorUiState(
    val id: String? = null,
    val name: String = "",
    val background: String = "",
    val termsLanguageId: String = "zh",
    val languageOptions: List<UiOption> = emptyList(),
    val terms: List<TermUiState> = emptyList(),
    val generationCount: String = "20",
    val generating: Boolean = false,
    val saving: Boolean = false,
    val builtin: Boolean = false,
    val message: UiMessage? = null,
    val canGenerate: Boolean = false,
)

/** editor 非空即显示编辑区，手机按返回先关闭编辑区，宽屏与列表并排。 */
data class ScenesUiState(val items: List<SceneUiState> = emptyList(), val selectedId: String = "", val editor: SceneEditorUiState? = null, val message: UiMessage? = null)

/** API 草稿：key=null 表示保留已存密钥，空字符串表示明确清空，其余为新值。 */
data class TranslationApiUiState(
    val presetId: String = "custom",
    val url: String = "",
    val model: String = "",
    val key: String? = null,
    val hasSavedKey: Boolean = false,
    val userAgent: String = "",
    val headers: List<HeaderUiState> = emptyList(),
    val testing: Boolean = false,
    val saving: Boolean = false,
    val canTest: Boolean = true,
    val message: UiMessage? = null,
    val resolvedUrl: String = "",
    /** 只有本次真实测试成功的完整地址才可由页面按钮确认应用。 */
    val suggestedUrl: String = "",
    val hasUnsavedChanges: Boolean = false,
)

/** 自定义请求头以稳定标识编辑，合法性与持久化由控制器校验。 */
data class HeaderUiState(val id: String, val name: String = "", val value: String = "")

/** 字幕配色来自实际 overlay 调色板，界面只渲染 ARGB 色值。 */
data class CaptionPaletteUiState(val id: String, val label: String, val foregroundArgb: Int, val backgroundArgb: Int, val originalArgb: Int = foregroundArgb)

/** 字幕预览明确为示例；真实字幕框拖动位置不由展示层持久化。 */
data class SubtitleStyleUiState(
    val textSp: Float = 18f,
    val backgroundAlpha: Float = 0.8f,
    val widthFraction: Float = 0.9f,
    val heightFraction: Float = 0f,
    val keepSentences: Int = 3,
    val keepChars: Int = 120,
    val maxLines: Int = 0,
    val showOriginal: Boolean = false,
    val showStats: Boolean = false,
    val paletteId: String = "",
    val palettes: List<CaptionPaletteUiState> = emptyList(),
    val minTextSp: Float = 10f,
    val maxTextSp: Float = 40f,
    val minWidthFraction: Float = 0.4f,
    /** 显示句数包含草稿；keepSentences仅用于旧配置迁移，不参与新版保留策略。 */
    val maxSentences: Int = maxOf(4, keepSentences.coerceIn(1, 10)),
    val limitMode: CaptionLimitMode = CaptionLimitMode.SENTENCES,
)

/** 高级设置与旧 Prefs 对齐，Jev 密钥同样使用 null 保留、空串清空的契约。 */
data class AdvancedUiState(
    val contextOn: Boolean = true,
    val contextSentences: Int = 3,
    val maxContextSentences: Int = 10,
    val dedupeJoin: Boolean = false,
    val jevOn: Boolean = false,
    val jevCheck: Boolean = false,
    val jevProviderId: String = "",
    val jevProviders: List<UiOption> = emptyList(),
    val jevNeedsKey: Boolean = false,
    val jevHasSavedKey: Boolean = false,
    val jevKey: String? = null,
    val jevMessage: UiMessage? = null,
)

/** 使用记录由本地日志索引提供；share 只传记录标识，由控制器创建分享 Intent。 */
data class UsageRecordUiState(val id: String, val title: String, val timeLabel: String, val detail: String, val sharing: Boolean = false, val active: Boolean = false)

/** 设置分区的草稿与持久化状态由控制器统一维护。 */
data class SettingsUiState(
    val api: TranslationApiUiState = TranslationApiUiState(),
    val subtitle: SubtitleStyleUiState = SubtitleStyleUiState(),
    val theme: UiThemeMode = UiThemeMode.SYSTEM,
    val dynamicColors: Boolean = false,
    val themeColorId: String = ThemePalettes.DEFAULT_ID,
    val customThemeSeed: Int = ThemePalettes.DEFAULT_CUSTOM_SEED,
    val launcherIcon: LauncherIconChoice = LauncherIconChoice.DEFAULT,
    val permissions: List<PermissionUiState> = emptyList(),
    val advanced: AdvancedUiState = AdvancedUiState(),
    val keepLogs: Boolean = false,
    val records: List<UsageRecordUiState> = emptyList(),
    val recordsLoading: Boolean = false,
    val recordsMessage: UiMessage? = null,
    val version: String = "",
    val updateMessage: String = "",
    val checkingUpdate: Boolean = false,
)

/**
 * 所有业务动作均从这里交回控制器；默认空回调用于单独预览，不代表业务已完成。
 * 草稿回调需立即回填状态；保存/测试应独立校验，测试中锁定草稿；运行中禁用重复测试。
 * 模型下载、完整性、权限、服务、网络、文件、持久化、分享均不在 UI 内执行。
 */
data class SimulSubActions(
    val onNavigate: (UiDestination) -> Unit = {},
    val onOnboardingNext: () -> Unit = {},
    val onOnboardingSkip: () -> Unit = {},
    val onOnboardingBack: () -> Unit = {},
    val onRestartOnboarding: () -> Unit = {},
    val onStart: () -> Unit = {},
    val onStop: () -> Unit = {},
    val onPermission: (String) -> Unit = {},
    val onSourceLanguage: (String) -> Unit = {},
    val onTargetLanguage: (String) -> Unit = {},
    val onCustomTargetChange: (String) -> Unit = {},
    val onApplyCustomTarget: () -> Unit = {},
    val onSelectScene: (String) -> Unit = {},
    val onFinalOnly: (Boolean) -> Unit = {},
    val onRefreshModels: () -> Unit = {},
    val onDownloadModel: (String) -> Unit = {},
    val onCancelDownload: (String) -> Unit = {},
    val onActivateModel: (String) -> Unit = {},
    val onRequestDeleteModel: (String) -> Unit = {},
    val onDismissDeleteModel: () -> Unit = {},
    val onConfirmDeleteModel: (String) -> Unit = {},
    val onModelSource: (String) -> Unit = {},
    val onModelLicense: (String) -> Unit = {},
    val onOpenModelBenchmark: () -> Unit = {},
    val onCreateScene: () -> Unit = {},
    val onEditScene: (String) -> Unit = {},
    val onSceneDraftChange: (SceneEditorUiState) -> Unit = {},
    val onAddTerm: () -> Unit = {},
    val onRemoveTerm: (String) -> Unit = {},
    val onGenerateTerms: () -> Unit = {},
    val onSaveScene: () -> Unit = {},
    val onCloseSceneEditor: () -> Unit = {},
    val onResetScene: (String) -> Unit = {},
    val onDeleteScene: (String) -> Unit = {},
    val onApplyApiPreset: (String) -> Unit = {},
    val onApiDraftChange: (TranslationApiUiState) -> Unit = {},
    val onAddHeader: () -> Unit = {},
    val onRemoveHeader: (String) -> Unit = {},
    val onSaveApi: () -> Unit = {},
    val onTestApi: () -> Unit = {},
    val onApplyTestedApiUrl: () -> Unit = {},
    val onKeepApiUrl: () -> Unit = {},
    val onDiscardApiDraft: () -> Unit = {},
    val onSubtitleStyleChange: (SubtitleStyleUiState) -> Unit = {},
    val onResetOverlayPosition: () -> Unit = {},
    val onThemeChange: (UiThemeMode) -> Unit = {},
    val onThemeColorChange: (String) -> Unit = {},
    val onCustomThemeColorChange: (Int) -> Unit = {},
    val onLauncherIconChange: (LauncherIconChoice) -> Unit = {},
    val onDynamicColorsChange: (Boolean) -> Unit = {},
    val onAdvancedChange: (AdvancedUiState) -> Unit = {},
    val onSaveJevKey: () -> Unit = {},
    val onKeepLogsChange: (Boolean) -> Unit = {},
    val onRefreshRecords: () -> Unit = {},
    val onShareRecord: (String) -> Unit = {},
    val onDeleteRecord: (String) -> Unit = {},
    val onDeleteAllRecords: () -> Unit = {},
    val onCheckUpdate: () -> Unit = {},
    val onOpenRepository: () -> Unit = {},
    val onOpenUpstreamRepository: () -> Unit = {},
    val onDismissMessage: () -> Unit = {},
)
