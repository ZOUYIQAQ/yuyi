package io.github.korenkrita.simulsub.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import android.os.Build
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 分区导航属于展示状态，所有设置草稿和保存结果继续由控制器提供。 */
private enum class SettingsSection(val title: String) {
    API("翻译 API"), SUBTITLE("字幕样式"), APPEARANCE("外观"), ADVANCED("高级设置"), RECORDS("使用记录"), ABOUT("关于")
}

/** 设置使用可横向滚动的分区标签，宽屏把说明与操作区分开。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(state: SettingsUiState, sessionBusy: Boolean, actions: SimulSubActions, wide: Boolean) {
    var selectedName by rememberSaveable { mutableStateOf(SettingsSection.API.name) }
    val selected = SettingsSection.valueOf(selectedName)
    val navigation: @Composable () -> Unit = {
        SecondaryScrollableTabRow(
            selectedTabIndex = selected.ordinal, edgePadding = 0.dp,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            SettingsSection.entries.forEach { section ->
                Tab(selected = selected == section, onClick = { selectedName = section.name },
                    text = { Text(section.title) }, modifier = Modifier.heightIn(min = 48.dp))
            }
        }
    }
    key(selected) {
        when (selected) {
            SettingsSection.API -> TranslationApiSettings(state.api, sessionBusy, actions, wide, navigation)
            SettingsSection.SUBTITLE -> SubtitleSettings(state.subtitle, actions, wide, navigation)
            SettingsSection.APPEARANCE -> AppearanceSettings(state, actions, wide, navigation)
            SettingsSection.ADVANCED -> AdvancedSettings(state.advanced, sessionBusy, actions, wide, navigation)
            SettingsSection.RECORDS -> UsageRecords(state, actions, wide, navigation)
            SettingsSection.ABOUT -> AboutSettings(state, actions, wide, navigation)
        }
    }
}

/** 先选服务再编辑连接信息，附加请求参数默认收起，保存与测试始终明确。 */
@Composable
private fun TranslationApiSettings(api: TranslationApiUiState, sessionBusy: Boolean, actions: SimulSubActions, wide: Boolean, navigation: @Composable () -> Unit) {
    val editable = !api.testing && !api.saving
    var advancedOpen by rememberSaveable { mutableStateOf(api.headers.isNotEmpty() || api.userAgent.isNotEmpty()) }
    PageColumns(wide, header = navigation, first = {
        item("api-intro") {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SectionHeading("连接翻译服务", "声音在本机识别，识别出的文字交给你选择的服务翻译。")
                OptionPicker("服务预设", api.presetId,
                    listOf(UiOption("custom", "自定义 / 手动填写", "保留当前表单，所有字段自行填写。")) + TranslationPresets.entries.map { UiOption(it.id, it.title, it.detail) },
                    enabled = editable, onSelect = actions.onApplyApiPreset)
                SupportingText("预设只帮助填写。地址和模型都可继续修改，保存后才生效。")
                if (sessionBusy) SupportingText("同传运行期间可保存配置，从后续句子使用；测试连接需先停止同传。")
                api.message?.let { MessagePanel(it) }
            }
        }
    }, second = {
        item("api-form") {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("连接信息", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(value = api.url, onValueChange = { actions.onApiDraftChange(api.copy(url = it, presetId = TranslationPresets.match(it))) }, label = { Text("服务地址") }, supportingText = { Text("HTTPS 基础地址或完整聊天接口地址，均可编辑。") }, enabled = editable, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = api.model, onValueChange = { actions.onApiDraftChange(api.copy(model = it)) }, label = { Text("模型 ID") }, enabled = editable, modifier = Modifier.fillMaxWidth())
                    KeyEditor(api.key, api.hasSavedKey, editable) { actions.onApiDraftChange(api.copy(key = it)) }
                    if (api.testing || api.saving) LinearProgressIndicator(Modifier.fillMaxWidth())
                    ActionRow {
                        Button(onClick = actions.onSaveApi, enabled = editable, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (api.saving) "正在保存…" else "保存翻译配置") }
                        OutlinedButton(onClick = actions.onTestApi, enabled = editable && api.canTest && !sessionBusy, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (api.testing) "正在测试…" else "测试连接") }
                    }
                    SupportingText("测试会先保存并发送少量请求，服务商可能计费。")
                }
            }
        }
        item("api-advanced-toggle") {
            TextButton(onClick = { advancedOpen = !advancedOpen }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(if (advancedOpen) "收起附加请求参数" else "附加请求参数（可选）")
            }
        }
        if (advancedOpen) {
            item("api-advanced") {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    SectionHeading("附加请求参数", "一般无需填写。修改后需再次保存翻译配置。")
                    if (api.resolvedUrl.isNotEmpty()) SupportingText("实际请求地址：${api.resolvedUrl}")
                    OutlinedTextField(value = api.userAgent, onValueChange = { actions.onApiDraftChange(api.copy(userAgent = it)) }, label = { Text("User-Agent（可选）") }, enabled = editable, modifier = Modifier.fillMaxWidth())
                }
            }
            items(api.headers, key = { "header:${it.id}" }) { header ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = header.name, onValueChange = { value -> actions.onApiDraftChange(api.copy(headers = api.headers.map { if (it.id == header.id) it.copy(name = value) else it })) }, label = { Text("请求头名称") }, enabled = editable, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = header.value, onValueChange = { value -> actions.onApiDraftChange(api.copy(headers = api.headers.map { if (it.id == header.id) it.copy(value = value) else it })) }, label = { Text("请求头值") }, enabled = editable, modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = { actions.onRemoveHeader(header.id) }, enabled = editable, modifier = Modifier.heightIn(min = 48.dp)) { Text("移除此请求头") }
                }
            }
            item("api-add-header") {
                OutlinedButton(onClick = actions.onAddHeader, enabled = editable, modifier = Modifier.heightIn(min = 48.dp)) { Text("添加请求头") }
            }
        }
    })
}

/** 应用主题与字幕主题分开设置，动态色在旧系统由主题层安全回退。 */
@Composable
private fun AppearanceSettings(state: SettingsUiState, actions: SimulSubActions, wide: Boolean, navigation: @Composable () -> Unit) {
    PageColumns(wide, header = navigation, first = { item { SectionHeading("界面外观", "选择浅色、深色或跟随系统。悬浮字幕的颜色在「字幕样式」中设置。") } }, second = {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OptionPicker("应用主题", state.theme.name, UiThemeMode.entries.map { UiOption(it.name, it.title) }) { name -> actions.onThemeChange(UiThemeMode.valueOf(name)) }
                SectionHeading("主题配色", "选择喜欢的配色，浅色和深色会自动采用对应色调。")
                ThemePalettePicker(state, actions)
                ToggleSetting("动态取色", if (Build.VERSION.SDK_INT >= 31) "使用系统壁纸生成配色，选择手动主题色可退出动态取色。" else "需要 Android 12 或更高版本；当前系统使用所选手动配色。", state.dynamicColors,
                    enabled = Build.VERSION.SDK_INT >= 31, onChange = actions.onDynamicColorsChange)
            }
        }
    })
}

/** 色块只作预览，完整中文标签与选中状态确保颜色不是唯一识别线索。 */
@Composable
private fun ThemePalettePicker(state: SettingsUiState, actions: SimulSubActions) {
    ActionRow {
        ThemePalettes.entries.forEach { palette ->
            FilterChip(
                selected = !state.dynamicColors && state.themeColorId == palette.id,
                onClick = { actions.onThemeColorChange(palette.id) },
                label = { Text(palette.title) },
                leadingIcon = { Box(Modifier.size(20.dp).background(Color(palette.seed), CircleShape)) },
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }
    }
}

/** 展示真实使用记录，删除必须先确认，正在写入的记录不允许分享或删除。 */
@Composable
private fun UsageRecords(state: SettingsUiState, actions: SimulSubActions, wide: Boolean, navigation: @Composable () -> Unit) {
    var pendingId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingTitle by rememberSaveable { mutableStateOf("") }
    var clearAll by rememberSaveable { mutableStateOf(false) }
    PageColumns(wide, header = navigation, first = {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionHeading("使用记录", "记录保存在本机，可通过系统分享给其他应用，也可删除不再需要的记录。")
                ToggleSetting("保存会话记录", "开启后记录后续会话的原文、译文与诊断信息；分享前请检查内容。", state.keepLogs, onChange = actions.onKeepLogsChange)
                OutlinedButton(onClick = actions.onRefreshRecords, enabled = !state.recordsLoading, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (state.recordsLoading) "正在读取…" else "刷新记录") }
                TextButton(onClick = { clearAll = true }, enabled = !state.recordsLoading && state.records.any { !it.active }, modifier = Modifier.heightIn(min = 48.dp)) { Text("清空使用记录") }
                if (state.recordsLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.recordsMessage?.let { MessagePanel(it) }
            }
        }
    }, second = {
        if (state.records.isEmpty()) item { SupportingText(if (state.recordsLoading) "正在读取本地记录。" else "暂无记录。开启保存并完成一次会话后，可在这里分享。") }
        items(state.records, key = { it.id }) { record ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(record.title, style = MaterialTheme.typography.titleMedium)
                SupportingText(record.timeLabel)
                SupportingText(record.detail)
                ActionRow {
                    OutlinedButton(onClick = { actions.onShareRecord(record.id) }, enabled = !record.sharing && !record.active, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (record.sharing) "正在准备分享…" else "分享记录") }
                    TextButton(onClick = { pendingId = record.id; pendingTitle = record.title }, enabled = !record.active && !record.sharing && !state.recordsLoading, modifier = Modifier.heightIn(min = 48.dp)) { Text("删除记录") }
                }
                HorizontalDivider()
            }
        }
    })
    if (pendingId != null || clearAll) {
        val canDelete = !state.recordsLoading && if (clearAll) state.records.any { !it.active }
            else state.records.any { it.id == pendingId && !it.active }
        AlertDialog(
            onDismissRequest = { pendingId = null; clearAll = false },
            title = { Text(if (clearAll) "清空使用记录？" else "删除这条记录？") },
            text = {
                Text(if (clearAll) "将删除本机所有已结束的会话记录，删除后无法恢复。正在写入的记录会保留，不会中断当前同传。"
                    else "将删除「$pendingTitle」的本机会话记录（$pendingId），删除后无法恢复。正在写入的记录会保留。")
            },
            confirmButton = {
                TextButton(onClick = {
                    if (clearAll) actions.onDeleteAllRecords() else pendingId?.let(actions.onDeleteRecord)
                    pendingId = null; clearAll = false
                }, enabled = canDelete) { Text(if (clearAll) "确认清空" else "确认删除") }
            },
            dismissButton = { TextButton(onClick = { pendingId = null; clearAll = false }) { Text("取消") } },
        )
    }
}

/** 关于页面保留版本、仓库与更新入口，检查结果不由界面伪造。 */
@Composable
private fun AboutSettings(state: SettingsUiState, actions: SimulSubActions, wide: Boolean, navigation: @Composable () -> Unit) {
    PageColumns(wide, header = navigation, first = {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionHeading("同传字幕", "在设备上识别正在播放的声音，使用自己的翻译服务显示字幕。")
                Text(if (state.version.isEmpty()) "版本信息待加载" else "版本 ${state.version}", style = MaterialTheme.typography.titleMedium)
                SupportingText("识别模型单独下载，模型来源与许可可在模型库中查看。")
            }
        }
    }, second = {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionHeading("项目与更新")
                OutlinedButton(onClick = actions.onOpenRepository, modifier = Modifier.heightIn(min = 48.dp)) { Text("打开项目仓库") }
                Button(onClick = actions.onCheckUpdate, enabled = !state.checkingUpdate, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (state.checkingUpdate) "正在检查…" else "检查更新") }
                if (state.checkingUpdate) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (state.updateMessage.isNotEmpty()) MessagePanel(UiMessage(state.updateMessage))
            }
        }
    })
}
