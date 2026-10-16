package io.github.korenkrita.simulsub

import android.os.Build
import android.view.WindowManager
import android.content.Context

/** 与系统允许的穿透上限保持一致，预览和真实字幕使用同一合成透明度。 */
internal fun captionWindowOpacity(context: Context): Float = if (Build.VERSION.SDK_INT >= 31)
    context.getSystemService(android.hardware.input.InputManager::class.java).maximumObscuringOpacityForTouch.coerceIn(0f, 1f)
    else 1f

/** 仅关闭系统附加位移动画，保留应用内部滚动与伸缩。 */
internal fun WindowManager.LayoutParams.disableCaptionWindowMoveAnimation() {
    if (Build.VERSION.SDK_INT >= 34) setCanPlayMoveAnimation(false)
}
