package dev.skillbill.desktop.app.tray

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

internal class KdeDisplayLayout {
  fun query(): String? {
    val process = try {
      ProcessBuilder("kscreen-doctor", "-j").redirectError(ProcessBuilder.Redirect.DISCARD).start()
    } catch (_: Exception) {
      TrayDiagnostics.record("tray_kde_layout_query_failed")
      return null
    }
    val output = CompletableFuture.supplyAsync { process.inputStream.bufferedReader().use { it.readText() } }
    return try {
      if (!process.waitFor(1_000, TimeUnit.MILLISECONDS)) {
        TrayDiagnostics.record("tray_kde_layout_query_timeout")
        null
      } else if (process.exitValue() != 0) {
        TrayDiagnostics.record("tray_kde_layout_query_failed")
        null
      } else output.get(250, TimeUnit.MILLISECONDS).takeIf(String::isNotBlank)
        ?: run { TrayDiagnostics.record("tray_kde_layout_query_failed"); null }
    } catch (_: InterruptedException) {
      Thread.currentThread().interrupt()
      TrayDiagnostics.record("tray_kde_layout_query_interrupted")
      null
    } catch (_: Exception) {
      TrayDiagnostics.record("tray_kde_layout_query_failed")
      null
    } finally {
      process.destroyForcibly()
      output.cancel(true)
    }
  }
}
