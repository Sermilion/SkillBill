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
    emit(Level.WARNING, oneLine(message, error))
  }

  override fun info(message: String) {
    emit(Level.INFO, message)
  }

  override fun error(
    message: String,
    error: Throwable?,
  ) {
    emit(Level.SEVERE, message, error)
  }

  private fun emit(
    level: Level,
    text: String,
    thrown: Throwable? = null,
  ) {
    val frame =
      StackWalker.getInstance().walk { frames ->
        frames.filter { frame -> !isDiagnosticsFrame(frame.className) }.findFirst().orElse(null)
      }
    val callerClass = frame?.className ?: JdkRuntimeDiagnostics::class.java.name
    val callerMethod = frame?.methodName ?: ""
    log.logp(level, callerClass, callerMethod, text, thrown)
  }

  private fun oneLine(
    message: String,
    error: Throwable?,
  ): String {
    if (error == null) return message
    val detail = error.message?.replace('\n', ' ')?.replace('\r', ' ')
    return "$message (${error::class.java.simpleName}: $detail)"
  }

  private fun isDiagnosticsFrame(className: String): Boolean =
    isClassOrNested(className, JdkRuntimeDiagnostics::class.java.name) ||
      isClassOrNested(className, RuntimeDiagnostics::class.java.name) ||
      className.substringAfterLast('.').substringBefore('$') == "RuntimeDiagnosticsBestEffortWarning"

  private fun isClassOrNested(
    className: String,
    ownerName: String,
  ): Boolean = className == ownerName || className.startsWith("$ownerName$")
}
