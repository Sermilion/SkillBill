package dev.skillbill.desktop.core.data.di

/**
 * Lives as long as one selected database and Claude config directory. `DatabaseSessionManager` owns it: a selection change
 * cancels the session's observations and releases its runtime graph before the next session starts.
 */
abstract class DatabaseSessionScope private constructor()
