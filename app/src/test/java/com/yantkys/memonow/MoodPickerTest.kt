package com.yantkys.memonow

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.yantkys.memonow.data.Mood
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class MoodPickerTest {
    @get:Rule val compose = createComposeRule()

    @Test fun fourEmojiChoicesFitOneRowWithFullTouchTargetsAndAccessibleToggleState() {
        var selected by mutableStateOf<String?>(null)
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(296.dp).testTag("picker")) {
                    MoodPicker(selected, true, { selected = if (selected == it) null else it }, { selected = null })
                }
            }
        }
        assertEquals(listOf("happy", "angry", "sad", "tired"), Mood.selectable.map { it.id })
        compose.onAllNodes(isToggleable()).assertCountEquals(4)
        compose.onNodeWithTag("picker").assertHeightIsEqualTo(48.dp)
        val bounds = compose.onNodeWithTag("picker").fetchSemanticsNode().boundsInRoot
        for (mood in Mood.selectable) {
            val node = compose.onNodeWithContentDescription(mood.accessibilityName)
            node.assertIsDisplayed().assertIsOff().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            node.performClick().assertIsOn()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "選択中"))
            assertEquals(mood.id, selected)
            node.performClick().assertIsOff()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "未選択"))
            assertNull(selected)
            val button = node.fetchSemanticsNode().boundsInRoot
            assertTrue(button.left >= bounds.left && button.right <= bounds.right)
        }
        // Switching choices is a single tap; no second selected button remains.
        compose.onNodeWithContentDescription("Happy／うれしい").performClick()
        compose.onNodeWithContentDescription("Angry／怒り").performClick()
        compose.onAllNodes(isOn()).assertCountEquals(1)
        compose.onNodeWithContentDescription("Happy／うれしい").assertIsOff()
        compose.onNodeWithContentDescription("Sad／悲しい").assertExists()
        for (mood in Mood.entries) {
            compose.onNodeWithText(mood.label).assertDoesNotExist()
            compose.onNodeWithText(mood.display).assertDoesNotExist()
        }
    }

    @Test fun narrowerWidthWrapsHeadingWithoutShrinkingOrClippingTouchTargets() {
        compose.setContent { MaterialTheme {
            Box(Modifier.width(216.dp).testTag("picker")) { MoodPicker(null, true, {}, {}) }
        } }
        val bounds = compose.onNodeWithTag("picker").fetchSemanticsNode().boundsInRoot
        val buttons = Mood.selectable.map {
            compose.onNodeWithContentDescription(it.accessibilityName).assertIsDisplayed()
                .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).fetchSemanticsNode().boundsInRoot
        }
        assertTrue(buttons.all { it.left >= bounds.left && it.right <= bounds.right })
        assertEquals(1, buttons.map { it.top }.distinct().size)
    }

    @Test fun legacyMoodIsExplicitlyRetainedUntilChangedOrClearedWithoutBecomingAFifthChoice() {
        var selected by mutableStateOf<String?>("fun")
        compose.setContent { MaterialTheme {
            MoodPicker(selected, true, { selected = if (selected == it) null else it }, { selected = null })
        } }
        for (id in listOf("fun", "normal")) {
            val mood = Mood.find(id)!!
            compose.runOnIdle { selected = id }
            compose.onAllNodes(isToggleable()).assertCountEquals(4)
            compose.onAllNodes(isOn()).assertCountEquals(0)
            compose.onNodeWithContentDescription("以前の気分：${mood.accessibilityName}").assertIsDisplayed()
            assertEquals(id, selected)
            compose.onNodeWithText("解除").performClick()
            assertNull(selected)
            compose.onNodeWithContentDescription("以前の気分：${mood.accessibilityName}").assertDoesNotExist()
            compose.runOnIdle { selected = id }
            compose.onNodeWithContentDescription("Happy／うれしい").performClick().assertIsOn()
            assertEquals("happy", selected)
            compose.onNodeWithText("解除").assertDoesNotExist()
        }
    }

    @Test fun savingDisablesChoicesAndLegacyOrUnknownClearing() {
        var selected by mutableStateOf("fun")
        compose.setContent { MaterialTheme { MoodPicker(selected, false, { fail("disabled choice changed") }, {}) } }
        for (mood in Mood.selectable) compose.onNodeWithContentDescription(mood.accessibilityName).assertIsNotEnabled().assertIsOff()
        compose.onNodeWithText("解除").assertIsNotEnabled()
        compose.runOnIdle { selected = "future-id" }
        compose.onNodeWithText("未対応の気分を解除").assertIsNotEnabled()
    }

    @Test fun badgesKeepAllSixEmojiAndAccessibleMeaningsWithoutLabelsOrNullPlaceholder() {
        var id by mutableStateOf<String?>(null)
        compose.setContent { MaterialTheme { MoodBadge(id) } }
        for (mood in Mood.entries) compose.onNodeWithContentDescription("気分：${mood.accessibilityName}").assertDoesNotExist()
        for (mood in Mood.entries) {
            compose.runOnIdle { id = mood.id }
            compose.onNodeWithContentDescription("気分：${mood.accessibilityName}").assertIsDisplayed()
            compose.onNodeWithText(mood.label).assertDoesNotExist()
            compose.onNodeWithText(mood.display).assertDoesNotExist()
        }
        compose.runOnIdle { id = null }
        compose.onNodeWithContentDescription("気分：Tired／疲れた").assertDoesNotExist()
        compose.runOnIdle { id = "future-id" }
        compose.onNodeWithContentDescription("気分：未対応").assertIsDisplayed()
        compose.onNodeWithContentDescription("気分：Happy／うれしい").assertDoesNotExist()
    }
}
