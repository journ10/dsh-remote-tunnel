package com.dshremote.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val FENCE = Regex("(?s)```([a-zA-Z0-9_+.-]*)\\n?(.*?)```")
private val INLINE = Regex("(\\*\\*[^*]+\\*\\*|`[^`]+`|https?://[^\\s<>()]+)")

@Composable
fun MarkdownContent(
    text: String,
    modifier: Modifier = Modifier,
    onOpenUrl: ((String) -> Unit)? = null,
) {
    val segments = remember(text) { splitFences(text) }
    Column(modifier = modifier) {
        for (segment in segments) {
            when (segment) {
                is Segment.Prose -> SelectionContainer {
                    Text(
                        text = inline(segment.text, onOpenUrl),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                is Segment.Code -> SelectionContainer {
                    Text(
                        text = segment.code,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                            .horizontalScroll(rememberScrollState())
                            .padding(12.dp),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

private sealed interface Segment {
    data class Prose(val text: String) : Segment
    data class Code(val code: String) : Segment
}

private fun splitFences(text: String): List<Segment> {
    val result = mutableListOf<Segment>()
    var cursor = 0
    for (match in FENCE.findAll(text)) {
        val before = text.substring(cursor, match.range.first)
        if (before.isNotBlank()) result.add(Segment.Prose(before.trim()))
        result.add(Segment.Code(match.groupValues[2].trimEnd()))
        cursor = match.range.last + 1
    }
    val tail = text.substring(cursor)
    if (tail.isNotBlank()) result.add(Segment.Prose(tail.trim()))
    if (result.isEmpty()) result.add(Segment.Prose(text))
    return result
}

private fun inline(text: String, onOpenUrl: ((String) -> Unit)?): AnnotatedString = buildAnnotatedString {
    var cursor = 0
    for (match in INLINE.findAll(text)) {
        append(text.substring(cursor, match.range.first))
        val token = match.value
        when {
            token.startsWith("**") && token.endsWith("**") -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                append(token.substring(2, token.length - 2))
            }
            token.startsWith("`") && token.endsWith("`") -> withStyle(
                SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0x1A808080)),
            ) {
                append(token.substring(1, token.length - 1))
            }
            token.startsWith("http://") || token.startsWith("https://") -> {
                val url = token.trimEnd('.', ',', ';', ':', ')', ']')
                val suffix = token.substring(url.length)
                if (onOpenUrl != null) {
                    withLink(
                        LinkAnnotation.Clickable(
                            tag = url,
                            styles = TextLinkStyles(
                                style = SpanStyle(textDecoration = TextDecoration.Underline),
                            ),
                            linkInteractionListener = { onOpenUrl(url) },
                        ),
                    ) { append(url) }
                } else {
                    withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) { append(url) }
                }
                append(suffix)
            }
            else -> append(token)
        }
        cursor = match.range.last + 1
    }
    append(text.substring(cursor))
}
