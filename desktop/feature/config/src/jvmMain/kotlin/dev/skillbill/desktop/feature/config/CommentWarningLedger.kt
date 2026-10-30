package dev.skillbill.desktop.feature.config

import dev.skillbill.desktop.core.common.di.AppScope
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import java.util.concurrent.ConcurrentHashMap

@Inject
@SingleIn(AppScope::class)
class CommentWarningLedger {
  private val paths = ConcurrentHashMap.newKeySet<String>()

  fun confirmed(path: String): Boolean = path in paths

  fun confirm(path: String) {
    paths.add(path)
  }
}
