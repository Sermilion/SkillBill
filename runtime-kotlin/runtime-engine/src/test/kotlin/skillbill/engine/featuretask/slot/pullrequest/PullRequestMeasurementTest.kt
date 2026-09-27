package skillbill.engine.featuretask.slot.pullrequest

import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeMeasuredFactKeys
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.goalrunner.runner.PullRequestIdentityLookup
import skillbill.ports.goalrunner.runner.model.PullRequestIdentity
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PullRequestMeasurementTest {
  private val found = PullRequestIdentity.Found("https://github.com/o/r/pull/7", 7)

  @Test
  fun `a pull request that appears during the step is created`() {
    val diagnostics = RecordingDiagnostics()

    val facts = measurement(diagnostics).facts(PullRequestIdentity.Absent, found)

    assertEquals(
      mapOf(
        FeatureTaskRuntimeMeasuredFactKeys.PR_URL to found.url,
        FeatureTaskRuntimeMeasuredFactKeys.PR_NUMBER to found.number,
        FeatureTaskRuntimeMeasuredFactKeys.PR_CREATED to true,
      ),
      facts,
    )
    assertTrue(diagnostics.warnings.isEmpty())
  }

  @Test
  fun `a pull request that was already open is reused`() {
    val facts = measurement(RecordingDiagnostics()).facts(found, found)

    assertEquals(false, facts[FeatureTaskRuntimeMeasuredFactKeys.PR_CREATED])
  }

  @Test
  fun `an unavailable pre-step lookup leaves created unknown and emits a diagnostics record`() {
    val diagnostics = RecordingDiagnostics()

    val facts = measurement(diagnostics).facts(PullRequestIdentity.Unavailable("gh missing"), found)

    assertEquals(found.url, facts[FeatureTaskRuntimeMeasuredFactKeys.PR_URL])
    assertEquals(FeatureTaskRuntimeMeasuredFactKeys.UNKNOWN, facts[FeatureTaskRuntimeMeasuredFactKeys.PR_CREATED])
    assertEquals(1, diagnostics.warnings.size)
  }

  @Test
  fun `no pull request after the step leaves every fact unknown and emits a diagnostics record`() {
    listOf(PullRequestIdentity.Absent, PullRequestIdentity.Unavailable("gh missing")).forEach { after ->
      val diagnostics = RecordingDiagnostics()

      val facts = measurement(diagnostics).facts(PullRequestIdentity.Absent, after)

      assertEquals(
        mapOf(
          FeatureTaskRuntimeMeasuredFactKeys.PR_URL to FeatureTaskRuntimeMeasuredFactKeys.UNKNOWN,
          FeatureTaskRuntimeMeasuredFactKeys.PR_NUMBER to FeatureTaskRuntimeMeasuredFactKeys.UNKNOWN,
          FeatureTaskRuntimeMeasuredFactKeys.PR_CREATED to FeatureTaskRuntimeMeasuredFactKeys.UNKNOWN,
        ),
        facts,
      )
      assertEquals(1, diagnostics.warnings.size)
    }
  }

  @Test
  fun `a failing lookup or a missing branch is unavailable`() {
    val throwing = PullRequestIdentityLookup { _, _ -> error("network down") }
    val measurement = PullRequestMeasurement(throwing, Path.of("/tmp/repo"), RecordingDiagnostics())

    assertTrue(measurement.identity(BRANCH) is PullRequestIdentity.Unavailable)
    assertTrue(measurement.identity(null) is PullRequestIdentity.Unavailable)
  }

  private fun measurement(diagnostics: RuntimeDiagnostics): PullRequestMeasurement =
    PullRequestMeasurement({ _, _ -> PullRequestIdentity.Absent }, Path.of("/tmp/repo"), diagnostics)

  private companion object {
    const val BRANCH = "feat/SKILL-1-thing"
  }
}

private class RecordingDiagnostics : RuntimeDiagnostics {
  val warnings = mutableListOf<String>()

  override fun warning(
    message: String,
    error: Throwable?,
  ) {
    warnings += message
  }

  override fun error(
    message: String,
    error: Throwable?,
  ) = Unit
}
