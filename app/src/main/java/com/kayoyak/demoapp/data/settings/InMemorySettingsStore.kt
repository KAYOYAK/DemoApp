package com.kayoyak.demoapp.data.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Settings that live only as long as the object.
 *
 * For a caller that has no file to read: tests, and any future entry point that
 * builds a ViewModel before a container exists. Every value it holds is the same
 * default the real store uses, so a caller that forgets to pass one gets the
 * shipped behaviour rather than something invented for the test.
 */
class InMemorySettingsStore(initial: Settings = Settings()) : SettingsStore {

    private val _settings = MutableStateFlow(initial)
    override val settings: StateFlow<Settings> = _settings.asStateFlow()

    override suspend fun update(update: (Settings) -> Settings) {
        _settings.value = update(_settings.value)
    }
}
