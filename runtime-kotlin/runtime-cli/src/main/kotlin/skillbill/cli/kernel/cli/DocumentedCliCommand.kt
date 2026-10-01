package skillbill.cli.kernel.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.NoOpCliktCommand
import com.github.ajalt.clikt.core.UsageError

abstract class DocumentedCliCommand(
  name: String,
  private val helpText: String,
) : CliktCommand(name) {
  override fun help(context: Context): String = helpText
}

abstract class DocumentedNoOpCliCommand(
  name: String,
  private val helpText: String,
) : NoOpCliktCommand(name) {
  override fun help(context: Context): String = helpText
}

internal fun usageError(error: Throwable): Nothing {
  throw UsageError(error.message.orEmpty()).also { usage ->
    runCatching { usage.initCause(error) }
  }
}
