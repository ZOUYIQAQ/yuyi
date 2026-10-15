package io.github.korenkrita.simulsub.ui

import io.github.korenkrita.simulsub.ByokTranslator

/** 预设是可编辑的填写模板，不绑定供应商账户，也不自动保存或请求网络。 */
data class TranslationPreset(val id: String, val title: String, val url: String, val model: String, val detail: String)

/** 地址与建议模型核对于2026-10-15；来源及维护说明见docs/translation.md。 */
object TranslationPresets {
    val entries = listOf(
        TranslationPreset("deepseek", "DeepSeek 官方", "https://api.deepseek.com/v1", "deepseek-flash", "填入官方地址与建议模型，使用你自己的 DeepSeek API 密钥。"),
        TranslationPreset("siliconflow", "硅基流动", "https://api.siliconflow.cn/v1", "deepseek-ai/DeepSeek-V4-Flash", "使用硅基流动账户的模型权限与 API 密钥。"),
        TranslationPreset("openrouter", "OpenRouter", "https://openrouter.ai/api/v1", "", "已填入地址；请按账户可用模型填写完整模型 ID。"),
    )

    /** 按规范化请求地址识别模板，手动修改模型不会锁定或重填表单。 */
    fun match(url: String): String {
        val endpoint = ByokTranslator.chatUrl(url).second ?: return "custom"
        return entries.firstOrNull { ByokTranslator.chatUrl(it.url).second == endpoint }?.id ?: "custom"
    }

    /** 更换地址时不继承旧凭据；用户保存前只改变草稿。 */
    fun apply(draft: TranslationApiUiState, id: String): TranslationApiUiState {
        if (id == "custom") return draft.copy(presetId = id, message = null)
        val preset = entries.firstOrNull { it.id == id } ?: return draft
        val changed = ByokTranslator.chatUrl(draft.url).second != ByokTranslator.chatUrl(preset.url).second
        return draft.copy(
            presetId = id, url = preset.url, model = preset.model,
            key = if (changed) "" else draft.key,
            hasSavedKey = if (changed) false else draft.hasSavedKey,
            headers = if (changed) emptyList() else draft.headers,
            userAgent = if (changed) "" else draft.userAgent,
            resolvedUrl = ByokTranslator.chatUrl(preset.url).second.orEmpty(),
            message = UiMessage("已填入${preset.title}，地址和模型仍可修改。" + if (changed) "请填写此服务的密钥；原密钥和自定义请求头不会带入。保存后生效。" else "保存后生效。"),
        )
    }
}
