package dev.skillbill.desktop.core.common

import kotlinx.coroutines.CoroutineDispatcher

/** The dispatchers the app runs on: `ui` for the window, `io` for files and processes, `db` for database reads. */
class AppDispatchers(
  val ui: CoroutineDispatcher,
  val io: CoroutineDispatcher,
  val db: CoroutineDispatcher,
)
