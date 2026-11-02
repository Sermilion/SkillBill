package dev.skillbill.desktop.core.data.repository

import dev.skillbill.desktop.core.common.di.AppScope
import kotlinx.coroutines.sync.Mutex
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@Inject
@SingleIn(AppScope::class)
class GitReadLock {
  val mutex = Mutex()
}
