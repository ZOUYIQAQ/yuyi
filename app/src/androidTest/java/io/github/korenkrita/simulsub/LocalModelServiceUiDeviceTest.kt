package io.github.korenkrita.simulsub

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import io.github.korenkrita.simulsub.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** 仅使用内存状态验证听写卡片，不访问模型文件、网络、控制器或飞书通知。 */
class LocalModelServiceUiDeviceTest {
    @get:Rule val compose = createComposeRule()
    private var models by mutableStateOf(ModelsUiState())
    private var busy by mutableStateOf(false)
    private val events = mutableListOf<String>()
    private val scope = "models/reazonspeech/installed/\nmodels/reazonspeech/staging-*/"

    /** 未安装、下载中、失败及取消均从同一服务卡派发原模型动作，不导航到模型库。 */
    @Test fun downloadCancelAndRetryStayInsideListeningCard() {
        show(model().copy(canDownload = true))
        click("下载模型")
        compose.runOnIdle { models = models.copy(items = listOf(model().copy(
            status = ModelUiStatus.DOWNLOADING, progress = 0.42f, canCancel = true,
            downloadedLabel = "42 MiB / 100 MiB"))) }
        compose.onNodeWithText("已下载 42%").assertExists()
        compose.onNodeWithText("42 MiB / 100 MiB").assertExists()
        click("取消下载")
        for (status in listOf(ModelUiStatus.FAILED, ModelUiStatus.CANCELLED)) {
            compose.runOnIdle { models = models.copy(items = listOf(model().copy(
                status = status, canDownload = true, error = "下载失败原文：连接中断"))) }
            compose.onNodeWithText("下载失败原文：连接中断").assertExists()
            click("重试下载")
        }
        compose.runOnIdle { assertEquals(listOf("下载:reazonspeech", "取消:reazonspeech",
            "下载:reazonspeech", "下载:reazonspeech"), events) }
    }

    /** 已安装与已启用的本地模型均通过服务选择切换，避免只改模型偏好而仍使用云端服务。 */
    @Test fun installedAndPreviouslyActiveModelsSelectLocalService() {
        show(model().copy(status = ModelUiStatus.READY, canActivate = true))
        click("使用")
        compose.runOnIdle { models = models.copy(items = listOf(model().copy(status = ModelUiStatus.ACTIVE))) }
        compose.onNodeWithText("使用中").assertDoesNotExist()
        click("使用")
        compose.runOnIdle { assertEquals(listOf("使用:local:reazonspeech", "使用:local:reazonspeech"), events) }
    }

    /** 当前听写服务已经选中时使用按钮不可重复提交，仍可展开模型资料。 */
    @Test fun selectedServiceKeepsDetailsAndDisablesRepeatedSelection() {
        show(model().copy(status = ModelUiStatus.ACTIVE), selected = true)
        compose.onNodeWithText("正在使用").performScrollTo().assertIsNotEnabled()
        click("来源、许可与完整性")
        compose.onNodeWithText("来源：界面测试来源\n许可：Apache-2.0").assertExists()
        compose.runOnIdle { assertTrue(events.isEmpty()) }
    }

    /** 运行期间禁止切换；模型下载与删除能力仍以控制器传入的最新模型状态为准。 */
    @Test fun sessionBusyDisablesLocalSelection() {
        show(model().copy(status = ModelUiStatus.READY, canActivate = true))
        compose.runOnIdle { busy = true }
        compose.onNodeWithText("使用").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { assertTrue(events.isEmpty()) }
    }

    /** 校验阶段仅显示进度与取消操作，即使服务配置声称可用，也不能跳过校验选用。 */
    @Test fun verificationCannotBeBypassedByConfiguredProfile() {
        show(model().copy(status = ModelUiStatus.VERIFYING, canCancel = true))
        compose.onNodeWithText("正在校验模型完整性…").assertExists()
        compose.onNodeWithText("使用").assertDoesNotExist()
        compose.onNodeWithText("启用模型").assertDoesNotExist()
        click("取消下载")
        compose.runOnIdle { assertEquals(listOf("取消:reazonspeech"), events) }
    }

    /** 云端条目保持原有使用动作及标识前缀，不被本地模型卡的选择逻辑接管。 */
    @Test fun cloudSelectionKeepsOriginalProfilePrefix() {
        show(model(), extraProfiles = listOf(ServiceProfileUiState("cloud-fixture", "千问测试听写", kind = "asr")))
        click("使用")
        compose.runOnIdle { assertEquals(listOf("使用:profile:cloud-fixture"), events) }
    }

    /** 原模型卡的简介、语言、准确性、耗时、来源、许可与完整性在听写卡中保留。 */
    @Test fun modelMetadataAndSourceActionsRemainAvailable() {
        show(model().copy(canDownload = true))
        compose.onNodeWithText("电视节目语音训练的小模型，体积小、识别快，适合日语；英语表现较弱。").assertExists()
        compose.onNodeWithText("日语、").assertExists()
        compose.onNodeWithText("英语").assertExists()
        compose.onNodeWithText("日语识别错误率").assertExists()
        compose.onNodeWithText("英语识别错误率").assertExists()
        compose.onNodeWithText("识别耗时（日/英）").assertExists()
        click("来源、许可与完整性")
        compose.onNodeWithText("来源：界面测试来源\n许可：Apache-2.0").assertExists()
        compose.onNodeWithText("逐文件校验 SHA-256").assertExists()
        click("查看下载来源")
        click("查看许可")
        click("评测来源与条件")
        compose.runOnIdle { assertEquals(listOf("来源:reazonspeech", "许可:reazonspeech", "评测"), events) }
    }

    /** 删除必须先展示固定文件范围和停用后果，取消后无删除；确认才传回原始模型标识。 */
    @Test fun deletionRequiresConfirmationAndCancellationPreservesModel() {
        show(model().copy(status = ModelUiStatus.READY, canDelete = true, deletionScope = scope))
        clickDelete()
        compose.onNodeWithText(scope).assertExists()
        compose.onNodeWithText("删除会停用此模型；如果它是当前选中模型，将清空选择，不会自动切换到其他模型。再次使用需要重新下载。").assertExists()
        compose.onNode(hasText("取消") and hasClickAction()).performClick()
        compose.runOnIdle { assertTrue(events.isEmpty()); assertEquals(1, models.items.size) }
        clickDelete()
        compose.runOnIdle { assertTrue(events.isEmpty()) }
        compose.onNodeWithText("确认删除").performClick()
        compose.runOnIdle { assertEquals(listOf("删除:reazonspeech"), events) }
    }

    /** 确认打开后状态变化会禁用删除，删除进行中不能重复提交或取消。 */
    @Test fun deleteConfirmationTracksLiveCapabilitiesAndInFlightState() {
        show(model().copy(status = ModelUiStatus.READY, canDelete = true, deletionScope = scope))
        clickDelete()
        compose.runOnIdle { models = models.copy(items = listOf(models.items.single().copy(
            canDelete = false, deleteBlockedReason = "同传正在运行"))) }
        compose.onNodeWithText("确认删除").assertIsNotEnabled()
        compose.onNode(hasText("同传正在运行") and hasAnyAncestor(isDialog())).assertExists()
        compose.runOnIdle { models = models.copy(deletingId = "reazonspeech") }
        compose.onNodeWithText("正在删除…").assertIsNotEnabled()
        compose.onNode(hasText("取消") and hasClickAction()).assertIsNotEnabled()
        compose.runOnIdle { assertTrue(events.isEmpty()) }
    }

    /** 兼容未传模型状态的旧调用，缺失详情只能刷新，不能根据配置标记伪造可用模型。 */
    @Test fun defaultModelStateOffersRefreshWithoutLegacyNavigation() {
        compose.setContent { MaterialTheme {
            ListeningServiceManagement(SettingsUiState(listeningProfiles = listOf(profile())), false,
                SimulSubActions(onRefreshModels = { events += "刷新" }, onNavigate = { events += "导航:$it" }), false)
        } }
        compose.onNodeWithText("模型详情尚未加载，请刷新模型状态。").assertExists()
        compose.onNodeWithText("下载模型").assertDoesNotExist()
        compose.onNodeWithText("刷新模型状态").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("刷新"), events) }
    }

    /** 宽屏复用服务列表两栏，本地与云服务首行并列，不增加集中模型管理区域。 */
    @Test fun wideLayoutKeepsLocalAndCloudCardsInExistingColumns() {
        val cloud = ServiceProfileUiState("cloud-fixture", "千问测试听写", kind = "asr")
        show(model(), wide = true, extraProfiles = listOf(cloud))
        compose.onNodeWithTag("service-profiles-two-columns").assertExists()
        val localBounds = compose.onNodeWithTag("service-profile-local:reazonspeech").getUnclippedBoundsInRoot()
        val cloudBounds = compose.onNodeWithTag("service-profile-cloud-fixture").getUnclippedBoundsInRoot()
        assertTrue("本地模型与云服务必须分别占据左右栏", localBounds.right < cloudBounds.left)
        assertTrue("两栏首项必须对齐", kotlin.math.abs(localBounds.top.value - cloudBounds.top.value) <= 1f)
    }

    /** 构造单一模型状态来源，界面操作只记录事件或更新确认标识。 */
    private fun show(item: ModelUiState, selected: Boolean = false, wide: Boolean = false,
        extraProfiles: List<ServiceProfileUiState> = emptyList()) {
        models = ModelsUiState(items = listOf(item))
        val actions = SimulSubActions(
            onNavigate = { events += "导航:$it" }, onDownloadModel = { events += "下载:$it" },
            onCancelDownload = { events += "取消:$it" }, onActivateModel = { events += "旧启用:$it" },
            onSelectListeningService = { events += "使用:$it" },
            onModelSource = { events += "来源:$it" }, onModelLicense = { events += "许可:$it" },
            onOpenModelBenchmark = { events += "评测" },
            onRequestDeleteModel = { models = models.copy(deleteConfirmationId = it) },
            onDismissDeleteModel = { models = models.copy(deleteConfirmationId = null) },
            onConfirmDeleteModel = { events += "删除:$it" },
        )
        compose.setContent { MaterialTheme {
            Box(if (wide) Modifier.requiredWidth(1000.dp) else Modifier) {
                ListeningServiceManagement(SettingsUiState(listeningProfiles = listOf(profile(selected)) + extraProfiles),
                    busy, actions, wide, models)
            }
        } }
    }

    /** 模型标识不带服务前缀，能力由各测试按状态明确提供。 */
    private fun model() = ModelUiState("reazonspeech", "ReazonSpeech", "界面测试简介", "日语、英语",
        "74.1 MiB", "Apache-2.0", "界面测试来源", sourceUrl = "https://example.com/model",
        licenseUrl = "https://example.com/license", verificationDetail = "逐文件校验 SHA-256")

    /** 本地服务标识严格使用前缀并与模型标识一一对应。 */
    private fun profile(selected: Boolean = false) = ServiceProfileUiState("local:reazonspeech", "ReazonSpeech",
        kind = "local", selected = selected)

    /** 点击前滚动卡内真实控件，保证操作不由夹具直接调用代替。 */
    private fun click(label: String) { compose.onNodeWithText(label).performScrollTo().performClick() }

    /** 删除入口使用模型专属无障碍说明，避免误点其他服务。 */
    private fun clickDelete() {
        compose.onNodeWithContentDescription("删除ReazonSpeech").performScrollTo().performClick()
    }
}
