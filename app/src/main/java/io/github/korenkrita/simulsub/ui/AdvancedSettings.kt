package io.github.korenkrita.simulsub.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import io.github.korenkrita.simulsub.ListeningMode
import io.github.korenkrita.simulsub.JevProvider

/** 上下文、接口去重与 Jev 辅助保持独立能力，运行中的生效时机明确说明。 */
@Composable
internal fun AdvancedSettings(state: AdvancedUiState, sessionBusy: Boolean, actions: SimulSubActions, wide: Boolean, navigation: (@Composable () -> Unit)?,
    mode: ListeningMode = ListeningMode.LOCAL, platformKeys: List<PlatformKeyUiState> = emptyList()) {
    val cancelProbe = rememberUpdatedState(actions.onCancelJevTest)
    DisposableEffect(Unit) { onDispose { cancelProbe.value() } }
    val locked = sessionBusy && mode == ListeningMode.CLOUD_ASR
    PageColumns(wide, header = navigation, first = {
        item("advanced-context") {
            SettingsCard {
                SectionHeading("翻译上下文", "参考刚说过的句子，帮助理解人称、指代和省略。上下文越多，请求文字越多，可能增加延迟与费用。")
                if (locked) SupportingText("在线听写会话使用启动时的翻译、上下文和高级配置，请先停止同传，再修改。")
                ToggleSetting("结合上文翻译", "把最近定稿的原文交给翻译服务参考。", state.contextOn, enabled = !locked) { actions.onAdvancedChange(state.copy(contextOn = it)) }
                ValueSlider("参考句数", state.contextSentences.toFloat(), 1f..state.maxContextSentences.coerceAtLeast(2).toFloat(), "${state.contextSentences} 句", enabled = !locked && state.contextOn, steps = (state.maxContextSentences - 2).coerceAtLeast(0)) { actions.onAdvancedChange(state.copy(contextSentences = it.roundToInt())) }
                ToggleSetting("去掉衔接处重复的字", "分段识别时移除接口处重复内容；说话人刻意重复的词也可能被去掉。", state.dedupeJoin, enabled = !locked) { actions.onAdvancedChange(state.copy(dedupeJoin = it)) }
            }
        }
    }, second = {
        item("advanced-jev") {
            SettingsCard {
                SectionHeading("Jev 在线辅助", "联网发送识别文字与译文进行辅助判断，不发送声音。第三方服务可能计费、限速或不可用。")
                ToggleSetting("智能断句", "判断一句话是否真正说完，减少将一句话切成两半。", state.jevOn, enabled = !sessionBusy) { actions.onAdvancedChange(state.copy(jevOn = it)) }
                ToggleSetting("检查译文", "检查漏译否定、意思相反等问题；必要时重新翻译，会增加字幕延迟。", state.jevCheck, enabled = !sessionBusy) { actions.onAdvancedChange(state.copy(jevCheck = it)) }
                OptionPicker("Jev 服务", state.jevProviderId, state.jevProviders, enabled = !locked) { actions.onAdvancedChange(state.copy(jevProviderId = it)) }
                if (state.jevNeedsKey) {
                    val provider = JevProvider.entries.firstOrNull { it.name == state.jevProviderId }
                    if (provider != null) {
                        // 空 ID 由控制器解析为该供应商平台的默认密钥，选择后立即持久化。
                        ServiceCredentialPicker(
                            io.github.korenkrita.simulsub.PlatformCredentials.forUrl(provider.url)?.id.orEmpty(),
                            "jev:${provider.name}", state.jevCredentialOptions, state.jevCredentialId,
                            state.jevCredentialLabel, !sessionBusy && !state.jevTesting, actions)
                    }
                } else SupportingText("当前服务不要求密钥。公共服务的可用性以实际请求结果为准。")
                if (state.jevTesting) LinearProgressIndicator(Modifier.fillMaxWidth())
                ActionRow {
                    FilledTonalButton(onClick = actions.onTestJevConnection, enabled = !sessionBusy && !state.jevTesting,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("jev-test-connection")) {
                        Text(if (state.jevTesting) "正在测试…" else "测试 Jev 连接")
                    }
                    if (state.jevTesting) TextButton(onClick = actions.onCancelJevTest,
                        modifier = Modifier.heightIn(min = 48.dp)) { Text("取消测试") }
                }
                SupportingText("使用一小段示例文字验证连接与判断结果，不采集音频；服务商可能计费。")
                state.jevMessage?.let { MessagePanel(it) }
            }
        }
    })
}
