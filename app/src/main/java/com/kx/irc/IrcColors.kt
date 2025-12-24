package com.kx.irc

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration

private val MircColors = mapOf(
    0 to Color(0xFFFFFFFF),
    1 to Color(0xFF000000),
    2 to Color(0xFF00007F),
    3 to Color(0xFF009300),
    4 to Color(0xFFFF0000),
    5 to Color(0xFF7F0000),
    6 to Color(0xFF9C009C),
    7 to Color(0xFFFC7F00),
    8 to Color(0xFFFFFF00),
    9 to Color(0xFF00FC00),
    10 to Color(0xFF009393),
    11 to Color(0xFF00FFFF),
    12 to Color(0xFF0000FC),
    13 to Color(0xFFFF00FF),
    14 to Color(0xFF7F7F7F),
    15 to Color(0xFFD2D2D2)
)

private data class StyleState(
    val fg: Color? = null,
    val bg: Color? = null,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false
)

fun buildStyledMessage(message: String): AnnotatedString {
    val builder = AnnotatedString.Builder()
    var state = StyleState()
    var index = 0

    fun applyStyle(text: String) {
        if (text.isEmpty()) return
        val style = SpanStyle(
            color = state.fg ?: Color.Unspecified,
            background = state.bg ?: Color.Unspecified,
            fontWeight = if (state.bold) FontWeight.Bold else null,
            fontStyle = if (state.italic) FontStyle.Italic else null,
            textDecoration = if (state.underline) TextDecoration.Underline else null
        )
        val start = builder.length
        builder.append(text)
        builder.addStyle(style, start, builder.length)
    }

    val buffer = StringBuilder()
    while (index < message.length) {
        val ch = message[index]
        when (ch) {
            '\u0002' -> { // bold
                applyStyle(buffer.toString())
                buffer.clear()
                state = state.copy(bold = !state.bold)
                index += 1
            }
            '\u001d' -> { // italic
                applyStyle(buffer.toString())
                buffer.clear()
                state = state.copy(italic = !state.italic)
                index += 1
            }
            '\u001f' -> { // underline
                applyStyle(buffer.toString())
                buffer.clear()
                state = state.copy(underline = !state.underline)
                index += 1
            }
            '\u000f' -> { // reset
                applyStyle(buffer.toString())
                buffer.clear()
                state = StyleState()
                index += 1
            }
            '\u0003' -> { // color
                applyStyle(buffer.toString())
                buffer.clear()
                val (fg, bg, consumed) = parseColorCodes(message, index + 1)
                state = state.copy(fg = fg ?: state.fg, bg = bg ?: state.bg)
                index += consumed + 1
            }
            else -> {
                buffer.append(ch)
                index += 1
            }
        }
    }

    applyStyle(buffer.toString())
    return builder.toAnnotatedString()
}

private fun parseColorCodes(text: String, start: Int): Triple<Color?, Color?, Int> {
    if (start >= text.length) return Triple(null, null, 0)

    var idx = start
    val fgDigits = StringBuilder()
    while (idx < text.length && fgDigits.length < 2 && text[idx].isDigit()) {
        fgDigits.append(text[idx])
        idx += 1
    }

    val fg = fgDigits.toString().toIntOrNull()?.let { MircColors[it] }
    var bg: Color? = null

    if (idx < text.length && text[idx] == ',') {
        idx += 1
        val bgDigits = StringBuilder()
        while (idx < text.length && bgDigits.length < 2 && text[idx].isDigit()) {
            bgDigits.append(text[idx])
            idx += 1
        }
        bg = bgDigits.toString().toIntOrNull()?.let { MircColors[it] }
    }

    return Triple(fg, bg, idx - start)
}
