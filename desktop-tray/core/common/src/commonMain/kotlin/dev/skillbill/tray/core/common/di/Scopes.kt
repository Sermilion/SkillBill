package dev.skillbill.tray.core.common.di

/** Lives as long as the application process; owns preferences and the database-session factory. */
abstract class AppScope private constructor()

/** Lives as long as one selected database; closing it releases the runtime graph, Room handles, and observers. */
abstract class DatabaseSessionScope private constructor()
