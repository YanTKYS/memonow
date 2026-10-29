package com.yantkys.memonow

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.yantkys.memonow.data.Post
import java.time.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
class PostTimelineTest {
    @get:Rule val compose=createComposeRule()
    private val day=LocalDate.of(1970,1,1)
    private val posts=(0 until 100).map { Post(sequence=it+1L,id="$it",body="記録 $it",createdAt=if(it<50)0 else 86_400_000L,moodId="fun") }
    private fun ready(rows:List<Post> = posts, selected:LocalDate?=null,zone:ZoneId=ZoneOffset.UTC):FeedState.Ready {
        val index=PostDays(rows,zone)
        return FeedState.Ready(rows,selected,index.counts,index.visible(rows,selected),zone)
    }
    private fun position()=compose.onNodeWithTag("post-timeline").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()

    @Test fun filterRestorationAndMetadataDoNotMoveTheAnchorAndAllRestoresItsOwnPosition() {
        var state by mutableStateOf<FeedState>(ready())
        var edited:String?=null
        val restore=StateRestorationTester(compose)
        restore.setContent { MaterialTheme {
            PostTimeline(state,null,false,{}, {state=ready()}, {}, {edited=it.id}, {},Modifier.width(320.dp).height(360.dp))
        } }
        compose.onNodeWithTag("post-99").assertIsDisplayed()
        compose.onNodeWithTag("post-timeline").performScrollToIndex(20)
        val allPosition=position()
        compose.runOnIdle { state=ready(selected=day) }
        compose.onNodeWithText("1970年1月1日 · 50件").assertIsDisplayed()
        compose.onNodeWithTag("post-0").assertIsDisplayed()
        compose.onNodeWithTag("post-99").assertDoesNotExist()
        compose.onNodeWithTag("post-timeline").performScrollToIndex(10)
        val filteredPosition=position()
        restore.emulateSavedInstanceStateRestore()
        assertEquals(filteredPosition,position())
        compose.onNode(hasText("編集") and hasAnyAncestor(hasTestTag("post-10"))).performClick()
        assertEquals("10",edited)
        compose.runOnIdle { state=ready(posts.map { if(it.id=="0")it.copy(body="変わった本文",placeName="沖縄")else it },day) }
        assertEquals(filteredPosition,position())
        compose.onNodeWithText("全件表示").performClick()
        compose.onNodeWithText("全件表示").assertDoesNotExist()
        assertEquals(allPosition,position())
    }

    @Test fun newPostAfterFilteringScrollsOnceAndLaterRecreationOrMetadataDoesNotScrollAgain() {
        var state by mutableStateOf<FeedState>(ready())
        var sentId by mutableStateOf<String?>(null)
        val consumed=mutableListOf<String>()
        val restore=StateRestorationTester(compose)
        restore.setContent { MaterialTheme {
            PostTimeline(state,sentId,false,{consumed+=it;sentId=null},{state=ready()},{},{},{},Modifier.height(360.dp))
        } }
        compose.runOnIdle { state=ready(selected=day) }
        val added=Post(sequence=101,id="new",body="いまの投稿",createdAt=172_800_000)
        // First remove the filter; the Room row may arrive after the sent-ID event.
        compose.runOnIdle { state=ready();sentId=added.id }
        compose.runOnIdle { state=ready(posts+added) }
        compose.waitForIdle()
        compose.onNodeWithTag("post-new").assertIsDisplayed()
        assertEquals(listOf("new"),consumed)
        compose.onNodeWithTag("post-timeline").performScrollToIndex(30)
        val anchor=position()
        compose.runOnIdle { state=ready((posts+added).map { it.copy(placeName="属性だけ更新") }) }
        assertEquals(anchor,position())
        restore.emulateSavedInstanceStateRestore()
        assertEquals(anchor,position())
        assertEquals(listOf("new"),consumed)
    }

    @Test fun aRestoredFilteredPositionIsNotConsumedByTheInitialLoadingFrame() {
        var restored=false
        var loaded by mutableStateOf(false)
        val restore=StateRestorationTester(compose)
        restore.setContent { MaterialTheme {
            DisposableEffect(Unit) { onDispose { restored=true } }
            PostTimeline(if(restored && !loaded) FeedState.Loading else ready(selected=day),null,false,{},{},{},{},{},Modifier.height(360.dp))
        } }
        compose.onNodeWithTag("post-timeline").performScrollToIndex(15)
        val anchor=position()
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("post-timeline").assertDoesNotExist()
        compose.runOnIdle { loaded=true }
        assertEquals(anchor,position())
    }

    @Test fun rowTimestampAndSelectedDayBothFollowTheSameChangedTimeZone() {
        val previous=java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.US)
            val row=Post(id="boundary",body="年境界の記録",createdAt=Instant.parse("2026-12-31T15:00:00Z").toEpochMilli(),moodId="normal")
            var state by mutableStateOf<FeedState>(ready(listOf(row),LocalDate.of(2027,1,1),ZoneId.of("Asia/Tokyo")))
            compose.setContent { MaterialTheme {
                PostTimeline(state,null,false,{},{},{},{},{},Modifier.height(360.dp))
            } }
            compose.onNodeWithText("2027年1月1日 · 1件").assertIsDisplayed()
            compose.onNode(hasText("2027",substring=true) and hasAnyAncestor(hasTestTag("post-boundary"))).assertIsDisplayed()
            compose.runOnIdle { state=ready(listOf(row),LocalDate.of(2026,12,31),ZoneOffset.UTC) }
            compose.onNodeWithText("2026年12月31日 · 1件").assertIsDisplayed()
            compose.onNode(hasText("2026",substring=true) and hasAnyAncestor(hasTestTag("post-boundary"))).assertIsDisplayed()
            compose.onNode(hasText("2027",substring=true) and hasAnyAncestor(hasTestTag("post-boundary"))).assertDoesNotExist()
            compose.onNodeWithContentDescription("気分：Normal／普通").assertIsDisplayed()
        } finally { java.util.Locale.setDefault(previous) }
    }
}
