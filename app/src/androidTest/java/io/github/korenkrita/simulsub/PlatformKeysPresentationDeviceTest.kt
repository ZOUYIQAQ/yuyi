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

/** 手机、平板及大字号下验证中央密钥页的展示、滚动和默认操作。 */
@RunWith(Parameterized::class)
class PlatformKeysPresentationDeviceTest(private val tablet: Boolean, private val fontScale: Float) {
    @get:Rule val compose = createComposeRule()
    private var keys by mutableStateOf(listOf(
        PlatformKeyUiState("qwen", "阿里百炼 · 千问", key = "测试密钥A", hasSavedKey = true,
            credentialId = "a", credentialLabel = "账号 A", isDefault = true),
        PlatformKeyUiState("qwen", "阿里百炼 · 千问", key = "测试密钥B", hasSavedKey = true,
            credentialId = "b", credentialLabel = "账号 B"),
        PlatformKeyUiState("deepseek", "DeepSeek 官方", credentialId = "c", credentialLabel = "账号 C"),
    ))
    private var busy by mutableStateOf(false)
    private val defaults = mutableListOf<Pair<String, String>>()
    private val saves = mutableListOf<Pair<String, String>>()
    private val additions = mutableListOf<String>()

    companion object {
        /** 同时验证两种屏幕尺寸和系统放大字号。 */
        @JvmStatic @Parameterized.Parameters(name = "平板={0}，字号={1}")
        fun cases(): List<Array<Any>> = listOf(false, true).flatMap { tablet ->
            listOf(1f, 1.5f).map { arrayOf<Any>(tablet, it) }
        }
    }

    /** 点击已保存的 B 密钥后，默认标识随上层状态更新且 A 恢复可选。 */
    @Test fun savedAlternativeCanBecomeDefault() {
        show()
        reveal("platform-key-default-qwen:a").assertIsNotEnabled().assertTextContains("已默认")
        reveal("platform-key-default-qwen:b").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf("qwen" to "b"), defaults) }
        reveal("platform-key-default-qwen:b").assertIsNotEnabled().assertTextContains("已默认")
        reveal("platform-key-default-qwen:a").assertIsEnabled().assertTextContains("设为默认")
    }

    /** 未保存、空值、纯空白、修改中、新建以及会话锁定都不能设为默认。 */
    @Test fun ineligibleCredentialsCannotBecomeDefault() {
        show()
        val saved = keys[1]
        listOf(saved.copy(hasSavedKey = false), saved.copy(key = ""), saved.copy(key = "   "),
            saved.copy(hasUnsavedChanges = true), saved.copy(isNew = true)).forEach { candidate ->
            compose.runOnIdle { keys = listOf(candidate) }
            reveal("platform-key-default-qwen:b").assertIsNotEnabled()
        }
        compose.runOnIdle { keys = listOf(saved); busy = true }
        listOf("platform-key-default-qwen:b", "platform-key-save-qwen:b", "platform-key-qwen:b",
            "platform-key-label-qwen:b", "platform-key-add-qwen").forEach { reveal(it).assertIsNotEnabled() }
        compose.runOnIdle { assertTrue(defaults.isEmpty()) }
    }

    /** 名称、密钥原文、显隐和保存仍通过每条凭据的稳定入口操作。 */
    @Test fun editorsKeepRawDraftAndIndependentActions() {
        show()
        val raw = "  测试原文B-_/=+  "
        reveal("platform-key-label-qwen:b").performTextReplacement("新的 B 名称")
        reveal("platform-key-qwen:b").performTextReplacement(raw)
        compose.onNode(hasContentDescription("显示阿里百炼 · 千问密钥") and
            hasAnyAncestor(hasTestTag("platform-key-qwen:b")), useUnmergedTree = true).performClick()
        compose.onNodeWithTag("platform-key-qwen:b").assertTextContains(raw)
        compose.onNode(hasContentDescription("隐藏阿里百炼 · 千问密钥") and
            hasAnyAncestor(hasTestTag("platform-key-qwen:b")), useUnmergedTree = true).performClick()
        reveal("platform-key-status-qwen:b").assertTextContains("有未保存的修改", substring = true)
        reveal("platform-key-default-qwen:b").assertIsNotEnabled()
        reveal("platform-key-save-qwen:b").performClick()
        compose.runOnIdle {
            assertEquals(listOf("qwen" to "b"), saves)
            assertEquals(raw, keys[1].key)
            assertEquals("新的 B 名称", keys[1].credentialLabel)
            assertEquals("测试密钥A", keys[0].key)
        }
        reveal("platform-key-default-qwen:b").assertIsEnabled()
        reveal("platform-key-add-qwen").performClick()
        compose.runOnIdle { assertEquals(listOf("qwen"), additions) }
    }

    /** 空平台列表提供可读反馈，标题仍保留简短的多密钥说明。 */
    @Test fun emptyPageKeepsConciseExplanation() {
        compose.runOnIdle { keys = emptyList() }
        show()
        compose.onNodeWithText("同一平台可保存多把密钥").assertIsDisplayed()
        compose.onNodeWithText("0 个平台").assertIsDisplayed()
        compose.onNodeWithText("暂无平台密钥信息，请返回后重新进入。").assertIsDisplayed()
    }

    /** 平台分组在平板分居两栏，长平台中的末条密钥仍可精准滚动。 */
    @Test fun groupsUseBothColumnsAndLongListsRemainReachable() {
        compose.runOnIdle { keys = listOf(keys[0], keys[2]) }
        show()
        if (tablet) {
            val left = compose.onNodeWithTag("platform-heading-qwen").fetchSemanticsNode().boundsInRoot
            val right = compose.onNodeWithTag("platform-heading-deepseek").fetchSemanticsNode().boundsInRoot
            assertTrue("两个平台应分居左右栏", left.right <= right.left)
        }
        compose.runOnIdle {
            keys = (0 until 18).map { index -> PlatformKeyUiState("qwen", "阿里百炼 · 千问",
                credentialId = "long-$index", credentialLabel = "密钥 $index") } + keys.last()
        }
        reveal("platform-key-label-qwen:long-17").performTextReplacement("最后一条")
        reveal("platform-key-qwen:long-17").performTextReplacement("末条原文")
        reveal("platform-key-save-qwen:long-17").performClick()
        reveal("platform-key-add-qwen").performClick()
        reveal("platform-key-deepseek:c").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals("最后一条", keys[17].credentialLabel)
            assertEquals("末条原文", keys[17].key)
            assertEquals(listOf("qwen" to "long-17"), saves)
            assertEquals(listOf("qwen"), additions)
        }
    }

    /** 使用真实主题与页面，仅把业务动作替换成可观察的内存回填。 */
    private fun show() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                SimulSubTheme(SettingsUiState(dynamicColors = false)) {
                    Box(Modifier.requiredSize(if (tablet) 1100.dp else 412.dp, if (tablet) 760.dp else 880.dp)) {
                        PlatformKeysSettings(keys, busy, SimulSubActions(
                            onSetDefaultPlatformKey = { id, credentialId ->
                                defaults += id to credentialId
                                keys = keys.map { if (it.id == id) it.copy(isDefault = it.credentialId == credentialId) else it }
                            },
                            onPlatformKeyLabelChange = { id, credentialId, label ->
                                keys = keys.map { if (it.id == id && it.credentialId == credentialId)
                                    it.copy(credentialLabel = label, hasUnsavedChanges = true) else it }
                            },
                            onPlatformCredentialChange = { id, credentialId, key ->
                                keys = keys.map { if (it.id == id && it.credentialId == credentialId)
                                    it.copy(key = key, hasUnsavedChanges = true) else it }
                            },
                            onSavePlatformCredential = { id, credentialId ->
                                saves += id to credentialId
                                keys = keys.map { if (it.id == id && it.credentialId == credentialId)
                                    it.copy(hasSavedKey = true, hasUnsavedChanges = false, isNew = false) else it }
                            },
                            onAddPlatformKey = { additions += it },
                        ), tablet)
                    }
                }
            }
        }
    }

    /** 在真实懒加载列表内按稳定标识定位，兼容两栏各自滚动。 */
    private fun reveal(tag: String): SemanticsNodeInteraction {
        val matcher = hasTestTag(tag)
        compose.waitForIdle()
        val lists = compose.onAllNodes(hasScrollToIndexAction()).fetchSemanticsNodes().size
        var failure: AssertionError? = null
        for (index in 0 until lists) {
            try {
                compose.onAllNodes(hasScrollToIndexAction())[index].performScrollToNode(matcher)
                return compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
            } catch (error: AssertionError) { failure = error }
        }
        throw failure ?: AssertionError("未找到密钥页面滚动列表")
    }
}
