package ru.savefood.app.feature.needy.find

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

internal fun sanitizeTimeDigits(value: String): String = value.filter(Char::isDigit).take(4)

/** Formats right-aligned time digits: 9 -> 0:9, 930 -> 09:30, 1230 -> 12:30. */
internal fun formatTimeDigits(value: String): String {
    val digits = sanitizeTimeDigits(value)
    return when (digits.length) {
        0 -> ""
        1, 2 -> "0:$digits"
        3 -> "0${digits[0]}:${digits.substring(1)}"
        else -> "${digits.substring(0, 2)}:${digits.substring(2)}"
    }
}

internal object TimeVisualTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val digits = sanitizeTimeDigits(text.text)
        return TransformedText(
            text = AnnotatedString(formatTimeDigits(digits)),
            offsetMapping = TimeOffsetMapping(digits.length),
        )
    }
}

private class TimeOffsetMapping(private val digitCount: Int) : OffsetMapping {
    override fun originalToTransformed(offset: Int): Int {
        val safeOffset = offset.coerceIn(0, digitCount)
        return when (digitCount) {
            0 -> 0
            1, 2 -> safeOffset + 2
            3 -> if (safeOffset == 0) 1 else safeOffset + 2
            else -> if (safeOffset <= 2) safeOffset else safeOffset + 1
        }
    }

    override fun transformedToOriginal(offset: Int): Int {
        return when (digitCount) {
            0 -> 0
            1, 2 -> (offset - 2).coerceIn(0, digitCount)
            3 -> when {
                offset <= 1 -> 0
                offset <= 3 -> 1
                else -> (offset - 2).coerceIn(0, digitCount)
            }
            else -> if (offset <= 2) {
                offset.coerceIn(0, digitCount)
            } else {
                (offset - 1).coerceIn(0, digitCount)
            }
        }
    }
}
