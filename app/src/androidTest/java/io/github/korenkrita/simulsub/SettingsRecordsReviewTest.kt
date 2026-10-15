package io.github.korenkrita.simulsub

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.korenkrita.simulsub.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** 验证使用记录的实际确认弹窗，点击入口或取消均不能直接删除。 */
class SettingsRecordsReviewTest {
    @get:Rule val compose = createComposeRule()

    /** 单条删除必须展示记录明细并再次确认，取消不派发删除动作。 */
    @Test fun deletingOneRecordRequiresConfirmation() {
        var deleted: String? = null
        showRecords(listOf(record("结束记录")), SimulSubActions(onDeleteRecord = { deleted = it }))
        compose.onNodeWithText("删除记录").performScrollTo().performClick()
        compose.onNodeWithText("删除这条记录？").assertIsDisplayed()
        compose.onNodeWithText("将删除「结束记录」的本机会话记录（结束记录），删除后无法恢复。正在写入的记录会保留。").assertIsDisplayed()
        compose.runOnIdle { assertNull("弹出确认框时不能删除", deleted) }
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertNull("取消后不能删除", deleted) }
        compose.onNodeWithText("删除记录").performClick()
        compose.onNodeWithText("确认删除").performClick()
        compose.runOnIdle { assertEquals("结束记录", deleted) }
    }

    /** 全部清空必须二次确认，并明确保留活动记录及不可恢复的后果。 */
    @Test fun clearingRecordsRequiresConfirmation() {
        var cleared = 0
        showRecords(listOf(record("结束记录")), SimulSubActions(onDeleteAllRecords = { cleared++ }))
        compose.onNodeWithText("清空使用记录").performScrollTo().performClick()
        compose.onNodeWithText("清空使用记录？").assertIsDisplayed()
        compose.onNodeWithText("将删除本机所有已结束的会话记录，删除后无法恢复。正在写入的记录会保留，不会中断当前同传。").assertIsDisplayed()
        compose.runOnIdle { assertEquals("弹出确认框时不能清空", 0, cleared) }
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertEquals("取消后不能清空", 0, cleared) }
        compose.onNodeWithText("清空使用记录").performClick()
        compose.onNodeWithText("确认清空").performClick()
        compose.runOnIdle { assertEquals(1, cleared) }
    }

    /** 只有活动记录时，单条删除、全部清空和分享入口均不可操作。 */
    @Test fun activeRecordsCannotBeSharedOrDeleted() {
        showRecords(listOf(record("活动记录").copy(active = true)), SimulSubActions())
        compose.onNodeWithText("清空使用记录").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("删除记录").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("分享记录").assertIsNotEnabled()
    }

    /** 只渲染隔离的界面状态，不读取或修改设备日志。 */
    private fun showRecords(records: List<UsageRecordUiState>, actions: SimulSubActions) {
        compose.setContent {
            SimulSubApp(SimulSubUiState(destination = UiDestination.SETTINGS, settings = SettingsUiState(records = records)), actions)
        }
        compose.onNodeWithText("使用记录").performScrollTo().performClick()
    }

    /** 构造确定的记录标题，便于验证确认框中的记录明细。 */
    private fun record(id: String) = UsageRecordUiState(id, id, "测试时间", "完整记录")
}
