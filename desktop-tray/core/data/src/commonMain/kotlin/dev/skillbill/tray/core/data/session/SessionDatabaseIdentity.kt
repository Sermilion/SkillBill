package dev.skillbill.tray.core.data.session

import dev.skillbill.tray.core.common.di.DatabaseSessionScope
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import kotlin.concurrent.Volatile

data class DatabaseFileIdentity(val path: String, val fileKey: String)

sealed interface DatabaseIdentityCapture {
  data class Captured(val identity: DatabaseFileIdentity) : DatabaseIdentityCapture

  data class Unavailable(val reason: String) : DatabaseIdentityCapture
}

@Inject
@SingleIn(DatabaseSessionScope::class)
class SessionDatabaseIdentity {
  @Volatile
  var capture: DatabaseIdentityCapture = DatabaseIdentityCapture.Unavailable("The selected database identity is unavailable.")
    private set
  private var initialized = false

  fun initialize(value: DatabaseIdentityCapture) {
    check(!initialized)
    initialized = true
    capture = value
  }

  fun clear() {
    capture = DatabaseIdentityCapture.Unavailable("The database session is closed.")
  }
}
