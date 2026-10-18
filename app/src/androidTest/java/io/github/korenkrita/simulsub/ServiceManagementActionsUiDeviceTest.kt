package io.github.korenkrita.simulsub

import android.view.KeyEvent
import android.view.View
import android.view.inspector.WindowInspector
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.korenkrita.simulsub.ui.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** 服务管理交互仅使用内存夹具，不访问偏好、网络、平台密钥存储、采音或飞书通知。 */
class ServiceManagementActionsUiDeviceTest {
    @get:Rule val compose = createComposeRule()
    private var settings by mutableStateOf(SettingsUiState())
    private var busy by mutableStateOf(false)
    private val events = mutableListOf<String>()
    private val custom = ServiceProfileUiState("自定义服务 +/=", "自定义翻译", builtIn = false,
        selected = true, platformId = "deepseek", credentialLabel = "课程密钥 +/= 原文")
    private val builtIn = ServiceProfileUiState("builtin-deepseek", "DeepSeek · 内置翻译",
        platformId = "deepseek", credentialOptions = listOf(UiOption("backup +/= 原文", "备用密钥 +/= 原文", "已保存")),
        credentialId = "backup +/= 原文", credentialLabel = "备用密钥 +/= 原文")

    /** 自定义服务先确认，取消无动作；当前服务说明切换且保留平台密钥，确认按原文标识回调。 */
    @Test fun customDeletionRequiresConfirmationAndPreservesCredentials() {
        show(listOf(custom, builtIn))
        delete(custom).performClick()
        compose.onNodeWithText("删除翻译服务？").assertIsDisplayed()
        compose.onNodeWithText("平台密钥会保留，其他服务仍可继续使用。").assertIsDisplayed()
        compose.onNodeWithText("这是当前使用的服务，删除后将切换到其他服务。").assertIsDisplayed()
        compose.runOnIdle { assertTrue(events.isEmpty()) }
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertTrue(events.isEmpty()) }
        delete(custom).performClick()
        compose.onNodeWithTag("confirm-delete-translation-service").performClick()
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(listOf("删除:${custom.id}"), events)
            assertEquals(custom.credentialLabel, settings.translationProfiles.first().credentialLabel)
        }
    }

    /** 内置条目同样允许按标识删除，界面不清理平台密钥或擅自切换当前服务。 */
    @Test fun builtInServiceCanBeDeletedAfterConfirmation() {
        show(listOf(builtIn, custom))
        delete(builtIn).assertIsEnabled().performClick()
        compose.onNodeWithText("这是当前使用的服务，删除后将切换到其他服务。").assertDoesNotExist()
        compose.onNodeWithTag("confirm-delete-translation-service").performClick()
        compose.runOnIdle { assertEquals(listOf("删除:${builtIn.id}"), events) }
    }

    /** 弹窗打开后开始运行也必须禁用确认，返回列表后删除、编辑和使用均不可操作。 */
    @Test fun runningDisablesDeletionIncludingAnAlreadyOpenConfirmation() {
        show(listOf(custom, builtIn))
        delete(custom).performClick()
        compose.runOnIdle { busy = true }
        compose.onNodeWithTag("confirm-delete-translation-service").assertIsNotEnabled()
        compose.onNodeWithText("取消").performClick()
        delete(custom).assertIsNotEnabled()
        cardAction(custom, "编辑").assertIsNotEnabled()
        reveal(hasTestTag("service-profile-${builtIn.id}"))
        cardAction(builtIn, "使用").assertIsNotEnabled()
        compose.runOnIdle { assertTrue(events.isEmpty()) }
    }

    /** 删除期间列表变成最后一项时及时锁定确认，保留唯一服务的删除图标为禁用状态。 */
    @Test fun lastRemainingServiceCannotBeDeleted() {
        show(listOf(custom, builtIn))
        delete(custom).performClick()
        compose.runOnIdle { settings = settings.copy(translationProfiles = listOf(custom)) }
        compose.onNodeWithTag("confirm-delete-translation-service").assertIsNotEnabled()
        compose.onNodeWithText("取消").performClick()
        delete(custom).assertIsNotEnabled()
        compose.runOnIdle { assertTrue(events.isEmpty()) }
    }

    /** 短标题独立于服务名，密钥绑定和快捷添加均保留带翻译前缀的原始标识。 */
    @Test fun credentialDialogKeepsShortTitleRawBindingAndQuickAdd() {
        show(listOf(builtIn.copy(name = "DeepSeek · 很长的服务名称 · deepseek-flash"), custom))
        cardAction(builtIn, "选择密钥").performClick()
        compose.onNode(hasText("选择密钥") and hasAnyAncestor(isDialog())).assertIsDisplayed()
        compose.onNodeWithTag("service-credential-name").assertTextEquals("DeepSeek · 很长的服务名称 · deepseek-flash")
        compose.onNodeWithText("当前使用：备用密钥 +/= 原文").assertIsDisplayed()
        compose.onNode(hasAnyAncestor(hasTestTag("service-credential-picker")) and hasClickAction()).performClick()
        compose.onNode(hasText("备用密钥 +/= 原文") and hasAnyAncestor(isPopup())).performClick()
        compose.runOnIdle { assertEquals(listOf("绑定:translation:${builtIn.id}:backup +/= 原文"), events) }
        compose.onNodeWithTag("quick-key-add-translation:${builtIn.id}").performClick()
        compose.runOnIdle { assertEquals("快捷:deepseek:translation:${builtIn.id}", events.last()) }
        compose.onNodeWithText("管理平台密钥").assertHasClickAction()
        compose.onNodeWithText("连接设置与测试").assertHasClickAction()
        compose.runOnIdle { busy = true }
        compose.onNodeWithTag("quick-key-add-translation:${builtIn.id}").assertIsNotEnabled()
        compose.onNodeWithText("管理平台密钥").assertIsNotEnabled()
        compose.onNodeWithText("连接设置与测试").assertIsNotEnabled()
        compose.runOnIdle { busy = false }
        compose.onNodeWithText("连接设置与测试").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("编辑:${builtIn.id}", "打开编辑器"), events.takeLast(2)) }
        compose.onNode(isDialog()).assertDoesNotExist()
        cardAction(builtIn, "选择密钥").performClick()
        compose.onNodeWithText("管理平台密钥").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("平台密钥", events.last()) }
        compose.onNode(isDialog()).assertDoesNotExist()
        cardAction(builtIn, "选择密钥").assertIsDisplayed()
    }

    /** 没有已存密钥的内置服务保持直接填写快捷密钥，不绕到选择弹窗。 */
    @Test fun builtInWithoutSavedKeyStillOpensQuickEntryDirectly() {
        show(listOf(builtIn.copy(configured = false, credentialOptions = emptyList()), custom))
        cardAction(builtIn, "选择密钥").performClick()
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf("快捷:deepseek:translation:${builtIn.id}"), events) }
    }

    /** 列表滚到尾部后完成或系统返回关闭弹窗，均保留原卡片位置。 */
    @Test fun credentialDialogCompletionAndBackKeepListScrollPosition() {
        val profiles = (1..18).map { builtIn.copy(id = "builtin-$it", name = "翻译服务 $it") }
        val target = profiles.last()
        show(profiles)
        reveal(hasTestTag("service-profile-${target.id}"))
        val action = cardAction(target, "选择密钥")
        action.performScrollTo()
        val before = compose.onNodeWithTag("service-profile-${target.id}").getUnclippedBoundsInRoot()
        action.performClick()
        compose.onNodeWithText("完成").performClick()
        assertEquals(before, compose.onNodeWithTag("service-profile-${target.id}").getUnclippedBoundsInRoot())
        compose.onNode(isDialog()).assertDoesNotExist()
        val previousRoots = compose.runOnUiThread { WindowInspector.getGlobalWindowViews().toSet() }
        action.performClick()
        waitForFocusedDialog(previousRoots)
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
        compose.onNode(isDialog()).assertDoesNotExist()
        assertEquals(before, compose.onNodeWithTag("service-profile-${target.id}").getUnclippedBoundsInRoot())
        compose.runOnIdle { assertTrue(events.isEmpty()) }
    }

    /** 通过点击前后的窗口根差集定位新弹窗，等待显示和焦点就绪，避免返回落到后方 Activity。 */
    private fun waitForFocusedDialog(previousRoots: Set<View>) {
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodes(isDialog()).fetchSemanticsNodes().size == 1
        }
        compose.onNode(isDialog()).assertIsDisplayed()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.runOnUiThread {
                val newRoots = WindowInspector.getGlobalWindowViews().toSet() - previousRoots
                newRoots.singleOrNull { it.isAttachedToWindow && it.isShown && it.hasWindowFocus() } != null
            }
        }
    }

    /** 渲染真实服务列表并仅记录业务回调，所有状态都留在独立内存内。 */
    private fun show(profiles: List<ServiceProfileUiState>) {
        settings = SettingsUiState(translationProfiles = profiles)
        compose.setContent {
            SimulSubTheme(settings) {
                Box(Modifier.fillMaxSize()) {
                    TranslationServiceManagement(settings, busy, SimulSubActions(
                        onDeleteTranslationService = { events += "删除:$it" },
                        onServiceCredentialChange = { serviceId, keyId -> events += "绑定:$serviceId:$keyId" },
                        onOpenQuickPlatformKey = { platformId, serviceId -> events += "快捷:$platformId:$serviceId" },
                        onOpenPlatformKeys = { events += "平台密钥" },
                        onEditTranslationService = { events += "编辑:$it" },
                    ), wide = false, onOpenEditor = { events += "打开编辑器" })
                }
            }
        }
    }

    /** 用稳定标识定位删除入口，避免服务名换行影响查找。 */
    private fun delete(profile: ServiceProfileUiState) =
        compose.onNodeWithTag("delete-translation-service-${profile.id}")

    /** 卡片内部按文字定位操作，同名按钮不会跨服务匹配。 */
    private fun cardAction(profile: ServiceProfileUiState, label: String) =
        compose.onNode(hasText(label) and hasAnyAncestor(hasTestTag("service-profile-${profile.id}")))

    /** 先由惰性列表定位目标卡片，避免对尚未组合的尾部节点直接滚动。 */
    private fun reveal(matcher: SemanticsMatcher) {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(matcher)
    }
}
