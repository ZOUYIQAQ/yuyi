package io.github.korenkrita.simulsub.ui

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DragIndicator
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ScreenRotation
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material.icons.outlined.ZoomOut
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.korenkrita.simulsub.Caption
import io.github.korenkrita.simulsub.ByokTranslator
import io.github.korenkrita.simulsub.CaptionOverlayView
import io.github.korenkrita.simulsub.CaptionRetention
import io.github.korenkrita.simulsub.CaptionService
import io.github.korenkrita.simulsub.Timing
import kotlin.math.roundToInt

/** 页面内的预览只持有局部状态；滚动不销毁，离开字幕设置后随组合释放。 */
@Composable
internal fun FloatingSubtitlePreview(style: SubtitleStyleUiState, finalOnly: Boolean = false) {
    var collapsed by remember { mutableStateOf(false) }
    var landscape by remember { mutableStateOf(false) }
    var enlarged by remember { mutableStateOf(false) }
    var position by remember { mutableStateOf<Offset?>(null) }
    var panelSize by remember { mutableStateOf(IntSize.Zero) }
    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds()) {
        val watchingArea = subtitleWatchingArea(LocalContext.current)
        val panelWidth = minOf(360.dp, maxWidth - 16.dp).coerceAtLeast(0.dp)
        val bodyHeight = minOf(220.dp, maxHeight * 0.4f, (maxHeight - 48.dp).coerceAtLeast(0.dp))
        val maxX = (constraints.maxWidth - panelSize.width).coerceAtLeast(0).toFloat()
        val maxY = (constraints.maxHeight - panelSize.height).coerceAtLeast(0).toFloat()
        // 未拖动时跟随当前窗口选择默认角落；自定义位置只在尺寸变化后收敛到可见范围。
        val defaultPosition = Offset(if (maxWidth >= 600.dp) 0f else maxX, maxY)
        LaunchedEffect(maxX, maxY) {
            position = position?.let { Offset(it.x.coerceIn(0f, maxX), it.y.coerceIn(0f, maxY)) }
        }
        val drag = Modifier.pointerInput(maxX, maxY, defaultPosition) {
            detectDragGestures { change, delta ->
                change.consume()
                val current = position?.let { Offset(it.x.coerceIn(0f, maxX), it.y.coerceIn(0f, maxY)) } ?: defaultPosition
                position = Offset((current.x + delta.x).coerceIn(0f, maxX), (current.y + delta.y).coerceIn(0f, maxY))
            }
        }.semantics {
            contentDescription = "拖动字幕预览"
            customActions = listOf(
                CustomAccessibilityAction("移到顶部") { position = Offset((position?.x ?: defaultPosition.x).coerceIn(0f, maxX), 0f); true },
                CustomAccessibilityAction("移到底部") { position = Offset((position?.x ?: defaultPosition.x).coerceIn(0f, maxX), maxY); true },
            )
        }
        Surface(
            modifier = Modifier.offset {
                IntOffset((position?.x ?: defaultPosition.x).coerceIn(0f, maxX).roundToInt(), (position?.y ?: defaultPosition.y).coerceIn(0f, maxY).roundToInt())
            }.then(if (collapsed) Modifier.widthIn(max = panelWidth).then(drag) else Modifier.width(panelWidth))
                .onSizeChanged { panelSize = it }.testTag("subtitle-floating-preview"),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shadowElevation = 4.dp,
        ) {
            if (collapsed) {
                FilledTonalButton(onClick = { collapsed = false }, modifier = Modifier.heightIn(min = 48.dp)
                    .testTag("subtitle-preview-restore").semantics { contentDescription = "恢复字幕预览" }) {
                    Icon(Icons.Outlined.Subtitles, null)
                    Spacer(Modifier.width(8.dp))
                    Text("预览")
                }
            } else {
                Column {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.weight(1f).heightIn(min = 48.dp).then(drag).testTag("subtitle-preview-handle")
                            .padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.DragIndicator, null)
                            Text(if (enlarged) { if (landscape) "横屏示例·局部" else "竖屏示例·局部" }
                                else if (landscape) "横屏示例" else "竖屏示例", style = MaterialTheme.typography.labelLarge,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        IconToggleButton(checked = enlarged, onCheckedChange = { enlarged = it }, modifier = Modifier.size(48.dp)) {
                            Icon(if (enlarged) Icons.Outlined.ZoomOut else Icons.Outlined.ZoomIn,
                                if (enlarged) "返回完整观看画面" else "放大字幕局部")
                        }
                        IconToggleButton(checked = landscape, onCheckedChange = { landscape = it }, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Outlined.ScreenRotation, if (landscape) "切换为竖屏预览" else "切换为横屏预览")
                        }
                        IconButton(onClick = { collapsed = true }, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Outlined.ExpandLess, "暂时收起字幕预览")
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(bodyHeight).padding(8.dp), contentAlignment = Alignment.Center) {
                        val shortSide = minOf(watchingArea.width, watchingArea.height).toFloat()
                        val longSide = maxOf(watchingArea.width, watchingArea.height).toFloat()
                        val ratio = if (landscape) longSide / shortSide else shortSide / longSide
                        val availableHeight = (bodyHeight - 16.dp).coerceAtLeast(0.dp)
                        val stageWidth = (if (enlarged) panelWidth - 16.dp else minOf(panelWidth - 16.dp, availableHeight * ratio)).coerceAtLeast(0.dp)
                        val stageHeight = (if (enlarged) availableHeight else stageWidth / ratio).coerceAtLeast(0.dp)
                        AndroidView(
                            modifier = Modifier.size(stageWidth, stageHeight).clipToBounds().testTag("subtitle-native-preview"),
                            factory = { context -> SubtitlePreviewStage(context) },
                            onReset = null,
                            onRelease = { it.captionView.setLines(emptyList(), null) },
                            update = { it.render(style, landscape, enlarged, finalOnly) },
                        )
                    }
                }
            }
        }
    }
}

/** 与真实字幕服务一样扣除系统栏和显示缺口，只读取窗口尺寸，不创建系统悬浮窗。 */
private fun subtitleWatchingArea(context: Context): IntSize {
    if (android.os.Build.VERSION.SDK_INT >= 30) {
        val metrics = context.getSystemService(android.view.WindowManager::class.java).currentWindowMetrics
        val area = io.github.korenkrita.simulsub.captionWindowArea(metrics)
        return IntSize(area.width().coerceAtLeast(1), area.height().coerceAtLeast(1))
    }
    val metrics = context.resources.displayMetrics
    return IntSize(metrics.widthPixels.coerceAtLeast(1), metrics.heightPixels.coerceAtLeast(1))
}

/** 样例复用生产保留规则；所有原始样例都是不可变数据，字数按译文 Unicode 码点计算。 */
internal fun subtitlePreviewLines(style: SubtitleStyleUiState, finalOnly: Boolean = false): List<CaptionOverlayView.Line> {
    val examples = List(10) { index ->
        Caption(index.toLong(), 1, index < 9, ByokTranslator.PROBE_TEXT,
            "这个v是什么人？真是令人在意的v",
            Timing(0, 0, 0, 0, 0, 0, 0))
    }
    return CaptionRetention.apply(if (finalOnly) examples.filter { it.isFinal } else examples,
        style.limitMode, style.maxSentences.coerceIn(1, 10), style.keepChars.coerceIn(10, 300))
        .map { CaptionOverlayView.Line(it.zh, if (style.showOriginal) it.ja else null, it.isFinal) }
}

/** 在真实观看尺寸下排版原生字幕，再将整个画幅等比缩小；没有系统悬浮窗或服务。 */
internal class SubtitlePreviewStage(context: Context) : FrameLayout(context) {
    internal val captionView = CaptionOverlayView(context, interactive = false)
    internal var watchingWidthPx: Int = 1; private set
    internal var watchingHeightPx: Int = 1; private set
    private var style = SubtitleStyleUiState()
    private var enlarged = false

    init {
        setBackgroundColor(0xFF344044.toInt())
        clipChildren = true
        addView(captionView)
    }

    /** 参数变化只更新示例视图，不写入任何业务偏好或字幕窗口位置。 */
    internal fun render(value: SubtitleStyleUiState, landscape: Boolean, enlarged: Boolean, finalOnly: Boolean = false) {
        style = value
        this.enlarged = enlarged
        val area = subtitleWatchingArea(context)
        val short = minOf(area.width, area.height)
        val long = maxOf(area.width, area.height)
        watchingWidthPx = if (landscape) long else short
        watchingHeightPx = if (landscape) short else long
        captionView.setFinalOnly(finalOnly)
        captionView.setReserveDraftSpace(style.reserveDraftSpace)
        captionView.setShown(style.showOriginal, style.showStats)
        captionView.setColors(CaptionOverlayView.palette(style.paletteId))
        captionView.applyStyle(style.textSp, style.backgroundAlpha)
        captionView.setWindowHeightLimit(watchingHeightPx)
        captionView.setMaxCaptionLines(0)
        // 与 CaptionService.layout 共用正文比例，耗时区同样最多占可用高度的两成。
        captionView.setMaxContentHeight((watchingHeightPx * CaptionService.MAX_CAPTION_FRAC).toInt(), (watchingHeightPx * 0.2f).toInt())
        captionView.setCaptionHeight((style.heightFraction * watchingHeightPx).roundToInt())
        captionView.setLines(subtitlePreviewLines(style, finalOnly), if (style.showStats) "识别 120ms · 翻译 320ms" else null)
        requestLayout()
    }

    /** 子视图按虚拟观看宽度真实折行，禁止用预览缩略图的宽度替代实际排版宽度。 */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(View.MeasureSpec.getSize(widthMeasureSpec), View.MeasureSpec.getSize(heightMeasureSpec))
        val captionWidth = (watchingWidthPx * style.widthFraction.coerceIn(0.1f, 1f)).roundToInt().coerceAtLeast(1)
        captionView.measure(View.MeasureSpec.makeMeasureSpec(captionWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(watchingHeightPx, View.MeasureSpec.AT_MOST))
    }

    /** 同时缩放字幕的宽、高和字形，保持横竖观看画幅的真实比例。 */
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        // 原大局部允许子视图超出视口，但绘制必须截在观察区内，不能盖住拖动和收起按钮。
        clipBounds = android.graphics.Rect(0, 0, width, height)
        // 局部观察只作原大裁切，字幕仍按同一观看宽高排版，不改变字号或折行。
        val scale = if (enlarged) 1f else minOf(width.toFloat() / watchingWidthPx, height.toFloat() / watchingHeightPx)
        captionView.layout(0, 0, captionView.measuredWidth, captionView.measuredHeight)
        captionView.pivotX = 0f
        captionView.pivotY = 0f
        captionView.scaleX = scale
        captionView.scaleY = scale
        captionView.translationX = (width - captionView.measuredWidth * scale) / 2f
        captionView.translationY = height - captionView.measuredHeight * scale
    }
}
