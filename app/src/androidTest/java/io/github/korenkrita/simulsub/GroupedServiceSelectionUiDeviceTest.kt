package io.github.korenkrita.simulsub

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import io.github.korenkrita.simulsub.ui.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** 组合服务卡的四项定向交互仅使用内存选项，不访问网络、真实密钥、模型或通知。 */
class GroupedServiceSelectionUiDeviceTest {
    @get:Rule val compose = createComposeRule()
    private var state by mutableStateOf(LiveUiState())
    private val events = mutableListOf<String>()

    /** 本地路径的两行菜单均可选择，选择回填原始标识并更新可见的假密钥名称。 */
    @Test fun localMenusSelectServicesAndShowSupportingKeyLabels() {
        show()
        compose.onAllNodesWithTag("service-selection").assertCountEquals(1)
        supporting("listening-service-picker", "Keyname：听写假内容A").assertIsDisplayed()
        supporting("translation-service-picker", "Keyname：翻译假内容A").assertIsDisplayed()
        choose("listening-service-picker", "服务B")
        anchor("listening-service-picker").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "服务B"))
        supporting("listening-service-picker", "Keyname：听写假内容B").assertIsDisplayed()
        choose("translation-service-picker", "服务B")
        anchor("translation-service-picker").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "服务B"))
        supporting("translation-service-picker", "Keyname：翻译假内容B").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(listOf("听写:local:service-b", "翻译:translation-b"), events)
            assertEquals("local:service-b", state.listeningServiceId)
            assertEquals("translation-b", state.translationServiceId)
        }
    }

    /** 直接听译仅保留一个听写选择器，翻译行显示已包含说明并且不提供独立选择器。 */
    @Test fun onlineDirectHasOneListeningPickerAndIncludesTranslation() {
        show(mode = ListeningMode.ONLINE)
        compose.onAllNodesWithTag("listening-service-picker").assertCountEquals(1)
        compose.onNodeWithTag("translation-included").assertIsDisplayed()
        compose.onNodeWithTag("translation-service-picker").assertDoesNotExist()
        choose("listening-service-picker", "服务B")
        compose.onNodeWithTag("translation-included").assertIsDisplayed()
        compose.onNodeWithTag("translation-service-picker").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf("听写:online:service-b"), events) }
    }

    /** 禁用状态直接检查有状态描述的锚定行，真实点击也不能展开菜单或派发选择动作。 */
    @Test fun disabledAnchorsCannotOpenMenusOrSelectServices() {
        show(enabled = false)
        listOf("listening-service-picker", "translation-service-picker").forEach { tag ->
            anchor(tag).assertIsDisplayed().assertIsNotEnabled().performTouchInput { click() }
            compose.onNode(isPopup()).assertDoesNotExist()
        }
        compose.runOnIdle {
            assertTrue(events.isEmpty())
            assertEquals("local:service-a", state.listeningServiceId)
            assertEquals("translation-a", state.translationServiceId)
        }
    }

    /** 两种服务均为空时保留独立准备入口，点击各自只派发对应的设置回调。 */
    @Test fun emptyServicesPreparationButtonsOpenTheirOwnSettings() {
        show(empty = true)
        compose.onNodeWithTag("listening-services-empty").assertIsDisplayed()
        compose.onNodeWithTag("translation-services-empty").assertIsDisplayed()
        compose.onNodeWithTag("listening-service-picker").assertDoesNotExist()
        compose.onNodeWithTag("translation-service-picker").assertDoesNotExist()
        compose.onNodeWithText("查看听写服务").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithText("查看翻译服务").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf("准备听写", "准备翻译"), events) }
    }

    /** 使用现有主题直接渲染组合卡，回调只更新测试内存并记录原始选项标识。 */
    private fun show(mode: ListeningMode = ListeningMode.LOCAL, enabled: Boolean = true, empty: Boolean = false) {
        val prefix = if (mode == ListeningMode.ONLINE) "online" else "local"
        state = LiveUiState(listeningMode = mode, listeningServiceId = "$prefix:service-a",
            listeningServices = if (empty) emptyList() else listOf(
                UiOption("$prefix:service-a", "服务A", "Keyname：听写假内容A"),
                UiOption("$prefix:service-b", "服务B", "Keyname：听写假内容B")),
            translationServiceId = "translation-a", translationServices = if (empty) emptyList() else listOf(
                UiOption("translation-a", "服务A", "Keyname：翻译假内容A"),
                UiOption("translation-b", "服务B", "Keyname：翻译假内容B")))
        compose.setContent {
            SimulSubTheme(SettingsUiState()) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    ServiceSelectionCard(state.listeningServiceId, state.listeningServices,
                        state.translationServiceId, state.translationServices, state.listeningMode,
                        enabled, SimulSubActions(
                            onSelectListeningService = {
                                events += "听写:$it"
                                state = state.copy(listeningServiceId = it)
                            },
                            onSelectTranslationService = {
                                events += "翻译:$it"
                                state = state.copy(translationServiceId = it)
                            },
                            onOpenListeningSettings = { events += "准备听写" },
                            onOpenApiSettings = { events += "准备翻译" },
                        ))
                }
            }
        }
    }

    /** 通过锚点自身的状态描述定位可用性，避免把外层测试标签容器当成可点击行。 */
    private fun anchor(tag: String) = compose.onNode(
        hasAnyAncestor(hasTestTag(tag)) and SemanticsMatcher.keyIsDefined(SemanticsProperties.StateDescription))

    /** 在对应行内查找密钥说明，菜单收起后仍须显示已选服务的辅助文案。 */
    private fun supporting(tag: String, label: String) =
        compose.onNode(hasText(label) and hasAnyAncestor(hasTestTag(tag)))

    /** 点击真实锚定行，再在弹出菜单中按服务名选择，避免命中另一行的同名服务。 */
    private fun choose(tag: String, label: String) {
        anchor(tag).performScrollTo().assertIsEnabled().performClick()
        compose.onNode(hasText(label) and hasAnyAncestor(isPopup())).assertIsDisplayed().performClick()
        compose.onNode(isPopup()).assertDoesNotExist()
    }
}
