package com.example.demoapp.model

import com.example.demoapp.R

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the catalogue's licence rules.
 *
 * The catalogue is the point where the project's first non-negotiable
 * constraint -- model licences must be Apache-2.0, MIT or BSD -- is expressed
 * in a form that can fail. These tests are what make that failure happen during
 * development rather than after release.
 */
class ModelCatalogueTest {

    @Test
    fun `every catalogue entry has an accepted licence`() {
        val offending = ModelCatalogue.models.filter {
            it.license.spdxId !in ModelCatalogue.ACCEPTED_LICENSES
        }
        assertEquals(
            "These models have licences the project does not accept: " +
                offending.joinToString { "${it.id}=${it.license.spdxId}" },
            emptyList<ModelDescriptor>(),
            offending,
        )
    }

    @Test
    fun `every catalogue entry records where its licence was verified`() {
        val missing = ModelCatalogue.models.filter { it.license.sourceUrl.isBlank() }
        assertEquals(
            "A licence claim without a source cannot be re-checked later",
            emptyList<ModelDescriptor>(),
            missing,
        )
    }

    @Test
    fun `no Gemma model is offered`() {
        // Gemma 3 ships under a custom licence whose terms Google may change
        // unilaterally, and which can restrict use remotely -- which is why the
        // earlier generation was refused even when it was the best model
        // available. Gemma 4 is Apache-2.0 and would pass rule 1, and it is still
        // absent: it is not a Chinese model, and this app's catalogue holds only
        // models from Chinese labs. See docs/legal/MODEL_LICENSES.md.
        val gemma = ModelCatalogue.models.filter {
            it.url?.contains("gemma", ignoreCase = true) == true ||
                it.fileName.contains("gemma", ignoreCase = true)
        }
        assertTrue(
            "No Gemma model belongs in a China-facing catalogue: $gemma",
            gemma.isEmpty(),
        )
    }

    @Test
    fun `no model comes from outside China`() {
        // Not a technical rule, and the honest reason it is checked: a model
        // published elsewhere may not hold a Chinese conversation, its host may be
        // unreachable from mainland China, and its publisher has no stake in
        // whether it works there. An app for China should not offer one.
        //
        // The list is the publishers this catalogue is allowed to use. A new one
        // is a decision, not an accident -- adding it here is where that decision
        // is recorded.
        // Compared as resource ids, because that is what the catalogue holds. The
        // wording lives in strings.xml and is not this test's business; which
        // publishers were decided on is.
        val allowed = setOf(
            R.string.publisher_qwen,
            R.string.publisher_minicpm,
            R.string.publisher_deepseek,
        )
        val foreign = ModelCatalogue.models
            .map { it.publisherRes }
            .distinct()
            .filterNot { it in allowed }

        assertTrue(
            "These publishers are not Chinese labs this catalogue has decided to " +
                "carry: $foreign",
            foreign.isEmpty(),
        )
    }

    @Test
    fun `every model can be downloaded from China`() {
        // The first URL is the one the app tries, and it has to be reachable from
        // where the users are. `huggingface.co` is blocked in mainland China, so a
        // model whose primary source is there cannot be downloaded at all -- and
        // the failure looks like a broken app rather than a missing mirror.
        val unreachable = ModelCatalogue.models.filter {
            it.url?.contains("huggingface.co") == true
        }
        assertTrue(
            "These are served first from a host mainland China cannot reach: $unreachable",
            unreachable.isEmpty(),
        )
    }

    @Test
    fun `the catalogue starts at a size a phone can actually hold`() {
        // The first entry is what someone who has not chosen yet downloads, and
        // the ladder is meant to start where a device with little room to spare
        // can still use the app.
        val smallest = ModelCatalogue.models.minOf { it.sizeBytes }
        assertTrue(
            "The smallest model is ${smallest / 1_048_576} MB, which is not lightweight",
            smallest < 600L * 1_048_576L,
        )
    }

    @Test
    fun `no model is served over plain http`() {
        // Every entry the catalogue offers must have a source, and it must be
        // https. An imported model has no source at all and is not in this list.
        val insecure = ModelCatalogue.models.filter { it.url?.startsWith("https://") != true }
        assertEquals(emptyList<ModelDescriptor>(), insecure)
        assertEquals(
            "The mirror is checked too, and by the same rule",
            emptyList<ModelDescriptor>(),
            ModelCatalogue.models.filter { it.fallbackUrl?.startsWith("https://") == false },
        )
    }

    @Test
    fun `every digest is a well formed sha256`() {
        ModelCatalogue.models.forEach { model ->
            assertEquals(
                "Model '${model.id}' has a malformed sha256",
                64,
                model.sha256.length,
            )
            assertTrue(
                "Model '${model.id}' digest is not hexadecimal",
                model.sha256.all { it in "0123456789abcdefABCDEF" },
            )
        }
    }

    @Test
    fun `model ids are unique`() {
        val ids = ModelCatalogue.models.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `lookup by id returns the right model and null for unknown ids`() {
        assertEquals("qwen3-0.6b", ModelCatalogue.byId("qwen3-0.6b")?.id)
        assertNull(ModelCatalogue.byId("does-not-exist"))
    }

    @Test
    fun `validate reports no problems for the shipped catalogue`() {
        assertEquals(emptyList<String>(), ModelCatalogue.validate())
    }

    // ------------------------------------------------------------ language ---

    @Test
    fun `the default model can hold a Chinese conversation`() {
        // The first entry is what a user who has not chosen gets. An English-only
        // model there does not fail loudly -- it answers a Chinese question with
        // confident English about something else, which the first build of this
        // app did, and which reads as a broken app rather than a wrong choice.
        assertTrue(
            "The default is '${ModelCatalogue.default.id}', which cannot do Chinese",
            ModelLanguage.CHINESE in ModelCatalogue.default.languages,
        )
    }

    @Test
    fun `every model says what it can be talked to in`() {
        val silent = ModelCatalogue.models.filter { it.languages.isEmpty() }
        assertEquals(
            "A model with no language is one a user cannot judge before downloading it",
            emptyList<ModelDescriptor>(),
            silent,
        )
    }

    @Test
    fun `the Chinese models come before the English-only ones`() {
        // Not merely "the first one is Chinese": the ordering is what a user reads
        // down, and an English-only model sitting above a Chinese one invites the
        // same mistake one row further down.
        val lastChinese = ModelCatalogue.models.indexOfLast { ModelLanguage.CHINESE in it.languages }
        val firstEnglishOnly = ModelCatalogue.models.indexOfFirst { ModelLanguage.CHINESE !in it.languages }

        if (firstEnglishOnly >= 0) {
            assertTrue(
                "A Chinese-capable model appears below an English-only one: " +
                    ModelCatalogue.models.map { "${it.id}=${it.languages}" },
                lastChinese < firstEnglishOnly,
            )
        }
    }

    @Test
    fun `every model can hold a Chinese conversation`() {
        // Not "the first one": the catalogue holds Chinese models only, so the
        // rule covers all of them. An earlier version of this checked that an
        // English-only model said so in its description -- the right check while
        // the catalogue carried one, and a test that passed by having nothing to
        // check once it did not.
        val englishOnly = ModelCatalogue.models
            .filter { ModelLanguage.CHINESE !in it.languages }
        assertTrue(
            "These cannot do Chinese and must not be offered by this app: $englishOnly",
            englishOnly.isEmpty(),
        )
    }
}