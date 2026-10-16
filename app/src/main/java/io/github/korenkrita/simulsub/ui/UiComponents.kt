package io.github.korenkrita.simulsub.ui

import androidx.activity.compose.BackHandler

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.animation.animateContentSize
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp

/** 每种布局独立持有真实列表位置，不包含任何业务值或表单草稿。 */
internal class PageScrollStates(val single: LazyListState, val first: LazyListState, val second: LazyListState)

/** 将路由保存的位置传给实际列表；独立预览未提供时由列表自行管理。 */
internal val LocalPageScrollStates = staticCompositionLocalOf<PageScrollStates?> { null }

/** 仅把列表内置 Saver 注册到外层页面容器，禁止子控件把密钥或业务草稿纳入保存状态。 */
@Composable
internal fun PageScrollStateProvider(content: @Composable () -> Unit) {
    val single = rememberLazyListState()
    val first = rememberLazyListState()
    val second = rememberLazyListState()
    val states = remember(single, first, second) { PageScrollStates(single, first, second) }
    CompositionLocalProvider(LocalPageScrollStates provides states, LocalSaveableStateRegistry provides null, content = content)
}

/** 内容居中限宽；窄屏连续阅读，宽屏两栏各自滚动且保留一致的起始边距。 */
@Composable
internal fun PageColumns(
    wide: Boolean,
    first: LazyListScope.() -> Unit,
    second: LazyListScope.() -> Unit = {},
    header: (@Composable () -> Unit)? = null,
    bottomPadding: Dp? = null,
) {
    val states = LocalPageScrollStates.current
    val singleColumnState = states?.single ?: rememberLazyListState()
    val firstColumnState = states?.first ?: rememberLazyListState()
    val secondColumnState = states?.second ?: rememberLazyListState()
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
                        state = firstColumnState,
                        contentPadding = PaddingValues(start = pagePadding, end = 12.dp, top = 16.dp, bottom = bottomPadding ?: 24.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp), content = first,
                    )
                    LazyColumn(
                        Modifier.weight(1f).fillMaxHeight(),
                        state = secondColumnState,
                        contentPadding = PaddingValues(start = 12.dp, end = pagePadding, top = 16.dp, bottom = bottomPadding ?: 24.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp), content = second,
                    )
                }
            } else {
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(start = pagePadding,
                        end = pagePadding, top = pagePadding, bottom = bottomPadding ?: pagePadding),
                    state = singleColumnState,
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

/** 保留圆角选择入口，使用官方锚定下拉菜单；列表随可用空间滚动，选中后直接收起。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OptionPicker(title: String, selectedId: String, options: List<UiOption>, enabled: Boolean = true,
                          optionContent: (@Composable (UiOption) -> Unit)? = null, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val available = enabled && options.isNotEmpty()
    LaunchedEffect(available) { if (!available) expanded = false }
    // 非输入框锚点也应先关闭菜单，不能让系统返回穿透到页面导航。
    BackHandler(enabled = expanded && available) { expanded = false }
    val selected = options.firstOrNull { it.id == selectedId }
    ExposedDropdownMenuBox(
        expanded = expanded && available,
        onExpandedChange = { expanded = it && available },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium,
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = available)
                .fillMaxWidth().heightIn(min = 72.dp)
                .semantics {
                    stateDescription = selected?.label ?: "暂无选项"
                    if (!available) disabled()
                },
        ) {
            ListItem(
                modifier = Modifier.semantics { if (!available) disabled() },
                headlineContent = { Text(selected?.label ?: selectedId.ifEmpty { "暂无选项" }, style = MaterialTheme.typography.bodyLarge) },
                overlineContent = { Text(title, style = MaterialTheme.typography.labelMedium) },
                trailingContent = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded && available) },
                colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
            )
        }
        ExposedDropdownMenu(
            expanded = expanded && available,
            onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = 360.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = MaterialTheme.shapes.medium,
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = {
                        if (optionContent != null) optionContent(option)
                        else Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(option.label, style = MaterialTheme.typography.bodyLarge)
                            if (option.description.isNotEmpty()) Text(option.description,
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    trailingIcon = {
                        if (option.id == selectedId) Icon(Icons.Outlined.Check, null)
                        else Spacer(Modifier.size(24.dp))
                    },
                    enabled = expanded && available,
                    onClick = {
                        if (expanded && available) { expanded = false; onSelect(option.id) }
                    },
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    modifier = Modifier.heightIn(min = 48.dp).semantics { this.selected = option.id == selectedId },
                )
            }
        }
    }
}

/** 官方填充卡片统一设置分组的圆角、间距和内容尺寸变化。 */
@Composable
internal fun SettingsCard(padding: androidx.compose.ui.unit.Dp = 20.dp, spacing: androidx.compose.ui.unit.Dp = 16.dp, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth().animateContentSize(), shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(padding), verticalArrangement = Arrangement.spacedBy(spacing), content = content)
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
    var visible by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextField(
                        shape = TextFieldDefaults.shape,
                        colors = TextFieldDefaults.colors(),
            value = value.orEmpty(), onValueChange = { onChange(it) }, enabled = enabled,
            label = { Text("API 密钥") },
            visualTransformation = if (visible) androidx.compose.ui.text.input.VisualTransformation.None
                else androidx.compose.ui.text.input.PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { visible = !visible }, enabled = enabled) {
                    Icon(if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription = if (visible) "隐藏密钥" else "显示密钥")
                }
            },
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
