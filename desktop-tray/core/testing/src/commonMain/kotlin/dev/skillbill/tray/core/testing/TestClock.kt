package dev.skillbill.tray.core.testing

import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlin.time.Instant

class TestClock(@Volatile var current: Instant) : Clock {
  override fun now(): Instant = current
}
