package io.github.korenkrita.simulsub

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.korenkrita.simulsub.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File

/** 手机、平板与大字号只使用虚构密钥，验证紧凑列表、弹窗与删除确认。 */
@RunWith(Parameterized::class)
class CompactKeysUiDeviceTest(private val tablet: Boolean, private val fontScale: Float) {
    @get:Rule val compose = createComposeRule()
    private val raw = "sk-demo-1234567890-test"
    private var keys by mutableStateOf(listOf(
        PlatformKeyUiState("qwen", "阿里百炼 · 千问", raw, hasSavedKey = true,
            credentialLabel = "直播账号", isDefault = true, usedBy = listOf("千问实时听译", "千问文字翻译")),
        PlatformKeyUiState("qwen", "阿里百炼 · 千问", "sk-alternate-0987654321", hasSavedKey = true,
            credentialId = "second", credentialLabel = "备用账号"),
        PlatformKeyUiState("deepseek", "DeepSeek 官方", "sk-another-fake-4321", hasSavedKey = true,
            credentialLabel = "日常翻译", isDefault = true),
        PlatformKeyUiState("unused", "未使用平台", isPlaceholder = true),
    ))
    private var busy by mutableStateOf(false)
    private val deleted = mutableListOf<String>()
    private val additions = mutableListOf<String>()

    companion object {
        /** 一台设备以内存布局覆盖两种屏幕与放大字号。 */
        @JvmStatic @Parameterized.Parameters(name = "平板={0}，字号={1}")
        fun cases(): List<Array<Any>> = listOf(false, true).flatMap { tablet ->
            listOf(1f, 1.5f).map { arrayOf<Any>(tablet, it) }
        }
    }

    /** 未点击钥匙扣时不展开输入框，列表只展示首尾与默认标记。 */
    @Test fun listStartsCollapsed() {
        show()
        compose.onNodeWithTag("platform-key-qwen:default").assertDoesNotExist()
        compose.onNodeWithText(raw).assertDoesNotExist()
        compose.onNodeWithText("直播账号").assertIsDisplayed()
        compose.onNodeWithText("sk-d******test").assertIsDisplayed()
        compose.onNodeWithText("未使用平台").assertDoesNotExist()
        if (tablet) {
            val left = compose.onNodeWithTag("platform-heading-qwen").fetchSemanticsNode().boundsInRoot
            val right = compose.onNodeWithTag("platform-heading-deepseek").fetchSemanticsNode().boundsInRoot
            assertTrue(left.right <= right.left)
        }
        screenshot("钥匙扣")
    }

    /** 删除先展示服务影响，取消不派发；确认才删除目标且不影响其他条目。 */
    @Test fun deletionRequiresConfirmation() {
        show()
        compose.onNodeWithTag("platform-key-delete-qwen:default").performClick()
        compose.onNodeWithText("删除密钥？").assertIsDisplayed()
        compose.onNodeWithText("以下服务需要重新选择密钥：\n千问实时听译\n千问文字翻译").assertIsDisplayed()
        compose.runOnIdle { assertTrue(deleted.isEmpty()) }
        screenshot("删除确认")
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("直播账号").assertIsDisplayed()
        compose.onNodeWithTag("platform-key-delete-qwen:default").performClick()
        compose.onNodeWithTag("platform-key-confirm-delete").performClick()
        compose.runOnIdle { assertEquals(listOf("qwen:default"), deleted) }
        compose.onNodeWithText("直播账号").assertDoesNotExist()
        compose.onNodeWithText("备用账号").assertIsDisplayed()
    }

    /** 弹窗打开后仍读完整原文，修改退出需确认且单条放弃不会修改其他密钥。 */
    @Test fun editingAndDiscardAreScoped() {
        show()
        compose.onNodeWithTag("platform-key-ring-qwen:default").performClick()
        compose.onNodeWithTag("platform-key-qwen:default").performScrollTo().assertTextContains(raw)
        compose.onNodeWithTag("platform-key-qwen:default").performTextReplacement("新草稿原文")
        compose.onNodeWithText("完成").performClick()
        compose.onNodeWithText("配置尚未保存").assertIsDisplayed()
        compose.onNodeWithText("继续编辑").performClick()
        compose.onNodeWithTag("platform-key-qwen:default").assertTextContains("新草稿原文")
        compose.onNodeWithText("完成").performClick()
        compose.onNodeWithText("放弃修改").performClick()
        compose.runOnIdle { assertEquals(raw, keys.first().key); assertEquals("备用账号", keys[1].credentialLabel) }
        compose.onNodeWithTag("platform-key-qwen:default").assertDoesNotExist()
    }

    /** 无用平台不占列表；新增直接派发表单动作，会话锁定时禁止新增和删除。 */
    @Test fun unusedPlatformIsAvailableOnlyWhenAdding() {
        show()
        compose.onNodeWithTag("platform-key-add").performClick()
        compose.runOnIdle { assertEquals(listOf(""), additions); busy = true }
        compose.onNodeWithTag("platform-key-add").assertIsNotEnabled()
        compose.onNodeWithTag("platform-key-delete-qwen:default").assertIsNotEnabled()
    }

    /** 服务列表隐藏重复模型说明，密钥操作和使用按钮同行，选中状态使用轻量标记。 */
    @Test fun translationCardsAreCompactAndKeepActions() {
        var selected by mutableStateOf("a")
        var edited = ""
        compose.setContent {
            Fixture {
                TranslationServiceManagement(SettingsUiState(translationProfiles = listOf(
                    ServiceProfileUiState("a", "DeepSeek · deepseek-flash", "deepseek-flash", selected = selected == "a",
                        configured = true, builtIn = true, platformId = "deepseek", credentialLabel = "日常翻译",
                        credentialOptions = listOf(UiOption("default", "日常翻译", "已保存"))),
                    ServiceProfileUiState("b", "千问 · qwen-plus", "qwen-plus", selected = selected == "b",
                        configured = true, builtIn = false, credentialLabel = "直播账号"))), false,
                    SimulSubActions(onSelectTranslationService = { selected = it }, onEditTranslationService = { edited = it }), tablet, {})
            }
        }
        compose.onNodeWithText("deepseek-flash").assertDoesNotExist()
        val card = hasAnyAncestor(hasTestTag("service-profile-b"))
        val use = hasText("使用") and card
        reveal(use).performClick()
        compose.runOnIdle { assertEquals("b", selected) }
        reveal(hasText("密钥：直播账号") and card).assertIsDisplayed()
        reveal(hasTestTag("configure-service-b")).performClick()
        compose.runOnIdle { assertEquals("b", edited) }
        screenshot("翻译服务")
    }

    /** 使用生产组件，业务事件只回填本测试内存。 */
    private fun show() {
        compose.setContent { Fixture {
            PlatformKeysSettings(keys, busy, SimulSubActions(
                onDeletePlatformKey = { platform, id -> deleted += "$platform:$id"; keys = keys.filterNot { it.id == platform && it.credentialId == id } },
                onPlatformCredentialChange = { platform, id, value -> keys = keys.map {
                    if (it.id == platform && it.credentialId == id) it.copy(key = value, hasUnsavedChanges = true) else it } },
                onDiscardPlatformKey = { platform, id -> keys = keys.map {
                    if (it.id == platform && it.credentialId == id) it.copy(key = raw, hasUnsavedChanges = false) else it } },
                onOpenQuickPlatformKey = { platform, _ -> additions += platform }), tablet)
        } }
    }

    /** 逻辑平板压缩渲染在唯一模拟器中，并消费真实系统边距。 */
    @Composable private fun Fixture(content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalDensity provides Density(if (tablet) 0.6f else 1f, fontScale)) {
            SimulSubTheme(SettingsUiState(dynamicColors = false)) {
                Box(Modifier.requiredSize(if (tablet) 1040.dp else 360.dp, 720.dp)
                    .consumeWindowInsets(WindowInsets.systemBars.union(WindowInsets.displayCutout)).padding(top = 24.dp, bottom = 24.dp).testTag("compact-fixture")) { content() }
            }
        }
    }

    /** 在懒列表中寻找指定控件，兼容平板左右分栏。 */
    private fun reveal(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        if (compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty())
            return compose.onNode(matcher).performScrollTo().assertIsDisplayed()
        val count = compose.onAllNodes(hasScrollToIndexAction()).fetchSemanticsNodes().size
        for (index in 0 until count) runCatching {
            compose.onAllNodes(hasScrollToIndexAction())[index].performScrollToNode(matcher)
        }.onSuccess { return compose.onNode(matcher).performScrollTo().assertIsDisplayed() }
        throw AssertionError("未找到目标控件")
    }

    /** 只保存虚构数据截图供本轮视觉检查，不捕获用户密钥页。 */
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val inst = InstrumentationRegistry.getInstrumentation()
        val file = File(inst.targetContext.getExternalFilesDir(null), "compact-keys/$name-$tablet-$fontScale.png")
        file.parentFile!!.mkdirs()
        val bitmap = if (name == "删除确认") inst.uiAutomation.takeScreenshot()
            else compose.onNodeWithTag("compact-fixture").captureToImage().asAndroidBitmap()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
