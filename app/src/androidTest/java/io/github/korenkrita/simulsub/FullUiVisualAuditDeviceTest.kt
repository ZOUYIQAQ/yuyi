package io.github.korenkrita.simulsub

import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import io.github.korenkrita.simulsub.models.ModelCatalog
import io.github.korenkrita.simulsub.models.ModelKind
import io.github.korenkrita.simulsub.ui.*
import io.github.korenkrita.simulsub.icons.IconStyle
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * 真实设备视觉夹具：仅启动空白 Activity 和正式 Compose 页面，业务状态全部留在内存。
 * 不创建控制器、Prefs、模型仓库、服务或网络客户端，也不调用系统授权和分享入口。
 * 手机／平板尺寸、密度、字体缩放由执行者在设备上设置；这里不覆盖 LocalDensity。
 * 截图保存于 externalCacheDir/ui-ergonomics/<实际像素宽x高-系统字体缩放>/场景.png。
 * 常规设置的完整遍历由实际应用验收承担；此处只补充难稳定复现的状态及少量交互证据。
 */
class FullUiVisualAuditDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private var state by mutableStateOf(SimulSubUiState())
    private var pageGeneration by mutableStateOf(0)
    private var savedApi = TranslationApiUiState()
    private lateinit var focusManager: FocusManager
    private var keyboard: SoftwareKeyboardController? = null

    /** 三步都通过真实页脚跳过；正文滚到底部后，页脚仍必须可见、可操作。 */
    @Test fun onboardingThreeStepsCanBeSkippedAtDeviceSize() {
        show(sampleState().copy(onboarding = OnboardingUiState(),
            live = sampleLive().copy(permissions = permissions(false), canStart = false,
                modelLabel = "未启用模型", translationLabel = "未配置"),
            models = ModelsUiState(items = models(ModelUiStatus.NOT_INSTALLED, ModelUiStatus.NOT_INSTALLED)),
            settings = SettingsUiState(api = TranslationApiUiState(canTest = false))))
        screenshot("引导_01_权限_顶部")
        reveal(hasText("系统屏幕共享会在每次开始同传时询问，这里无需提前授权。"))
        compose.onNodeWithText("下一步").assertIsNotEnabled()
        compose.onNodeWithText("跳过这步").assertIsDisplayed().assertIsEnabled()
        screenshot("引导_01_权限_底部与跳过")
        compose.onNodeWithText("跳过这步").performClick()

        compose.onNodeWithText("配置翻译 API").assertIsDisplayed()
        screenshot("引导_02_API_顶部")
        reveal(button("保存翻译配置")).assertIsEnabled()
        reveal(button("测试连接")).assertIsNotEnabled()
        compose.onNodeWithText("跳过这步").assertIsDisplayed().assertIsEnabled()
        screenshot("引导_02_API_底部与跳过")
        compose.onNodeWithText("跳过这步").performClick()

        compose.onNodeWithText("下载并启用识别模型").assertIsDisplayed()
        screenshot("引导_03_模型_顶部")
        reveal(hasText(ModelCatalog.entries.last().name))
        compose.onAllNodes(button("下载模型")).onLast().performScrollTo().assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("进入羽译").assertIsNotEnabled()
        compose.onNodeWithText("跳过这步").assertIsDisplayed().assertIsEnabled()
        screenshot("引导_03_模型_底部与跳过")
        compose.onNodeWithText("跳过这步").performClick()
        compose.runOnIdle { assertNull("跳过第三步应退出引导", state.onboarding) }
    }

    /** 就绪、运行、缺权限用真实主操作切换，语言选择和开关必须回填到同一内存状态。 */
    @Test fun liveReadinessRunningAndPermissionStates() {
        show(sampleState())
        compose.onNodeWithText("开始同传").assertIsDisplayed().assertIsEnabled()
        screenshot("同传_01_准备就绪")
        reveal(button("原声语言")).performClick()
        screenshot("同传_02_原声语言选项")
        compose.onNode(button(Lang.EN.zh)).performClick()
        compose.runOnIdle { assertEquals(Lang.EN.code, state.live.sourceId) }
        reveal(button("仅显示定稿")).performClick()
        compose.runOnIdle { assertTrue("开关必须实际回填", state.live.finalOnly) }
        reveal(button("翻译设置")).assertIsEnabled()
        val modelLayouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithText(ModelCatalog.entries.first().name, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(modelLayouts) }
        assertEquals("识别模型名应获得实际排版", 1, modelLayouts.size)
        assertEquals("服务入口不能将模型名称挤成碎片", 1, modelLayouts.single().lineCount)
        screenshot("同传_03_底部服务与开关")
        compose.onNodeWithText("开始同传").assertIsDisplayed().performClick()
        reveal(hasText("同传进行中"))
        compose.onNodeWithText("停止同传").assertIsDisplayed().assertIsEnabled()
        screenshot("同传_04_运行中")
        compose.onNodeWithText("停止同传").performClick()
        compose.runOnIdle { assertFalse(state.live.running) }

        replacePage(state.copy(live = sampleLive().copy(canStart = false,
            statusTitle = "开启权限后即可开始", permissions = permissions(true).map {
                if (it.id == "overlay") it.copy(granted = false) else it
            })))
        compose.onNodeWithText("开启显示悬浮字幕").assertIsDisplayed().assertIsEnabled()
        screenshot("同传_05_缺少悬浮字幕权限")
        reveal(button("查看全部权限"))
        screenshot("同传_06_权限说明底部")
        compose.onNodeWithText("开启显示悬浮字幕").performClick()
        compose.runOnIdle {
            assertTrue("此操作只改变内存权限快照", state.live.permissions.all { it.granted })
        }
        compose.onNodeWithText("开始同传").assertIsDisplayed()

        // 只补充一个真实滑块交互，不重复实际应用的全部字幕设置验收。
        compose.onNode(isSelectable() and hasText("字幕")).performClick()
        compose.onNodeWithContentDescription("暂时收起字幕预览").performClick()
        reveal(hasContentDescription("文字大小"))
            .performSemanticsAction(SemanticsActions.SetProgress) { it(26f) }
        compose.runOnIdle { assertEquals(26f, state.settings.subtitle.textSp, 0.01f) }
        screenshot("字幕_文字大小滑块回填")
    }

    /** 两个真实目录模型保持正式卡片排版，下载、失败、校验及删除确认只改变夹具。 */
    @Test fun modelLibraryLifecycleAndDeleteConfirmation() {
        show(sampleState().copy(destination = UiDestination.MODELS,
            models = ModelsUiState(items = models(ModelUiStatus.READY, ModelUiStatus.NOT_INSTALLED))))
        screenshot("模型_01_已安装与未安装_顶部")
        reveal(button("启用模型"))
        screenshot("模型_02_已安装_操作区")
        compose.onNode(button("启用模型")).performClick()
        compose.runOnIdle { assertEquals(ModelUiStatus.ACTIVE, state.models.items.first().status) }
        compose.onAllNodes(button("来源、许可与完整性")).onFirst().performScrollTo().performClick()
        reveal(hasText("来源：优先国内源，失败时切换备用源\n许可：ReazonSpeech 上游 Apache-2.0 · VAD：MIT"))
        screenshot("模型_02a_展开来源与许可")
        reveal(button("评测来源与条件")).assertIsEnabled()
        screenshot("模型_02b_完整性与评测条件_底部")
        reveal(button("收起模型详情")).performClick()
        reveal(hasContentDescription("删除${ModelCatalog.entries.first().name}")).performClick()
        compose.onNodeWithText("删除${ModelCatalog.entries.first().name}？").assertIsDisplayed()
        screenshot("模型_03_删除确认_顶部")
        reveal(hasText("下载、校验或同传运行期间不能删除。其他模型、应用设置和使用记录会保留。"))
        compose.onNodeWithText("确认删除").assertIsDisplayed().assertIsEnabled()
        screenshot("模型_04_删除确认_后果与按钮")
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertNull(state.models.deleteConfirmationId) }

        reveal(hasText(ModelCatalog.entries.last().name))
        screenshot("模型_05_未安装_顶部")
        reveal(button("下载模型"))
        screenshot("模型_06_未安装_下载按钮")
        compose.onNode(button("下载模型")).performClick()
        reveal(hasText("已下载 42%"))
        reveal(button("取消下载")).assertIsEnabled()
        screenshot("模型_07_下载中_进度与取消")
        reveal(button("取消下载")).performClick()
        compose.runOnIdle { assertEquals(ModelUiStatus.CANCELLED, state.models.items.last().status) }

        replacePage(state.copy(models = ModelsUiState(items = models(ModelUiStatus.ACTIVE, ModelUiStatus.FAILED))))
        reveal(hasText(ModelCatalog.entries.last().name))
        screenshot("模型_08_下载失败_顶部")
        reveal(button("重试下载")).assertIsEnabled()
        screenshot("模型_09_下载失败_完整错误与重试")
        compose.onNode(button("重试下载")).performClick()
        compose.runOnIdle { assertEquals(ModelUiStatus.DOWNLOADING, state.models.items.last().status) }

        replacePage(state.copy(models = ModelsUiState(items = models(ModelUiStatus.ACTIVE, ModelUiStatus.VERIFYING))))
        reveal(hasText(ModelCatalog.entries.last().name))
        screenshot("模型_10_校验中_顶部")
        reveal(hasText("正在校验模型完整性…"))
        reveal(button("取消下载")).assertIsEnabled()
        screenshot("模型_11_校验中_说明与取消")
    }

    /** 填写、建议地址、未保存退出和命名管理沿用正式表单与弹窗，不执行连接测试。 */
    @Test fun apiDraftSuggestionsExitAndProfileManagement() {
        show(sampleState().copy(destination = UiDestination.SETTINGS))
        openSetting("翻译 API")
        screenshot("API_01_已填配置_顶部")
        reveal(button("服务预设")).performClick()
        screenshot("API_00_服务预设选项")
        compose.onNode(button("自定义 / 手动填写")).performClick()
        // 非空请求头会默认展开，先收起再验证用户重新打开的真实入口。
        reveal(button("收起附加请求参数")).performClick()
        reveal(button("附加请求参数（可选）")).performClick()
        reveal(hasText("User-Agent（可选）") and hasSetTextAction())
        screenshot("API_01b_附加请求参数_顶部")
        reveal(hasText("请求头值") and hasSetTextAction())
        reveal(button("添加请求头")).assertIsEnabled()
        screenshot("API_01c_请求头与添加入口")
        reveal(hasText("服务地址") and hasSetTextAction())
        screenshot("API_01a_已填连接字段")
        editApiModel("deepseek-flash-界面验收")
        screenshot("API_02_已填字段与未保存状态")
        reveal(button("保存翻译配置")).assertIsEnabled()
        reveal(button("测试连接")).performClick()
        reveal(button("保持原地址")).assertIsEnabled()
        screenshot("API_03_测试建议_底部操作")
        val originalUrl = state.settings.api.url
        compose.onNode(button("保持原地址")).performClick()
        compose.runOnIdle {
            assertEquals(originalUrl, state.settings.api.url)
            assertTrue("连接建议不应隐式保存草稿", state.settings.api.hasUnsavedChanges)
        }
        reveal(button("测试连接")).performClick()
        reveal(button("替换并保存")).performClick()
        compose.runOnIdle {
            assertTrue(state.settings.api.url.endsWith("/chat/completions"))
            assertFalse(state.settings.api.hasUnsavedChanges)
        }

        editApiModel("尚未保存的模型草稿")
        compose.onNodeWithContentDescription("返回设置").performClick()
        compose.onNodeWithText("配置尚未保存").assertIsDisplayed()
        screenshot("API_04_未保存退出确认")
        compose.onNodeWithText("继续编辑").performClick()
        compose.runOnIdle { assertEquals("尚未保存的模型草稿", state.settings.api.model) }
        compose.onNodeWithContentDescription("返回设置").performClick()
        compose.onNodeWithText("放弃修改").performClick()
        compose.runOnIdle { assertEquals(savedApi, state.settings.api) }

        openSetting("翻译 API")
        reveal(button("重命名")).performClick()
        compose.onNodeWithText("重命名配置").assertIsDisplayed()
        screenshot("API_05_配置重命名弹窗")
        compose.onNode(hasSetTextAction() and hasText("配置名称")).performTextReplacement("日语直播翻译")
        compose.onNode(button("确定")).performClick()
        compose.runOnIdle {
            assertEquals("日语直播翻译", state.settings.api.profiles.first { it.id == state.settings.api.profileId }.label)
        }
        hideKeyboard()
        reveal(button("新增配置")).performClick()
        compose.onNodeWithText("新增翻译配置").assertIsDisplayed()
        screenshot("API_06_新增配置弹窗")
        compose.onNode(hasSetTextAction() and hasText("配置名称")).performTextReplacement("课程备用翻译")
        compose.onNode(button("确定")).performClick()
        compose.runOnIdle { assertTrue(state.settings.api.profiles.any { it.label == "课程备用翻译" }) }
        hideKeyboard()
        reveal(button("删除配置")).performClick()
        compose.onNodeWithText("删除翻译配置？").assertIsDisplayed()
        screenshot("API_07_配置删除确认")
        compose.onNodeWithText("取消").performClick()
    }

    /** 空记录与已结束／正在写入记录使用正式列表，保存开关和分享反馈留在内存。 */
    @Test fun usageRecordsEmptyAndPopulated() {
        show(sampleState().copy(destination = UiDestination.SETTINGS))
        openSetting("使用记录")
        screenshot("记录_01_空记录_顶部")
        reveal(hasText("暂无记录。开启保存并完成一次会话后，可在这里分享。"))
        screenshot("记录_02_空记录_底部")
        reveal(button("保存会话记录")).performClick()
        compose.runOnIdle {
            assertTrue(state.settings.keepLogs)
            state = state.copy(settings = state.settings.copy(records = listOf(
                UsageRecordUiState("audit-ended", "日语课程字幕 · 已结束", "隔离样例 · 2026-10-16 10:20",
                    "12 分 30 秒 · 日语 → 中文\n此为界面样例记录，不读取本机会话文件。"),
                UsageRecordUiState("audit-active", "直播字幕 · 正在写入", "隔离样例 · 2026-10-16 10:40",
                    "正在写入的记录保留，结束会话后才可分享或删除。", active = true),
            )))
        }
        reveal(hasText("日语课程字幕 · 已结束"))
        screenshot("记录_03_有记录_已结束会话")
        reveal(button("分享记录") and isEnabled()).performClick()
        compose.runOnIdle { assertNotNull(state.settings.recordsMessage) }
        reveal(hasText("直播字幕 · 正在写入"))
        reveal(button("分享记录") and !isEnabled()).assertIsNotEnabled()
        reveal(button("删除记录") and !isEnabled()).assertIsNotEnabled()
        screenshot("记录_04_底部_正在写入与禁用操作")
    }

    /** 新版、检查失败和自动提示均为明确标注的隔离样例，所有外链动作均不出应用。 */
    @Test fun updateReleaseErrorAndPrompt() {
        val release = UpdateReleaseUiState("99.1.0", "隔离视觉样例，并非线上发行信息。\n改进字幕显示、模型管理与大字号设置布局。",
            "https://github.com/ZOUYIQAQ/yuyi/releases/tag/v99.1.0")
        show(sampleState().copy(destination = UiDestination.SETTINGS,
            settings = sampleState().settings.copy(updates = AppUpdateUiState(latest = release, lastChecked = "隔离样例"))))
        openSetting("应用更新")
        screenshot("更新_01_新版本_顶部")
        reveal(button("启动时检查更新")).performClick()
        compose.runOnIdle { assertFalse(state.settings.updates.autoCheck) }
        reveal(button("查看发布页")).assertIsEnabled()
        screenshot("更新_02_新版本_底部发布入口")
        reveal(button("检查更新")).performClick()
        reveal(hasText("检查更新失败：隔离夹具模拟网络连接中断，请稍后重试。"))
        screenshot("更新_03_检查错误")
        reveal(button("检查更新")).assertIsEnabled()

        compose.runOnIdle { state = state.copy(settings = state.settings.copy(
            updates = state.settings.updates.copy(prompt = release))) }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("发现新版本 99.1.0").fetchSemanticsNodes().isNotEmpty()
        }
        screenshot("更新_04_新版提示_顶部")
        reveal(button("关闭自动检查")).assertIsEnabled()
        screenshot("更新_05_新版提示_底部动作")
        reveal(button("跳过此版本")).performClick()
        compose.runOnIdle {
            assertNull(state.settings.updates.prompt)
            assertEquals("99.1.0", state.settings.updates.skippedVersion)
        }
    }

    /** 完整选色、键盘和图标组合只改内存，取消不会切换真实桌面入口。 */
    @Test fun appearanceDialogsAndDarkThemeAtDeviceSize() {
        show(sampleState().copy(destination = UiDestination.SETTINGS))
        openSetting("外观")
        screenshot("外观_01_主题与图标_顶部")
        reveal(hasContentDescription("自定义主题颜色")).performClick()
        screenshot("外观_02_自定义颜色_顶部")
        reveal(hasText("颜色值") and hasSetTextAction()).performClick().performTextReplacement("#D38569")
        compose.waitUntil(5_000) {
            ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        compose.onNodeWithText("保存").assertIsDisplayed()
        screenshot("外观_03_颜色输入_系统键盘")
        // 对话框有独立焦点与输入服务，先按该字段的完成动作收起其键盘。
        compose.onNode(hasText("颜色值") and hasSetTextAction()).performImeAction()
        hideKeyboard()
        reveal(hasText("界面会根据所选颜色生成适合阅读的浅色与深色配色。"))
        screenshot("外观_04_自定义颜色_效果与保存")
        compose.onNodeWithText("保存").performClick()
        compose.runOnIdle { assertEquals("custom", state.settings.themeColorId) }
        reveal(hasText("深色") and hasClickAction()).performClick()
        reveal(hasContentDescription("自定义主题颜色"))
        screenshot("外观_05_深色配色与选中状态")
        reveal(hasContentDescription("选择应用图标")).performClick()
        screenshot("图标_01_彩色默认组合")
        reveal(button("图标样式")).performClick()
        compose.onNode(button("纯色")).performClick()
        reveal(hasContentDescription("图标颜色：海蓝")).performClick()
        screenshot("图标_02_纯色与颜色选择")
        reveal(button("图标背景")).performClick()
        compose.onNode(button("奶油白")).performClick()
        reveal(button("飘落花瓣")).performClick()
        compose.onNodeWithText("保存").assertIsDisplayed().assertIsEnabled()
        screenshot("图标_03_背景花瓣与保存")
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertEquals(IconStyle.VECTOR, state.settings.launcherIcon.style) }
    }

    /** 字幕设置逐项记录，句数和字数只能选一种；完整浮窗与局部放大都保留真实排版。 */
    @Test fun subtitleControlsAndPreviewAtDeviceSize() {
        show(sampleState().copy(destination = UiDestination.SUBTITLE))
        screenshot("字幕_01_竖屏悬浮预览")
        compose.onNodeWithContentDescription("切换为横屏预览").performClick()
        screenshot("字幕_02_横屏悬浮预览")
        compose.onNodeWithContentDescription("暂时收起字幕预览").performClick()
        reveal(hasContentDescription("文字大小"))
        reveal(button("显示原文")).performClick()
        reveal(button("显示耗时信息")).performClick()
        screenshot("字幕_03_文字背景与开关")
        reveal(hasContentDescription("字幕宽度"))
            .performSemanticsAction(SemanticsActions.SetProgress) { it(.65f) }
        reveal(hasContentDescription("字幕高度"))
            .performSemanticsAction(SemanticsActions.SetProgress) { it(.2f) }
        screenshot("字幕_04_宽高设置")
        reveal(button("显示句数")).performClick()
        compose.onNode(button("5 句")).performClick()
        compose.runOnIdle { assertEquals(5, state.settings.subtitle.maxSentences) }
        screenshot("字幕_05_按句数保留")
        reveal(button("保留方式")).performClick()
        compose.onNode(button(CaptionLimitMode.CHARACTERS.title)).performClick()
        reveal(hasContentDescription("最多译文字数"))
            .performSemanticsAction(SemanticsActions.SetProgress) { it(100f) }
        compose.onNode(button("显示句数")).assertDoesNotExist()
        compose.onAllNodes(hasScrollToIndexAction()).onLast().performTouchInput { swipeUp() }
        val lastNote = compose.onNodeWithText("只计算译文字数。单句超长时显示最新尾部，原文与原始定稿不会被改写。")
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val restore = compose.onNodeWithTag("subtitle-preview-restore").fetchSemanticsNode().boundsInRoot
        assertTrue("末项必须能完整滚动到恢复预览按钮上方", lastNote.bottom <= restore.top)
        screenshot("字幕_06_按字数保留")
        compose.onNodeWithTag("subtitle-preview-restore").performClick()
        screenshot("字幕_07_滚动后持续横屏预览")
        compose.onNodeWithContentDescription("放大字幕局部").performClick()
        screenshot("字幕_08_放大字幕局部")
    }

    /** 长术语表底部、键盘和固定返回都可达；不向用户场景写入任何测试内容。 */
    @Test fun sceneEditorKeyboardBottomAndReturnAtDeviceSize() {
        val editor = SceneEditorUiState(id = "audit-scene", name = "课程讲解", background = "视频中的课程讲解，保留专有名词。",
            languageOptions = listOf(UiOption("zh", "中文")), terms = (1..8).map { TermUiState("$it", "用語$it", "术语$it") })
        show(sampleState().copy(destination = UiDestination.SCENES, scenes = ScenesUiState(
            items = listOf(SceneUiState("audit-scene", "课程讲解", editor.background, 8, "中文")), editor = editor)))
        screenshot("场景_01_列表与编辑器")
        reveal(hasText("内容背景") and hasSetTextAction()).performClick()
        compose.waitUntil(5_000) {
            ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        screenshot("场景_02_输入键盘与固定返回")
        hideKeyboard()
        reveal(button("保存场景")).assertIsEnabled()
        screenshot("场景_03_长术语表底部动作")
        reveal(button("删除场景")).performClick()
        screenshot("场景_04_删除确认")
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithContentDescription("返回场景列表").assertIsDisplayed().performClick()
        compose.runOnIdle { assertNull(state.scenes.editor) }
    }

    /** 每个测试只安装一次根内容，设备窗口沿用真实入口的边缘到边缘模式。 */
    private fun show(initial: SimulSubUiState) {
        state = initial
        savedApi = initial.settings.api
        compose.runOnUiThread {
            compose.activity.enableEdgeToEdge()
            compose.activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        val actions = memoryActions()
        compose.setContent {
            focusManager = LocalFocusManager.current
            keyboard = LocalSoftwareKeyboardController.current
            key(pageGeneration) { SimulSubApp(state, actions) }
        }
        compose.waitForIdle()
    }

    /** 只有主动换场景时清空组件内的滚动位置；交互回调保留当前正式组件实例。 */
    private fun replacePage(next: SimulSubUiState) {
        compose.runOnIdle { state = next; pageGeneration++ }
        compose.waitForIdle()
    }

    /** 夹具仅接上此次会触达的合同边界，所有业务反馈都明确标明为隔离样例。 */
    private fun memoryActions() = SimulSubActions(
        onNavigate = { state = state.copy(destination = it) },
        onOnboardingNext = { advanceOnboarding() },
        onOnboardingSkip = { advanceOnboarding() },
        onOnboardingBack = {
            state.onboarding?.let { current -> state = state.copy(onboarding = current.copy(
                step = OnboardingStep.entries[(current.step.ordinal - 1).coerceAtLeast(0)])) }
        },
        onPermission = { id ->
            val updated = state.live.permissions.map { if (id == "all" || it.id == id) it.copy(granted = true) else it }
            state = state.copy(live = state.live.copy(permissions = updated, canStart = updated.all { it.granted },
                statusTitle = if (updated.all { it.granted }) "准备就绪" else "开启权限后即可开始"),
                settings = state.settings.copy(permissions = updated))
        },
        onStart = { state = state.copy(live = state.live.copy(running = true, statusTitle = "同传进行中",
            statusDetail = "隔离夹具：会话运行状态样例，不捕获音频。", elapsed = "12:30")) },
        onStop = { state = state.copy(live = state.live.copy(running = false, statusTitle = "准备就绪", elapsed = "")) },
        onSourceLanguage = { state = state.copy(live = state.live.copy(sourceId = it)) },
        onTargetLanguage = { state = state.copy(live = state.live.copy(targetId = it)) },
        onSelectScene = { state = state.copy(live = state.live.copy(sceneId = it)) },
        onFinalOnly = { state = state.copy(live = state.live.copy(finalOnly = it)) },
        onSubtitleStyleChange = { state = state.copy(settings = state.settings.copy(subtitle = it)) },
        onThemeChange = { state = state.copy(settings = state.settings.copy(theme = it)) },
        onThemeColorChange = { state = state.copy(settings = state.settings.copy(themeColorId = it, dynamicColors = false)) },
        onCustomThemeColorChange = { state = state.copy(settings = state.settings.copy(customThemeSeed = it, themeColorId = "custom", dynamicColors = false)) },
        onLauncherIconChange = { state = state.copy(settings = state.settings.copy(launcherIcon = it)) },
        onDynamicColorsChange = { state = state.copy(settings = state.settings.copy(dynamicColors = it)) },
        onAdvancedChange = { state = state.copy(settings = state.settings.copy(advanced = it)) },
        onSceneDraftChange = { state = state.copy(scenes = state.scenes.copy(editor = it)) },
        onCloseSceneEditor = { state = state.copy(scenes = state.scenes.copy(editor = null)) },
        onDownloadModel = { id -> changeModel(id, ModelUiStatus.DOWNLOADING) },
        onCancelDownload = { id -> changeModel(id, ModelUiStatus.CANCELLED) },
        onActivateModel = { id -> changeModel(id, ModelUiStatus.ACTIVE) },
        onRequestDeleteModel = { state = state.copy(models = state.models.copy(deleteConfirmationId = it)) },
        onDismissDeleteModel = { state = state.copy(models = state.models.copy(deleteConfirmationId = null)) },
        onApiDraftChange = { changeApi(it.copy(hasUnsavedChanges = true, suggestedUrl = "")) },
        onApplyApiPreset = { changeApi(TranslationPresets.apply(state.settings.api, it).copy(hasUnsavedChanges = true)) },
        onSaveApi = { saveApi(state.settings.api) },
        onDiscardApiDraft = { changeApi(savedApi) },
        onTestApi = { changeApi(state.settings.api.copy(
            suggestedUrl = state.settings.api.url.trimEnd('/').removeSuffix("/chat/completions") + "/chat/completions",
            message = UiMessage("隔离夹具：连接建议样例，未发送请求。"))) },
        onKeepApiUrl = { changeApi(state.settings.api.copy(suggestedUrl = "")) },
        onApplyTestedApiUrl = { saveApi(state.settings.api.copy(url = state.settings.api.suggestedUrl, suggestedUrl = "")) },
        onSelectApiProfile = { changeApi(state.settings.api.copy(profileId = it, hasUnsavedChanges = false)); savedApi = state.settings.api },
        onRenameApiProfile = { name ->
            changeApi(state.settings.api.copy(profiles = state.settings.api.profiles.map {
                if (it.id == state.settings.api.profileId) it.copy(label = name) else it
            })); savedApi = state.settings.api
        },
        onAddApiProfile = { name ->
            val id = "audit-profile-${state.settings.api.profiles.size}"
            changeApi(TranslationApiUiState(profileId = id, profiles = state.settings.api.profiles + UiOption(id, name)))
            savedApi = state.settings.api
        },
        onKeepLogsChange = { state = state.copy(settings = state.settings.copy(keepLogs = it)) },
        onShareRecord = { state = state.copy(settings = state.settings.copy(
            recordsMessage = UiMessage("隔离夹具：已选择记录 $it，不启动系统分享。"))) },
        onAutoUpdateChange = { state = state.copy(settings = state.settings.copy(updates = state.settings.updates.copy(autoCheck = it))) },
        onCheckUpdate = { state = state.copy(settings = state.settings.copy(
            updates = state.settings.updates.copy(checkFailed = true, latest = null),
            updateMessage = "检查更新失败：隔离夹具模拟网络连接中断，请稍后重试。")) },
        onLaterUpdate = { state = state.copy(settings = state.settings.copy(updates = state.settings.updates.copy(prompt = null))) },
        onSkipUpdate = { state = state.copy(settings = state.settings.copy(updates = state.settings.updates.copy(
            skippedVersion = state.settings.updates.prompt?.versionName.orEmpty(), prompt = null))) },
    )

    /** 保存只记入测试对象，连接探测、加密存储和真实偏好完全不参与。 */
    private fun saveApi(api: TranslationApiUiState) {
        savedApi = api.copy(hasUnsavedChanges = false, hasSavedConfiguration = true, suggestedUrl = "")
        changeApi(savedApi)
    }

    /** 单处回填 API 状态，避免把隔离表单误接到真实控制器。 */
    private fun changeApi(api: TranslationApiUiState) {
        state = state.copy(settings = state.settings.copy(api = api))
    }

    /** 引导推进沿用真实三步枚举，不向持久化写入完成标记。 */
    private fun advanceOnboarding() {
        val current = state.onboarding ?: return
        state = state.copy(onboarding = if (current.step == OnboardingStep.MODEL) null
            else current.copy(step = OnboardingStep.entries[current.step.ordinal + 1]))
    }

    /** 状态切换重新从可信目录生成卡片能力，不启动下载、校验或模型加载。 */
    private fun changeModel(id: String, status: ModelUiStatus) {
        state = state.copy(models = state.models.copy(items = modelWorkCapabilities(state.models.items.map {
            if (it.id == id) model(id, status) else it
        })))
    }

    /** 根样例只使用合同类型；具体主题和图标沿用 SettingsUiState 的正式默认值。 */
    private fun sampleState() = SimulSubUiState(live = sampleLive(),
        models = ModelsUiState(items = models(ModelUiStatus.ACTIVE, ModelUiStatus.NOT_INSTALLED)),
        settings = SettingsUiState(api = sampleApi(), permissions = permissions(true), version = BuildConfig.VERSION_NAME))

    /** 原声与字幕语言直接取核心真实枚举，避免把字符串误当成后台语言对象。 */
    private fun sampleLive() = LiveUiState(canStart = true, statusTitle = "准备就绪",
        statusDetail = "ReazonSpeech · 音频仅在设备上识别", modelLabel = ModelCatalog.entries.first().name,
        translationLabel = "默认 · ${TranslationPresets.entries.first().model}",
        sourceId = Lang.JA.code, targetId = Target.ZH.stored,
        sourceOptions = listOf(Lang.JA, Lang.EN).map { UiOption(it.code, it.zh) } + UiOption("auto", "自动识别"),
        targetOptions = Target.PRESETS.map { UiOption(it.stored, it.zh) },
        sceneOptions = listOf(UiOption("", "通用"), UiOption("audit-course", "课程讲解")), permissions = permissions(true))

    /** 服务字段取正式预设，密钥明确为无效示例，绝不读取本机已存值。 */
    private fun sampleApi(): TranslationApiUiState {
        val preset = TranslationPresets.entries.first()
        return TranslationApiUiState(profiles = listOf(UiOption("", "默认"), UiOption("audit-course", "课程字幕")),
            presetId = preset.id, url = preset.url, model = preset.model, key = "仅供界面验收的无效密钥",
            userAgent = "Yuyi/界面验收", headers = listOf(HeaderUiState("audit-header", "X-Client-Scene", "课程字幕")),
            hasSavedConfiguration = true)
    }

    /** 必需权限文案与应用默认合同保持一致，不查询或改变系统授权。 */
    private fun permissions(granted: Boolean) = listOf(
        PermissionUiState("audio", "录制播放音频", "仅识别应用播放的媒体声音；系统会在开始时再次询问捕获范围。", granted),
        PermissionUiState("overlay", "显示悬浮字幕", "允许字幕显示在播放器、课程或直播应用上方。", granted),
    )

    /** 两张实际目录卡片用于检验平板并排和手机纵向布局，不制造额外模型。 */
    private fun models(first: ModelUiStatus, second: ModelUiStatus) =
        modelWorkCapabilities(ModelCatalog.entries.mapIndexed { index, spec -> model(spec.id, if (index == 0) first else second) })

    /** 正式控制器在任一模型下载或校验期间禁止删除所有模型，夹具保留相同按钮能力。 */
    private fun modelWorkCapabilities(items: List<ModelUiState>): List<ModelUiState> {
        val busy = items.any { it.status == ModelUiStatus.DOWNLOADING || it.status == ModelUiStatus.VERIFYING }
        return items.map { it.copy(canDelete = it.deletionScope.isNotBlank() && !busy,
            deleteBlockedReason = if (busy) "下载或校验结束后可删除模型" else null) }
    }

    /** 模型属性来自固定目录，比较结果由正式 ModelComparisons 按真实 ID 自动渲染。 */
    private fun model(id: String, status: ModelUiStatus): ModelUiState {
        val spec = ModelCatalog.entries.first { it.id == id }
        val installed = status == ModelUiStatus.READY || status == ModelUiStatus.ACTIVE
        val working = status == ModelUiStatus.DOWNLOADING || status == ModelUiStatus.VERIFYING
        return ModelUiState(id = spec.id, name = spec.name, description = spec.description,
            languages = spec.languages.joinToString("、"), sizeLabel = String.format(Locale.ROOT, "%.1f MiB", spec.sizeBytes / 1048576.0),
            license = (if (spec.kind == ModelKind.SENSEVOICE) "FunASR 模型协议 v1.1" else "ReazonSpeech 上游 Apache-2.0") + " · VAD：MIT",
            sourceLabel = "优先国内源，失败时切换备用源", sourceUrl = spec.files.first().sources.first(),
            status = status, progress = if (status == ModelUiStatus.DOWNLOADING) 0.42f else null,
            error = if (status == ModelUiStatus.FAILED) "隔离夹具：下载连接中断，模型尚未安装。请检查网络后重试。" else null,
            canDownload = status in listOf(ModelUiStatus.NOT_INSTALLED, ModelUiStatus.CANCELLED, ModelUiStatus.FAILED),
            canActivate = status == ModelUiStatus.READY, canCancel = working, canDelete = installed,
            verificationDetail = "逐文件核对完整大小与 SHA-256，校验通过后才能启用。",
            deletionScope = if (installed) "models/$id/installed/（识别模型和 VAD）\nmodels/$id/staging-*/（下载暂存及 .part 文件）\nmodels/$id/invalid-*/（损坏旧版备份）" else "")
    }

    /** 从正式设置首页进入详情，兼容手机单列和平板双列。 */
    private fun openSetting(title: String) {
        reveal(hasText(title) and hasClickAction()).performClick()
        compose.waitForIdle()
    }

    /** 用真实文本输入动作触发草稿回调，再关闭键盘以记录完整表单布局。 */
    private fun editApiModel(value: String) {
        reveal(hasSetTextAction() and hasText("模型 ID")).performTextReplacement(value)
        hideKeyboard()
        reveal(hasText("API 密钥") and hasSetTextAction())
    }

    /** 不发送可能误触页面返回的按键，只通过当前 Compose 输入服务收起键盘。 */
    private fun hideKeyboard() {
        compose.runOnIdle { focusManager.clearFocus(force = true); keyboard?.hide() }
        compose.waitUntil(5_000) {
            ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) != true
        }
        compose.waitForIdle()
    }

    /** 点击匹配要求真实交互语义，避开同名标题和说明。 */
    private fun button(label: String) = hasText(label) and (hasClickAction() or hasAnyAncestor(hasClickAction()))

    /** 优先滚动已组合节点；目标未组合时逐个搜索有限的实际懒列表，不吞掉最终失败。 */
    private fun reveal(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        val target = compose.onNode(matcher)
        if (runCatching { target.performScrollTo().assertIsDisplayed() }.isSuccess) return target
        if (runCatching { target.assertIsDisplayed() }.isSuccess) return target
        val lists = compose.onAllNodes(hasScrollToIndexAction())
        var lastError: AssertionError? = null
        for (index in lists.fetchSemanticsNodes().indices) {
            try {
                lists[index].performScrollToNode(matcher)
                return target.performScrollTo().assertIsDisplayed()
            } catch (error: AssertionError) {
                lastError = error
            }
        }
        throw AssertionError("滚动后仍无法触达控件：$matcher", lastError)
    }

    /** 等待 Compose 与系统空闲后保存整屏真实像素，日志和仪器输出均给出完整中文路径。 */
    private fun screenshot(scene: String) {
        compose.waitForIdle()
        instrumentation.waitForIdleSync()
        // 系统截图读取合成后的缓冲区，等待短暂入场动画完成，避免把上一页当作当前页。
        android.os.SystemClock.sleep(300)
        instrumentation.waitForIdleSync()
        // 窗口切换期间合成器可能暂时拒绝取帧；仅有界重试截图，不跳过任何页面断言。
        var frame = instrumentation.uiAutomation.takeScreenshot()
        val deadline = android.os.SystemClock.uptimeMillis() + 2_000
        while (frame == null && android.os.SystemClock.uptimeMillis() < deadline) {
            android.os.SystemClock.sleep(100)
            frame = instrumentation.uiAutomation.takeScreenshot()
        }
        val bitmap = checkNotNull(frame) { "无法取得设备整屏截图：$scene" }
        try {
            val fontScale = compose.activity.resources.configuration.fontScale
            val cache = checkNotNull(compose.activity.externalCacheDir) { "设备未提供应用外部缓存目录" }
            val directory = File(cache, "ui-ergonomics/${bitmap.width}x${bitmap.height}-$fontScale")
            check(directory.isDirectory || directory.mkdirs()) { "无法创建截图目录：${directory.absolutePath}" }
            val output = File(directory, "$scene.png")
            output.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "保存截图失败：$scene" } }
            val description = "视觉验收截图已保存：${output.absolutePath}；实际密度=${compose.activity.resources.displayMetrics.densityDpi}dpi；系统字体缩放=$fontScale"
            Log.i("FullUiVisualAudit", description)
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "$description\n") })
        } finally {
            bitmap.recycle()
        }
    }
}
