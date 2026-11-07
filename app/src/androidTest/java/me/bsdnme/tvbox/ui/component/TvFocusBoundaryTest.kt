package me.bsdnme.tvbox.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import me.bsdnme.tvbox.core.theme.FeigeMediaTheme
import me.bsdnme.tvbox.core.theme.FormFactor
import org.junit.Rule
import org.junit.Test

@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
class TvFocusBoundaryTest {
    @get:Rule val rule = createComposeRule()

    @Test fun overlayKeepsFocusAtEachEdgeAndMovesInside() {
        var clicks = 0
        rule.setContent {
            FeigeMediaTheme(FormFactor.Television) {
                val initial = remember { FocusRequester() }
                Box {
                    // This obscured target sits to the left of the overlay's back button.
                    Text("hidden", Modifier.offset(x = 0.dp, y = 80.dp).size(60.dp).clickable {}.testTag("hidden"))
                    Column(Modifier.offset(x = 100.dp, y = 80.dp).tvFocusBoundary()) {
                        Row {
                            Text("back", Modifier.size(70.dp).focusRequester(initial).clickable { clicks++ }.testTag("back"))
                            Text("next", Modifier.size(70.dp).clickable {}.testTag("next"))
                        }
                        Text("below", Modifier.size(70.dp).clickable {}.testTag("below"))
                    }
                }
                LaunchedEffect(Unit) { initial.requestFocus() }
            }
        }
        fun press(tag: String, key: Key) = rule.onNodeWithTag(tag).performKeyInput { pressKey(key) }
        press("back", Key.DirectionLeft)
        rule.onNodeWithTag("back").assertIsFocused()
        press("back", Key.DirectionUp)
        rule.onNodeWithTag("back").assertIsFocused()
        press("back", Key.DirectionRight)
        rule.onNodeWithTag("next").assertIsFocused()
        press("next", Key.DirectionRight)
        rule.onNodeWithTag("next").assertIsFocused()
        press("next", Key.DirectionLeft)
        press("back", Key.DirectionDown)
        rule.onNodeWithTag("below").assertIsFocused()
        press("below", Key.DirectionDown)
        rule.onNodeWithTag("below").assertIsFocused()
        press("below", Key.DirectionUp)
        press("back", Key.DirectionCenter)
        rule.runOnIdle { check(clicks == 1) }
    }
}
