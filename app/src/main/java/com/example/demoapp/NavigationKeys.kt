package com.example.demoapp

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/**
 * Chat is the start destination: it is what the app is for.
 *
 * When no model is installed it shows an empty state with a way to reach
 * [Models], rather than sending every new install into a settings-like list
 * before it can do anything.
 */
@Serializable data object Chat : NavKey

@Serializable data object Models : NavKey

/**
 * The conversations on this device.
 *
 * Reached from the chat screen rather than being a tab. There are two things a
 * user does here -- talk to a model, and manage the model files -- and history is
 * a detail of the first, not a third place to be.
 */
@Serializable data object History : NavKey

/**
 * What the app is, what it promises, and how to erase all of it.
 *
 * Reached from the model screen, which is where a user who is thinking about
 * what the app holds already is.
 */
@Serializable data object About : NavKey

/** How the model runs, and what it is told. */
@Serializable data object Settings : NavKey
