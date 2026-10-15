package io.github.korenkrita.simulsub.ui

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.roundToInt

/** 官方对话框只维护本次草稿，返回、点击外部及取消都不触发保存。 */
@Composable
internal fun ThemeColorPickerDialog(
    initialSeed: Int,
    dark: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val initialHsv = remember(initialSeed) { FloatArray(3).also { AndroidColor.colorToHSV(initialSeed, it) } }
    var hue by rememberSaveable(initialSeed) { mutableFloatStateOf(initialHsv[0]) }
    var saturation by rememberSaveable(initialSeed) { mutableFloatStateOf(initialHsv[1]) }
    var brightness by rememberSaveable(initialSeed) { mutableFloatStateOf(initialHsv[2]) }
    var hex by rememberSaveable(initialSeed) { mutableStateOf(themeColorHex(initialSeed)) }
    var previewSeed by rememberSaveable(initialSeed) { mutableIntStateOf(initialSeed or 0xFF000000.toInt()) }
    val parsed = parseThemeColor(hex)
    val previewScheme = remember(previewSeed, dark) { ThemePalettes.scheme("custom", dark, previewSeed) }

    /** 滑杆保留独立的色相值，灰色及黑色仍可继续调整色相和饱和度。 */
    fun updateHsv(h: Float = hue, s: Float = saturation, v: Float = brightness) {
        hue = h
        saturation = s
        brightness = v
        previewSeed = AndroidColor.HSVToColor(floatArrayOf(h, s, v))
        hex = themeColorHex(previewSeed)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("自定义主题颜色") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("输入颜色值或调整滑杆，保存后应用到界面。", style = MaterialTheme.typography.bodyMedium)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Surface(
                        Modifier.size(48.dp).semantics { contentDescription = "颜色预览 ${themeColorHex(previewSeed)}" },
                        shape = MaterialTheme.shapes.medium,
                        color = Color(previewSeed),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    ) {}
                    Column {
                        Text("所选颜色", style = MaterialTheme.typography.labelMedium)
                        Text(themeColorHex(previewSeed), style = MaterialTheme.typography.titleMedium)
                    }
                }
                OutlinedTextField(
                    value = hex,
                    onValueChange = { value ->
                        hex = value
                        parseThemeColor(value)?.let { seed ->
                            previewSeed = seed
                            val hsv = FloatArray(3).also { AndroidColor.colorToHSV(seed, it) }
                            hue = hsv[0]
                            saturation = hsv[1]
                            brightness = hsv[2]
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("颜色值") },
                    placeholder = { Text("#RRGGBB") },
                    singleLine = true,
                    isError = parsed == null,
                    supportingText = { Text(if (parsed == null) "请输入 6 位十六进制颜色，例如 #D38569" else "支持 #RRGGBB，不含透明度。") },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Ascii,
                        imeAction = ImeAction.Done,
                    ),
                )
                ThemeColorSlider(
                    "色相", "${hue.roundToInt()}°", hue, 0f..360f,
                    (0..6).map { Color(AndroidColor.HSVToColor(floatArrayOf(it * 60f, 1f, 1f))) },
                ) { updateHsv(h = it) }
                ThemeColorSlider(
                    "饱和度", "${(saturation * 100).roundToInt()}%", saturation, 0f..1f,
                    listOf(Color(AndroidColor.HSVToColor(floatArrayOf(hue, 0f, brightness))),
                        Color(AndroidColor.HSVToColor(floatArrayOf(hue, 1f, brightness)))),
                ) { updateHsv(s = it) }
                ThemeColorSlider(
                    "明度", "${(brightness * 100).roundToInt()}%", brightness, 0f..1f,
                    listOf(Color.Black, Color(AndroidColor.HSVToColor(floatArrayOf(hue, saturation, 1f)))),
                ) { updateHsv(v = it) }
                Text("主题效果", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(Modifier.weight(1f), shape = MaterialTheme.shapes.medium,
                        color = previewScheme.primary, contentColor = previewScheme.onPrimary) {
                        Text("主题主色", Modifier.padding(12.dp), style = MaterialTheme.typography.labelLarge)
                    }
                    Surface(Modifier.weight(1f), shape = MaterialTheme.shapes.medium,
                        color = previewScheme.primaryContainer, contentColor = previewScheme.onPrimaryContainer) {
                        Text("容器与文字", Modifier.padding(12.dp), style = MaterialTheme.typography.labelLarge)
                    }
                }
                Text("界面会根据所选颜色生成适合阅读的浅色与深色配色。", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = { parsed?.let(onConfirm) }, enabled = parsed != null) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 官方滑杆提供键盘及无障碍调节，底部渐变显示对应分量的真实变化范围。 */
@Composable
private fun ThemeColorSlider(
    label: String,
    valueLabel: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    colors: List<Color>,
    onValueChange: (Float) -> Unit,
) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(valueLabel, style = MaterialTheme.typography.labelLarge)
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp).height(16.dp)
                .clip(MaterialTheme.shapes.small).background(Brush.horizontalGradient(colors)))
            Slider(
                value = value,
                onValueChange = onValueChange,
                valueRange = valueRange,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                    contentDescription = label
                    stateDescription = valueLabel
                },
                colors = SliderDefaults.colors(activeTrackColor = Color.Transparent, inactiveTrackColor = Color.Transparent),
            )
        }
    }
}

/** 显示固定六位 RGB，不将透明度带入用户输入或种子预览。 */
internal fun themeColorHex(seed: Int): String = String.format(Locale.ROOT, "#%06X", seed and 0xFFFFFF)

/** 只接受完整六位颜色，拒绝透明色、短写及无效字符，避免悄悄保存错误值。 */
private fun parseThemeColor(value: String): Int? {
    val rgb = value.trim().removePrefix("#")
    if (rgb.length != 6 || rgb.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) return null
    return rgb.toIntOrNull(16)?.or(0xFF000000.toInt())
}
