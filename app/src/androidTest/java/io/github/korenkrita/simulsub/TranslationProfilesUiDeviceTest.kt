package io.github.korenkrita.simulsub

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import io.github.korenkrita.simulsub.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** 配置目录界面使用隔离内存数据，不发送网络请求或读取真实凭据。 */
class TranslationProfilesUiDeviceTest {
    @get:Rule val compose = createComposeRule()
    private var api by mutableStateOf(TranslationApiUiState(profiles = listOf(UiOption("", "默认"), UiOption("p1", "备用"))))
    private val events = mutableListOf<String>()

    /** 真实官方选择面板与确认弹窗共用实际回调，放弃动作必须先于切换。 */
    @Test fun dirtySelectionCanCancelOrDiscardBeforeSwitching() {
        api = api.copy(hasUnsavedChanges = true)
        show()
        selectBackup()
        compose.onNodeWithText("配置尚未保存").assertIsDisplayed()
        compose.onNodeWithText("继续编辑").performClick()
        compose.runOnIdle { assertTrue(events.isEmpty()); assertEquals("", api.profileId) }
        selectBackup()
        compose.onNodeWithText("放弃修改").performClick()
        compose.runOnIdle { assertEquals(listOf("discard", "select:p1"), events); assertEquals("p1", api.profileId) }
    }
    /** 名称必须唯一，确认才新增；重命名不能创建新配置或改写密钥草稿。 */
    @Test fun createAndRenameValidateNamesBeforeCallbacks() {
        show()
        compose.onNodeWithText("新增配置").performClick()
        compose.onNodeWithText("确定").assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextInput("备用")
        compose.onNodeWithText("已有同名配置").assertIsDisplayed()
        compose.onNodeWithText("确定").assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextReplacement("本地")
        compose.onNodeWithText("确定").performClick()
        compose.runOnIdle { assertEquals(listOf("add:本地"), events) }
        compose.onNodeWithText("重命名").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("主服务")
        compose.onNodeWithText("确定").performClick()
        compose.runOnIdle { assertEquals("rename:主服务", events.last()) }
    }
    /** 删除有独立二次确认，取消无副作用，最后一套配置不允许删除。 */
    @Test fun deletionRequiresConfirmationAndLastProfileIsProtected() {
        show()
        compose.onNodeWithText("删除配置").performClick()
        compose.onNodeWithText("删除翻译配置？").assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertTrue(events.isEmpty()) }
        compose.onNodeWithText("删除配置").performClick()
        compose.onNodeWithText("删除").performClick()
        compose.runOnIdle { assertEquals(listOf("delete"), events); api = api.copy(profiles = listOf(UiOption("", "默认"))) }
        compose.onNodeWithText("删除配置").assertIsNotEnabled()
    }
    /** 使用真实UI状态验证测试期间按钮禁用，避免回调与连接测试同时改配置。 */
    @Test fun testingDisablesManagement() {
        api = api.copy(testing = true)
        show()
        for (text in listOf("新增配置", "重命名", "删除配置")) compose.onNodeWithText(text).assertIsNotEnabled()
        compose.runOnIdle { assertTrue(events.isEmpty()) }
    }

    /** 渲染独立目录并记录回调，用户输入和存储不进入测试。 */
    private fun show() {
        compose.setContent { SimulSubTheme(SettingsUiState()) {
            Box(Modifier.requiredSize(360.dp, 740.dp)) {
                TranslationProfiles(api, SimulSubActions(
                    onSelectApiProfile = { events += "select:$it"; api = api.copy(profileId = it) },
                    onAddApiProfile = { events += "add:$it" }, onRenameApiProfile = { events += "rename:$it" },
                    onDeleteApiProfile = { events += "delete" },
                    onDiscardApiDraft = { events += "discard"; api = api.copy(hasUnsavedChanges = false) },
                ), false)
            }
        } }
    }
    /** 打开官方选择器后按名称选择配置，不依赖固定坐标。 */
    private fun selectBackup() {
        compose.onNodeWithText("默认").performClick()
        compose.onNodeWithText("备用").performClick()
        compose.waitForIdle()
    }
}
