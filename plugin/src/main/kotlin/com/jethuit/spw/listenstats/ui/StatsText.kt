package com.jethuit.spw.listenstats.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.moriafly.salt.ui.LocalTextStyle
import com.moriafly.salt.ui.Text as SaltText

/** Keep the inherited font while applying this page's sizes, weights and colors. */
@Composable
fun StatsText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = TextStyle.Default,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    textAlign: TextAlign? = null
) {
    SaltText(
        text = text,
        modifier = modifier,
        style = LocalTextStyle.current.merge(style),
        maxLines = maxLines,
        overflow = overflow,
        textAlign = textAlign
    )
}
