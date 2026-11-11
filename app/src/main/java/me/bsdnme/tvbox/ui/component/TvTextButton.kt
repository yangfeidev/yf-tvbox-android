package me.bsdnme.tvbox.ui.component

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import me.bsdnme.tvbox.core.theme.LocalFormFactor

/** Keeps Material's button behavior while making remote focus visible on television. */
@Composable
fun TvTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val focusStyle = if (LocalFormFactor.current.isTv) Modifier
        .onFocusChanged { focused = it.isFocused }
        .then(if (focused && enabled) Modifier.border(3.dp, Color.White, CircleShape) else Modifier)
    else Modifier
    TextButton(onClick = onClick, modifier = modifier.then(focusStyle), enabled = enabled, content = content)
}
