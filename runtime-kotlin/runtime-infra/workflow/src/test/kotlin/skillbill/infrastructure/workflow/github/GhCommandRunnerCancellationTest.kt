package skillbill.infrastructure.workflow.github

import skillbill.infrastructure.workflow.monitor.GhMonitorPullRequestSource
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.monitor.model.MonitorRepositoryIdentity
import java.nio.file.Path
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GhCommandRunnerCancellationTest {
  @Test
  fun `process runner propagates interruption and cancellation`() {
    assertFailsWith<InterruptedException> {
      ProcessGhCommandRunner(ghExecutableResolver = { throw InterruptedException("stopped") }).run(
        Path.of("."),
        emptyList(),
      )
    }
    assertFailsWith<CancellationException> {
      ProcessGhCommandRunner(ghExecutableResolver = { throw CancellationException("stopped") }).run(
        Path.of("."),
        emptyList(),
      )
    }
  }

  @Test
  fun `cancelled monitor poll propagates without a poll failure diagnostic`() {
    val diagnostics = RecordingDiagnostics()
    val source =
      GhMonitorPullRequestSource(
        GhCommandRunner { _, _ -> throw InterruptedException("stopped") },
        diagnostics,
      )

    assertFailsWith<InterruptedException> {
      source.pullRequest(MonitorRepositoryIdentity.canonical("github.com", "acme", "repo"), 427)
    }
    assertEquals(emptyList(), diagnostics.warnings)
  }
}

private class RecordingDiagnostics : RuntimeDiagnostics {
  val warnings = mutableListOf<Pair<String, Throwable?>>()

  override fun warning(
    message: String,
    error: Throwable?,
  ) {
    warnings += message to error
  }

  override fun error(
    message: String,
    error: Throwable?,
  ) = Unit

  override fun info(message: String) = Unit
}
