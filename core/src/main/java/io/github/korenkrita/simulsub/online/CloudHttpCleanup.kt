package io.github.korenkrita.simulsub.online

import okhttp3.OkHttpClient

/** TLS 连接释放可能写出关闭通知；取消立即执行，实际清理始终在后台完成。 */
internal fun closeCloudHttpClient(client: OkHttpClient) {
    client.dispatcher.cancelAll()
    Thread({
        try {
            client.connectionPool.evictAll()
        } finally {
            client.dispatcher.executorService.shutdown()
        }
    }, "在线连接回收").apply { isDaemon = true }.start()
}
