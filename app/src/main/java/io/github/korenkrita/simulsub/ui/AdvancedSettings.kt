package io.github.korenkrita.simulsub.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import io.github.korenkrita.simulsub.JevCustom
import io.github.korenkrita.simulsub.JevProtocol
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
    var editingCustom by remember { mutableStateOf(false) }
    if (editingCustom) JevCustomDialog(state.jevCustom, { editingCustom = false }) { actions.onSaveJevCustom(it); editingCustom = false }
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
                if (state.jevProviderId == JevProvider.CUSTOM.name) {
                    OutlinedButton(onClick = { editingCustom = true }, enabled = !sessionBusy && !state.jevTesting,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("jev-custom-config")) {
                        Text(if (state.jevCustom == null) "配置自定义服务" else "编辑连接 · ${state.jevCustom.model}")
                    }
                    if (state.jevEndpoint.isNotEmpty()) SupportingText(state.jevEndpoint)
                }
                if (state.jevNeedsKey) {
                    val provider = JevProvider.entries.firstOrNull { it.name == state.jevProviderId }
                    if (provider != null && state.jevEndpoint.isNotEmpty()) {
                        // 空 ID 由控制器解析为该供应商平台的默认密钥，选择后立即持久化。
                        ServiceCredentialPicker(
                            io.github.korenkrita.simulsub.PlatformCredentials.forUrl(state.jevEndpoint)?.id.orEmpty(),
                            "jev:${provider.name}", state.jevCredentialOptions, state.jevCredentialId,
                            state.jevCredentialLabel, !sessionBusy && !state.jevTesting, actions)
                    }
                    if (state.jevProviderId == JevProvider.CUSTOM.name) SupportingText("密钥可选；需要鉴权时选择已保存的平台密钥。")
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

/** 自定义决策连接使用独立弹窗，确认后整体生效，输入过程不影响运行服务。 */
@Composable
private fun JevCustomDialog(saved: JevCustom?, onDismiss: () -> Unit, onSave: (JevCustom) -> Unit) {
    var protocol by remember { mutableStateOf(saved?.protocol ?: JevProtocol.SYSTEMONE) }
    var url by remember { mutableStateOf(saved?.url.orEmpty()) }
    var model by remember { mutableStateOf(saved?.model.orEmpty()) }
    var confirmingDiscard by remember { mutableStateOf(false) }
    val discard = {
        if (protocol != (saved?.protocol ?: JevProtocol.SYSTEMONE) || url != saved?.url.orEmpty() || model != saved?.model.orEmpty()) confirmingDiscard = true
        else onDismiss()
    }
    if (confirmingDiscard) AlertDialog(onDismissRequest = { confirmingDiscard = false },
        title = { Text("配置尚未保存") }, text = { Text("要放弃本次修改吗？") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("放弃修改") } },
        dismissButton = { TextButton(onClick = { confirmingDiscard = false }) { Text("继续编辑") } })
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val config = JevCustom(protocol, url.trim(), model.trim())
    val error = config.endpoint().first
    AlertDialog(onDismissRequest = discard, title = { Text("自定义 Jev 服务") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OptionPicker("决策协议", protocol.name, JevProtocol.entries.map { UiOption(it.name, it.label) }) {
                protocol = JevProtocol.valueOf(it)
            }
            OutlinedTextField(url, { url = it }, label = { Text("服务地址") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth().testTag("jev-custom-url"))
            OutlinedTextField(model, { model = it }, label = { Text("模型 ID") }, singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focus.clearFocus(); keyboard?.hide() }),
                modifier = Modifier.fillMaxWidth().testTag("jev-custom-model"))
            SupportingText("填写兼容所选协议的服务；密钥在保存后选择。")
            if (url.isNotEmpty() && error != null) Text(error, color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = {
        Button(onClick = { onSave(config) }, enabled = error == null && model.isNotBlank(), modifier = Modifier.testTag("jev-custom-save")) { Text("保存连接") }
    }, dismissButton = { TextButton(onClick = discard) { Text("取消") } })
}
