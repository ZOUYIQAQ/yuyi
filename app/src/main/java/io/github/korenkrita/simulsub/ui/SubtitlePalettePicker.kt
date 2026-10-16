package io.github.korenkrita.simulsub.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 复用共享菜单的滚动、返回与勾选，只在明确选择时交回新的配色标识。 */
@Composable
internal fun SubtitlePalettePicker(style: SubtitleStyleUiState, onStyleChange: (SubtitleStyleUiState) -> Unit) {
    OptionPicker(
        "字幕配色", style.paletteId, style.palettes.map { UiOption(it.id, it.label) },
        optionContent = { option ->
            SubtitlePalettePreview(style.palettes.first { it.id == option.id }, style.backgroundAlpha)
        },
    ) { id -> onStyleChange(style.copy(paletteId = id)) }
}

/** 与生产字幕一致：用户不透明度替换背景原有透明通道，并沿用截断到整数的规则。 */
internal fun captionPalettePreviewBackground(palette: CaptionPaletteUiState, alpha: Float): Int =
    (palette.backgroundArgb and 0x00FFFFFF) or ((alpha * 255).toInt().coerceIn(0, 255) shl 24)

/** 名称在色卡外保持可读，卡内同时呈现真实原文色与译文色，所有文字自然换行。 */
@Composable
internal fun SubtitlePalettePreview(palette: CaptionPaletteUiState, backgroundAlpha: Float) {
    val background = Color(captionPalettePreviewBackground(palette, backgroundAlpha))
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(palette.label, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyLarge)
        Column(
            Modifier.fillMaxWidth().testTag("caption-palette-preview-${palette.id}")
                .clip(RoundedCornerShape(8.dp))
                .drawBehind {
                    // 固定棋盘格仅表示字幕背后的画面，真实背景按生产透明度叠加，避免主题色混入色值。
                    val tile = 12.dp.toPx()
                    for (row in 0..(size.height / tile).toInt()) {
                        for (column in 0..(size.width / tile).toInt()) {
                            drawRect(
                                if ((row + column) % 2 == 0) Color(0xFF404040) else Color(0xFF606060),
                                topLeft = Offset(column * tile, row * tile), size = Size(tile, tile),
                            )
                        }
                    }
                    drawRect(background)
                }.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "花が咲くでしょう～", color = Color(palette.originalArgb), fontSize = 13.sp,
                modifier = Modifier.fillMaxWidth().testTag("caption-palette-original-${palette.id}"),
            )
            Text(
                "花朵绽放出来吧", color = Color(palette.foregroundArgb), fontSize = 18.sp,
                modifier = Modifier.fillMaxWidth().testTag("caption-palette-translated-${palette.id}"),
            )
        }
    }
}
