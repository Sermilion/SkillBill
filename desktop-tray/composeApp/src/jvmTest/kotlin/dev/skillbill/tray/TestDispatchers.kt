package dev.skillbill.tray

import dev.skillbill.tray.core.common.DispatcherProvider
import kotlinx.coroutines.Dispatchers

fun testDispatchers() = DispatcherProvider(io = Dispatchers.IO, ui = Dispatchers.IO)
