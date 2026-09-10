package ru.savefood.app.core.designsystem.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.savefood.app.R

@Composable
fun BrandLockup(
    modifier: Modifier = Modifier,
    markSize: Dp = 42.dp,
    compact: Boolean = false,
) {
    val name = stringResource(R.string.app_name)
    val split = name.indexOf("Food", ignoreCase = true).takeIf { it > 0 } ?: name.length
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier.size(markSize),
        )
        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurface)) {
                    append(name.substring(0, split))
                }
                withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) {
                    append(name.substring(split))
                }
            },
            fontWeight = FontWeight.ExtraBold,
            fontSize = if (compact) 20.sp else 29.sp,
            letterSpacing = (-0.8).sp,
        )
    }
}
