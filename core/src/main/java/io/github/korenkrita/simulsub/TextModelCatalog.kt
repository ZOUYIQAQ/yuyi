package io.github.korenkrita.simulsub

/** 官方文字模型建议目录；只帮助填写，不限制账户模型、代理部署名称或后续新模型。 */
object TextModelCatalog {
    const val VERIFIED_DATE = "2026-10-17"
    private val models = mapOf(
        "deepseek" to listOf("deepseek-flash", "deepseek-v4-pro"),
        "qwen" to listOf("qwen-plus", "qwen-flash", "qwen3.8-flash", "qwen3.7-plus", "qwen3.8-max"),
        "siliconflow" to listOf("deepseek-ai/DeepSeek-V4-Flash"),
        "zhipu" to listOf("glm-5.3-flash", "glm-5.3"),
        "moonshot" to listOf("kimi-k3", "kimi-k2.6"),
        "minimax" to listOf("MiniMax-M3", "MiniMax-M2.7-highspeed", "MiniMax-M2.7"),
        "stepfun" to listOf("step-3.7-flash", "step-3.5-flash"),
        "doubao" to listOf("doubao-seed-2-1-pro-260915"),
        "baidu" to listOf("ernie-4.5-turbo-128k"),
        "hunyuan" to listOf("hunyuan-turbos-latest"),
        "anthropic" to listOf("claude-haiku-5-5", "claude-sonnet-5-5", "claude-opus-5-5"),
        "openai" to listOf("gpt-6-luna", "gpt-6.1-sol", "gpt-6-astra"),
        "gemini" to listOf("gemini-3.8-flash", "gemini-3.5-flash-lite", "gemini-3.6-flash"),
        "openrouter" to listOf("google/gemini-3.8-flash", "deepseek/deepseek-v4.1-flash", "z-ai/glm-5.3-flash"),
        "xai" to listOf("grok-4.7"),
        "groq" to listOf("llama-3.3-70b-versatile", "llama-3.1-8b-instant", "openai/gpt-oss-20b", "openai/gpt-oss-120b"),
        "mistral" to listOf("mistral-small-latest", "mistral-large-latest"),
        "together" to listOf("MiniMaxAI/MiniMax-M3", "deepseek-ai/DeepSeek-V4.1-Flash", "openai/gpt-oss-120b"),
        "fireworks" to listOf("accounts/fireworks/models/glm-5p3-flash", "accounts/fireworks/models/glm-5p3", "accounts/fireworks/models/kimi-k3"),
        "deepinfra" to listOf("deepseek-ai/DeepSeek-V4.1-Flash", "zai-org/GLM-5.3-Flash", "meta-llama/Llama-3.3-70B-Instruct-Turbo"),
        "cerebras" to listOf("qwen-3.8-27b", "gpt-oss-120b"),
        "zai" to listOf("glm-5.3", "glm-5.3-flash"),
        "nvidia" to listOf("nvidia/nemotron-3-super-120b-a12b", "deepseek-ai/deepseek-v4.1-flash", "z-ai/glm-5.3-flash"),
        "minimax-global" to listOf("MiniMax-M3", "MiniMax-M2.7-highspeed", "MiniMax-M2.7"),
    )

    /** 按供应商标识返回专属建议；未知接口不套用其他平台模型。 */
    fun forProvider(providerId: String): List<String> = models[providerId].orEmpty()
}
