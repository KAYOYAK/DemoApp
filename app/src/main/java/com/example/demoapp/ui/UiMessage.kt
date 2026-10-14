package com.example.demoapp.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.demoapp.R

/**
 * A message for the person using the app.
 *
 * A resource id and its arguments, never a string. The app is in Chinese and has
 * no translation layer, so a message assembled from a literal is a message that
 * will be shown in the wrong language -- and the way that happens is quiet: the
 * screen looks finished, and one status line is in English. Making it impossible
 * to construct one from a literal is the only version of this rule that holds,
 * because the alternative is remembering.
 *
 * [detail] holds the technical text separately -- an exception's message, an HTTP
 * status, a byte count. It is not translated and is not meant to be read as a
 * sentence: it is what someone needs in order to act, or to report a problem. It
 * is kept apart from [textRes] so that a failure has both a sentence a person can
 * read and the specifics a person can use.
 */
data class UiMessage(
    @param:StringRes val textRes: Int,
    val args: List<Any> = emptyList(),
    val detail: String? = null,
) {
    constructor(@StringRes textRes: Int, vararg args: Any) : this(textRes, args.toList())

    /** The same message with the technical part attached. */
    fun withDetail(detail: String?): UiMessage =
        if (detail.isNullOrBlank()) this else copy(detail = detail)
}

/**
 * Renders a [UiMessage]: the sentence in the app's language, and the technical
 * part underneath in a smaller, quieter style.
 *
 * One composable rather than the same three lines at each call site, because the
 * two parts are for different readers and the arrangement is the point. Putting
 * the detail inside the sentence makes a Chinese sentence with an English stack
 * trace through the middle of it; leaving it out leaves a failure nobody can act
 * on. Both mistakes are easy to make once and copy everywhere.
 */
@Composable
fun UiMessageText(
    message: UiMessage,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    errorColor: Color = MaterialTheme.colorScheme.error,
) {
    Column(modifier) {
        Text(
            stringResource(message.textRes, *message.args.toTypedArray()),
            style = MaterialTheme.typography.bodySmall,
            color = errorColor,
        )
        message.detail?.let {
            Spacer(Modifier.height(2.dp))
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = color.takeIf { it != Color.Unspecified }
                    ?: MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
