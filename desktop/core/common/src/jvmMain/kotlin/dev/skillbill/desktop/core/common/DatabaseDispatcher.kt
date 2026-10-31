package dev.skillbill.desktop.core.common

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** The dispatcher every runtime read, write and graph build runs on. Tests pass a test dispatcher. */
class DatabaseDispatcher(val dispatcher: CoroutineDispatcher = Dispatchers.IO)
