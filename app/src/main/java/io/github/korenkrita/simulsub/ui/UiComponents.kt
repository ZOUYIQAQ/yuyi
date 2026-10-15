package io.github.korenkrita.simulsub.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.unit.dp

/** 内容居中限宽；窄屏连续阅读，宽屏两栏各自滚动且保留一致的起始边距。 */
@Composable
internal fun PageColumns(
    wide: Boolean,
    first: LazyListScope.() -> Unit,
    second: LazyListScope.() -> Unit = {},
    header: (@Composable () -> Unit)? = null,
) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val twoColumns = wide && maxWidth >= 720.dp
        val pagePadding = if (twoColumns) 24.dp else 16.dp
        Column(Modifier.widthIn(max = if (twoColumns) 1120.dp else 640.dp).fillMaxSize()) {
            if (header != null && twoColumns) {
                Box(Modifier.fillMaxWidth().padding(horizontal = pagePadding, vertical = 12.dp)) { header() }
            }
            if (twoColumns) {
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    LazyColumn(
                        Modifier.weight(1f).fillMaxHeight(),
                        contentPadding = PaddingValues(start = pagePadding, end = 12.dp, top = 16.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp), content = first,
                    )
                    LazyColumn(
                        Modifier.weight(1f).fillMaxHeight(),
                        contentPadding = PaddingValues(start = 12.dp, end = pagePadding, top = 16.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp), content = second,
                    )
                }
            } else {
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(pagePadding),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    if (header != null) item("page-navigation") { header() }
                    first()
                    second()
                }
            }
        }
    }
}

/** 以紧凑标题与完整辅助文案建立分区层级，并保留读屏标题语义。 */
@Composable
internal fun SectionHeading(title: String, description: String = "") {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        if (description.isNotEmpty()) SupportingText(description)
    }
}

/** 辅助文字保留完整内容，允许系统字号与多行换行。 */
@Composable
internal fun SupportingText(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** 操作结果以真实文本显示，错误使用即时播报语义并允许选取原文。 */
@Composable
internal fun MessagePanel(message: UiMessage, dismiss: (() -> Unit)? = null) {
    Surface(
        color = if (message.isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (message.isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = if (message.isError) LiveRegionMode.Assertive else LiveRegionMode.Polite },
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SelectionContainer { Text(message.text, style = MaterialTheme.typography.bodyMedium) }
            if (dismiss != null) TextButton(onClick = dismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("关闭提示") }
        }
    }
}

/** 整行都是开关触控区，读屏只获得一个带状态的控件。 */
@Composable
internal fun ToggleSetting(title: String, detail: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (detail.isNotEmpty()) SupportingText(detail)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** 选项通过对话框滚动展示，长标签与大字号不受单行下拉框约束。 */
@Composable
internal fun OptionPicker(title: String, selectedId: String, options: List<UiOption>, enabled: Boolean = true, onSelect: (String) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val selected = options.firstOrNull { it.id == selectedId }
    Surface(
        onClick = { expanded = true }, enabled = enabled && options.isNotEmpty(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).semantics { stateDescription = selected?.label ?: "暂无选项" },
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(selected?.label ?: selectedId.ifEmpty { "暂无选项" }, style = MaterialTheme.typography.bodyLarge)
            }
            Icon(Icons.Outlined.ExpandMore, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (expanded && enabled) {
        AlertDialog(
            onDismissRequest = { expanded = false }, title = { Text(title) },
            text = {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 440.dp)) {
                    items(options, key = { it.id }) { option ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 56.dp)
                                .clickable(role = Role.RadioButton) { expanded = false; onSelect(option.id) }
                                .semantics { this.selected = option.id == selectedId }
                                .padding(vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = option.id == selectedId, onClick = null)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(option.label, style = MaterialTheme.typography.bodyLarge)
                                if (option.description.isNotEmpty()) SupportingText(option.description)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { expanded = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") } },
        )
    }
}

/** 数值滑块含可读标签与当前值，范围由合同或旧字幕能力给出。 */
@Composable
internal fun ValueSlider(title: String, value: Float, range: ClosedFloatingPointRange<Float>, label: String, enabled: Boolean = true, steps: Int = 0, onChange: (Float) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("$title · $label", style = MaterialTheme.typography.titleSmall)
        Slider(
            value = value.coerceIn(range), onValueChange = onChange, valueRange = range, steps = steps, enabled = enabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = title; stateDescription = label },
        )
    }
}

/** 可换行按钮组用于窄屏、分屏与大字号，不压缩触控范围。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ActionRow(content: @Composable () -> Unit) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
}

/** 保留已存密钥与明确清空分开表达，输入值只交给控制器草稿。 */
@Composable
internal fun KeyEditor(value: String?, saved: Boolean, enabled: Boolean, onChange: (String?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = value.orEmpty(), onValueChange = { onChange(it) }, enabled = enabled,
            label = { Text("API 密钥") },
            supportingText = { Text(if (value == null && saved) "已保存密钥；不输入则保留。" else if (value == "" && saved) "保存后将清空密钥。" else "如服务需要密钥，在这里填写；使用 Android Keystore 加密保存在本机。") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        ActionRow {
            if (saved && value != null) TextButton(onClick = { onChange(null) }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("保留已存密钥") }
            if (saved || !value.isNullOrEmpty()) TextButton(onClick = { onChange("") }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("清空密钥") }
        }
    }
}

/** 破坏性场景操作再次列出对象和影响，再派发控制器动作。 */
@Composable
internal fun ConfirmSceneAction(title: String, detail: String, confirmLabel: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(detail) },
        confirmButton = { TextButton(onClick = onConfirm, modifier = Modifier.heightIn(min = 48.dp)) { Text(confirmLabel, color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") } },
    )
}
