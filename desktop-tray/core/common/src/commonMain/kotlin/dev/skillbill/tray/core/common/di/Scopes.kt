package dev.skillbill.tray.core.common.di

/** Lives as long as the application process; owns preferences and the database-session factory. */
abstract class AppScope private constructor()

/** Lives as long as one selected database; closing it releases the runtime graph, Room handles, and observers. */
abstract class DatabaseSessionScope private constructor()

/** Lives as long as one desktop window is not disposed. Hiding a window keeps it; disposing it or exiting the app ends it. */
abstract class WindowScope private constructor()

/** Lives as long as one navigation entry is on a window's back stack, or until its window or database generation ends. */
abstract class EntryScope private constructor()
