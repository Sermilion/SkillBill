package dev.skillbill.desktop.core.common

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * The dispatcher every runtime read, write and graph build runs on. Two slots let one read run beside a write or a session
 * switch without letting the runtime's blocking SQLite calls take every IO thread. Tests pass a test dispatcher.
 */
class DatabaseDispatcher(val dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(2))
