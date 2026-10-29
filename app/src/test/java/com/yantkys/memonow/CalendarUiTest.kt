package com.yantkys.memonow

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import java.time.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
class CalendarUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun toolbarOpensCalendarAndOverflowStillReachesBothExistingActions() {
        var open by mutableStateOf(false)
        var picked: LocalDate? = null
        var backups = 0
        var settings = 0
        val day = LocalDate.now(ZoneOffset.UTC)
        compose.setContent { MaterialTheme {
            Row { AppActions(true,false,false,{open=true},{backups++},{settings++}) }
            if(open) CalendarDialog(mapOf(day to 2),null,ZoneOffset.UTC,{picked=it;open=false},{open=false})
        } }
        compose.onNodeWithContentDescription("カレンダー").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText("カレンダー").assertIsDisplayed()
        compose.onNodeWithContentDescription("${day.year}年${day.monthValue}月${day.dayOfMonth}日、投稿2件").performClick()
        assertEquals(day,picked)
        compose.onNodeWithText("カレンダー").assertDoesNotExist()
        compose.onNodeWithContentDescription("メニュー").assertWidthIsAtLeast(48.dp).performClick()
        compose.onNodeWithText("バックアップ").performClick()
        assertEquals(1,backups)
        compose.onNodeWithContentDescription("メニュー").performClick()
        compose.onNodeWithText("設定").performClick()
        assertEquals(1,settings)
    }

    @Test fun leapDayCountsDisabledEmptyDatesAndNarrowMonthLayoutStayConsistent() {
        var month by mutableStateOf(YearMonth.of(2024,2))
        var counts by mutableStateOf(mapOf(LocalDate.of(2024,2,29) to 2))
        var selected by mutableStateOf<LocalDate?>(null)
        compose.setContent { MaterialTheme {
            Column(Modifier.width(296.dp).testTag("month")) { CalendarMonth(month,counts,selected,{month=it},{selected=it}) }
        } }
        val leap = "2024年2月29日、投稿2件"
        compose.onNodeWithContentDescription("2024年2月28日、投稿0件").assertIsNotEnabled().performClick()
        assertNull(selected)
        compose.onNodeWithContentDescription(leap).assertIsEnabled().performClick().assertIsSelected()
        assertEquals(LocalDate.of(2024,2,29),selected)
        val bounds=compose.onNodeWithTag("month").fetchSemanticsNode().boundsInRoot
        for(day in 1..29) {
            val count=if(day==29)2 else 0
            val cell=compose.onNodeWithContentDescription("2024年2月${day}日、投稿${count}件").fetchSemanticsNode().boundsInRoot
            assertTrue(cell.left>=bounds.left && cell.right<=bounds.right)
        }
        compose.runOnIdle { counts=mapOf(LocalDate.of(2024,2,29) to 3) }
        compose.onNodeWithContentDescription("2024年2月29日、投稿3件").assertIsDisplayed()
        compose.onNodeWithContentDescription(leap).assertDoesNotExist()
        compose.onNodeWithContentDescription("次の月").performClick()
        compose.onNodeWithText("2024年3月").assertIsDisplayed()
        compose.onNodeWithText("この月の投稿はありません。").assertIsDisplayed()
        compose.onAllNodes(isEnabled() and hasClickAction() and hasContentDescription("投稿",substring=true)).assertCountEquals(0)
        compose.onNodeWithContentDescription("前の月").performClick()
        compose.onNodeWithContentDescription("2024年2月29日、投稿3件").assertIsSelected()
    }

    @Test fun dialogKeepsBrowsedMonthAcrossStateRestorationAndCanDismissWithoutFiltering() {
        val restore=StateRestorationTester(compose)
        var dismissals=0
        restore.setContent { MaterialTheme {
            CalendarDialog(mapOf(LocalDate.of(2026,12,31) to 1),LocalDate.of(2026,12,31),ZoneOffset.UTC,
                {fail("navigation must not select a day")},{dismissals++})
        } }
        compose.onNodeWithContentDescription("次の月").performClick()
        compose.onNodeWithText("2027年1月").assertIsDisplayed()
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("2027年1月").assertIsDisplayed()
        compose.onNodeWithText("最新の記録").performClick()
        compose.onNodeWithText("2026年12月").assertIsDisplayed()
        compose.onNodeWithText("閉じる").performClick()
        assertEquals(1,dismissals)
    }
}
