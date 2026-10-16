package com.kayoyak.demoapp.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import com.kayoyak.demoapp.R
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/** A reply split into the parts that need different treatment. */
sealed interface ReplySegment {
    data class Prose(val text: String) : ReplySegment

    /** A fenced block, with the language if one was named. */
    data class Code(val text: String, val language: String?) : ReplySegment

    /** A `#` line. [level] is 1..6, as written. */
    data class Heading(val level: Int, val text: String) : ReplySegment

    /**
     * A run of list items.
     *
     * One segment for the whole run rather than one per item, because a numbered
     * list is a single thing: rendering each item alone would renumber it, and
     * `3.` would appear as `1.`.
     */
    data class Bullets(val items: List<String>, val ordered: Boolean) : ReplySegment

    /** A `>` line or run of them. */
    data class Quote(val text: String) : ReplySegment

    /**
     * A pipe table.
     *
     * Kept as rows of cells rather than as text, because a table that has been
     * parsed is one that can be laid out. Rendering the pipes as prose is what the
     * app did before, and a four-column table arrived as a paragraph of vertical
     * bars.
     *
     * The separator row is dropped here: `|---|---|` is syntax, not content.
     */
    data class Table(val header: List<String>, val rows: List<List<String>>) : ReplySegment
}

/**
 * Splits a reply into the parts that need different treatment.
 *
 * Still not a Markdown implementation, and the difference matters. This handles
 * the constructs that are **unambiguous when partly written** and that a small
 * model actually emits: fenced code, headings, lists, quotes, and inline
 * emphasis. It does not handle tables, images, footnotes, HTML, or nested
 * lists -- each of which needs a real parser to avoid turning text into
 * something worse than the characters it replaced.
 *
 * The rule for anything not handled is that it stays exactly as written. A reply
 * full of pipes and dashes shows as pipes and dashes, which is ugly and honest,
 * rather than as a table with columns in the wrong place, which is wrong.
 *
 * An unterminated fence is treated as prose from the fence onwards. A model cut
 * off mid-block would otherwise have its last words shown in a code font for no
 * reason.
 */
fun splitReply(text: String): List<ReplySegment> {
    val segments = mutableListOf<ReplySegment>()
    val lines = text.split('\n')

    var prose = StringBuilder()
    var code: StringBuilder? = null
    var language: String? = null
    // Accumulated across consecutive lines so a list or a quote is one segment.
    val block = mutableListOf<String>()
    var blockKind: String? = null

    fun flushProse() {
        // Trimmed before the emptiness test, not after. A run of blank lines
        // passes a check made on the untrimmed text and is then emitted as an
        // empty prose segment, which draws as a gap in the reply.
        val body = prose.toString().trim('\n')
        if (body.isNotEmpty()) segments += ReplySegment.Prose(body)
        prose = StringBuilder()
    }

    fun flushBlock() {
        if (block.isEmpty()) {
            blockKind = null
            return
        }
        when (blockKind) {
            "bullet" -> segments += ReplySegment.Bullets(block.toList(), ordered = false)
            "number" -> segments += ReplySegment.Bullets(block.toList(), ordered = true)
            "quote" -> segments += ReplySegment.Quote(block.joinToString("\n"))
            "heading" -> {
                val raw = block.first()
                val level = raw.takeWhile { it == '#' }.length.coerceIn(1, 6)
                segments += ReplySegment.Heading(level, raw.drop(level).trim())
            }
            "table" -> parseTable(block)?.let { segments += it }
        }
        block.clear()
        blockKind = null
    }

    for (line in lines) {
        val fence = line.trimStart()
        val isFence = fence.startsWith("```")
        val marker = if (isFence) fence.removePrefix("```").trim() else ""

        if (code != null) {
            // Inside a fence nothing is markup. A `#` in a shell script is a
            // comment, and turning it into a heading would corrupt the listing.
            when {
                isFence && marker.isEmpty() -> {
                    val contents = code.toString().trim('\n')
                    // An empty fence is two markers and nothing between them. A
                    // grey panel with no text is worse than the backticks.
                    if (contents.isNotEmpty()) segments += ReplySegment.Code(contents, language)
                    code = null
                    language = null
                }
                else -> code.appendLine(line)
            }
            continue
        }

        if (isFence) {
            flushBlock()
            flushProse()
            code = StringBuilder()
            language = marker.ifBlank { null }
            continue
        }

        val trimmed = line.trim()
        // A blank line ends whatever was being accumulated, which is what makes
        // two adjacent lists two lists rather than one odd one.
        if (trimmed.isEmpty()) {
            flushBlock()
            prose.appendLine(line)
            continue
        }

        val tableRow = trimmed.startsWith("|")
        val heading = HEADING.matchEntire(trimmed)
        val bullet = BULLET.matchEntire(trimmed)
        val number = NUMBER.matchEntire(trimmed)
        val quote = QUOTE.matchEntire(trimmed)

        when {
            tableRow -> {
                // Only the first row ends what came before. Flushing on every row
                // discards the header the moment the separator arrives, and the
                // table parses as a one-line block that has no header -- which
                // renders as nothing at all.
                if (blockKind != "table") {
                    flushBlock()
                    flushProse()
                    blockKind = "table"
                }
                block += trimmed
            }
            heading != null -> {
                flushBlock()
                flushProse()
                blockKind = "heading"
                block += trimmed
                flushBlock()
            }
            bullet != null -> {
                if (blockKind != "bullet") {
                    flushBlock()
                    flushProse()
                    blockKind = "bullet"
                }
                block += bullet.groupValues[1].trim()
            }
            number != null -> {
                if (blockKind != "number") {
                    flushBlock()
                    flushProse()
                    blockKind = "number"
                }
                block += number.groupValues[1].trim()
            }
            quote != null -> {
                if (blockKind != "quote") {
                    flushBlock()
                    flushProse()
                    blockKind = "quote"
                }
                block += quote.groupValues[1].trim()
            }
            else -> {
                flushBlock()
                prose.appendLine(line)
            }
        }
    }

    // A block that never closed.
    code?.let {
        val contents = it.toString().trim('\n')
        if (contents.isNotEmpty()) segments += ReplySegment.Prose(contents)
    }
    flushBlock()
    flushProse()

    return segments.ifEmpty { listOf(ReplySegment.Prose(text)) }
}

// Anchored and single-line on purpose: a list marker mid-sentence is punctuation,
// and a `#` after a word is a hash. Matching those turned prose into structure.
private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
private val BULLET = Regex("^[-*+]\\s+(.*)$")
private val NUMBER = Regex("^\\d{1,3}[.)]\\s+(.*)$")
private val QUOTE = Regex("^>\\s?(.*)$")

/**
 * Renders the inline emphasis a model writes inside a line.
 *
 * Corrupts nothing it does not understand: an unmatched `*` or a lone backtick is
 * left as the character it is. Balanced markers only -- a rule that closes
 * whatever it finds turns `2 * 3 * 4` into `2 3 4` in italic.
 */
fun parseInline(text: String): AnnotatedString = buildAnnotatedString {
    // A marker is a backtick or an asterisk, and nothing else in a line can begin
    // a span. Scanning for those two characters and testing the flanking rule
    // directly is what this replaced: the three patterns all started with a
    // lookbehind over `[\w*]`, which is unanchored, so the engine tried every
    // position in the string as a possible start -- and `find` was called from the
    // current offset on every iteration. A 42,000-character line measured 2.2
    // seconds for one call, on the frame thread, in a composable that a streaming
    // reply re-enters on every token.
    //
    // The rules are the same ones the patterns expressed:
    //
    //   * an opening marker is not preceded by a word character or another
    //     asterisk, and is not followed by whitespace;
    //   * a closing marker is not preceded by whitespace, and is not followed by
    //     either.
    //
    // `\w` is deliberately the ASCII one, as it was before: it excludes `2*3*4`
    // and `a*b*c`, which read as formulas far more often than as emphasis, while
    // leaving `这是*重点*内容` alone, because Chinese characters are not `\w` and a
    // language without spaces cannot be required to put one before a marker.
    var index = 0
    while (index < text.length) {
        val marker = nextMarker(text, index)
        if (marker == null) {
            append(text, index, text.length)
            return@buildAnnotatedString
        }

        // No marker starts here. Emit up to the one that does.
        if (marker.start != index) {
            append(text, index, marker.start)
            index = marker.start
            continue
        }

        withStyle(marker.style) { append(text, marker.body.first, marker.body.last + 1) }
        index = marker.end
    }
}

/** Where a span starts and ends, and how it is drawn. */
private class Marker(
    val start: Int,
    /** The inner text, as a range into the original string. */
    val body: IntRange,
    /** One past the closing marker. */
    val end: Int,
    val style: SpanStyle,
)

/**
 * The first span that starts at or after [from], or null when the rest is plain.
 *
 * Returns the earliest of the three kinds rather than the first found, which is
 * what the old code got by taking the minimum of three `find` results.
 */
private fun nextMarker(text: String, from: Int): Marker? {
    var i = from
    while (i < text.length) {
        val c = text[i]
        if (c != '`' && c != '*') {
            i++
            continue
        }

        if (c == '`') {
            // Code first, and without the flanking rule: a model writing about
            // `**kwargs` means the literal characters, and a backtick has no
            // emphasis ambiguity to resolve.
            val close = text.indexOf('`', i + 1)
            if (close > i + 1) return Marker(i, (i + 1)..(close - 1), close + 1, CODE_STYLE)
            i++
            continue
        }

        // An asterisk. `**` is bold and a single one is italic, so the run length
        // decides which -- and a run of three or more is neither.
        val runLength = text.asterisksAt(i)
        val opening = i == 0 || !text.isWordOrAsterisk(i - 1)
        if (!opening || i + runLength >= text.length || text[i + runLength].isWhitespace()) {
            i += runLength
            continue
        }

        for (candidate in runLength downTo 1) {
            val bodyStart = i + candidate
            val close = text.findClosing(candidate, bodyStart)
            if (close >= 0) {
                val style = if (candidate == 2) BOLD_STYLE else ITALIC_STYLE
                return Marker(i, bodyStart..(close - 1), close + candidate, style)
            }
        }
        i += runLength
    }
    return null
}

/** How many asterisks in a row start at [at]. */
private fun String.asterisksAt(at: Int): Int {
    var n = 0
    while (at + n < length && this[at + n] == '*') n++
    return n
}

/** A closing run of [length] asterisks at or after [from], or -1. */
private fun String.findClosing(length: Int, from: Int): Int {
    var i = from
    while (true) {
        i = indexOf('*', i)
        if (i < 0) return -1
        // Flanking: the character before the closing run may not be whitespace.
        if (i == from || !this[i - 1].isWhitespace()) {
            val runLength = asterisksAt(i)
            if (runLength >= length) return i
            i += runLength
            continue
        }
        i++
    }
}

private fun String.isWordOrAsterisk(at: Int): Boolean {
    val c = this[at]
    // ASCII, because that is what the pattern's `\w` meant. A Chinese character
    // here would make `这是*重点*` unparseable.
    return c == '*' || c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_'
}

private val CODE_STYLE = SpanStyle(fontFamily = FontFamily.Monospace)
private val BOLD_STYLE = SpanStyle(fontWeight = FontWeight.Bold)
private val ITALIC_STYLE = SpanStyle(fontStyle = FontStyle.Italic)

/**
 * Renders a reply, with structure set apart.
 *
 * Code scrolls sideways rather than wrapping: a wrapped line of code reads as two
 * lines that are both wrong, and copying either one gives something that does not
 * run.
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    /** Called with a code block's text when the reader asks for it. */
    onCopyCode: (String) -> Unit = {},
    /** Copies one paragraph of a reply, without the rest of it. */
    onCopyParagraph: (String) -> Unit = {},
) {
    val segments = remember(text) { splitReply(text) }

    if (segments.size == 1 && segments[0] is ReplySegment.Prose) {
        Text(
            parseInline((segments[0] as ReplySegment.Prose).text),
            modifier = modifier,
            style = MaterialTheme.typography.bodyMedium,
        )
        return
    }

    Column(modifier) {
        segments.forEachIndexed { index, segment ->
            if (index > 0) Spacer(Modifier.height(6.dp))
            when (segment) {
                is ReplySegment.Prose ->
                    // Long-press a paragraph to copy that paragraph.
                    //
                    // The alternative was a drag across the text, which works and is
                    // fiddly on a phone for anything longer than a line. The paragraph
                    // is already a unit -- the parser produced it -- and a press is the
                    // gesture people try first on a block of text.
                    //
                    // Copying immediately rather than opening a menu: the menu belongs
                    // to the whole message and already exists one gesture away, and a
                    // second menu that looks like the first is one nobody can tell
                    // apart.
                    Text(
                        parseInline(segment.text),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.combinedClickable(
                            onClick = {},
                            onLongClick = { onCopyParagraph(segment.text) },
                        ),
                    )

                is ReplySegment.Heading -> Text(
                    parseInline(segment.text),
                    style =
                        when (segment.level) {
                            1 -> MaterialTheme.typography.titleLarge
                            2 -> MaterialTheme.typography.titleMedium
                            else -> MaterialTheme.typography.titleSmall
                        },
                )

                is ReplySegment.Quote ->
                    Row(Modifier.fillMaxWidth()) {
                        // A rule down the side rather than a coloured panel: a
                        // quote is set apart from the reply, not raised above it.
                        Column(
                            Modifier
                                .width(3.dp)
                                .height(if (segment.text.length > 60) 48.dp else 20.dp)
                                .background(MaterialTheme.colorScheme.outlineVariant),
                        ) {}
                        Spacer(Modifier.width(8.dp))
                        Text(
                            parseInline(segment.text),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                is ReplySegment.Bullets ->
                    Column {
                        segment.items.forEachIndexed { position, item ->
                            Row(Modifier.fillMaxWidth()) {
                                Text(
                                    if (segment.ordered) "${position + 1}. " else "• ",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(parseInline(item), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }

                is ReplySegment.Code -> Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        segment.language?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        // Copying the block, not the whole reply.
                        //
                        // A reply with five code blocks in it is the normal shape of
                        // an answer to a programming question, and copying the whole
                        // thing to keep one of them means pasting five into an editor
                        // and cutting four out. The text is still selectable; this is
                        // for the case where the block is the thing you wanted.
                        TextButton(onClick = { onCopyCode(segment.text) }) {
                            Text(
                                stringResource(R.string.markdown_copy_code),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                    // Coloured, in the same monospace the block already used.
                    //
                    // The colour is built here because this is where the theme is:
                    // the scanner says what a run is, and knows nothing about
                    // Compose's colours or about which theme is on.
                    Text(
                        highlighted(segment.text),
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    )
                }

                is ReplySegment.Table -> TableBlock(segment)
            }
        }
    }
}


/**
 * Reads the accumulated lines of a pipe table.
 *
 * Two lines at least, and the second has to be a separator. That test is what
 * keeps a paragraph that merely contains a `|` from becoming a one-row table with
 * no header -- which would render as a stray box around a sentence.
 *
 * Ragged rows are padded rather than dropped. A model that emits a row with one
 * cell missing has still said something, and discarding the row would lose it;
 * an empty cell says the same thing without pretending the text is not there.
 */
private fun parseTable(lines: List<String>): ReplySegment.Table? {
    if (lines.size < 2) return null
    if (!TABLE_SEPARATOR.matches(lines[1].trim())) return null

    fun cells(line: String): List<String> =
        line.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }

    val header = cells(lines[0])
    val rows = lines.drop(2).map { cells(it) }
    if (header.isEmpty()) return null

    val width = (header.size).coerceAtLeast(rows.maxOfOrNull { it.size } ?: 0)
    return ReplySegment.Table(
        header = header + List(width - header.size) { "" },
        rows = rows.map { it + List(width - it.size) { "" } },
    )
}

/** `|---|---|`, `| :-- | --: |`, and the other ways a separator row is written. */
/**
 * `|---|`, `| --- | --- |`, `|:--|--:|`, and the other ways a separator row is
 * written.
 *
 * Written so a single-column table matches. An earlier version required a dash run
 * after an alternation that had already consumed one, so `|---|` -- which is what a
 * one-column table looks like -- backtracked into no match at all, and that table
 * silently stayed prose.
 */
private val TABLE_SEPARATOR = Regex("""^\|?\s*:?-{1,}:?\s*(\|\s*:?-{1,}:?\s*)*\|?$""")

/**
 * A table, scrolling sideways when it is wider than the screen.
 *
 * Sideways rather than wrapped. A wrapped table stops being a table: the columns
 * stop lining up, and a cell reading "3.2 GB" becomes "3.2" above "GB" in a column
 * of unrelated text. A phone cannot show four columns at once, so the choice is
 * between scrolling and misrepresenting the structure, and scrolling is honest.
 *
 * Every cell is one width, so the columns line up as rows rather than as text.
 */
@Composable
private fun TableBlock(table: ReplySegment.Table) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .horizontalScroll(rememberScrollState())
            .padding(8.dp),
    ) {
        Row {
            table.header.forEach { cell ->
                Text(
                    parseInline(cell),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.width(CELL_WIDTH).padding(end = 12.dp),
                )
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        table.rows.forEach { row ->
            Row {
                row.forEach { cell ->
                    Text(
                        parseInline(cell),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.width(CELL_WIDTH).padding(end = 12.dp),
                    )
                }
            }
        }
    }
}

/** Enough for a short value; a longer one wraps inside its column. */
private val CELL_WIDTH = 108.dp

/**
 * A listing with its keywords, strings, comments and numbers coloured.
 *
 * The palette comes from the theme's own error and primary colours rather than from
 * a hardcoded set, so a listing does not become the one part of the app that ignores
 * the theme. It is built per block rather than remembered: the theme can change
 * while a reply is on screen, and a remembered palette would keep the old one.
 */
@Composable
private fun highlighted(code: String): AnnotatedString {
    val palette = SyntaxHighlighter.Palette(
        // Deliberately not the error colour: a keyword rendered in red reads as
        // something having gone wrong, and a listing is full of them.
        keyword = MaterialTheme.colorScheme.tertiary,
        string = MaterialTheme.colorScheme.primary,
        comment = MaterialTheme.colorScheme.onSurfaceVariant,
        number = MaterialTheme.colorScheme.secondary,
        plain = MaterialTheme.colorScheme.onSurface,
    )
    return AnnotatedString(
        text = code,
        spanStyles = run {
            var index = 0
            SyntaxHighlighter.spans(code).map { span ->
                val start = index
                index += span.text.length
                SpanStyle(color = SyntaxHighlighter.colourFor(span.token, palette)) to
                    (start until index)
            }.map { (style, range) ->
                AnnotatedString.Range(style, range.first, range.last + 1)
            }
        },
    )
}