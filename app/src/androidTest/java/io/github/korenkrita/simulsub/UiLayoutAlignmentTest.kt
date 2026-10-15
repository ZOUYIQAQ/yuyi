package io.github.korenkrita.simulsub

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.korenkrita.simulsub.ui.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** 只使用状态与空动作渲染，不调用下载器、权限、网络或真实识别服务。 */
class UiLayoutAlignmentTest {
    @get:Rule val compose = createComposeRule()

    /** 宽屏长标题、简介和语言换行后，对应段落与卡片底部操作仍逐一对齐。 */
    @Test fun wideCardsAlignEverySectionWithWrappedContent() {
        val first = model("first", "短标题", "简短说明", "日语", "74 MiB")
        val second = model(
            "second", "具有较长名称的多语言离线识别模型，需要完整换行展示",
            "支持更多语言的模型，适合多语言内容。此段简介故意保留较长的文字，用于检查内容换行之后，语言、模型大小、主操作与详情入口是否仍然对齐。",
            "中文、英语、日语、韩语、粤语，以及需要完整显示的其他语言", "229 MiB",
        )
        compose.setContent {
            SimulSubTheme(SettingsUiState()) {
                Box(Modifier.requiredWidth(1040.dp).fillMaxHeight()) {
                    ModelsScreen(ModelsUiState(items = listOf(first, second)), SimulSubActions(), wide = true)
                }
            }
        }
        assertAligned(first.name, second.name)
        assertAligned(first.description, second.description)
        assertAligned(first.languages, second.languages)
        assertAligned(first.sizeLabel, second.sizeLabel)
        assertRepeatedAligned("未安装")
        assertRepeatedAligned("下载模型")
        assertRepeatedAligned("来源、许可与完整性")
        assertFullText(second.name)
        assertFullText(second.description)
        assertFullText(second.languages)
    }

    /** 下载进度和字节说明出现时，取消与启用按钮及详情仍保持相同基线。 */
    @Test fun downloadProgressKeepsActionsAligned() {
        val downloading = model("download", "正在下载的模型", "本机识别", "日语", "74 MiB").copy(
            status = ModelUiStatus.DOWNLOADING, progress = 0.42f,
            downloadedLabel = "已下载 31 MiB / 74 MiB，等待剩余文件完成", canDownload = false, canCancel = true,
        )
        val ready = model("ready", "已就绪的模型", "多语言识别", "中英日韩粤", "229 MiB").copy(
            status = ModelUiStatus.READY, canDownload = false, canActivate = true,
        )
        compose.setContent {
            SimulSubTheme(SettingsUiState()) {
                Box(Modifier.requiredWidth(1040.dp).fillMaxHeight()) {
                    ModelsScreen(ModelsUiState(items = listOf(downloading, ready)), SimulSubActions(), wide = true)
                }
            }
        }
        compose.onNodeWithText("已下载 42%").assertExists()
        compose.onNodeWithText(downloading.downloadedLabel).assertExists()
        assertAligned("取消下载", "启用模型")
        assertRepeatedAligned("来源、许可与完整性")
        compose.onNodeWithText("取消下载").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("启用模型").assertHeightIsAtLeast(48.dp)
    }

    /** 两个正式模型的短介绍与基本属性逐段对齐，耗时紧贴设备说明且没有术语长文。 */
    @Test fun builtInModelsShowAlignedConciseComparison() {
        val reazon = model("reazonspeech", "ReazonSpeech", "旧介绍", "日语、英语", "74.1 MiB")
        val sense = model("sensevoice", "SenseVoice Small", "旧介绍", "中文、英语、日语、韩语、粤语", "229.1 MiB")
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                SimulSubTheme(SettingsUiState()) {
                    Box(Modifier.requiredWidth(1040.dp).fillMaxHeight()) {
                        ModelsScreen(ModelsUiState(items = listOf(reazon, sense)), SimulSubActions(), wide = true)
                    }
                }
            }
        }
        assertAligned("电视节目语音训练的小模型，体积小、识别快，适合日语；英语表现较弱。",
            "支持中英日韩粤五种语言，日语和英语识别效果较好，体积较大。")
        listOf("识别语言", "模型大小", "日语识别错误率", "英语识别错误率", "参考识别耗时（日/英）", "参考设备：Mac M4 Pro").forEach {
            assertRepeatedAligned(it)
        }
        assertAligned("8.48%", "7.52%")
        assertAligned("22.66%", "8.33%")
        assertAligned("93 / 71 ms", "203 / 157 ms")
        assertRepeatedAligned("下载模型")
        compose.onNodeWithText("上游历史", substring = true).assertDoesNotExist()
        compose.onNodeWithText("实时率 RTF").assertDoesNotExist()
        compose.onNodeWithText("并非安卓实测", substring = true).assertDoesNotExist()
    }

    /** 手机两倍字号下真实属性名称和值完整换行，仍可滚动到下载与详情入口。 */
    @Test fun builtInComparisonRemainsCompleteAtDoubleFontSize() {
        var requested: String? = null
        val item = model("sensevoice", "SenseVoice Small", "旧介绍", "中文、英语、日语、韩语、粤语", "229.1 MiB")
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                SimulSubTheme(SettingsUiState()) {
                    Box(Modifier.requiredWidth(320.dp).fillMaxHeight()) {
                        ModelsScreen(ModelsUiState(items = listOf(item)),
                            SimulSubActions(onDownloadModel = { requested = it }), wide = false)
                    }
                }
            }
        }
        listOf("日语识别错误率", "英语识别错误率", "参考识别耗时（日/英）", "203 / 157 ms", "参考设备：Mac M4 Pro").forEach {
            compose.onNodeWithText(it).performScrollTo()
            assertFullText(it)
        }
        compose.onNodeWithText("下载模型").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("sensevoice", requested) }
        compose.onNodeWithText("来源、许可与完整性").performScrollTo().assertIsDisplayed()
    }

    /** 窄屏即使收到宽屏标记也退回单列，大字号长文无省略且操作可滚动到达。 */
    @Test fun narrowLargeTextRemainsCompleteAndActionable() {
        var requested: String? = null
        val item = model(
            "large-text", "需要完整显示的多语言离线语音识别模型名称",
            "这是一段较长的模型说明，用于验证手机和分屏使用大字号时，文字可以完整换行，不会挤压后面的属性与操作。",
            "中文、英语、日语、韩语、粤语", "229 MiB",
        )
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 1.8f)) {
                SimulSubTheme(SettingsUiState()) {
                    Box(Modifier.requiredWidth(320.dp).fillMaxHeight()) {
                        ModelsScreen(
                            ModelsUiState(items = listOf(item)),
                            SimulSubActions(onDownloadModel = { requested = it }), wide = true,
                        )
                    }
                }
            }
        }
        assertFullText(item.name)
        assertFullText(item.description)
        compose.onNodeWithText("下载模型").performScrollTo().assertHeightIsAtLeast(48.dp)
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle { assertEquals(item.id, requested) }
    }

    /** 构造纯展示模型，所有测试都不会连接真实模型目录或持久化。 */
    private fun model(id: String, name: String, description: String, languages: String, size: String) = ModelUiState(
        id, name, description, languages, size, "测试许可", "测试来源", canDownload = true,
    )

    /** 使用未裁剪坐标比较实际排版，避免宿主设备视口影响宽屏测量断言。 */
    private fun assertAligned(first: String, second: String) {
        val firstTop = compose.onNodeWithText(first).getUnclippedBoundsInRoot().top.value
        val secondTop = compose.onNodeWithText(second).getUnclippedBoundsInRoot().top.value
        assertEquals("对应内容段应对齐：$first / $second", firstTop, secondTop, 0.5f)
    }

    /** 同一行重复的状态或操作按其排版位置比较，不依赖截图像素。 */
    private fun assertRepeatedAligned(text: String) {
        val nodes = compose.onAllNodesWithText(text)
        nodes.assertCountEquals(2)
        assertEquals("两卡内容应对齐：$text", nodes[0].getUnclippedBoundsInRoot().top.value, nodes[1].getUnclippedBoundsInRoot().top.value, 0.5f)
    }

    /** 检查文字真实测量结果，不能仅凭语义树含有原文就判定无截断。 */
    private fun assertFullText(text: String) {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        assertTrue("应取得文字排版结果", results.isNotEmpty())
        results.forEach { assertFalse("文字不应被截断：$text；宽溢出=${it.didOverflowWidth}；高溢出=${it.didOverflowHeight}；尺寸=${it.size}；段高=${it.multiParagraph.height}；约束=${it.layoutInput.constraints}；行数=${it.lineCount}", it.hasVisualOverflow) }
    }
}
