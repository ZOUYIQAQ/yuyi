package io.github.korenkrita.simulsub.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 核对模型卡片与正式目录及已复核的小样本结果一致。 */
class ModelComparisonCatalogTest {
    /** 三款新增模型均有简洁介绍和已复核的固定小样本结果。 */
    @Test fun additionalModelsHaveHonestComparisonEntries() {
        val ids = listOf("whisper-tiny", "moonshine-tiny-en", "moonshine-tiny-ja")
        ids.forEach { id ->
            val spec = requireNotNull(ModelCatalog.entries.find { it.id == id })
            val comparison = requireNotNull(ModelComparisons[id])
            assertEquals(spec.introduction, comparison.introduction)
            assertTrue(comparison.japaneseErrorRate.isNotBlank())
            assertTrue(comparison.englishErrorRate.isNotBlank())
            assertTrue(comparison.evaluationNote.contains("FLEURS"))
            assertTrue(comparison.evaluationNote.contains("前20句"))
        }
        assertEquals("10.32%", ModelComparisons["moonshine-tiny-en"]?.englishErrorRate)
        assertEquals(100, ModelComparisons["moonshine-tiny-en"]?.englishDecodeMs)
        assertEquals("19.77%", ModelComparisons["moonshine-tiny-ja"]?.japaneseErrorRate)
        assertEquals(137, ModelComparisons["moonshine-tiny-ja"]?.japaneseDecodeMs)
        assertEquals("51.98%", ModelComparisons["whisper-tiny"]?.japaneseErrorRate)
        assertEquals("12.43%", ModelComparisons["whisper-tiny"]?.englishErrorRate)
        assertEquals(2969, ModelComparisons["whisper-tiny"]?.japaneseDecodeMs)
        assertEquals(1687, ModelComparisons["whisper-tiny"]?.englishDecodeMs)
    }

    /** 单语模型明确写不支持；旧两款统一改为相同模拟器的20句结果。 */
    @Test fun unsupportedLanguagesAndComparableMeasurementsRemainExplicit() {
        assertEquals("不支持日语", ModelComparisons["moonshine-tiny-en"]?.japaneseErrorRate)
        assertEquals("不支持英语", ModelComparisons["moonshine-tiny-ja"]?.englishErrorRate)
        assertEquals("6.40%", ModelComparisons["reazonspeech"]?.japaneseErrorRate)
        assertEquals("18.52%", ModelComparisons["reazonspeech"]?.englishErrorRate)
        assertEquals(145, ModelComparisons["reazonspeech"]?.japaneseDecodeMs)
        assertEquals(110, ModelComparisons["reazonspeech"]?.englishDecodeMs)
        assertEquals("5.56%", ModelComparisons["sensevoice"]?.japaneseErrorRate)
        assertEquals("7.41%", ModelComparisons["sensevoice"]?.englishErrorRate)
        assertEquals(223, ModelComparisons["sensevoice"]?.japaneseDecodeMs)
        assertEquals(154, ModelComparisons["sensevoice"]?.englishDecodeMs)
        assertTrue(ModelComparisons["reazonspeech"]!!.evaluationNote.contains("sdk_gphone64_arm64"))
    }
}
