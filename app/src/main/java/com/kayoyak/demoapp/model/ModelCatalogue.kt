package com.kayoyak.demoapp.model

import com.kayoyak.demoapp.R

import androidx.annotation.StringRes

/**
 * A language a model can actually hold a conversation in.
 *
 * Recorded per model because the alternative is a trap. The smallest model in a
 * list is the most tempting one, and a model that has never seen Chinese does not
 * say so -- it answers a Chinese question with confident English about something
 * else, which reads as a broken app rather than as the wrong model for the job.
 * That is not hypothetical: it is what the first build of this app did.
 *
 * What a person reads is not here. It is `R.string.language_*`, reached through
 * `ModelLanguage.labelRes()`. This enum carried a `label: String` of Chinese for a
 * while after that moved, and nothing read it -- the KDoc here still described it as
 * "what a person reads", which is how a dead field survives: the comment explaining
 * it keeps sounding true after the code stops being true.
 */
enum class ModelLanguage {
    CHINESE,
    ENGLISH,
    MULTILINGUAL,
}

/**
 * The licence a model is published under, and where that was verified.
 *
 * [sourceUrl] is not decoration. Every entry below was read from the model
 * repository's own page, because a repackaged model can declare a licence that
 * the original publisher never granted. Recording where the claim came from is
 * what makes it checkable later.
 */
data class ModelLicense(
    val spdxId: String,
    val sourceUrl: String,
)

/**
 * A model the application is allowed to offer.
 *
 * [license] is not nullable. There is no way to add a model to the catalogue
 * without stating its licence and where that was confirmed, which is the whole
 * point: see docs/PRINCIPLES.md.
 */
data class ModelDescriptor(
    val id: String,
    val displayName: String,
    /**
     * Who published the model, as a string resource.
     *
     * A resource for the same reason the description is: this is shown in the
     * model list, and copy in two places is how one of them ends up in the wrong
     * language.
     */
    @param:StringRes val publisherRes: Int,
    /**
     * What this model is for, as a string resource.
     *
     * A resource rather than prose because every word the app shows lives in
     * strings.xml. A description written here would be copy in a second place,
     * which is how one of them ends up in the wrong language.
     */
    @param:StringRes val descriptionRes: Int,

    /**
     * What this model can be talked to in.
     *
     * Not a guess from the publisher's name: a model's training data decides
     * this, and two models from the same family can differ. Where a model is
     * usable in English but not Chinese, saying so is the difference between a
     * user choosing it deliberately and a user concluding the app is broken.
     */
    val languages: Set<ModelLanguage> = setOf(ModelLanguage.ENGLISH),

    /**
     * The first source tried, and the one that works in China.
     *
     * `hf-mirror.com`, a long-standing mirror of the same files. `huggingface.co`
     * itself is unreachable from mainland China, and this app is for China, so
     * trying the unreachable host first would spend the user's time proving
     * something already known. The mirror serves the same bytes: it is a proxy,
     * not a different catalogue.
     *
     * A third-party mirror is acceptable here for one specific reason, and it is
     * not trust: the downloaded bytes are checked against [sha256] before the
     * file is made available. A mirror can serve the wrong thing, but it cannot
     * make the app accept it. That check is what turns an untrusted source into a
     * usable one, which is why it is not optional.
     *
     * Null for a model the user imported from a file: there is nothing to fetch,
     * and the app must not pretend otherwise. Everything that offers a download
     * reads [downloadUrls] rather than this.
     */
    val url: String? = null,
    val fileName: String,
    val sizeBytes: Long,

    /**
     * Tried when [url] cannot be reached.
     *
     * The publisher's own host, for a user who can reach it. Not the first
     * choice, for the reason above.
     */
    val fallbackUrl: String? = null,

    /**
     * SHA-256 of the file as published. Verified before the model is made
     * available to the engine.
     *
     * This is what makes a download trustworthy: an interrupted download that
     * resumed against a changed upstream file would otherwise be loaded as if
     * it were intact, and a corrupt multi-gigabyte model produces nonsense
     * rather than an error.
     */
    val sha256: String,

    val license: ModelLicense,

    /** Parameter count, for display. Approximate by nature. */
    val parametersBillions: Double,
) {
    /**
     * Every URL for this file, in the order they should be tried.
     *
     * The reachable host comes first. That is the mirror, not the publisher's,
     * because this app is for China and the publisher's host is blocked there.
     */
    val downloadUrls: List<String> get() = listOfNotNull(url, fallbackUrl)
}

/**
 * The catalogue of models this application will download.
 *
 * Membership rules, in order of importance:
 *
 *  1. The licence must be Apache-2.0, MIT or BSD. Nothing else.
 *  2. The model must not be gated behind a login.
 *  3. Both facts must have been verified against the model's own repository,
 *     including the upstream base model where the file is a conversion.
 *
 * `docs/legal/MODEL_LICENSES.md` records the reasoning, including why Gemma 3
 * is refused while Gemma 4 is accepted.
 *
 * [validate] enforces rule 1 at class-initialisation time, so a model with an
 * unacceptable licence cannot reach a user even by accident.
 */
object ModelCatalogue {

    private const val HF = "https://huggingface.co"
    private const val HF_MIRROR = "https://hf-mirror.com"

    /** Licences this project will distribute access to. Mirrors docs/legal/MODEL_LICENSES.md. */
    val ACCEPTED_LICENSES = setOf("Apache-2.0", "MIT", "BSD-2-Clause", "BSD-3-Clause")

    /**
     * What this app offers, Chinese-capable first.
     *
     * The order is not cosmetic. The first entry is what a user who has not
     * chosen yet ends up with, and the first build of this app put an
     * English-only 360M model there -- so a Chinese question produced confident
     * English about something else, and the app looked broken. [validate] now
     * refuses a catalogue whose first entry cannot hold a Chinese conversation.
     *
     * Every description is in Chinese, because this app has one audience.
     */
    /**
     * What this app offers: open-source Chinese models, smallest first.
     *
     * Two rules ran this list, and both are about the audience rather than about
     * engineering taste.
     *
     * **Domestic.** Every model here is published by a Chinese lab -- Alibaba's
     * Qwen, ModelBest's MiniCPM, DeepSeek. A model made elsewhere can be
     * excellent and still be the wrong choice for an app whose users are in
     * China: it may not hold a Chinese conversation, its host may be unreachable,
     * and its publisher has no reason to care whether it works here.
     *
     * **Smallest first.** A phone is not a server, and the first entry is what
     * someone who has not chosen yet ends up with. The ladder starts at 329 MB so
     * that a device with little room to spare can still use the app at all.
     *
     * Every description is Chinese, and so is every licence claim -- checked
     * against each model's own repository. See docs/legal/MODEL_LICENSES.md.
     */
    val models: List<ModelDescriptor> = listOf(
        ModelDescriptor(
            id = "qwen3-0.6b-int4",
            displayName = "Qwen3 0.6B 量化版",
            publisherRes = R.string.publisher_qwen,
            descriptionRes = R.string.model_qwen3_0_6b_int4,
            languages = setOf(ModelLanguage.CHINESE, ModelLanguage.ENGLISH),
            url = "$HF_MIRROR/litert-community/Qwen3-0.6B/resolve/main/Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm",
            fallbackUrl = "$HF/litert-community/Qwen3-0.6B/resolve/main/Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm",
            fileName = "Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm",
            sizeBytes = 344_671_744L,
            // Re-pinned after the publisher replaced this file under the same name and
            // the same length. The old digest was right for the bytes it was computed
            // over, and ModelDownloader.verify was right to refuse the new ones; what
            // was missing is anything that would have noticed before a user spent
            // 344 MB finding out. scripts/check-model-pins.ps1 is that thing.
            sha256 = "03e7da1eb1108b50dffaa9bb52cc7bcbad2eb0c66ca990267f480c1e545d2856",
            license = ModelLicense(
                spdxId = "Apache-2.0",
                sourceUrl = "$HF/litert-community/Qwen3-0.6B",
            ),
            parametersBillions = 0.6,
        ),
        ModelDescriptor(
            id = "qwen3-0.6b",
            displayName = "Qwen3 0.6B",
            publisherRes = R.string.publisher_qwen,
            descriptionRes = R.string.model_qwen3_0_6b,
            languages = setOf(ModelLanguage.CHINESE, ModelLanguage.ENGLISH),
            url = "$HF_MIRROR/litert-community/Qwen3-0.6B/resolve/main/Qwen3-0.6B.litertlm",
            fallbackUrl = "$HF/litert-community/Qwen3-0.6B/resolve/main/Qwen3-0.6B.litertlm",
            fileName = "Qwen3-0.6B.litertlm",
            sizeBytes = 613_722_264L,
            // Re-pinned after the same replacement as the INT4 entry above -- this one
            // changed length as well, so a download of it now fails on the first
            // byte-count check rather than after the transfer.
            sha256 = "c5f39b4191926786c3e6668b62af4d6eb15248dfc7a5348252a9b406f33f2108",
            license = ModelLicense(
                spdxId = "Apache-2.0",
                sourceUrl = "$HF/litert-community/Qwen3-0.6B",
            ),
            parametersBillions = 0.6,
        ),
        ModelDescriptor(
            id = "minicpm5-1b",
            displayName = "MiniCPM5 1B 量化版",
            publisherRes = R.string.publisher_minicpm,
            descriptionRes = R.string.model_minicpm5_1b,
            languages = setOf(ModelLanguage.CHINESE, ModelLanguage.ENGLISH),
            url = "$HF_MIRROR/litert-community/MiniCPM5-1B/resolve/main/minicpm_wi4b32_wi8_afp32.litertlm",
            fallbackUrl = "$HF/litert-community/MiniCPM5-1B/resolve/main/minicpm_wi4b32_wi8_afp32.litertlm",
            fileName = "minicpm_wi4b32_wi8_afp32.litertlm",
            sizeBytes = 792_619_648L,
            sha256 = "ee1b70697ef583c46d26df5772055946d4ca2a9a80b059149bb51f4ab9646863",
            license = ModelLicense(
                spdxId = "Apache-2.0",
                sourceUrl = "$HF/litert-community/MiniCPM5-1B",
            ),
            parametersBillions = 1.0,
        ),
        ModelDescriptor(
            id = "qwen35-0.8b",
            displayName = "Qwen3.5 0.8B",
            publisherRes = R.string.publisher_qwen,
            descriptionRes = R.string.model_qwen35_0_8b,
            languages = setOf(ModelLanguage.CHINESE, ModelLanguage.ENGLISH),
            url = "$HF_MIRROR/litert-community/Qwen3.5-0.8B/resolve/main/Qwen3.5-0.8B_int8.litertlm",
            fallbackUrl = "$HF/litert-community/Qwen3.5-0.8B/resolve/main/Qwen3.5-0.8B_int8.litertlm",
            fileName = "Qwen3.5-0.8B_int8.litertlm",
            sizeBytes = 964_861_904L,
            sha256 = "b21b4d2b7a0c9ef593c4eb00d2e874352189b60bd7ffaac3877a515adab832c2",
            license = ModelLicense(
                spdxId = "Apache-2.0",
                sourceUrl = "$HF/litert-community/Qwen3.5-0.8B",
            ),
            parametersBillions = 0.8,
        ),
        ModelDescriptor(
            id = "minicpm5-2b-int4",
            displayName = "MiniCPM5 2B 量化版",
            publisherRes = R.string.publisher_minicpm,
            descriptionRes = R.string.model_minicpm5_2b_int4,
            languages = setOf(ModelLanguage.CHINESE, ModelLanguage.ENGLISH),
            url = "$HF_MIRROR/litert-community/MiniCPM5-2B/resolve/main/MiniCPM5-2B_int4.litertlm",
            fallbackUrl = "$HF/litert-community/MiniCPM5-2B/resolve/main/MiniCPM5-2B_int4.litertlm",
            fileName = "MiniCPM5-2B_int4.litertlm",
            sizeBytes = 1_553_670_064L,
            sha256 = "05b78bb57dd5ded8a76907cc953cbc57d6261ee9fa66fbfca21ae677a897da45",
            license = ModelLicense(
                spdxId = "Apache-2.0",
                sourceUrl = "$HF/litert-community/MiniCPM5-2B",
            ),
            parametersBillions = 2.0,
        ),
        ModelDescriptor(
            id = "deepseek-r1-qwen-1.5b",
            displayName = "DeepSeek-R1 蒸馏 1.5B",
            publisherRes = R.string.publisher_deepseek,
            descriptionRes = R.string.model_deepseek_r1_qwen_1_5b,
            languages = setOf(ModelLanguage.CHINESE, ModelLanguage.ENGLISH),
            url = "$HF_MIRROR/litert-community/DeepSeek-R1-Distill-Qwen-1.5B/resolve/main/DeepSeek-R1-Distill-Qwen-1.5B_multi-prefill-seq_q8_ekv4096.litertlm",
            fallbackUrl = "$HF/litert-community/DeepSeek-R1-Distill-Qwen-1.5B/resolve/main/DeepSeek-R1-Distill-Qwen-1.5B_multi-prefill-seq_q8_ekv4096.litertlm",
            fileName = "DeepSeek-R1-Distill-Qwen-1.5B_multi-prefill-seq_q8_ekv4096.litertlm",
            sizeBytes = 1_833_451_520L,
            sha256 = "69b35f01759eed765641ab4af589bbe98131fd2825662a086d9037409b8c1295",
            license = ModelLicense(
                spdxId = "MIT",
                sourceUrl = "$HF/litert-community/DeepSeek-R1-Distill-Qwen-1.5B",
            ),
            parametersBillions = 1.5,
        ),
        ModelDescriptor(
            id = "qwen3-1.7b",
            displayName = "Qwen3 1.7B",
            publisherRes = R.string.publisher_qwen,
            descriptionRes = R.string.model_qwen3_1_7b,
            languages = setOf(ModelLanguage.CHINESE, ModelLanguage.ENGLISH),
            url = "$HF_MIRROR/litert-community/Qwen3-1.7B/resolve/main/Qwen3_1.7B.litertlm",
            fallbackUrl = "$HF/litert-community/Qwen3-1.7B/resolve/main/Qwen3_1.7B.litertlm",
            fileName = "Qwen3_1.7B.litertlm",
            sizeBytes = 2_056_729_520L,
            sha256 = "362ec1c906a49bbe1e68b118fdb789b70a713063b00ac914043cbda03bcf3d9a",
            license = ModelLicense(
                spdxId = "Apache-2.0",
                sourceUrl = "$HF/litert-community/Qwen3-1.7B",
            ),
            parametersBillions = 1.7,
        ),
    )

    /**
     * The model a user who has not chosen yet gets.
     *
     * Defined by position, because that is what "first" means in every list the
     * app shows. [validate] guarantees it can do Chinese.
     */
    val default: ModelDescriptor get() = models.first()

    fun byId(id: String): ModelDescriptor? = models.firstOrNull { it.id == id }

    /**
     * Fails loudly at class-initialisation if anything in the catalogue is
     * internally inconsistent.
     *
     * This runs on every process start, and in every test that touches the
     * catalogue, so a mistake here is a build and run failure rather than
     * something discovered by reading a document.
     *
     * It checks what can be checked mechanically: the shape of the entries and
     * the relationships between them. It cannot tell whether a licence was
     * recorded correctly, or whether a digest belongs to the file it names --
     * those are facts about the outside world that this file asserts and only a
     * download can confirm. What it can do is refuse to let two entries collide
     * on the one field whose collision has a consequence.
     */
    @JvmStatic
    fun validate(): List<String> {
        val problems = mutableListOf<String>()
        val seenIds = mutableSetOf<String>()
        val seenFileNames = mutableSetOf<String>()

        for (model in models) {
            if (!seenIds.add(model.id)) problems += "duplicate model id: ${model.id}"

            // Two entries sharing a file name would share the `.part` file and
            // the installed file: a download of one would be found by the other,
            // and deleting one would delete the other's bytes.
            if (!seenFileNames.add(model.fileName)) {
                problems += "duplicate file name '${model.fileName}' (model '${model.id}')"
            }

            if (model.license.spdxId !in ACCEPTED_LICENSES) {
                problems += "model '${model.id}' has licence '${model.license.spdxId}', " +
                    "which is not one of $ACCEPTED_LICENSES"
            }
            if (model.license.sourceUrl.isBlank()) {
                problems += "model '${model.id}' does not record where its licence was verified"
            }
            if (model.url == null || !model.url.startsWith("https://")) {
                problems += "model '${model.id}' has no https source"
            }
            if (model.sha256.length != 64 || !model.sha256.all { it.isDigit() || it in 'a'..'f' }) {
                problems += "model '${model.id}' has a malformed sha256"
            }
            if (model.sizeBytes <= 0) {
                problems += "model '${model.id}' has a non-positive size"
            }
            if (model.languages.isEmpty()) {
                problems += "model '${model.id}' does not say what it can be talked to in"
            }

            // Every model must be reachable from where its users are. A model
            // whose only source is `huggingface.co` cannot be downloaded in
            // mainland China at all, and the failure looks like a broken app
            // rather than a missing mirror.
            if (model.url != null && model.url.contains("huggingface.co")) {
                problems += "model '${model.id}' is served first from huggingface.co, " +
                    "which is unreachable from mainland China"
            }

            model.fallbackUrl?.let { fallback ->
                if (!fallback.startsWith("https://")) {
                    problems += "model '${model.id}' fallback is not served over https"
                }
                // A fallback must serve the same path on a different host. If the
                // paths differ it is a different file, and the recorded digest
                // would only reject it after a full download rather than before.
                val primaryPath = model.url?.substringAfter("://")?.substringAfter("/")
                val fallbackPath = fallback.substringAfter("://").substringAfter("/")
                if (primaryPath != null && fallbackPath != primaryPath) {
                    problems += "model '${model.id}' fallback path '$fallbackPath' does not " +
                        "match '$primaryPath'"
                }
            }
        }

        // The first entry is what a user who has not chosen gets, and a model
        // that cannot do Chinese does not fail loudly -- it answers a Chinese
        // question with confident English about something else. The first build
        // of this app shipped exactly that, and it reads as a broken app.
        if (models.isNotEmpty() && ModelLanguage.CHINESE !in models.first().languages) {
            problems += "the first model ('${models.first().id}') cannot hold a Chinese " +
                "conversation, and the first model is the default"
        }

        if (problems.isNotEmpty()) {
            throw IllegalStateException(
                "Model catalogue violates the project's licence rules:\n  " +
                    problems.joinToString("\n  ")
            )
        }
        return emptyList()
    }

    init {
        validate()
    }
}
