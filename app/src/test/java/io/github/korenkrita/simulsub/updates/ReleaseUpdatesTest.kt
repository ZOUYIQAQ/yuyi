package io.github.korenkrita.simulsub.updates

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ReleaseUpdatesTest {
    private val repo = "https://github.com/ZOUYIQAQ/yuyi"

    /** 按数字组件比较，补零并支持超出整数范围的组件。 */
    @Test fun comparesNumericComponents() {
        for ((candidate, installed) in listOf("v1.10" to "1.9", "2" to "1.99.99",
            "1.0.1" to "1", "999999999999999999999" to "9")) {
            assertTrue("$candidate > $installed", ReleaseVersions.isNewer(candidate, installed))
        }
        for ((candidate, installed) in listOf("1" to "1.0.0", "v1.0" to "1",
            "1.9" to "1.10", "01.002" to "1.2")) {
            assertFalse(ReleaseVersions.isNewer(candidate, installed))
        }
    }

    /** 任一侧格式错误或含预发行标记，都不能判断为更新。 */
    @Test fun rejectsMalformedAndPrereleaseVersions() {
        for (invalid in listOf("", "v", "1..2", "1.", ".1", "-1", "1.2-beta",
            "2-rc1", "1.0+build", "release-2", "1a", "１.２", " 2 ")) {
            assertFalse(invalid, ReleaseVersions.isNewer(invalid, "1"))
            assertFalse(invalid, ReleaseVersions.isNewer("99", invalid))
        }
    }

    /** 稳定发行即使没有安装包，也保留可信发行页供用户打开。 */
    @Test fun keepsReleaseWithoutApk() {
        val result = parse(release())!!
        assertEquals("v1.10.0", result.tag)
        assertEquals("1.10.0", result.versionName)
        assertEquals("$repo/releases/tag/v1.10.0", result.releaseUrl)
        assertNull(result.downloadUrl)
    }

    /** 草稿、预发行及伪装成稳定发行的预发行标签均不展示。 */
    @Test fun ignoresDraftAndPrereleases() {
        assertNull(parse(release().put("draft", true)))
        assertNull(parse(release().put("prerelease", true)))
        assertNull(parse(release().put("tag_name", "v2.0-rc1")))
    }

    /** 优先专用架构，其次通用包，同优先级不受接口返回顺序影响。 */
    @Test fun prefersArm64AndSortsNamesDeterministically() {
        val names = listOf("z-arm64-v8a.apk", "a-arm64-v8a.apk", "a-arm64.apk", "a.apk")
        for (order in listOf(names, names.reversed())) {
            assertEquals(download("a-arm64-v8a.apk"), parse(release(order.map { asset(it) }))!!.downloadUrl)
        }
        assertEquals(download("a-arm64.apk"), parse(release(listOf(asset("z.apk"), asset("a-arm64.apk"))))!!.downloadUrl)
        assertEquals(download("a.apk"), parse(release(listOf(asset("z.apk"), asset("a.apk"))))!!.downloadUrl)
    }

    /** 不接受外站、其他仓库、代理、认证信息及不兼容架构的资产。 */
    @Test fun ignoresUnsafeAndIncompatibleAssets() {
        val invalidUrls = listOf("https://evil.example/a.apk",
            "https://github.com/other/yuyi/releases/download/v1.10.0/a.apk",
            "https://github.com/ZOUYIQAQ/other/releases/download/v1.10.0/a.apk",
            "https://gh-proxy.com/${download("a.apk")}",
            "https://github.com.evil.example/ZOUYIQAQ/yuyi/releases/download/v1.10.0/a.apk",
            download("a.apk").replace("https://", "http://"),
            download("a.apk").replace("github.com", "user@github.com"),
            download("a.apk") + "?redirect=evil", download("a.apk") + "#fragment",
            download("%2e%2e%2fa.apk"), download("a.apk").replace("v1.10.0", "v1.9.0"))
        val assets = invalidUrls.map { asset("a-arm64.apk", it) } +
            listOf("x86.apk", "x86_64.apk", "armeabi-v7a.apk", "app-32bit.apk",
                "app-armv7.apk", "app-arm64-x86.apk", "app.zip").map { asset(it) } +
            asset("safe.apk", download("app-x86.apk"))
        assertNull(parse(release(assets))!!.downloadUrl)
    }

    /** 发行页不能跳到其他仓库、其他路径或带查询参数的链接。 */
    @Test fun rejectsUntrustedReleasePages() {
        for (url in listOf("https://evil.example/releases/tag/v1.10.0",
            "$repo/releases/latest", "$repo/releases/tag/v1.9.0",
            "$repo/releases/tag/v1.10.0?next=evil",
            "https://github.com/other/yuyi/releases/tag/v1.10.0")) {
            assertThrows(IOException::class.java) { parse(release().put("html_url", url)) }
        }
    }

    /** 响应上限按 UTF-8 字节计算，恰好达到上限允许，多一字节拒绝。 */
    @Test fun boundsResponseBytes() {
        val text = release().toString()
        val padding = 256 * 1024 - text.toByteArray(Charsets.UTF_8).size
        assertNotNull(GithubReleaseUpdateSource.parseRelease(text + " ".repeat(padding)))
        assertThrows(IOException::class.java) {
            GithubReleaseUpdateSource.parseRelease(text + " ".repeat(padding + 1))
        }
        assertThrows(IOException::class.java) { parse(release().put("body", "中".repeat(90_000))) }
    }

    /** 展示文本去掉常见标记、控制字符和 HTML，并限制长度。 */
    @Test fun boundsAndSimplifiesNotes() {
        val notes = parse(release().put("body", "## 修复\n- **稳定** [说明](https://example.com) <b>完成</b>\u0000"))!!.notes
        assertTrue(notes.contains("修复"))
        assertTrue(notes.contains("稳定"))
        assertFalse(notes.contains("https://"))
        assertFalse(notes.contains("<b>"))
        assertFalse(notes.contains('\u0000'))
        assertTrue(parse(release().put("body", "中".repeat(10_000)))!!.notes.length <= 2_000)
        assertTrue(parse(release().put("body", JSONObject.NULL))!!.notes.isNotBlank())
    }

    /** 错误 JSON 和错误字段以中文异常报告。 */
    @Test fun malformedPayloadHasFriendlyError() {
        for (text in listOf("<html>错误</html>", "{}", release().put("draft", "false").toString())) {
            val error = assertThrows(IOException::class.java) { GithubReleaseUpdateSource.parseRelease(text) }
            assertTrue(error.message!!.contains("更新"))
        }
    }

    /** 构造不依赖网络的发行数据。 */
    private fun release(assets: List<JSONObject> = emptyList()): JSONObject = JSONObject()
        .put("tag_name", "v1.10.0").put("name", "展示标题")
        .put("draft", false).put("prerelease", false).put("body", "修复问题")
        .put("html_url", "$repo/releases/tag/v1.10.0").put("assets", JSONArray(assets))

    /** 构造包含真实下载链接的资产。 */
    private fun asset(name: String, url: String = download(name)): JSONObject = JSONObject()
        .put("name", name).put("browser_download_url", url)

    /** 生成当前发行的同仓库下载路径。 */
    private fun download(name: String): String = "$repo/releases/download/v1.10.0/$name"

    /** 通过正式纯解析入口执行测试。 */
    private fun parse(json: JSONObject): AvailableUpdate? = GithubReleaseUpdateSource.parseRelease(json.toString())
}
