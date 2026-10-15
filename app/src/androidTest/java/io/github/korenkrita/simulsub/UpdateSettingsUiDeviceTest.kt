package io.github.korenkrita.simulsub

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.korenkrita.simulsub.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** 手机、平板和大字号使用同一真实设置组件，不打开浏览器或真实下载。 */
@RunWith(Parameterized::class)
class UpdateSettingsUiDeviceTest(private val tablet: Boolean, private val fontScale: Float) {
    @get:Rule val compose = createComposeRule()
    private var state by mutableStateOf(SettingsUiState(version = "1.5.0"))
    private val events = mutableListOf<String>()
    private val release = UpdateReleaseUiState("99.1.0", "改进字幕显示和更新设置。", "https://github.com/ZOUYIQAQ/yuyi/releases/tag/v99.1.0", "https://github.com/ZOUYIQAQ/yuyi/releases/download/v99.1.0/yuyi-arm64.apk")
    companion object {
        /** 同时覆盖手机／平板与两倍字号。 */
        @JvmStatic @Parameterized.Parameters(name = "平板={0}，字号={1}")
        fun cases(): List<Array<Any>> = listOf(false, true).flatMap { tablet -> listOf(1f, 2f).map { arrayOf<Any>(tablet, it) } }
    }
    /** 自动开关、跳过状态和恢复按钮均可达，关闭后手动检查仍可操作。 */
    @Test fun updatePreferencesAreReachableAndManualCheckWorksWhenOff() {
        state = state.copy(updates = AppUpdateUiState(skippedVersion = "99.0.0"))
        show()
        scroll("启动时检查更新"); compose.onNodeWithText("启动时检查更新").performClick()
        compose.runOnIdle { assertFalse(state.updates.autoCheck) }
        scroll("恢复版本提醒"); compose.onNodeWithText("恢复版本提醒").performScrollTo().performClick()
        scroll("检查更新"); compose.onNodeWithText("检查更新").assertIsEnabled().performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("自动:false", "恢复", "检查"), events) }
    }
    /** 新版提示三个提醒动作及浏览器更新动作均可见且独立派发。 */
    @Test fun promptActionsRemainAccessibleWithoutInstaller() {
        state = state.copy(updates = AppUpdateUiState(prompt = release))
        show()
        for ((label, event) in listOf("更新" to "打开浏览器", "稍后提醒" to "稍后", "跳过此版本" to "跳过", "关闭自动检查" to "自动:false")) {
            compose.onNodeWithText(label).performScrollTo().assertIsDisplayed().performClick()
            compose.runOnIdle { assertEquals(event, events.last()) }
        }
        compose.onNodeWithText("安装更新").assertDoesNotExist()
    }
    /** 暂无APK时按钮明确改成发布页入口，不能显示虚假的下载／安装进度。 */
    @Test fun missingApkShowsReleasePageAction() {
        state = state.copy(updates = AppUpdateUiState(latest = release.copy(downloadUrl = null)))
        show(); scroll("查看发布页")
        compose.onNodeWithText("查看发布页").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("打开浏览器"), events) }
        compose.onNodeWithText("正在下载").assertDoesNotExist()
    }
    /** 输入API或运行同传期间不弹自动提示，离开编辑后仍能呈现待处理新版。 */
    @Test fun rootDefersPromptWhileEditingApiOrRunning() {
        var root by mutableStateOf(SimulSubUiState(destination = UiDestination.SETTINGS, settings = state.copy(updates = AppUpdateUiState(prompt = release)), live = LiveUiState(running = true)))
        compose.setContent { SimulSubApp(root, SimulSubActions()) }
        compose.onNodeWithText("发现新版本 99.1.0").assertDoesNotExist()
        compose.onNodeWithText("翻译 API").performClick()
        compose.runOnIdle { root = root.copy(live = root.live.copy(running = false)) }
        compose.onNodeWithText("发现新版本 99.1.0").assertDoesNotExist()
        compose.onNodeWithContentDescription("返回设置").performClick()
        awaitUpdatePrompt()
    }

    /** 删除确认框已有焦点时不叠加更新弹窗，确认关闭后再提示待处理新版。 */
    @Test fun updateWaitsUntilExistingConfirmationCloses() {
        val model = ModelUiState(id = "qa", name = "测试模型", description = "", languages = "日语", sizeLabel = "100 MB",
            license = "MIT", sourceLabel = "测试来源", deletionScope = "仅测试模型目录", canDelete = true)
        var root by mutableStateOf(SimulSubUiState(destination = UiDestination.MODELS,
            models = ModelsUiState(items = listOf(model), deleteConfirmationId = "qa")))
        compose.setContent { SimulSubApp(root, SimulSubActions(onDismissDeleteModel = {
            root = root.copy(models = root.models.copy(deleteConfirmationId = null))
        })) }
        compose.onNodeWithText("删除测试模型？").assertIsDisplayed()
        compose.runOnIdle { root = root.copy(settings = root.settings.copy(updates = AppUpdateUiState(prompt = release))) }
        compose.onNodeWithText("发现新版本 99.1.0").assertDoesNotExist()
        compose.onNodeWithText("取消").performClick()
        awaitUpdatePrompt()
    }

    /** 窗口焦点由系统异步交还，等待真实提示可见后仍执行同一显示断言。 */
    private fun awaitUpdatePrompt() {
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithText("发现新版本 99.1.0").assertIsDisplayed() }.isSuccess
        }
        compose.onNodeWithText("发现新版本 99.1.0").assertIsDisplayed()
    }

    /** 组件事件只修改本地夹具，更新对话框保持打开便于逐项验证。 */
    private fun show() {
        val actions = SimulSubActions(
            onAutoUpdateChange = { events += "自动:$it"; state = state.copy(updates = state.updates.copy(autoCheck = it)) },
            onCheckUpdate = { events += "检查" }, onOpenUpdate = { events += "打开浏览器" },
            onLaterUpdate = { events += "稍后" }, onSkipUpdate = { events += "跳过" },
            onRestoreUpdateReminders = { events += "恢复"; state = state.copy(updates = state.updates.copy(skippedVersion = "")) })
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(if (tablet) 0.6f else 1f, fontScale)) {
                SimulSubTheme(state) {
                    Box(Modifier.requiredSize(if (tablet) 1040.dp else 360.dp, 640.dp)) { UpdateSettings(state, actions, tablet) }
                    state.updates.prompt?.let { UpdatePrompt(it, actions) }
                }
            }
        }
    }
    /** 根据实际懒列表寻找目标条目，随后由子节点滚动保证完整可见。 */
    private fun scroll(label: String) {
        var last: Throwable? = null
        val lists = compose.onAllNodes(hasScrollToIndexAction())
        for (index in lists.fetchSemanticsNodes().indices) {
            try { lists[index].performScrollToNode(hasText(label)); return } catch (error: AssertionError) { last = error }
        }
        throw AssertionError("未找到更新设置：$label", last)
    }
}
