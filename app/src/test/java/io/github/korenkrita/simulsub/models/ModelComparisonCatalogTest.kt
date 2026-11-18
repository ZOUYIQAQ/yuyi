package io.github.korenkrita.simulsub.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 核对模型卡片与正式目录、公开参考和独立本机耗时一致。 */
class ModelComparisonCatalogTest {
    /** 三款新增模型均有简洁介绍和可追溯的公开参考。 */
    @Test fun additionalModelsHaveHonestComparisonEntries() {
        val ids = listOf("whisper-tiny", "moonshine-tiny-en", "moonshine-tiny-ja")
        ids.forEach { id ->
            val spec = requireNotNull(ModelCatalog.entries.find { it.id == id })
            val comparison = requireNotNull(ModelComparisons[id])
            assertEquals(spec.introduction, comparison.introduction)
            assertTrue(comparison.japaneseErrorRate.isNotBlank())
            assertTrue(comparison.englishErrorRate.isNotBlank())
            assertTrue(comparison.evaluationNote.contains("论文"))
            assertTrue(comparison.evaluationNote.contains("本机20段"))
        }
        assertEquals("4.52%", ModelComparisons["moonshine-tiny-en"]?.englishErrorRate)
        assertEquals(100, ModelComparisons["moonshine-tiny-en"]?.englishDecodeMs)
        assertEquals("17.87%", ModelComparisons["moonshine-tiny-ja"]?.japaneseErrorRate)
        assertEquals(137, ModelComparisons["moonshine-tiny-ja"]?.japaneseDecodeMs)
        assertEquals("37.0%", ModelComparisons["whisper-tiny"]?.japaneseErrorRate)
        assertEquals("12.4%", ModelComparisons["whisper-tiny"]?.englishErrorRate)
        assertEquals(2969, ModelComparisons["whisper-tiny"]?.japaneseDecodeMs)
        assertEquals(1687, ModelComparisons["whisper-tiny"]?.englishDecodeMs)
    }

    /** 单语模型明确写不支持；缺少对应公开成绩不能借用其他版本或小样本数字。 */
    @Test fun unsupportedLanguagesAndComparableMeasurementsRemainExplicit() {
        assertEquals("不支持日语", ModelComparisons["moonshine-tiny-en"]?.japaneseErrorRate)
        assertEquals("不支持英语", ModelComparisons["moonshine-tiny-ja"]?.englishErrorRate)
        assertEquals("8.48%", ModelComparisons["reazonspeech"]?.japaneseErrorRate)
        assertEquals("22.66%", ModelComparisons["reazonspeech"]?.englishErrorRate)
        assertEquals(145, ModelComparisons["reazonspeech"]?.japaneseDecodeMs)
        assertEquals(110, ModelComparisons["reazonspeech"]?.englishDecodeMs)
        assertEquals("11.96%", ModelComparisons["sensevoice"]?.japaneseErrorRate)
        assertEquals("14.71%", ModelComparisons["sensevoice"]?.englishErrorRate)
        assertEquals(223, ModelComparisons["sensevoice"]?.japaneseDecodeMs)
        assertEquals(154, ModelComparisons["sensevoice"]?.englishDecodeMs)
        assertTrue(ModelComparisons["reazonspeech"]!!.evaluationNote.contains("原项目实测"))
        assertEquals("原项目实测（FLEURS）", ModelComparisons["reazonspeech"]?.benchmarkLabel)
    }
}
