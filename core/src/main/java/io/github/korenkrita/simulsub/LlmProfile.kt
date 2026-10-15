package io.github.korenkrita.simulsub

/** 上游命名翻译配置；空标识沿用旧存储键，升级后已有地址和密钥无需迁移。 */
data class LlmProfile(val id: String, val name: String) {
    val suffix: String get() = if (id.isEmpty()) "" else ":$id"

    companion object {
        const val MAX_NAME = 20
        const val MAX_PROFILES = 20
        val FIRST = LlmProfile("", "默认")

        /** 只读取合法且标识不重复的配置；损坏或空列表回退到原有默认配置。 */
        fun all(saved: String?): List<LlmProfile> {
            val array = runCatching { org.json.JSONArray(saved ?: "[]") }.getOrNull() ?: return listOf(FIRST)
            return (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val id = item.optString("id")
                val name = item.optString("name").trim().take(MAX_NAME)
                if (name.isEmpty() || !id.all { it in 'a'..'z' || it in '0'..'9' }) null else LlmProfile(id, name)
            }.distinctBy { it.id }.take(MAX_PROFILES).ifEmpty { listOf(FIRST) }
        }

        /** 配置目录只保存标识与名称，凭据由平台存储独立加密。 */
        fun store(list: List<LlmProfile>): String = org.json.JSONArray().apply {
            list.forEach { put(org.json.JSONObject().put("id", it.id).put("name", it.name)) }
        }.toString()

        /** 名称校验与上游保持一致，重命名时排除当前配置。 */
        fun name(typed: String, list: List<LlmProfile>, self: String? = null): Pair<String?, String?> {
            val name = typed.trim()
            return when {
                name.isEmpty() -> null to "名称不能为空"
                name.length > MAX_NAME -> null to "名称最多 $MAX_NAME 个字"
                list.any { it.id != self && it.name.equals(name, ignoreCase = true) } -> null to "已有同名配置"
                else -> name to null
            }
        }

        /** 使用不冲突的字母数字标识，避免标识进入存储键后改变其他字段。 */
        fun newId(list: List<LlmProfile>, now: Long = System.currentTimeMillis()): String {
            var number = now.coerceAtLeast(0)
            while (list.any { it.id == "p$number" }) number++
            return "p$number"
        }
    }
}
