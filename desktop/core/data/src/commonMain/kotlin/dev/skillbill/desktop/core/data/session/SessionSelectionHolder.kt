package dev.skillbill.desktop.core.data.session

import dev.skillbill.desktop.core.data.di.DatabaseSessionScope
import kotlinx.coroutines.flow.MutableStateFlow
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/** The selection a session component was created for, chosen once right after creation and before any binding reads it. */
@Inject
@SingleIn(DatabaseSessionScope::class)
class SessionSelectionHolder {
  private val chosen = MutableStateFlow<SessionSelection?>(null)

  fun choose(selection: SessionSelection) {
    check(chosen.compareAndSet(null, selection)) { "A database session selects its database once." }
  }

  val selection: SessionSelection get() = checkNotNull(chosen.value) { "The database session has no selection yet." }
}
