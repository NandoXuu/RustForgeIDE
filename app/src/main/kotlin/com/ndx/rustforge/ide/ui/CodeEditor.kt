package com.ndx.rustforge.ide.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val codeStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, lineHeight = 20.sp)

private val keywords = setOf(
    "fn", "let", "mut", "pub", "struct", "enum", "impl", "trait", "use", "mod", "match", "if", "else",
    "for", "while", "loop", "return", "self", "Self", "const", "static", "async", "await", "move",
    "where", "crate", "super", "as", "in", "true", "false", "type", "dyn", "ref", "unsafe", "extern"
)

private val types = setOf(
    "i8", "i16", "i32", "i64", "i128", "isize", "u8", "u16", "u32", "u64", "u128", "usize",
    "f32", "f64", "bool", "char", "str", "String", "Vec", "Option", "Result", "Box"
)

private val tokens = Regex("""//[^\n]*|"(?:\\.|[^"\\])*"|\b\d[\d_]*(?:\.\d+)?\b|\b[A-Za-z_][A-Za-z0-9_]*\b""")

private class Palette(
    val keyword: Color,
    val type: Color,
    val string: Color,
    val number: Color,
    val comment: Color
)

private fun highlight(text: String, palette: Palette): AnnotatedString = buildAnnotatedString {
    append(text)
    tokens.findAll(text).forEach { match ->
        val token = match.value
        val color = when {
            token.startsWith("//") -> palette.comment
            token.startsWith("\"") -> palette.string
            token[0].isDigit() -> palette.number
            token in keywords -> palette.keyword
            token in types -> palette.type
            else -> null
        }
        if (color != null) addStyle(SpanStyle(color = color), match.range.first, match.range.last + 1)
    }
}

@Composable
fun CodeEditor(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme
    val transformation = remember(cs) {
        val palette = Palette(
            keyword = cs.primary,
            type = cs.tertiary,
            string = cs.secondary,
            number = cs.tertiary,
            comment = cs.onSurfaceVariant
        )
        VisualTransformation { text ->
            TransformedText(highlight(text.text, palette), OffsetMapping.Identity)
        }
    }
    val lineCount = value.text.count { it == '\n' } + 1

    Row(modifier.verticalScroll(rememberScrollState())) {
        Text(
            text = (1..lineCount).joinToString("\n"),
            style = codeStyle.copy(color = cs.onSurfaceVariant),
            textAlign = TextAlign.End,
            modifier = Modifier
                .background(cs.surfaceVariant)
                .padding(horizontal = 10.dp, vertical = 8.dp)
        )
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = codeStyle.copy(color = cs.onSurface),
            cursorBrush = SolidColor(cs.primary),
            visualTransformation = transformation,
            modifier = Modifier.weight(1f).padding(8.dp)
        )
    }
}
