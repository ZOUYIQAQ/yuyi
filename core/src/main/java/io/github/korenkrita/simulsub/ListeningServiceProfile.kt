package io.github.korenkrita.simulsub

import io.github.korenkrita.simulsub.online.CloudAsrConfig
import io.github.korenkrita.simulsub.online.LiveTranslateConfig
import java.util.UUID

/** 用户创建的具名听写服务；连接凭据始终由端点来源对应的中央密钥管理。 */
data class ListeningServiceProfile(
    val id: String,
    val name: String,
    val mode: ListeningMode,
    val endpoint: String,
    val model: String,
) {
    /** 校验并规范名称；端点原文保持不变，交给现有协议校验器判断。 */
    fun validated(): ListeningServiceProfile {
        require(runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)) { "听写服务标识必须是 UUID" }
        val checkedName = name.trim()
        require(checkedName.isNotEmpty()) { "听写服务名称不能为空" }
        require(checkedName.length <= MAX_NAME) { "听写服务名称最多 $MAX_NAME 个字" }
        val error = when (mode) {
            ListeningMode.CLOUD_ASR -> CloudAsrConfig(endpoint = endpoint, model = model, apiKey = VALIDATION_KEY)
                .validationErrors().firstOrNull()
            ListeningMode.ONLINE -> LiveTranslateConfig(endpoint = endpoint, model = model, apiKey = VALIDATION_KEY)
                .validationError()
            ListeningMode.LOCAL -> "本地模型无需创建听写服务"
        }
        require(error == null) { error.orEmpty() }
        return copy(name = checkedName)
    }

    companion object {
        const val MAX_NAME = 40
        private const val VALIDATION_KEY = "仅用于配置校验"

        /** 为新建服务生成独立于厂商与端点的稳定标识。 */
        fun newId(): String = UUID.randomUUID().toString()
    }
}
