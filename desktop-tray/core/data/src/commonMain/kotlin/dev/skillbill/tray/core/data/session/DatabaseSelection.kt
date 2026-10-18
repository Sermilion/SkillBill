package dev.skillbill.tray.core.data.session

import dev.skillbill.tray.core.common.di.DatabaseSessionScope
import kotlinx.coroutines.flow.MutableStateFlow
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * The database a session component was created for. The path is chosen exactly once, before anything else in the
 * session component is read, so every session-scoped binding sees the same selection. `null` means "use the runtime's
 * default database".
 */
@Inject
@SingleIn(DatabaseSessionScope::class)
class DatabaseSelection {
  private class Choice(val path: String?)

  private val chosen = MutableStateFlow<Choice?>(null)

  fun choose(path: String?) {
    check(chosen.compareAndSet(null, Choice(path))) { "A database session selects its database once." }
  }

  val path: String? get() = checkNotNull(chosen.value) { "The database session has no selected database yet." }.path
}
