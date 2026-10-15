package io.github.korenkrita.simulsub.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** 上下文、接口去重与 Jev 辅助保持独立能力，运行中的生效时机明确说明。 */
@Composable
internal fun AdvancedSettings(state: AdvancedUiState, sessionBusy: Boolean, actions: SimulSubActions, wide: Boolean, navigation: (@Composable () -> Unit)?) {
    PageColumns(wide, header = navigation, first = {
        item("advanced-context") {
            SettingsCard {
                SectionHeading("翻译上下文", "参考刚说过的句子，帮助理解人称、指代和省略。上下文越多，请求文字越多，可能增加延迟与费用。")
                if (sessionBusy) SupportingText("上下文、重复字处理与 Jev 服务配置从后续句子使用；请先停止同传，再调整智能断句和译文检查开关。")
                ToggleSetting("结合上文翻译", "把最近定稿的原文交给翻译服务参考。", state.contextOn) { actions.onAdvancedChange(state.copy(contextOn = it)) }
                ValueSlider("参考句数", state.contextSentences.toFloat(), 1f..state.maxContextSentences.coerceAtLeast(2).toFloat(), "${state.contextSentences} 句", enabled = state.contextOn, steps = (state.maxContextSentences - 2).coerceAtLeast(0)) { actions.onAdvancedChange(state.copy(contextSentences = it.roundToInt())) }
                ToggleSetting("去掉衔接处重复的字", "分段识别时移除接口处重复内容；说话人刻意重复的词也可能被去掉。", state.dedupeJoin) { actions.onAdvancedChange(state.copy(dedupeJoin = it)) }
            }
        }
    }, second = {
        item("advanced-jev") {
            SettingsCard {
                SectionHeading("Jev 在线辅助", "联网发送识别文字与译文进行辅助判断，不发送声音。第三方服务可能计费、限速或不可用。")
                ToggleSetting("智能断句", "判断一句话是否真正说完，减少将一句话切成两半。", state.jevOn, enabled = !sessionBusy) { actions.onAdvancedChange(state.copy(jevOn = it)) }
                ToggleSetting("检查译文", "检查漏译否定、意思相反等问题；必要时重新翻译，会增加字幕延迟。", state.jevCheck, enabled = !sessionBusy) { actions.onAdvancedChange(state.copy(jevCheck = it)) }
                OptionPicker("Jev 服务", state.jevProviderId, state.jevProviders) { actions.onAdvancedChange(state.copy(jevProviderId = it)) }
                if (state.jevNeedsKey) {
                    key(state.jevProviderId) {
                        KeyEditor(state.jevKey, state.jevHasSavedKey, enabled = true) { actions.onAdvancedChange(state.copy(jevKey = it)) }
                        Button(onClick = actions.onSaveJevKey, modifier = Modifier.heightIn(min = 48.dp)) { Text("保存 Jev 密钥") }
                    }
                } else SupportingText("当前服务不要求密钥。公共服务的可用性以实际请求结果为准。")
                state.jevMessage?.let { MessagePanel(it) }
            }
        }
    })
}
