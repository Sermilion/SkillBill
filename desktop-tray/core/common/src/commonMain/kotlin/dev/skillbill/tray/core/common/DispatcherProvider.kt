package dev.skillbill.tray.core.common

import kotlinx.coroutines.CoroutineDispatcher

/** [ui] is the dispatcher every navigation and window state change runs on; [io] is for blocking storage and process work. */
class DispatcherProvider(val io: CoroutineDispatcher, val ui: CoroutineDispatcher)
