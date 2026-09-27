package skillbill.cli.featuretask

import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parsers.CommandLineParser
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeGoalContinuationLaunchTokens
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQualityGateSelection
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FeatureTaskRuntimeQualityGateSelectionUsageTest {
  private val tokens = FeatureTaskRuntimeGoalContinuationLaunchTokens

  @Test
  fun `a misspelled quality gate flag is a usage error naming the allowed values`() {
    val command = parsedProbe(listOf(tokens.QUALITY_GATE_SELECTION_FLAG, "biuld"))

    val error = assertFailsWith<UsageError> { command.requestedQualityGateSelection(emptyMap()) }

    assertContains(error.message.orEmpty(), tokens.QUALITY_GATE_SELECTION_FLAG)
    assertContains(error.message.orEmpty(), "'biuld'")
    assertContains(error.message.orEmpty(), ALLOWED_VALUES)
  }

  @Test
  fun `a misspelled quality gate environment value is a usage error`() {
    val command = parsedProbe(emptyList())

    val error =
      assertFailsWith<UsageError> {
        command.requestedQualityGateSelection(mapOf(tokens.QUALITY_GATE_SELECTION_ENV to "biuld"))
      }

    assertContains(error.message.orEmpty(), tokens.QUALITY_GATE_SELECTION_ENV)
    assertContains(error.message.orEmpty(), "'biuld'")
    assertContains(error.message.orEmpty(), ALLOWED_VALUES)
  }

  @Test
  fun `no flag and no environment value selects validate`() {
    val command = parsedProbe(emptyList())

    assertEquals(
      FeatureTaskRuntimeQualityGateSelection.VALIDATE,
      command.requestedQualityGateSelection(emptyMap()),
    )
  }

  private fun parsedProbe(argv: List<String>): QualityGateUsageProbeCommand {
    val command = QualityGateUsageProbeCommand()
    CommandLineParser.parseAndRun(command, argv) { it.run() }
    return command
  }
}

private const val ALLOWED_VALUES = "Allowed: build, validate."

private class QualityGateUsageProbeCommand : FeatureTaskRuntimePhaseAgentCommand("probe", "probe") {
  override fun run() = Unit
}
