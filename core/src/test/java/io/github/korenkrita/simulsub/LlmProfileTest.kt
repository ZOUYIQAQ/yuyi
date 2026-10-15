package io.github.korenkrita.simulsub

import org.junit.Assert.*
import org.junit.Test

/** 配置目录必须兼容旧数据，并拒绝进入存储键的非法标识。 */
class LlmProfileTest {
    /** 默认项沿用无后缀旧键，目录损坏时不丢失旧配置。 */
    @Test fun legacyAndBrokenDirectoriesUseDefault() {
        for (value in listOf(null, "", "[]", "{}", "invalid", "[{\"id\":\"../key\",\"name\":\"坏配置\"}]")) {
            assertEquals(listOf(LlmProfile.FIRST), LlmProfile.all(value))
        }
        assertEquals("", LlmProfile.FIRST.suffix)
    }
    /** 合法目录往返保持顺序，重复项和超出限制的内容不能无限堆积。 */
    @Test fun roundTripKeepsNamesAndBoundsDirectory() {
        val profiles = listOf(LlmProfile.FIRST, LlmProfile("p1", "局域网"))
        assertEquals(profiles, LlmProfile.all(LlmProfile.store(profiles)))
        assertEquals(2, LlmProfile.all(LlmProfile.store(profiles + profiles)).size)
        assertEquals(LlmProfile.MAX_PROFILES, LlmProfile.all(LlmProfile.store(List(40) { LlmProfile("p$it", "配置$it") })).size)
        assertEquals(":p1", profiles[1].suffix)
    }
    /** 名称去除首尾空格，重名不区分大小写，重命名可保留自身名称。 */
    @Test fun namesAreValidatedAgainstOtherProfiles() {
        val list = listOf(LlmProfile.FIRST, LlmProfile("p1", "Local"))
        assertEquals("测试", LlmProfile.name(" 测试 ", list).first)
        assertNotNull(LlmProfile.name("local", list).second)
        assertNull(LlmProfile.name("Local", list, "p1").second)
        assertNotNull(LlmProfile.name(" ", list).second)
        assertNotNull(LlmProfile.name("长".repeat(21), list).second)
    }
    /** 同毫秒连续创建仍得到唯一合法标识。 */
    @Test fun idsDoNotCollideWithinTheDirectory() {
        assertEquals("p3", LlmProfile.newId(listOf(LlmProfile("p1", "甲"), LlmProfile("p2", "乙")), 1))
        assertEquals("p0", LlmProfile.newId(emptyList(), -1))
    }
}
