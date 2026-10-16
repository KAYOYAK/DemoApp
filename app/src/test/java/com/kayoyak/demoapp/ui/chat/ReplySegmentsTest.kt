package com.kayoyak.demoapp.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.system.measureTimeMillis

/**
 * Splitting a reply is where a partial Markdown renderer can do more harm than
 * the plain text it replaced: text shown in a code font is confusing, and code
 * shown as prose is unreadable.
 */
class ReplySegmentsTest {

    @Test
    fun `plain text stays one piece`() {
        val segments = splitReply("The sky is blue.")
        assertEquals(1, segments.size)
        assertEquals("The sky is blue.", (segments[0] as ReplySegment.Prose).text)
    }

    @Test
    fun `a fenced block is separated from the prose around it`() {
        val segments = splitReply(
            """
            Here is how:

            ```kotlin
            val x = 1
            ```

            That is all.
            """.trimIndent(),
        )

        assertEquals(3, segments.size)
        assertEquals("Here is how:", (segments[0] as ReplySegment.Prose).text.trim())
        assertEquals("val x = 1", (segments[1] as ReplySegment.Code).text)
        assertEquals("kotlin", (segments[1] as ReplySegment.Code).language)
        assertEquals("That is all.", (segments[2] as ReplySegment.Prose).text.trim())
    }

    @Test
    fun `a fence with no language is still a code block`() {
        val segments = splitReply("```\nplain\n```")
        assertEquals(1, segments.size)
        assertEquals("plain", (segments[0] as ReplySegment.Code).text)
        assertEquals(null, (segments[0] as ReplySegment.Code).language)
    }

    @Test
    fun `an unterminated fence is shown as prose`() {
        // The model was cut off mid-block. Its last words in a code font, with no
        // closing fence, reads as the app having mangled the reply.
        val segments = splitReply("Before\n\n```kotlin\nval x = 1")
        assertTrue(
            "An unfinished block must not be presented as code",
            segments.none { it is ReplySegment.Code },
        )
        assertTrue(segments.joinToString("") { it.text() }.contains("val x = 1"))
    }

    @Test
    fun `an empty reply is one empty piece rather than nothing`() {
        val segments = splitReply("")
        assertEquals(1, segments.size)
        assertEquals("", segments[0].text())
    }

    @Test
    fun `text with three backticks inline is not treated as a fence boundary mid-line`() {
        val segments = splitReply("Use ``` to fence code.")
        assertEquals(
            "A line that only mentions a fence is still prose",
            1,
            segments.size,
        )
    }

    private fun ReplySegment.text(): String = when (this) {
        is ReplySegment.Prose -> text
        is ReplySegment.Code -> text
        is ReplySegment.Heading -> text
        is ReplySegment.Bullets -> items.joinToString("\n")
        is ReplySegment.Quote -> text
        is ReplySegment.Table -> (listOf(header) + rows).joinToString("\n") { it.joinToString("|") }
    }
    @Test
    fun `an empty fence does not become an empty code box`() {
        // Two markers with nothing between them. Rendering them produced a grey
        // panel with no text in it, which reads as the app having mangled the
        // reply rather than as the model having written an empty block.
        val segments = splitReply("before\n\n```\n```\n\nafter")
        assertTrue(
            "No empty code box: $segments",
            segments.none { it is ReplySegment.Code },
        )
        assertTrue(segments.joinToString(" ") { it.text() }.contains("after"))
    }

    @Test
    fun `only a bare fence closes a block`() {
        // A line inside a block that happens to begin with ``` and names a
        // language is content, not a closing marker. Closing on it cut the block
        // short and left its remainder rendered as prose.
        val segments = splitReply("```md\n```js\nconst x = 1\n```\n")
        assertEquals(1, segments.size)
        val code = segments[0] as ReplySegment.Code
        assertEquals("md", code.language)
        assertTrue("The nested marker is content: '${code.text}'", code.text.contains("```js"))
        assertTrue(code.text.contains("const x = 1"))
    }

    @Test
    fun `blank lines alone are not a segment`() {
        val segments = splitReply("text\n\n\n\n")
        assertEquals(1, segments.size)
        assertEquals("text", segments[0].text())
    }

    // ------------------------------------------------------------ structure ---

    @Test
    fun `a heading becomes a heading`() {
        val segments = splitReply("## 步骤")
        assertEquals(1, segments.size)
        val heading = segments[0] as ReplySegment.Heading
        assertEquals(2, heading.level)
        assertEquals("步骤", heading.text)
    }

    @Test
    fun `six hashes is still a heading and seven is not`() {
        // `####### x` is not a Markdown heading. Treating it as one is the kind of
        // near-miss that makes a renderer look broken rather than limited.
        assertEquals(6, (splitReply("###### x")[0] as ReplySegment.Heading).level)
        assertTrue(splitReply("####### x")[0] is ReplySegment.Prose)
    }

    @Test
    fun `emphasis works in a language that has no spaces`() {
        // The flanking rule is about whitespace, and Chinese does not use it
        // between words. A rule written for English would refuse to italicise
        // `这是*重点*内容`, which is how a Chinese writer actually marks it.
        assertEquals(1, parseInline("这是*重点*内容").spanStyles.size)
        assertEquals("这是重点内容", parseInline("这是*重点*内容").text)
    }

    @Test
    fun `a formula written without spaces is not emphasis`() {
        assertEquals("2*3*4", parseInline("2*3*4").text)
        assertTrue(parseInline("2*3*4").spanStyles.isEmpty())
    }

    @Test
    fun `a run of bullets is one list and keeps its order`() {
        val segments = splitReply("- 第一\n- 第二\n- 第三")
        assertEquals(1, segments.size)
        val list = segments[0] as ReplySegment.Bullets
        assertEquals(listOf("第一", "第二", "第三"), list.items)
        assertTrue("A dash list is not numbered", !list.ordered)
    }

    @Test
    fun `a numbered list keeps its numbers`() {
        val list = splitReply("1. a\n2. b")[0] as ReplySegment.Bullets
        assertTrue(list.ordered)
        assertEquals(listOf("a", "b"), list.items)
    }

    @Test
    fun `two lists separated by a blank line are two lists`() {
        // Without the break they merge, and the second one either renumbers from
        // one in the middle of the first or continues numbers the user did not
        // write.
        val segments = splitReply("- a\n\n- b")
        assertEquals(2, segments.size)
    }

    @Test
    fun `a quote becomes a quote`() {
        val quote = splitReply("> 引用的内容")[0] as ReplySegment.Quote
        assertEquals("引用的内容", quote.text)
    }

    @Test
    fun `consecutive quote lines are one quote`() {
        val quote = splitReply("> 第一行\n> 第二行")[0] as ReplySegment.Quote
        assertEquals("第一行\n第二行", quote.text)
    }

    @Test
    fun `markup inside a code fence stays literal`() {
        // A `#` in a shell script is a comment. Rendering it as a heading would
        // corrupt the listing, which is the one thing the fence exists to prevent.
        val segments = splitReply("```sh\n# a comment\n- not a bullet\n```")
        assertEquals(1, segments.size)
        val code = segments[0] as ReplySegment.Code
        assertEquals("# a comment\n- not a bullet", code.text)
    }

    @Test
    fun `a table is rendered, not left as pipes`() {
        // This replaces the opposite assertion, which was deliberate at the time:
        // "this renderer does not do tables, and a half-rendered one has its columns
        // in the wrong places. Ugly and honest beats wrong."
        //
        // That reasoning held while a table could only be guessed at from lines
        // containing a pipe. It stopped holding once the separator row became the
        // test -- `|---|---|` is not something prose contains by accident -- and once
        // ragged rows were padded rather than left to shift the columns. The reason
        // for the old behaviour is gone, so the behaviour goes with it.
        val table = "| a | b |\n| - | - |\n| 1 | 2 |"
        val segments = splitReply(table)

        val rendered = segments.filterIsInstance<ReplySegment.Table>().single()
        assertEquals(listOf("a", "b"), rendered.header)
        assertEquals(listOf(listOf("1", "2")), rendered.rows)
    }

    // --------------------------------------------------------------- inline ---

    @Test
    fun `bold is bold`() {
        val spans = parseInline("**重要**").spanStyles
        assertEquals(1, spans.size)
        assertEquals(0, spans[0].start)
        assertEquals(2, spans[0].end)
    }

    @Test
    fun `two bold runs do not become one`() {
        // `.+` would match from the first `**` to the last, putting the text
        // between two separate emphases inside the same bold run.
        val spans = parseInline("**a** and **b**").spanStyles
        assertEquals(2, spans.size)
    }

    @Test
    fun `multiplication is not italicised`() {
        // `2 * 3 * 4` is arithmetic. A rule that italicises between any two
        // asterisks deletes them and shows the sum wrong.
        val text = parseInline("2 * 3 * 4")
        assertEquals("2 * 3 * 4", text.text)
        assertTrue("Nothing should be styled", text.spanStyles.isEmpty())
    }

    @Test
    fun `an unmatched marker is left alone`() {
        val text = parseInline("a * b")
        assertEquals("a * b", text.text)
        assertTrue(text.spanStyles.isEmpty())
    }

    @Test
    fun `emphasis inside code is not emphasis`() {
        // A model writing about `**kwargs` means the characters, not bold.
        val text = parseInline("`**kwargs`")
        assertEquals("**kwargs", text.text)
        assertEquals("Only the code span is styled", 1, text.spanStyles.size)
    }

    @Test
    fun `a long line costs time in proportion to its length`() {
        // The bug this guards was quadratic, and quadratic work hides until the
        // input is big: a 1,500-character reply parsed in 1 ms and looked fine. At
        // 42,000 characters the old implementation took 2.2 seconds for one call,
        // on the frame thread, inside a composable that a streaming reply
        // re-enters on every token.
        //
        // Measured rather than asserted as a wall-clock limit, because a limit
        // that passes on this machine and fails on a slower one is worse than no
        // test. A linear implementation is ~8x slower on 8x the input; a quadratic
        // one is ~64x. The bound is deliberately loose -- it only has to separate
        // those two, and it still fails the old code by two orders of magnitude.
        val unit = "**粗体** 和 `代码` 以及普通文字。"
        val small = unit.repeat(200)
        val large = unit.repeat(1600) // 8x the input

        fun millisOf(text: String): Long {
            parseInline(text) // warm the JIT so the ratio is not measuring that
            return measureTimeMillis { parseInline(text) }
        }

        val smallMs = millisOf(small).coerceAtLeast(1)
        val largeMs = millisOf(large)

        assertTrue(
            "8x the input took ${largeMs}ms against ${smallMs}ms for the eighth; " +
                "that growth is not linear, and the renderer runs on the frame thread",
            largeMs <= smallMs * 24,
        )
    }

    @Test
    fun `plain text is unchanged by parsing`() {
        val original = "今天天气不错，气温 25 度。"
        assertEquals(original, parseInline(original).text)
    }

    // -------------------------------------------------------------- tables ---

    @Test
    fun `a pipe table becomes a table, not a paragraph of bars`() {
        // What it did before: the four-column table arrived as one line of prose
        // with vertical bars in it, which is unreadable and is not what the model
        // wrote.
        val segments = splitReply(
            """
            | Model | Size | RAM |
            | --- | --- | --- |
            | qwen3 | 329 MB | 493 MB |
            | qwen3-1.7b | 1961 MB | 2942 MB |
            """.trimIndent(),
        )

        val table = segments.filterIsInstance<ReplySegment.Table>().single()
        assertEquals(listOf("Model", "Size", "RAM"), table.header)
        assertEquals(2, table.rows.size)
        assertEquals(listOf("qwen3", "329 MB", "493 MB"), table.rows[0])
    }

    @Test
    fun `the separator row is not kept as a row`() {
        // `|---|---|` is syntax. Rendering it as a row of dashes is the single
        // most obvious way to get this wrong.
        val segments = splitReply("| a | b |\n|---|---|\n| 1 | 2 |")

        val table = segments.filterIsInstance<ReplySegment.Table>().single()
        assertEquals(1, table.rows.size)
        assertTrue(table.rows.none { row -> row.any { it.contains("---") } })
    }

    @Test
    fun `a paragraph containing a pipe is not a table`() {
        // Without the separator check, any sentence with a `|` in it -- a shell
        // pipeline, a probability -- becomes a box with one cell in it.
        val segments = splitReply("run this command | grep foo and see")

        assertTrue(segments.none { it is ReplySegment.Table })
        assertTrue(segments.single() is ReplySegment.Prose)
    }

    @Test
    fun `a single pipe line is not a table`() {
        val segments = splitReply("| just one line |")

        assertTrue(segments.none { it is ReplySegment.Table })
    }

    @Test
    fun `a short row is padded rather than dropped`() {
        // A model that emits a row with a cell missing has still said something.
        // Dropping the row loses it; an empty cell keeps the table square without
        // pretending the text is not there.
        val segments = splitReply("| a | b | c |\n|---|---|---|\n| 1 | 2 |")

        val table = segments.filterIsInstance<ReplySegment.Table>().single()
        assertEquals(listOf("1", "2", ""), table.rows[0])
    }

    @Test
    fun `a table inside a code fence stays code`() {
        // Markdown in a listing is a listing. Rendering a fenced table would be
        // the app corrupting the thing it was asked to show verbatim.
        val segments = splitReply("```\n| a | b |\n|---|---|\n| 1 | 2 |\n```")

        assertTrue(segments.single() is ReplySegment.Code)
    }

    @Test
    fun `text after a table is prose again`() {
        val segments = splitReply("| a |\n|---|\n| 1 |\n\nand then some text")

        assertTrue(segments.any { it is ReplySegment.Table })
        assertTrue(segments.any { it is ReplySegment.Prose })
    }

    @Test
    fun `a table with no header separator is left as prose`() {
        val segments = splitReply("| a | b |\n| 1 | 2 |")

        assertTrue(segments.none { it is ReplySegment.Table })
    }
}