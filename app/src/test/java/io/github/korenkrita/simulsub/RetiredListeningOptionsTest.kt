package io.github.korenkrita.simulsub

import io.github.korenkrita.simulsub.online.LiveTranslateModels
import io.github.korenkrita.simulsub.ui.UiOption
import io.github.korenkrita.simulsub.ui.serviceOptions
import org.junit.Assert.assertEquals
import org.junit.Test

/** 只验证配置选项过滤，不实例化控制器或启动设备界面。 */
class RetiredListeningOptionsTest {
    /** 首页与引导共用的兼容选项不能重新暴露旧分段模型，同时保留其他服务和顺序。 */
    @Test fun retiredDirectOptionsNeverReturnToThePicker() {
        val kept = listOf(UiOption("local:模型", "本地"),
            UiOption("direct:${LiveTranslateModels.REALTIME_ID}", "实时"), UiOption("profile:已保存", "用户服务"))
        val old = listOf(UiOption("catalog", "目录"), UiOption("manage", "管理"),
            UiOption("direct:${LiveTranslateModels.ECONOMY_ID}", "旧分段"),
            UiOption("direct:${LiveTranslateModels.ECONOMY_ID}-2025-12-01", "旧分段快照"))
        assertEquals(kept, (kept + old).serviceOptions())
    }
}
