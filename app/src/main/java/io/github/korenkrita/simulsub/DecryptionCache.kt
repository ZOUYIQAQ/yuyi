package io.github.korenkrita.simulsub

/** 仅在内存保留有限份成功解密结果，以完整密文区分版本和来源。 */
internal class DecryptionCache(
    private val capacity: Int = 64,
    private val decrypt: (String) -> String,
) {
    private val lock = Any()

    init {
        require(capacity > 0) { "解密缓存容量必须大于零" }
    }

    private val values = LinkedHashMap<String, String>(capacity, 0.75f, true)

    /** 命中时更新最近使用顺序；锁内解密合并并发读取，失败不写入也不淘汰旧项。 */
    fun open(ciphertext: String): String = synchronized(lock) {
        values[ciphertext]?.let { return@synchronized it }
        val plaintext = decrypt(ciphertext)
        values[ciphertext] = plaintext
        if (values.size > capacity) {
            val oldest = values.entries.iterator()
            oldest.next()
            oldest.remove()
        }
        plaintext
    }
}
