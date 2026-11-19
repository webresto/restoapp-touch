package cx.m42.restoapp.render

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.TriangleAlert
import cx.m42.superizer.kit.ButtonVariant
import cx.m42.superizer.kit.Dialog
import cx.m42.superizer.kit.Tone
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

/**
 * The one question before edits are lost, whichever way out was taken: the kit's dialog — a sheet on a
 * phone, a card wider — with its three answers, saving first when there is a save to offer.
 */
@Composable
internal fun UnsavedQuestion(
    words: RenderWords,
    canSave: Boolean,
    onSave: () -> Unit,
    onLeave: () -> Unit,
    onStay: () -> Unit,
) {
    SystemBackHandler(enabled = true, onBack = onStay)
    Dialog(
        onDismiss = onStay,
        title = words.unsavedTitle,
        description = words.unsavedHint,
        icon = Lucide.TriangleAlert,
        tone = Tone.Warn,
        footer = {
            Box(Modifier.testTag("unsaved:question")) {
                DialogButtons(
                    listOfNotNull(
                        if (canSave) DialogButton(words.saveAndLeave, ButtonVariant.Default, "unsaved:save", onSave) else null,
                        DialogButton(words.leaveWithoutSaving, ButtonVariant.Destructive, "unsaved:leave", onLeave),
                        DialogButton(words.stay, ButtonVariant.Outline, "unsaved:stay", onStay),
                    ),
                )
            }
        },
    )
}
