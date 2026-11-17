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
import cx.m42.superizer.kit.Button
import cx.m42.superizer.kit.ButtonSize
import cx.m42.superizer.kit.ButtonVariant
import cx.m42.superizer.kit.Kit
import cx.m42.superizer.kit.Text
import cx.m42.superizer.kit.TextVariant
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
    SystemBackHandler(enabled = true, onBack = onStay)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Kit.colors.scrim)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onStay)
            .testTag("unsaved:question"),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 360.dp)
                .padding(24.dp)
                .clip(Kit.shapes.xl)
                .background(Kit.colors.background)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = words.unsavedTitle, variant = TextVariant.H4)
            Text(text = words.unsavedHint, variant = TextVariant.Muted, modifier = Modifier.padding(bottom = 8.dp))
            if (canSave) {
                Button(
                    text = words.saveAndLeave,
                    onClick = onSave,
                    size = ButtonSize.Lg,
                    modifier = Modifier.fillMaxWidth().testTag("unsaved:save"),
                )
            }
            Button(
                text = words.leaveWithoutSaving,
                onClick = onLeave,
                variant = ButtonVariant.Destructive,
                size = ButtonSize.Lg,
                modifier = Modifier.fillMaxWidth().testTag("unsaved:leave"),
            )
            Button(
                text = words.stay,
                onClick = onStay,
                variant = ButtonVariant.Ghost,
                size = ButtonSize.Lg,
                modifier = Modifier.fillMaxWidth().testTag("unsaved:stay"),
            )
        }
    }
}
