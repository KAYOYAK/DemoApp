package com.example.demoapp.data.settings

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import com.example.demoapp.data.FileCipher
import com.example.demoapp.inference.GenerationConfig
import com.example.demoapp.inference.SamplingProfile
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * The choices the user has made that outlive a screen.
 *
 * One file, not one per setting. These are a handful of values read at start-up
 * and written when something changes, so the cost of keeping them together is
 * nothing and the benefit -- a single thing to read, a single thing to back up --
 * is real.
 */
/**
 * How many saved instructions are kept.
 *
 * A limit rather than none: these render as a row of chips on a phone, and past a
 * couple of dozen the row stops being a choice and becomes a wall. Well above what
 * anyone uses, so it only ever catches a hand-edited file.
 */
const val MAX_SAVED_PROMPTS = 24

/**
 * An instruction the user saved under a name.
 *
 * Both fields are the user's own text, so neither is a string resource: this is the
 * one place in the app where what is shown is not copy the app wrote.
 */
@Serializable
data class SavedPrompt(val name: String, val body: String)

@Serializable
data class Settings(
    /**
     * Which processor the model is asked to run on.
     *
     * Stored as the enum's name rather than its ordinal: an ordinal would silently
     * come to mean a different backend if the enum were ever reordered, and the
     * user would find their choice changed without anyone deciding to change it.
     * An unrecognised name falls back to the default instead.
     */
    val backend: String = "AUTO",
    /**
     * What the model is told it is, at the top of every conversation.
     *
     * Empty means [DEFAULT_SYSTEM_PROMPT]. Stored empty rather than pre-filled so
     * that a later change to the default reaches everyone who never edited it: a
     * copy of the old wording would pin them to text they never chose.
     *
     * Not a cosmetic setting. Measured: with no system prompt at all, a 360M model
     * asked "hello" produced three hundred words about storytelling, because it
     * was continuing a document rather than answering a question. The prompt is
     * the difference between an assistant and an autocomplete.
     */
    val systemPrompt: String = "",
    /**
     * The longest reply to ask for, in tokens.
     *
     * A cap rather than a target: the model stops when it has finished, and this
     * only bounds the answers that never do.
     */
    val maxReplyTokens: Int = 512,
    /**
     * How adventurous the wording is, in tenths.
     *
     * An integer so it round-trips through JSON exactly. A floating-point value
     * that drifted on every save would be a strange thing to find years later.
     */
    val temperatureTenths: Int = 8,
    /**
     * A sampling profile chosen for a particular model, by model id.
     *
     * A map rather than one value because the thing being adjusted is a property of
     * the model: a 0.6B model that repeats itself and a 2B model that does not are
     * the same settings screen, and a single global choice makes the user pick which
     * of the two to fix. Values are [SamplingProfile] names, like [theme], so
     * reordering the enum cannot silently change a choice and an unknown name falls
     * back rather than failing to parse.
     *
     * Absent means "use the app's defaults", which is also what an empty map means,
     * so a settings file written before this existed loads unchanged.
     */
    val modelSampling: Map<String, String> = emptyMap(),
    /**
     * Which appearance to use, as [AppTheme]'s name.
     *
     * Stored by name rather than ordinal, so reordering the enum cannot silently
     * change a user's choice, and falling back to the default when the name is
     * unknown.
     */
    val theme: String = AppTheme.SYSTEM.name,
    /**
     * Text size, as a percentage of what the system asks for.
     *
     * A percentage rather than an absolute size, because the system setting is
     * the user's own baseline -- someone who has already made text larger
     * everywhere should not find this app smaller than everything else. 100 means
     * "whatever the device says".
     */
    val fontScalePercent: Int = 100,
    /**
     * Instructions the user saved to reuse.
     *
     * The shipped presets cover the common wants; these are for the wording someone
     * arrived at themselves. That is the whole point: an instruction box that takes
     * ten minutes to get right is one you do not want to reproduce from memory every
     * time you switch tasks, and copying it back out of an old conversation is not a
     * workflow.
     *
     * A list with a default, so a settings file written before this existed still
     * parses and simply has none.
     */
    val savedPrompts: List<SavedPrompt> = emptyList(),
    /**
     * Whether the app asks for the device lock before showing anything.
     *
     * Off by default. A lock the user did not ask for is one they resent, and
     * the settings that turn it on say plainly what it does and does not do.
     */
    val lockEnabled: Boolean = false,
    /**
     * Whether every message carries the time it was written.
     *
     * Off by default. The chat interfaces people use hide timestamps and reveal
     * them on demand, because a time under every bubble is a column of numbers
     * between the words. A user who wants them wants them always, which is what
     * this is for.
     */
    val showTimestamps: Boolean = false,
) {
    val temperature: Double get() = temperatureTenths / 10.0

    /** The appearance to use, after the unknown-name fallback. */
    val chosenTheme: AppTheme
        get() = AppTheme.entries.firstOrNull { it.name == theme } ?: AppTheme.SYSTEM

    /** The multiplier to hand to the typography, clamped to what is readable. */
    val fontScale: Float get() = fontScalePercent / 100f

    /**
     * The sampling profile to use for [modelId], after the unknown-name fallback.
     *
     * No entry, or a name this build does not know, means [DEFAULT_SAMPLING_PROFILE].
     * A settings file edited by hand or written by a later version is the ordinary
     * case for those two, not an error worth telling the user about.
     */
    fun chosenSamplingProfileFor(modelId: String): SamplingProfile =
        modelSampling[modelId]
            ?.let { name -> SamplingProfile.entries.firstOrNull { it.name == name } }
            ?: DEFAULT_SAMPLING_PROFILE

    /**
     * The sampling configuration to load [modelId] with.
     *
     * The temperature slider still applies, because it is the one sampling value the
     * settings screen has always owned and taking it away would be a regression for
     * whoever set it. It is applied on top of the profile, so choosing a profile for
     * a model and then moving the slider does what the two controls read as doing --
     * the profile decides everything else, the slider decides how adventurous the
     * wording is.
     */
    fun samplingFor(modelId: String): GenerationConfig =
        chosenSamplingProfileFor(modelId)
            .appliedTo()
            .copy(temperature = temperature)

    /** The prompt actually used, after the empty-means-default rule. */
    val effectiveSystemPrompt: String get() = systemPrompt.ifBlank { DEFAULT_SYSTEM_PROMPT }

    /**
     * The values this build can actually use.
     *
     * The settings file is an ordinary JSON file. It can be edited by hand, half
     * written, or written by a version of the app that offered values this one
     * does not. None of those is hypothetical enough to skip checking: a reply
     * length of zero or a negative one is passed straight to the model, where the
     * result is whatever the runtime does with it -- an exception surfaced as a
     * bare message, or an immediately empty reply that records nothing -- and the
     * screen can explain neither.
     *
     * Applied on read, which is the last point before anything acts on a value
     * that came from outside the app.
     */
    fun sanitised(): Settings = copy(
        maxReplyTokens = maxReplyTokens.coerceIn(MIN_REPLY_TOKENS, MAX_REPLY_TOKENS),
        temperatureTenths = temperatureTenths.coerceIn(MIN_TEMPERATURE_TENTHS, MAX_TEMPERATURE_TENTHS),
        fontScalePercent = fontScalePercent.coerceIn(MIN_FONT_SCALE_PERCENT, MAX_FONT_SCALE_PERCENT),
        // The settings file can be edited by hand, so this list is external input
        // like every other field here. A blank name is a chip with no label; a blank
        // body is a preset that erases the instruction box; a duplicate name makes
        // delete-by-name ambiguous. Capped because nothing stops a hand-edited file
        // from carrying ten thousand of them into a settings screen.
        savedPrompts = savedPrompts
            .map { SavedPrompt(it.name.trim(), it.body.trim()) }
            .filter { it.name.isNotEmpty() && it.body.isNotEmpty() }
            .distinctBy { it.name }
            // The last, not the first. A list over the cap got that way by saving
            // one more, so the newest is the one to keep; and this has to agree with
            // the write path, which trims the same way. Keeping the first here and
            // the last there would mean a restart could silently restore the
            // twenty-four older instructions and drop the one just saved.
            .takeLast(MAX_SAVED_PROMPTS),
    )

    companion object {
        /**
         * What the model is told unless the user says otherwise.
         *
         * In Chinese, because the app is. A system prompt written in one language
         * and a question asked in another pulls a small model toward whichever it
         * sees more of, and the prompt is what it sees first.
         *
         * "简短" is doing real work: the same model, asked for a reply with no
         * length guidance, ran to the token cap and took twenty seconds to say
         * nothing in particular.
         *
         * It is also the difference between an assistant and an autocomplete. With
         * no system instruction at all, a small model given a conversation-shaped
         * list of turns continues the document instead of answering it -- measured
         * as three hundred words about storytelling in reply to "你好".
         */
        const val DEFAULT_SYSTEM_PROMPT =
            "你是一个乐于助人的助手。" +
                "你完全运行在用户自己的手机上，没有联网。" +
                "请直接、简短地回答用户的问题。" +
                "如果不知道答案，就直说不知道，不要编造。"

        /** The reply-length choices offered, in tokens. */
        val REPLY_LENGTHS = listOf(128, 256, 512, 1024, 2048)

        /** The creativity choices offered, in tenths. */
        val TEMPERATURES = listOf(0, 2, 5, 8, 10, 15)

        /**
         * What a model uses when the user has not chosen a profile for it.
         *
         * [SamplingProfile.BALANCED], whose values are the publisher's own
         * recommendation for the smallest models in this catalogue — so the
         * out-of-the-box behaviour is an argued position rather than whatever the
         * runtime happened to default to, which is what it used to be.
         */
        val DEFAULT_SAMPLING_PROFILE = SamplingProfile.BALANCED

        /**
         * The range a stored reply length is brought into.
         *
         * Wider than the offered choices, so a value another build wrote is still
         * usable rather than silently reset, but well inside anything the runtime
         * could be asked for.
         */
        const val MIN_REPLY_TOKENS = 16
        const val MAX_REPLY_TOKENS = 4096

        /** The range a stored temperature is brought into, in tenths. */
        const val MIN_TEMPERATURE_TENTHS = 0
        const val MAX_TEMPERATURE_TENTHS = 20

        /** The font-size choices offered, in percent of the system's own size. */
        val FONT_SCALES = listOf(85, 100, 115, 130, 150)

        /**
         * The range a stored font scale is brought into.
         *
         * Wider than the offered choices so a value another build wrote is still
         * usable, but bounded: below 70% the smallest labels stop being legible,
         * and above 200% a single word fills the screen.
         */
        const val MIN_FONT_SCALE_PERCENT = 70
        const val MAX_FONT_SCALE_PERCENT = 200
    }
}

interface SettingsStore {
    /** The current values. Emits on every change. */
    val settings: StateFlow<Settings>

    /** Writes [update] over the current values. */
    suspend fun update(update: (Settings) -> Settings)
}

/**
 * A settings file on disk.
 *
 * Writes go through a uniquely named temporary file and a rename, for the same
 * reason chat files do: a process killed mid-write must not leave a half-written
 * file where a valid one used to be, and a settings file that fails to parse is
 * one the app cannot fall back from.
 *
 * One instance per file, for the life of the process. The current value is held
 * in memory and only this object writes it, so a second instance over the same
 * path would not see the first one's changes and its own would overwrite them --
 * silently, because writing a value that matches the memory copy is skipped as
 * an unchanged write. The application builds exactly one, in its container.
 */
class FileSettingsStore(private val file: File) : SettingsStore {

    private val writeLock = Mutex()

    // Declared before anything that reads it. Kotlin initialises properties in
    // source order, and the first read of the file happens in a property
    // initialiser below -- with this declared after it, the reader ran against a
    // null Json and the store failed on its first access rather than on its
    // first read.
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _settings = MutableStateFlow(readOrDefaults())
    override val settings: StateFlow<Settings> = _settings.asStateFlow()

    init {
        // A write that died before its rename. Left over it would be re-read on
        // the next launch and, if it parsed, would be as valid as the real file
        // while describing a state the user never confirmed.
        file.parentFile?.listFiles()
            ?.filter { it.name.endsWith(TEMP_SUFFIX) }
            ?.forEach { it.delete() }
    }

    override suspend fun update(update: (Settings) -> Settings) {
        writeLock.withLock {
            val next = update(_settings.value)
            if (next == _settings.value) return@withLock
            writeNow(next)
            _settings.value = next
        }
    }

    private suspend fun writeNow(settings: Settings) = withContext(Dispatchers.IO) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.${UUID.randomUUID()}$TEMP_SUFFIX")
        try {
            temp.outputStream().use { out ->
                // Through the cipher. A device with no screen lock has its app storage
                // readable by anything that can read the filesystem, and this is the
                // file that holds the user's own system prompt.
                out.write(FileCipher.encrypt(json.encodeToString(settings).toByteArray()))
                out.flush()
                out.fd.sync()
            }
            try {
                Files.move(
                    temp.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (t: Throwable) {
            temp.delete()
            throw t
        }
    }

    /**
     * Reads what is on disk, or the defaults.
     *
     * Never throws. A settings file that cannot be read is not a reason to refuse
     * to start: every value in it has a defensible default, and an app that will
     * not open because a preference is malformed is worse than one that opens
     * with the preference reset.
     */
    private fun readOrDefaults(): Settings {
        if (!file.isFile) return Settings()
        return try {
            // Sanitised, not merely parsed. A file that decodes can still hold a
            // value no screen offers and no model should be asked for -- see
            // Settings.sanitised.
            FileCipher.read(file)?.decodeToString()?.let { json.decodeFromString<Settings>(it).sanitised() }
            ?: Settings()
        } catch (t: Throwable) {
            if (t is IOException || t is IllegalArgumentException) Settings()
            else throw t
        }
    }

    private companion object {
        const val TEMP_SUFFIX = ".tmp"
    }
}

/**
 * Brings a font scale a caller supplied into the range [Settings] accepts.
 *
 * Here rather than at each call site, so a value that arrives from a slider, a
 * test, or a hand-edited file is bounded by the same rule. `sanitised()` does the
 * same job on the way in from disk; this is the way in from code.
 */
fun Int.sanitisedFontScale(): Int =
    coerceIn(Settings.MIN_FONT_SCALE_PERCENT, Settings.MAX_FONT_SCALE_PERCENT)