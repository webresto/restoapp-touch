package cx.m42.restoapp.render

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.composeunstyled.Text
import cx.m42.superizer.theme.AppTheme
import cx.m42.superizer.ui.components.AppButton
import cx.m42.superizer.ui.components.ButtonVariant
import cx.m42.superizer.ui.platform.SystemBackHandler

/**
 * What a screen holds that the server has not got yet.
 *
 * @param save sends it, and calls `saved` only once the server has taken it: a refusal or an invalid
 *   form leaves the person on the screen, with the reason. Null where there is nothing to send in one
 *   go, and the question then offers only to leave or to stay.
 */
public class UnsavedWork(public val save: ((saved: () -> Unit) -> Unit)?)

/** Tells [hold] about the edits for as long as this is composed and they are [dirty]. */
@Composable
public fun HoldUnsaved(dirty: Boolean, hold: (UnsavedWork?) -> Unit, save: ((saved: () -> Unit) -> Unit)?) {
    val latest by rememberUpdatedState(save)
    val savable = save != null
    DisposableEffect(dirty, savable) {
        val send: ((() -> Unit) -> Unit)? = if (savable) { saved -> latest?.invoke(saved) } else null
        hold(if (dirty) UnsavedWork(send) else null)
        onDispose { hold(null) }
    }
}

/** The one question before edits are lost, whichever way out was taken. */
@Composable
internal fun UnsavedQuestion(
    words: RenderWords,
    canSave: Boolean,
    onSave: () -> Unit,
    onLeave: () -> Unit,
    onStay: () -> Unit,
) {
    val tokens = AppTheme
    SystemBackHandler(enabled = true, onBack = onStay)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(tokens.foreground.copy(alpha = 0.35f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onStay)
            .testTag("unsaved:question"),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 360.dp)
                .padding(24.dp)
                .clip(RoundedCornerShape(tokens.radius))
                .background(tokens.background)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = words.unsavedTitle, style = tokens.header, color = tokens.foreground)
            Text(text = words.unsavedHint, style = tokens.body, color = tokens.muted)
            if (canSave) {
                AppButton(
                    text = words.saveAndLeave,
                    onClick = onSave,
                    modifier = Modifier.fillMaxWidth().testTag("unsaved:save"),
                )
            }
            AppButton(
                text = words.leaveWithoutSaving,
                onClick = onLeave,
                variant = ButtonVariant.Destructive,
                modifier = Modifier.fillMaxWidth().testTag("unsaved:leave"),
            )
            AppButton(
                text = words.stay,
                onClick = onStay,
                variant = ButtonVariant.Ghost,
                modifier = Modifier.fillMaxWidth().testTag("unsaved:stay"),
            )
        }
    }
}
