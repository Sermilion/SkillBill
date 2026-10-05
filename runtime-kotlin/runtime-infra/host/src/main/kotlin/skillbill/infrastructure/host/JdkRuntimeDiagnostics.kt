package skillbill.infrastructure.host

import me.tatarka.inject.annotations.Inject
import skillbill.ports.diagnostics.RuntimeDiagnostics
import java.util.logging.Level
import java.util.logging.Logger

@Inject
class JdkRuntimeDiagnostics : RuntimeDiagnostics {
  private val log: Logger = Logger.getLogger(JdkRuntimeDiagnostics::class.java.name)

  override fun warning(
    message: String,
    error: Throwable?,
  ) {
    log(Level.WARNING, message, error)
  }

  override fun info(message: String) {
    log(Level.INFO, message, null)
  }

  override fun error(
    message: String,
    error: Throwable?,
  ) {
    log(Level.SEVERE, message, error)
  }

  private fun log(
    level: Level,
    message: String,
    error: Throwable?,
  ) {
    if (error == null) {
      log.log(level, message)
    } else {
      log.log(level, message, error)
    }
  }
}
