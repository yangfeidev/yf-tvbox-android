package me.bsdnme.tvbox.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import me.bsdnme.tvbox.core.theme.FeigeMediaTheme
import me.bsdnme.tvbox.core.theme.FormFactor
import me.bsdnme.tvbox.ui.search.SearchScreen
import me.bsdnme.tvbox.vm.AppUiState
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalComposeUiApi::class)
class SearchInitialFocusTest {
    @get:Rule val rule = createComposeRule()

    @Test fun openingSearchFocusesInputAndRequestsKeyboard() {
        var keyboardShows = 0
        val keyboard = object : SoftwareKeyboardController {
            override fun show() { keyboardShows++ }
            override fun hide() = Unit
        }
        rule.setContent {
            FeigeMediaTheme(FormFactor.Television) {
                CompositionLocalProvider(LocalSoftwareKeyboardController provides keyboard) {
                    var searchVisible by remember { mutableStateOf(false) }
                    val homeFocus = remember { FocusRequester() }
                    Box {
                        Box(Modifier.tvFocusBoundary(active = !searchVisible)) {
                            Text("open", Modifier.focusRequester(homeFocus).clickable { searchVisible = true }.testTag("open"))
                        }
                        if (searchVisible) Box(Modifier.tvFocusBoundary()) {
                            SearchScreen(state = AppUiState(), familyMode = true, onOpenDetail = { _, _ -> })
                        }
                    }
                    LaunchedEffect(Unit) { homeFocus.requestFocus() }
                }
            }
        }
        rule.onNodeWithTag("open").assertIsFocused().performClick()
        rule.onNodeWithText("输入片名").assertIsFocused()
        rule.runOnIdle { check(keyboardShows > 0) }
    }
}
