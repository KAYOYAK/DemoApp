package com.example.demoapp.data.settings

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Saved instructions are text the user typed once and expects back exactly.
 *
 * The tests here are about the three ways that can fail quietly: a save that
 * silently replaces something else, a delete that takes the wrong one, and a
 * hand-edited file that puts the settings screen into a state it cannot render.
 */
class SavedPromptTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun roundTrip(settings: Settings): Settings =
        json.decodeFromString<Settings>(json.encodeToString(settings)).sanitised()

    @Test
    fun `a saved instruction survives a write and a read`() {
        val saved = Settings(savedPrompts = listOf(SavedPrompt("翻译", "把它译成中文。")))

        val back = roundTrip(saved)

        assertEquals(listOf(SavedPrompt("翻译", "把它译成中文。")), back.savedPrompts)
    }

    @Test
    fun `a settings file written before this existed still parses`() {
        // The field is new. An older file has no `savedPrompts` key at all, and
        // refusing it would reset every other setting the user had chosen.
        val old = """{"backend":"CPU","systemPrompt":"x","maxReplyTokens":256}"""

        val back = json.decodeFromString<Settings>(old).sanitised()

        assertTrue(back.savedPrompts.isEmpty())
        assertEquals(256, back.maxReplyTokens)
        assertEquals("x", back.systemPrompt)
    }

    @Test
    fun `saving the same name again replaces rather than duplicates`() {
        // The name is the user's handle for the text. Saving "翻译" twice means
        // "this is what 翻译 means to me now" -- keeping both would leave two chips
        // with one label and no way to tell them apart.
        var settings = Settings()
        settings = settings.copy(
            savedPrompts = settings.savedPrompts + SavedPrompt("翻译", "old"),
        )
        settings = settings.copy(
            savedPrompts = settings.savedPrompts.filterNot { it.name == "翻译" } +
                SavedPrompt("翻译", "new"),
        )

        assertEquals(listOf(SavedPrompt("翻译", "new")), settings.sanitised().savedPrompts)
    }

    @Test
    fun `a blank name is dropped rather than rendering a chip with no label`() {
        val settings = Settings(
            savedPrompts = listOf(
                SavedPrompt("   ", "body"),
                SavedPrompt("real", "body"),
            ),
        )

        assertEquals(listOf("real"), settings.sanitised().savedPrompts.map { it.name })
    }

    @Test
    fun `a blank body is dropped rather than offering a preset that erases the box`() {
        val settings = Settings(
            savedPrompts = listOf(
                SavedPrompt("empty", "   "),
                SavedPrompt("real", "body"),
            ),
        )

        assertEquals(listOf("real"), settings.sanitised().savedPrompts.map { it.name })
    }

    @Test
    fun `names and bodies are trimmed`() {
        // A name pasted in with a trailing space would be a different name from the
        // same one typed by hand, and delete-by-name would then miss it.
        val settings = Settings(savedPrompts = listOf(SavedPrompt("  翻译  ", "  正文  ")))

        assertEquals(listOf(SavedPrompt("翻译", "正文")), settings.sanitised().savedPrompts)
    }

    @Test
    fun `duplicate names from a hand-edited file collapse to one`() {
        val settings = Settings(
            savedPrompts = listOf(
                SavedPrompt("翻译", "first"),
                SavedPrompt("翻译", "second"),
            ),
        )

        val kept = settings.sanitised().savedPrompts
        assertEquals(1, kept.size)
        assertEquals("The first one wins, so the file is read the same way every time", "first", kept[0].body)
    }

    @Test
    fun `a hand-edited file cannot fill the settings screen`() {
        val many = (1..500).map { SavedPrompt("name$it", "body$it") }

        assertEquals(
            MAX_SAVED_PROMPTS,
            Settings(savedPrompts = many).sanitised().savedPrompts.size,
        )
    }

    @Test
    fun `the cap keeps the newest, because that is the one just saved`() {
        // A list over the cap is written by saving one more, so the entry to keep is
        // the last. Dropping it would discard what the user just did and keep
        // twenty-four older ones.
        val many = (1..(MAX_SAVED_PROMPTS + 1)).map { SavedPrompt("name$it", "body$it") }

        val kept = Settings(savedPrompts = many).sanitised().savedPrompts
        assertEquals("name2", kept.first().name)
        assertEquals("name${MAX_SAVED_PROMPTS + 1}", kept.last().name)
    }

    @Test
    fun `saving past the cap does not lose the new one`() {
        // The same rule through the path a user actually takes, because the
        // sanitised copy above is applied on read and this one on write.
        var settings = Settings()
        repeat(MAX_SAVED_PROMPTS) { index ->
            settings = settings.copy(
                savedPrompts = settings.savedPrompts + SavedPrompt("old$index", "body"),
            )
        }

        settings = settings.copy(
            savedPrompts = (settings.savedPrompts + SavedPrompt("newest", "body")).takeLast(MAX_SAVED_PROMPTS),
        )

        val names = settings.sanitised().savedPrompts.map { it.name }
        assertTrue("The newest is kept", names.contains("newest"))
        assertTrue("The oldest is the one that goes", !names.contains("old0"))
    }
}
