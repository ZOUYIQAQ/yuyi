package io.github.korenkrita.simulsub

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.korenkrita.simulsub.ui.SubtitleStyleUiState
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** 在隔离偏好上验证句数范围、旧配置迁移和控制器绑定，不启动字幕服务。 */
@RunWith(AndroidJUnit4::class)
class SentenceRangeDeviceTest {
    /** 新版默认保留三句，旧区间默认值不变，重建偏好后保持一致。 */
    @Test fun defaultsPersistAcrossPrefsRecreation() = withPreferences { application, stored ->
        val prefs = Prefs(application)
        assertEquals(CaptionBoard.DEFAULT_SENTENCES..4, prefs.sentenceRange())
        assertEquals(CaptionBoard.DEFAULT_SENTENCES, stored.getInt("keepSentences", -1))
        assertEquals(4, stored.getInt("maxSentences", -1))
        assertEquals(prefs.sentenceRange(), Prefs(application).sentenceRange())
        assertEquals(3, prefs.captionSentenceCount)
        assertEquals(3, stored.getInt("captionSentenceCount", -1))
        assertEquals(3, Prefs(application).captionSentenceCount)
        assertEquals(CaptionLimitMode.SENTENCES, prefs.captionLimitMode)
        assertEquals(3, SubtitleStyleUiState().maxSentences)
        assertEquals(8, SubtitleStyleUiState(keepSentences = 8).maxSentences)
    }

    /** 旧最少值高于四句时同步提高最多值，其余字幕和翻译偏好保持原文。 */
    @Test fun legacyMinimumMigratesWithoutLosingOtherSettings() = withPreferences(
        seed = {
            putInt("keepSentences", 8)
            putInt("keepChars", 237)
            putFloat("textSp", 22f)
            putInt("maxCaptionLines", 7)
            putBoolean("finalOnly", true)
            putString("byokUrl", "https://example.com/v1")
            putString("byokUserAgent", "用户已有请求标识/1.0")
        },
    ) { application, stored ->
        val prefs = Prefs(application)
        assertEquals(8..8, prefs.sentenceRange())
        assertEquals(8, prefs.captionSentenceCount)
        assertEquals(8, Prefs(application).captionSentenceCount)
        assertEquals(237, prefs.keepChars)
        assertEquals(22f, prefs.textSp, 0f)
        assertEquals(7, prefs.maxCaptionLines)
        assertTrue(prefs.finalOnly)
        assertEquals("https://example.com/v1", prefs.byokUrl)
        assertEquals("用户已有请求标识/1.0", prefs.byokUserAgent)
        assertEquals(8, stored.getInt("keepSentences", -1))
        assertEquals(8, stored.getInt("maxSentences", -1))
        assertEquals("Yuyi/${BuildConfig.VERSION_NAME}", ByokTranslator.defaultUserAgent)
        assertEquals("io.github.zouyiqaq.yuyi", BuildConfig.APPLICATION_ID)
    }

    /** 旧最少值低于默认上限时保持原值，不把旧配置重置为默认最少值。 */
    @Test fun legacySmallMinimumKeepsDefaultMaximum() = withPreferences(
        seed = { putInt("keepSentences", 1) },
    ) { application, _ ->
        val prefs = Prefs(application)
        assertEquals(1..4, prefs.sentenceRange())
        assertEquals(4, prefs.captionSentenceCount)
    }

    /** 已存最多值不能在重新创建偏好对象时被迁移默认值覆盖。 */
    @Test fun savedMaximumSurvivesRecreation() = withPreferences(
        seed = { putInt("keepSentences", 3); putInt("maxSentences", 9) },
    ) { application, _ ->
        val prefs = Prefs(application)
        assertEquals(3..9, prefs.sentenceRange())
        assertEquals(9, prefs.captionSentenceCount)
        assertEquals(9, Prefs(application).captionSentenceCount)
        prefs.setSentenceRange(2, 6)
        assertEquals(2..6, Prefs(application).sentenceRange())
    }

    /** 已有新版句数和字数模式优先于旧区间，三句默认不会覆盖显式选择。 */
    @Test fun explicitRetentionOverridesLegacyMaximum() = withPreferences(
        seed = {
            putInt("maxSentences", 9)
            putInt("captionSentenceCount", 7)
            putString("captionLimitMode", CaptionLimitMode.CHARACTERS.name)
            putInt("keepChars", 211)
        },
    ) { application, _ ->
        val prefs = Prefs(application)
        assertEquals(7, prefs.captionSentenceCount)
        assertEquals(CaptionLimitMode.CHARACTERS, prefs.captionLimitMode)
        assertEquals(211, prefs.keepChars)
        assertEquals(7, Prefs(application).captionSentenceCount)
    }

    /** 迁移时修正旧越界值和反向区间，并持久化合法的两个端点。 */
    @Test fun invalidLegacyRangeIsNormalizedAndStored() = withPreferences(
        seed = { putInt("keepSentences", 99); putInt("maxSentences", -8) },
    ) { application, stored ->
        assertEquals(10..10, Prefs(application).sentenceRange())
        assertEquals(10, stored.getInt("keepSentences", -1))
        assertEquals(10, stored.getInt("maxSentences", -1))
        stored.edit().putInt("keepSentences", -3).putInt("maxSentences", 99).commit()
        assertEquals(1..10, Prefs(application).sentenceRange())
        assertEquals(1, stored.getInt("keepSentences", -1))
        assertEquals(10, stored.getInt("maxSentences", -1))
    }

    /** 范围接口和旧属性入口都保持一到十句以及最少不大于最多的不变量。 */
    @Test fun settersClampEndpointsAndPreserveMinimumOnConflict() = withPreferences { application, _ ->
        val prefs = Prefs(application)
        prefs.setSentenceRange(-10, 99)
        assertEquals(1..10, prefs.sentenceRange())
        prefs.setSentenceRange(7, 2)
        assertEquals(7..7, prefs.sentenceRange())
        prefs.setSentenceRange(2, 4)
        prefs.keepSentences = 6
        assertEquals(6..6, prefs.sentenceRange())
        prefs.maxSentences = 1
        assertEquals(6..6, prefs.sentenceRange())
        prefs.keepSentences = -1
        prefs.maxSentences = 99
        assertEquals(1..10, prefs.sentenceRange())
    }

    /** 每次变更的监听回调都应看到同一对新端点，不能观察到最少大于最多的中间状态。 */
    @Test fun rangeSaveNotifiesWithBothEndpointsAlreadyUpdated() = withPreferences { application, stored ->
        val prefs = Prefs(application)
        val observed = mutableListOf<Pair<Int, Int>>()
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "keepSentences" || key == "maxSentences") {
                observed += stored.getInt("keepSentences", -1) to stored.getInt("maxSentences", -1)
            }
        }
        prefs.listen(listener)
        try {
            prefs.setSentenceRange(7, 9)
            assertTrue("范围变化必须触发偏好监听", observed.isNotEmpty())
            assertTrue("监听不能看到半个新区间", observed.all { it == (7 to 9) })
            observed.clear()
            prefs.setSentenceRange(1, 2)
            assertTrue(observed.isNotEmpty())
            assertTrue(observed.all { it == (1 to 2) })
        } finally {
            prefs.unlisten(listener)
        }
    }

    /** 控制器迁移旧上限，保存后只启用一种策略，不再让下限或行数联动。 */
    @Test fun controllerSavesOneRetentionPolicy() = withPreferences(
        seed = { putInt("keepSentences", 6); putInt("maxCaptionLines", 7) },
    ) { application, _ ->
        val prefs = Prefs(application)
        val controller = AppController(application)
        val store = ViewModelStore().apply { put("保留方式回归", controller) }
        try {
            val initial = controller.state.value.settings.subtitle
            assertEquals(6, initial.maxSentences)
            assertEquals(0, initial.maxLines)
            assertEquals(CaptionLimitMode.SENTENCES, initial.limitMode)
            val actions = controller.actions({}, {}, {}, {})
            actions.onSubtitleStyleChange(initial.copy(maxSentences=3,keepChars=211,limitMode=CaptionLimitMode.CHARACTERS))
            assertEquals(3,prefs.captionSentenceCount)
            assertEquals(211,prefs.keepChars)
            assertEquals(CaptionLimitMode.CHARACTERS,prefs.captionLimitMode)
            actions.onSubtitleStyleChange(controller.state.value.settings.subtitle.copy(limitMode=CaptionLimitMode.SENTENCES))
            assertEquals(3,controller.state.value.settings.subtitle.maxSentences)
            assertEquals(211,controller.state.value.settings.subtitle.keepChars)
            assertEquals(CaptionLimitMode.SENTENCES,Prefs(application).captionLimitMode)
        } finally { store.clear() }
    }

    /** 新安装默认仅译文、无耗时；显式保存的开关与两个模式各自记忆。 */
    @Test fun freshDefaultsAndExplicitPreferencesArePreserved() = withPreferences { application, _ ->
        val prefs=Prefs(application)
        assertEquals(CaptionLimitMode.SENTENCES,prefs.captionLimitMode)
        assertEquals(3,prefs.captionSentenceCount)
        assertFalse(prefs.showJa)
        assertFalse(prefs.showStats)
        prefs.showJa=true;prefs.showStats=true
        prefs.setCaptionRetention(CaptionLimitMode.CHARACTERS,7,150)
        val restored=Prefs(application)
        assertTrue(restored.showJa);assertTrue(restored.showStats)
        assertEquals(7,restored.captionSentenceCount)
        assertEquals(150,restored.keepChars)
        assertEquals(CaptionLimitMode.CHARACTERS,restored.captionLimitMode)
    }

    /** 主线程创建独立的应用偏好命名空间，测试结束释放监听且不访问用户既有配置。 */
    private fun withPreferences(
        seed: SharedPreferences.Editor.() -> Unit = {},
        body: (Application, SharedPreferences) -> Unit,
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val token = UUID.randomUUID().toString()
        val application = object : Application() {
            /** 模型仓库使用真实应用上下文，但不启动模型或下载。 */
            override fun getApplicationContext(): Context = context.applicationContext
            /** 每个测试独立保存全部偏好。 */
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                context.getSharedPreferences("句数范围回归-$token-$name", mode)
            /** 控制器的记录读取指向独立缓存路径，不读取已有会话。 */
            override fun getExternalFilesDir(type: String?): File = File(context.cacheDir, "句数范围回归-$token")
        }
        var failure: Throwable? = null
        instrumentation.runOnMainSync {
            try {
                val stored = application.getSharedPreferences("jpsub", Context.MODE_PRIVATE)
                assertTrue("测试初始偏好应写入成功", stored.edit().apply(seed).commit())
                body(application, stored)
            } catch (error: Throwable) {
                failure = error
            }
        }
        failure?.let { throw it }
    }
}
