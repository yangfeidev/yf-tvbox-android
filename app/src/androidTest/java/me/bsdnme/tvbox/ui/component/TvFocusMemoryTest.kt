package me.bsdnme.tvbox.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import me.bsdnme.tvbox.core.theme.FeigeMediaTheme
import me.bsdnme.tvbox.core.theme.FormFactor
import org.junit.Rule
import org.junit.Test

class TvFocusMemoryTest {
    @get:Rule val rule = createComposeRule()

    @Test fun remembersTheControlThatOpenedAnOverlay() {
        val memory = TvFocusMemory()
        var saved: FocusRequester? = null
        rule.setContent {
            FeigeMediaTheme(FormFactor.Television) {
                CompositionLocalProvider(LocalTvFocusMemory provides memory) {
                    val first = remember { FocusRequester() }
                    val second = remember { FocusRequester() }
                    Column {
                        FocusableSurface(onClick = { saved = memory.current; second.requestFocus() },
                            modifier = Modifier.focusRequester(first).testTag("opener")) { Text("opener") }
                        FocusableSurface(onClick = {}, modifier = Modifier.focusRequester(second).testTag("overlay")) { Text("overlay") }
                    }
                    LaunchedEffect(Unit) { first.requestFocus() }
                }
            }
        }
        rule.onNodeWithTag("opener").assertIsFocused().performClick()
        rule.onNodeWithTag("overlay").assertIsFocused()
        rule.runOnIdle { checkNotNull(saved).requestFocus() }
        rule.onNodeWithTag("opener").assertIsFocused()
    }
}
