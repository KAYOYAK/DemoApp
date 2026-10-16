package com.kayoyak.demoapp.data.settings

/**
 * Which appearance the app uses.
 *
 * [SYSTEM] is the default because a phone that has been set up once should not
 * have to be set up again by every app on it. The other two exist because the
 * system setting is a guess about the whole day, and someone reading in bed at
 * midnight may want this screen dark without changing every other screen on the
 * device.
 */
enum class AppTheme {
    SYSTEM,
    LIGHT,
    DARK,
}
