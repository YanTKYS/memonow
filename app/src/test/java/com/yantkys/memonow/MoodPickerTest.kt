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

    @Test fun sixLabeledChoicesWrapInSmallWidthAndToggleWithOneTap() {
        var selected by mutableStateOf<String?>(null)
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(296.dp).testTag("picker")) {
                    MoodPicker(selected, true, { selected = if (selected == it) null else it }, { selected = null })
                }
            }
        }
        compose.onNodeWithText("気分（任意）").assertIsDisplayed()
        val bounds = compose.onNodeWithTag("picker").fetchSemanticsNode().boundsInRoot
        for (mood in Mood.entries) {
            val node = compose.onNodeWithText(mood.display)
            node.assertIsDisplayed().assertIsNotSelected().performClick().assertIsSelected()
            assertEquals(mood.id, selected)
            node.performClick().assertIsNotSelected()
            assertNull(selected)
            val chip = node.fetchSemanticsNode().boundsInRoot
            assertTrue("Chip exceeds small-screen width", chip.left >= bounds.left && chip.right <= bounds.right)
        }
    }

    @Test fun savingDisablesChoicesAndNeverMapsUnknownIdToKnownMood() {
        compose.setContent { MaterialTheme { MoodPicker("future-id", false, { fail("disabled choice changed") }, {}) } }
        for (mood in Mood.entries) compose.onNodeWithText(mood.display).assertIsNotEnabled().assertIsNotSelected()
        compose.onNodeWithText("未対応の気分を解除").assertIsNotEnabled()
    }

    @Test fun optionalBadgeHasNoPlaceholderAndUnknownValueHasNoInventedEmoji() {
        var id by mutableStateOf<String?>(null)
        compose.setContent { MaterialTheme { MoodBadge(id) } }
        for (mood in Mood.entries) compose.onNodeWithText(mood.display).assertDoesNotExist()
        compose.runOnIdle { id = "sad" }
        compose.onNodeWithText("😢 悲しい").assertIsDisplayed()
        compose.runOnIdle { id = "future-id" }
        compose.onNodeWithText("😢 悲しい").assertDoesNotExist()
        compose.onNodeWithText("気分：未対応").assertIsDisplayed()
    }
}
