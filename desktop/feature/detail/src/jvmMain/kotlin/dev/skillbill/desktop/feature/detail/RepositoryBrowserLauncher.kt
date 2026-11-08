package dev.skillbill.desktop.feature.detail

import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.domain.model.OpenOutcome
import kotlinx.coroutines.runInterruptible
import java.awt.Desktop
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit

internal class RepositoryBrowserLauncher(
  private val diagnostics: Diagnostics,
  private val browse: (URI) -> Boolean = ::browseWithDesktop,
  private val launch: suspend (List<String>) -> Int = ::launchBrowserCommand,
  private val linux: Boolean = System.getProperty("os.name").startsWith("Linux", ignoreCase = true),
) {
  suspend fun open(uri: URI): OpenOutcome {
    if (tryDesktop(uri)) return OpenOutcome.Opened
    if (!linux) return OpenOutcome.NotOpened
    diagnostics.record("detail.branch_page.xdg_open_fallback")
    return try {
      when (launch(listOf("xdg-open", uri.toASCIIString()))) {
        0 -> OpenOutcome.Opened
        else -> notOpened("detail.branch_page.xdg_open_failed")
      }
    } catch (_: IOException) {
      notOpened("detail.branch_page.xdg_open_io")
    } catch (_: SecurityException) {
      notOpened("detail.branch_page.xdg_open_denied")
    }
  }

  private fun tryDesktop(uri: URI): Boolean = try {
    browse(uri).also { supported ->
      if (!supported) diagnostics.record("detail.branch_page.browse_unsupported")
    }
  } catch (_: IOException) {
    diagnostics.record("detail.branch_page.io")
    false
  } catch (_: UnsupportedOperationException) {
    diagnostics.record("detail.branch_page.browse_unsupported")
    false
  } catch (_: SecurityException) {
    diagnostics.record("detail.branch_page.denied")
    false
  }

  private fun notOpened(category: String): OpenOutcome {
    diagnostics.record(category)
    return OpenOutcome.NotOpened
  }
}

private fun browseWithDesktop(uri: URI): Boolean {
  if (!Desktop.isDesktopSupported()) return false
  val desktop = Desktop.getDesktop()
  if (!desktop.isSupported(Desktop.Action.BROWSE)) return false
  desktop.browse(uri)
  return true
}

private suspend fun launchBrowserCommand(command: List<String>): Int = runInterruptible {
  val process = ProcessBuilder(command)
    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
    .redirectError(ProcessBuilder.Redirect.DISCARD)
    .start()
  try {
    process.outputStream.close()
    if (process.waitFor(10, TimeUnit.SECONDS)) process.exitValue() else -1
  } finally {
    if (process.isAlive) process.destroyForcibly()
  }
}
