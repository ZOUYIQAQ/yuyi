package io.github.korenkrita.simulsub

import io.github.korenkrita.simulsub.online.CloudAsrText
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 验证第三模式只为定稿付费、顺序输出、短句与取消，不访问外部服务。 */
class CloudAsrTranslationPipelineTest {
    /** 草稿和重复最终事件不会多发翻译请求。 */
    @Test fun draftsAndDuplicateFinalsAreNotBilledAgain() {
        val prompts = CopyOnWriteArrayList<String>()
        val captions = CopyOnWriteArrayList<Caption>()
        val errors = CopyOnWriteArrayList<String>()
        val translator = object : Mt {
            /** 返回可观察的真实处理顺序。 */
            override fun translate(prompt: String): MtResult { prompts += prompt; return MtResult("短字幕", 'e', 1, 1) }
            /** 内存替身不持有资源。 */
            override fun cancel() {}
        }
        CloudAsrTranslationPipeline(translator, { captions += it }, { errors += it }).use { pipeline ->
            pipeline.start()
            pipeline.accept(CloudAsrText("1", "途中", false))
            pipeline.accept(CloudAsrText("1", "完成。", true))
            pipeline.accept(CloudAsrText("1", "完成。", true))
            pipeline.accept(CloudAsrText("2", "次の文章。", true))
            pipeline.finishInput()
            assertTrue(pipeline.awaitCompletion(3_000))
        }
        assertEquals(2, prompts.size)
        assertTrue(prompts[1].contains("完成。"))
        assertEquals(2, captions.size)
        assertTrue(captions[1].segmentId > captions[0].segmentId)
        assertTrue(errors.isEmpty())
    }

    /** 关闭正在翻译的会话后，迟到的网络结果不能回到字幕板。 */
    @Test fun closeCancelsPendingTranslationWithoutLateCaptions() {
        val entered = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val captions = CopyOnWriteArrayList<Caption>()
        val translator = object : Mt {
            /** 模拟一个直到取消才返回的网络请求。 */
            override fun translate(prompt: String): MtResult {
                entered.countDown(); cancelled.await(2, TimeUnit.SECONDS)
                return MtResult("迟到字幕", 'e', null, null)
            }
            /** 取消将阻塞的请求释放。 */
            override fun cancel() { cancelled.countDown() }
        }
        val pipeline = CloudAsrTranslationPipeline(translator, { captions += it }, {})
        pipeline.start(); pipeline.accept(CloudAsrText("1", "文章。", true))
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        pipeline.close()
        assertTrue(pipeline.awaitCompletion(2_000))
        assertTrue(captions.isEmpty())
    }

    /** 日语完整句交给翻译一次；译文才分成短字幕，避免切成孤立语法片段。 */
    @Test fun translatesWholeSentenceBeforeSplittingReadableCaptions() {
        val prompts = CopyOnWriteArrayList<String>()
        val captions = CopyOnWriteArrayList<Caption>()
        val source = "多くの場合、海外のギャップイヤーコースに入学することで、帰国後に実際に大学に進学しやすくなります。"
        val translator = object : Mt {
            /** 输出跨越多个字幕长度的句子，验证显示分块不改变请求语义。 */
            override fun translate(prompt: String): MtResult {
                prompts += prompt
                return MtResult("很多时候，上了海外的间隔年课程，回国后其实会更容易升入大学。", 'e', 100, 20)
            }
            /** 内存翻译器无需回收外部资源。 */
            override fun cancel() {}
        }
        CloudAsrTranslationPipeline(translator, { captions += it }, { fail(it) }).use { pipeline ->
            pipeline.start(); pipeline.accept(CloudAsrText("whole", source, true)); pipeline.finishInput()
            assertTrue(pipeline.awaitCompletion(5_000))
        }
        assertEquals(1, prompts.size)
        assertTrue(prompts.single().contains(source))
        assertTrue(captions.size >= 2)
        assertEquals("很多时候，上了海外的间隔年课程，回国后其实会更容易升入大学。", captions.joinToString("") { it.zh })
    }

    /** 翻译失败不能自动重试、生成空定稿或继续为队列中的后续句子计费。 */
    @Test fun failedTranslationStopsWithoutRetry() {
        var requests = 0
        val errors = CopyOnWriteArrayList<String>()
        val translator = object : Mt {
            /** 模拟真实的额度不足响应。 */
            override fun translate(prompt: String): MtResult {
                requests++
                return MtResult("", 'f', null, null, http = 429)
            }
            /** 内存替身不持有外部连接。 */
            override fun cancel() {}
        }
        CloudAsrTranslationPipeline(translator, { fail("失败时不能输出字幕") }, { errors += it }).use { pipeline ->
            pipeline.start()
            pipeline.accept(CloudAsrText("1", "最初の文。", true))
            pipeline.accept(CloudAsrText("2", "次の文。", true))
            pipeline.finishInput()
            assertTrue(pipeline.awaitCompletion(3_000))
        }
        assertEquals(1, requests)
        assertEquals(1, errors.size)
        assertTrue(errors.single().contains("HTTP 429"))
    }
}
