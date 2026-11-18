package io.github.korenkrita.simulsub.ui

import io.github.korenkrita.simulsub.ByokTranslator
import io.github.korenkrita.simulsub.TextModelCatalog

/** 预设是可编辑的填写模板，不绑定供应商账户，也不自动保存或请求网络。 */
data class TranslationPreset(val id: String, val title: String, val url: String, val model: String, val detail: String)

/** 地址与示例模型核对于2026-10-17；来源及维护说明见docs/translation.md。 */
object TranslationPresets {
    val entries = io.github.korenkrita.simulsub.TextServiceProviders.entries.map { provider ->
        TranslationPreset(provider.id, provider.title, provider.url, provider.model,
            if (provider.id == "doubao") "填写账户已开通的模型 ID 或推理接入点 ID。"
            else if (provider.model.isBlank()) "填写账户可用的模型 ID，地址与模型均可修改。"
            else "已填入官方兼容地址与示例模型，可按账户修改。")
    }

    /** 按规范化请求地址识别模板，手动修改模型不会锁定或重填表单。 */
    fun match(url: String): String {
        val endpoint = ByokTranslator.chatUrl(url).second ?: return "custom"
        return entries.firstOrNull { ByokTranslator.chatUrl(it.url).second == endpoint ||
            it.url.removeSuffix("/chat/completions").trimEnd('/') == url.trim().trimEnd('/') }?.id ?: "custom"
    }

    /** 仅建议当前平台的文字模型，未知或手填值原样保留，不改变草稿。 */
    fun modelsFor(url: String, currentModel: String): List<UiOption> {
        val models = TextModelCatalog.forProvider(match(url))
        val choices = if (currentModel.isNotBlank() && currentModel !in models)
            listOf(currentModel) + models else models
        return choices.map { UiOption(it, it) }
    }

    /** 预设只更新连接草稿；目标平台及其中央密钥由控制器根据地址重新绑定。 */
    fun apply(draft: TranslationApiUiState, id: String): TranslationApiUiState {
        if (id == "custom") return draft.copy(presetId = id, message = null)
        val preset = entries.firstOrNull { it.id == id } ?: return draft
        val changed = ByokTranslator.chatUrl(draft.url).second != ByokTranslator.chatUrl(preset.url).second
        return draft.copy(
            presetId = id, url = preset.url, model = preset.model,
            headers = if (changed) emptyList() else draft.headers,
            userAgent = if (changed) "" else draft.userAgent,
            resolvedUrl = ByokTranslator.chatUrl(preset.url).second.orEmpty(),
            message = UiMessage("已填入${preset.title}，地址和模型仍可修改。" + if (changed)
                "请在平台密钥中管理此服务的密钥；原自定义请求头不会带入。保存后生效。" else "保存后生效。"),
        )
    }
}
