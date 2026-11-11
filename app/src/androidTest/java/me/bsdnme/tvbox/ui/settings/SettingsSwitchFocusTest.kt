package me.bsdnme.tvbox.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import me.bsdnme.tvbox.core.theme.FeigeMediaTheme
import me.bsdnme.tvbox.core.theme.FormFactor
import org.junit.Rule
import org.junit.Test

@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
class SettingsSwitchFocusTest {
    @get:Rule val rule = createComposeRule()

    @Test fun remoteOkTogglesTheFocusedSwitchOnce() {
        var changes = 0
        rule.setContent {
            FeigeMediaTheme(FormFactor.Television) {
                var checked by remember { mutableStateOf(false) }
                SwitchRow("测试开关", "测试说明", checked) { checked = it; changes++ }
            }
        }
        val switch = rule.onNodeWithContentDescription("测试开关")
        switch.performSemanticsAction(SemanticsActions.RequestFocus)
        switch.assertIsFocused().assertIsOff()
        switch.performKeyInput { pressKey(Key.DirectionCenter) }
        switch.assertIsFocused().assertIsOn()
        rule.runOnIdle { check(changes == 1) }
    }
}
