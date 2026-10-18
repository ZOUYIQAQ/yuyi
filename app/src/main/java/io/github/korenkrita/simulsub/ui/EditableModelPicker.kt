package io.github.korenkrita.simulsub.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** 模型可直接输入或从预设选择；只在内存记录菜单状态，草稿完全由调用方持有。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditableModelPicker(
    value: String,
    options: List<UiOption>,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onValueChange: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val canExpand = enabled && options.isNotEmpty()
    LaunchedEffect(canExpand) { if (!canExpand) expanded = false }
    BackHandler(enabled = expanded && canExpand) { expanded = false }
    ExposedDropdownMenuBox(
        expanded = expanded && canExpand,
        onExpandedChange = { expanded = it && canExpand },
        modifier = modifier.fillMaxWidth(),
    ) {
        TextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text("模型 ID") },
            supportingText = { Text("可选择预设，也可直接输入模型 ID。") },
            singleLine = true,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable, enabled = enabled),
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(
                    expanded = expanded && canExpand,
                    modifier = Modifier.testTag("model-picker-toggle")
                        .menuAnchor(ExposedDropdownMenuAnchorType.SecondaryEditable, enabled = canExpand),
                )
            },
        )
        // 菜单默认匹配输入框宽度，限定高度后仍可滚动浏览完整预设。
        ExposedDropdownMenu(
            expanded = expanded && canExpand,
            onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = 320.dp),
            matchAnchorWidth = true,
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = MaterialTheme.shapes.medium,
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    trailingIcon = {
                        if (option.id == value) Icon(Icons.Outlined.Check, contentDescription = null)
                    },
                    enabled = enabled,
                    onClick = {
                        expanded = false
                        onValueChange(option.id)
                    },
                    modifier = Modifier.semantics { selected = option.id == value },
                )
            }
        }
    }
}
