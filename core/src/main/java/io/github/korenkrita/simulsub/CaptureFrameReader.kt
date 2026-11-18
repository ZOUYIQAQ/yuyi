package io.github.korenkrita.simulsub

/** 非阻塞读取完整音频帧；停止、积压或工作线程失败均能打断零数据等待和半帧读取。 */
fun readCapturedFrame(frame: FloatArray, shouldStop: () -> Boolean,
    read: (FloatArray, Int, Int) -> Int, idle: () -> Unit): Boolean {
    var offset = 0
    while (offset < frame.size) {
        if (shouldStop()) return false
        val count = read(frame, offset, frame.size - offset)
        if (shouldStop()) return false
        check(count >= 0) { "读取音频失败 ($count)" }
        check(count <= frame.size - offset) { "音频读取返回的长度超过请求范围" }
        if (count == 0) idle() else offset += count
    }
    return !shouldStop()
}
