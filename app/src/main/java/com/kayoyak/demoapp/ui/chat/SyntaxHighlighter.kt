package com.kayoyak.demoapp.ui.chat

import androidx.compose.ui.graphics.Color

/**
 * Colouring a code block, without a highlighter library.
 *
 * ## Why not a library
 *
 * The good ones lex a language properly -- every operator, every literal, every
 * corner of the grammar -- and they are large, and most of them run JavaScript
 * through a WebView or a bundled engine. That is a heavy thing to add to an app
 * whose whole point is that it does not need much.
 *
 * What a model writes in a chat is not a file. It is a few dozen lines of an obvious
 * language, and what a reader needs from the colour is where the strings end, where
 * the comments are, and which words are the language rather than the author's. That
 * is four categories, and four categories are a scanner rather than a parser.
 *
 * ## What it will get wrong
 *
 * A keyword inside a string that a proper lexer would know is a string, and a
 * regex containing a `#` that this reads as a comment. The first is rare and the
 * second is ugly. Neither is worth a megabyte.
 *
 * The scan is single-pass and never rewinds: what it has consumed is a token, and
 * the token is coloured once. A highlighter that rescaned would be quadratic on a
 * long listing, which is exactly the case where the user notices.
 */
object SyntaxHighlighter {

    /** What a token is, for the caller that decides the colours. */
    enum class Token {
        KEYWORD,
        STRING,
        COMMENT,
        NUMBER,
        PLAIN,
    }

    /** A run of text and what it is. */
    data class Span(val text: String, val token: Token)

    /**
     * The words that are the language rather than the author.
     *
     * One set for all of them, deliberately. A block labelled `kotlin` and one
     * labelled `java` share almost all of these, and the words that differ --
     * `fun`/`def`, `val`/`const` -- are recognised whichever language is named.
     * Per-language sets would be three times the table for a difference nobody sees.
     */
    private val KEYWORDS = setOf(
        // Declarations and control flow, in the languages a model writes.
        "fun", "def", "function", "class", "object", "interface", "enum", "struct",
        "if", "else", "elif", "when", "switch", "case", "default",
        "for", "while", "do", "break", "continue", "return", "yield", "throw",
        "try", "catch", "finally", "except", "raise", "with", "as", "in", "is",
        "val", "var", "let", "const", "final", "static", "public", "private",
        "protected", "internal", "open", "override", "abstract", "sealed",
        "import", "package", "from", "use", "include", "export", "require",
        "new", "this", "self", "super", "null", "nil", "None", "true", "false",
        "True", "False", "and", "or", "not", "async", "await", "suspend", "lambda",
        // Types that read as keywords in a listing.
        "int", "long", "float", "double", "boolean", "bool", "String", "str",
        "void", "char", "byte", "short", "list", "dict", "set", "tuple",
    )

    /**
     * Splits [code] into coloured runs.
     *
     * [language] is not used to pick a grammar -- there is only one. It is accepted
     * so a caller can pass what the fence said, and ignored, so that a block labelled
     * with something nobody has heard of is coloured by the same rules as one
     * labelled `python` rather than being left grey.
     */
    fun spans(code: String): List<Span> {
        val out = mutableListOf<Span>()
        val plain = StringBuilder()

        fun flush() {
            if (plain.isNotEmpty()) {
                out += Span(plain.toString(), Token.PLAIN)
                plain.clear()
            }
        }

        var i = 0
        while (i < code.length) {
            val c = code[i]

            // A comment runs to the end of the line, in every notation below.
            val commentStart = when {
                c == '#' -> true
                c == '/' && code.getOrNull(i + 1) == '/' -> true
                else -> false
            }
            if (commentStart) {
                flush()
                val end = code.indexOf('\n', i).let { if (it < 0) code.length else it }
                out += Span(code.substring(i, end), Token.COMMENT)
                i = end
                continue
            }

            // A string, opened by a quote and closed by the same one. The escapes are
            // skipped so that "a \" in a string" does not end at the wrong quote.
            if (c == '"' || c == '\'' || c == '`') {
                flush()
                var j = i + 1
                while (j < code.length) {
                    if (code[j] == '\\') {
                        j += 2
                        continue
                    }
                    if (code[j] == c) {
                        j++
                        break
                    }
                    // An unterminated string ends at the line. A model that emitted an
                    // opening quote and no closing one should not turn the rest of the
                    // listing green.
                    if (code[j] == '\n') break
                    j++
                }
                out += Span(code.substring(i, minOf(j, code.length)), Token.STRING)
                i = minOf(j, code.length)
                continue
            }

            // A number. Includes the forms that start with a digit and run into
            // letters, which is what hex and long suffixes look like.
            if (c.isDigit() && (i == 0 || !isWordChar(code[i - 1]))) {
                flush()
                var j = i
                while (j < code.length && (code[j].isLetterOrDigit() || code[j] in "._")) j++
                out += Span(code.substring(i, j), Token.NUMBER)
                i = j
                continue
            }

            // An identifier, which is a keyword or is not.
            if (isWordChar(c) && !c.isDigit()) {
                var j = i
                while (j < code.length && isWordChar(code[j])) j++
                val word = code.substring(i, j)
                if (word in KEYWORDS) {
                    flush()
                    out += Span(word, Token.KEYWORD)
                } else {
                    plain.append(word)
                }
                i = j
                continue
            }

            plain.append(c)
            i++
        }

        flush()
        return out
    }

    private fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_'

    /**
     * The colour for a token.
     *
     * Passed in rather than fixed here, because the palette depends on the theme and
     * this file knows nothing about Compose's colours. The caller decides; the
     * scanner only says what a run is.
     */
    fun colourFor(token: Token, palette: Palette): Color = when (token) {
        Token.KEYWORD -> palette.keyword
        Token.STRING -> palette.string
        Token.COMMENT -> palette.comment
        Token.NUMBER -> palette.number
        Token.PLAIN -> palette.plain
    }

    /** The five colours a listing needs. */
    data class Palette(
        val keyword: Color,
        val string: Color,
        val comment: Color,
        val number: Color,
        val plain: Color,
    )
}
