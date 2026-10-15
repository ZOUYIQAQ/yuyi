package io.github.korenkrita.simulsub

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.korenkrita.simulsub.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** 仅测试模型页与状态回调，不创建真实仓库、不下载也不删除用户模型。 */
class ModelDeletionUiDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val scope = "models/reazonspeech/installed/（识别模型和 VAD）\n" +
        "models/reazonspeech/staging-*/（下载暂存及 .part 文件）\n" +
        "models/reazonspeech/invalid-*/（损坏旧版备份）"

    /** 构造纯界面模型，删除能力由夹具控制，不查询应用磁盘。 */
    private fun model(canDelete: Boolean = true, reason: String? = null): ModelUiState = ModelUiState(
        id = "reazonspeech", name = "ReazonSpeech", description = "日英双语", languages = "日语、英语",
        sizeLabel = "74.1 MiB", license = "Apache-2.0", sourceLabel = "测试来源", status = ModelUiStatus.ACTIVE,
        canDelete = canDelete, deletionScope = scope, deleteBlockedReason = reason,
    )

    /** 请求删除只打开正式确认；取消保留选择和卡片，不发送实际删除动作。 */
    @Test fun cancelConfirmationKeepsModelAndShowsExactConsequences() {
        var state by mutableStateOf(ModelsUiState(items = listOf(model())))
        var deletes = 0
        compose.setContent { MaterialTheme {
            ModelsScreen(state, SimulSubActions(
                onRequestDeleteModel = { state = state.copy(deleteConfirmationId = it) },
                onDismissDeleteModel = { state = state.copy(deleteConfirmationId = null) },
                onConfirmDeleteModel = { deletes++ },
            ), wide = false)
        } }
        compose.onNodeWithContentDescription("删除ReazonSpeech").performScrollTo().performClick()
        compose.onNodeWithText("删除ReazonSpeech？").assertIsDisplayed()
        compose.onNodeWithText(scope).assertExists()
        compose.onNodeWithText("删除会停用此模型；如果它是当前选中模型，将清空选择，不会自动切换到其他模型。再次使用需要重新下载。").assertExists()
        compose.onNodeWithText("取消", useUnmergedTree = true).performClick()
        compose.onNodeWithText("删除ReazonSpeech？").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, deletes); assertEquals(ModelUiStatus.ACTIVE, state.items.single().status) }
    }

    /** 只有确认按钮派发指定模型标识，失败返回后仍显示实际错误和残留。 */
    @Test fun confirmedDeletionFailureStaysVisibleAndRetriable() {
        var state by mutableStateOf(ModelsUiState(items = listOf(model())))
        var requested: String? = null
        compose.setContent { MaterialTheme {
            ModelsScreen(state, SimulSubActions(
                onRequestDeleteModel = { state = state.copy(deleteConfirmationId = it) },
                onConfirmDeleteModel = { id ->
                    requested = id
                    state = state.copy(deleteConfirmationId = null,
                        items = listOf(model().copy(error = "拒绝删除：测试文件系统只读")))
                },
            ), wide = false)
        } }
        compose.onNodeWithContentDescription("删除ReazonSpeech").performScrollTo().performClick()
        compose.runOnIdle { assertNull(requested) }
        compose.onNodeWithText("确认删除").performClick()
        compose.runOnIdle { assertEquals("reazonspeech", requested) }
        compose.onNodeWithText("拒绝删除：测试文件系统只读").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("删除ReazonSpeech").assertIsEnabled()
    }

    /** 弹窗打开后才开始下载或同传，最新能力必须立即禁止确认，不能沿用打开时快照。 */
    @Test fun workStartingAfterConfirmationDisablesDelete() {
        var state by mutableStateOf(ModelsUiState(items = listOf(model()), deleteConfirmationId = "reazonspeech"))
        var deletes = 0
        compose.setContent { MaterialTheme {
            ModelsScreen(state, SimulSubActions(onConfirmDeleteModel = { deletes++ }), wide = false)
        } }
        for (reason in listOf("模型正在下载或校验", "同传正在运行")) {
            compose.runOnIdle { state = state.copy(items = listOf(model(false, reason))) }
            compose.onNodeWithText("确认删除").assertIsNotEnabled()
        }
        compose.runOnIdle { assertEquals(0, deletes) }
    }

    /** 已进入后台删除时不能再次提交或取消，避免让用户误认为进行中的操作已撤销。 */
    @Test fun inFlightDeletionCannotBeRepeatedOrCancelled() {
        val state = ModelsUiState(items = listOf(model()), deleteConfirmationId = "reazonspeech", deletingId = "reazonspeech")
        compose.setContent { MaterialTheme { ModelsScreen(state, SimulSubActions(), wide = false) } }
        compose.onNodeWithText("正在删除…").assertIsNotEnabled()
        compose.onNode(hasText("取消") and hasClickAction()).assertIsNotEnabled()
    }

    /** 主卡以短介绍和真实错误率展示差异，参考耗时紧贴设备名且不混入评测长文。 */
    @Test fun comparisonUsesConciseIntroductionAndHonestMetricLabels() {
        compose.setContent { MaterialTheme { ModelsScreen(ModelsUiState(items = listOf(model())), SimulSubActions(), wide = false) } }
        compose.onNodeWithText("如何选择").assertDoesNotExist()
        compose.onNodeWithText("电视节目语音训练的小模型，体积小、识别快，适合日语；英语表现较弱。").assertExists()
        compose.onNodeWithText("日语识别错误率").assertExists()
        compose.onNodeWithText("英语识别错误率").assertExists()
        compose.onNodeWithText("8.48%").assertExists()
        compose.onNodeWithText("22.66%").assertExists()
        compose.onNodeWithText("参考识别耗时（日/英）").assertExists()
        compose.onNodeWithText("93 / 71 ms").assertExists()
        compose.onNodeWithText("参考设备：Mac M4 Pro").assertExists()
        compose.onNodeWithText("实时率 RTF").assertDoesNotExist()
        compose.onNodeWithText("0.0075").assertDoesNotExist()
        compose.onNodeWithText("上游历史", substring = true).assertDoesNotExist()
        compose.onNodeWithText("并非安卓实测", substring = true).assertDoesNotExist()
        compose.onNodeWithText("评测条件：", substring = true).assertDoesNotExist()
        compose.onNodeWithText("准确率", substring = true).assertDoesNotExist()
        compose.onNodeWithText("吐字延迟", substring = true).assertDoesNotExist()
    }

    /** 详细评测口径、下载来源、许可及完整性仅在用户主动展开后出现。 */
    @Test fun expandedDetailsKeepConditionsSourceLicenseAndIntegrity() {
        var opened = 0
        val item = model().copy(verificationDetail = "逐文件核对完整大小与 SHA-256，校验通过后才能启用。")
        compose.setContent { MaterialTheme { ModelsScreen(ModelsUiState(items = listOf(item)),
            SimulSubActions(onOpenModelBenchmark = { opened++ }), wide = false) } }
        compose.onNodeWithText("来源、许可与完整性").performScrollTo().performClick()
        compose.onNodeWithText("来源：测试来源\n许可：Apache-2.0").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(item.verificationDetail).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("评测条件：", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("评测来源与条件").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, opened) }
        compose.onNodeWithText("收起模型详情").performScrollTo().performClick()
        compose.onNodeWithText("评测条件：", substring = true).assertDoesNotExist()
    }
}
