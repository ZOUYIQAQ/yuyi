package io.github.korenkrita.simulsub

import java.net.URI

/** 文字服务模板只提供连接信息；模型和地址可编辑，凭据独立保存。 */
data class TextServiceProvider(val id: String, val title: String, val url: String,
    val model: String, val docsUrl: String, val group: String = "国内平台") {
    val platformId: String get() = when (id) {
        "deepseek" -> "deepseek"
        "qwen" -> "qwen"
        else -> "origin:https://${URI(url).host}"
    }
}

/** 依据官方兼容文档维护的模板目录，不将示例模型当作账户必然拥有的模型。 */
object TextServiceProviders {
    const val VERIFIED_DATE = "2026-10-17"
    val entries = listOf(
        TextServiceProvider("deepseek", "DeepSeek 官方", "https://api.deepseek.com/v1/chat/completions", "deepseek-flash", "https://api-docs.deepseek.com/"),
        TextServiceProvider("qwen", "阿里百炼 · 千问", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus", "https://help.aliyun.com/zh/model-studio/compatibility-of-openai-with-dashscope"),
        TextServiceProvider("siliconflow", "硅基流动", "https://api.siliconflow.cn/v1", "deepseek-ai/DeepSeek-V4-Flash", "https://docs.siliconflow.cn/docs/api/chat-completions-post"),
        TextServiceProvider("zhipu", "智谱 BigModel", "https://open.bigmodel.cn/api/paas/v4/chat/completions", "", "https://docs.bigmodel.cn/cn/guide/develop/openai/introduction"),
        TextServiceProvider("moonshot", "月之暗面 · Kimi", "https://api.moonshot.cn/v1", "", "https://platform.moonshot.cn/docs/guide/start-using-kimi-api"),
        TextServiceProvider("minimax", "MiniMax 国内", "https://api.minimax.cn/v1", "MiniMax-M3", "https://platform.minimaxi.com/docs/api-reference/text-openai-api"),
        TextServiceProvider("stepfun", "阶跃星辰", "https://api.stepfun.com/v1", "", "https://platform.stepfun.com/"),
        TextServiceProvider("doubao", "火山方舟 · 豆包", "https://ark.cn-beijing.volces.com/api/v3/chat/completions", "", "https://docs.volcengine.com/docs/ark/base-url-and-authentication"),
        TextServiceProvider("baidu", "百度智能云 · 千帆", "https://qianfan.baidubce.com/v2/chat/completions", "", "https://cloud.baidu.com/doc/qianfan-docs/s/Fm9l6ocai"),
        TextServiceProvider("hunyuan", "腾讯云 · 混元", "https://api.hunyuan.cloud.tencent.com/v1", "hunyuan-turbos-latest", "https://cloud.tencent.com/document/product/1729/111007"),
        TextServiceProvider("anthropic", "Anthropic · Claude", "https://api.anthropic.com/v1", "", "https://platform.claude.com/docs/en/cli-sdks-libraries/libraries/openai-sdk", "海外平台"),
        TextServiceProvider("openai", "OpenAI", "https://api.openai.com/v1", "", "https://platform.openai.com/docs/api-reference/chat", "海外平台"),
        TextServiceProvider("gemini", "Google · Gemini", "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions", "gemini-3.8-flash", "https://ai.google.dev/gemini-api/docs/openai", "海外平台"),
        TextServiceProvider("openrouter", "OpenRouter", "https://openrouter.ai/api/v1", "", "https://openrouter.ai/docs/quickstart", "海外平台"),
        TextServiceProvider("xai", "xAI · Grok", "https://api.x.ai/v1", "", "https://docs.x.ai/developers/rest-api-reference/inference", "海外平台"),
        TextServiceProvider("groq", "Groq", "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile", "https://console.groq.com/docs/openai", "海外平台"),
        TextServiceProvider("mistral", "Mistral AI", "https://api.mistral.ai/v1", "mistral-small-latest", "https://docs.mistral.ai/api", "海外平台"),
        TextServiceProvider("together", "Together AI", "https://api.together.ai/v1", "", "https://docs.together.ai/docs/inference/openai-compatibility", "海外平台"),
        TextServiceProvider("fireworks", "Fireworks AI", "https://api.fireworks.ai/inference/v1", "", "https://docs.fireworks.ai/tools-sdks/openai-compatibility", "海外平台"),
        TextServiceProvider("deepinfra", "DeepInfra", "https://api.deepinfra.com/v1/openai/chat/completions", "", "https://deepinfra.com/docs/openai_api", "海外平台"),
        TextServiceProvider("cerebras", "Cerebras", "https://api.cerebras.ai/v1", "", "https://inference-docs.cerebras.ai/resources/openai", "海外平台"),
        TextServiceProvider("zai", "Z.AI 国际", "https://api.z.ai/api/paas/v4/chat/completions", "", "https://docs.z.ai/guides/develop/openai/python", "海外平台"),
        TextServiceProvider("nvidia", "NVIDIA NIM", "https://integrate.api.nvidia.com/v1", "", "https://docs.nvidia.com/aiq-blueprint/2.2.0/customization/swapping-models.html", "海外平台"),
        TextServiceProvider("minimax-global", "MiniMax 国际", "https://api.minimax.io/v1", "MiniMax-M3", "https://platform.minimax.io/docs/api-reference/text-openai-api", "海外平台"),
    )

    /** 同一来源归入同一密钥目录，不对相似主机、非安全协议或其他端口放宽边界。 */
    fun requiresKey(platformId: String?): Boolean = entries.any { it.platformId == platformId }
}
