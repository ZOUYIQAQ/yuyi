package io.github.korenkrita.simulsub

import org.junit.Assert.assertEquals
import org.junit.Test

/** 验证延迟绘制来不及执行便停止时仍保留真正的错误提示。 */
class CaptionServiceStatusTest {
    /** 最新错误优先于上一帧的加载状态或旧错误。 */
    @Test fun pendingErrorSurvivesStopBeforeNextFrame() {
        assertEquals("⚠ 连接失败", stoppedCaptionStatus("⚠ 连接失败", "正在连接在线服务…"))
        assertEquals("出错：无法启动", stoppedCaptionStatus("出错：无法启动", "⚠ 旧提示"))
        assertEquals("请先配置密钥", stoppedCaptionStatus("请先配置密钥", ""))
    }

    /** 沿用已发布的错误，但正常提示不能被误保留为停止原因。 */
    @Test fun publishedErrorsRemainVisibleAndNormalStopHasPlainStatus() {
        assertEquals("⚠ 权限已撤销", stoppedCaptionStatus("", "⚠ 权限已撤销"))
        assertEquals("同传已停止", stoppedCaptionStatus("正在准备…", "正在同传"))
    }
}
