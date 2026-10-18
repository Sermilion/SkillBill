package skillbill.cli.featuretask

import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parsers.CommandLineParser
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeGoalContinuationLaunchTokens
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQaSelection
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FeatureTaskRuntimeQaSelectionUsageTest {
  private val tokens = FeatureTaskRuntimeGoalContinuationLaunchTokens

  @Test
  fun `an unknown qa flag value is a usage error naming the allowed values`() {
    val command = parsedProbe(listOf(tokens.QA_SELECTION_FLAG, "aut"))

    val error = assertFailsWith<UsageError> { command.requestedQaSelection(emptyMap()) }

    assertContains(error.message.orEmpty(), tokens.QA_SELECTION_FLAG)
    assertContains(error.message.orEmpty(), "'aut'")
    assertContains(error.message.orEmpty(), "Allowed: auto, off.")
  }

  @Test
  fun `repeated qa values are rejected and omission remains unspecified`() {
    val duplicate = parsedProbe(listOf(tokens.QA_SELECTION_FLAG, "off", tokens.QA_SELECTION_FLAG, "off"))
    assertFailsWith<UsageError> { duplicate.requestedQaSelection(emptyMap()) }

    assertEquals(null, parsedProbe(emptyList()).requestedQaSelection(emptyMap()))
    assertEquals(
      FeatureTaskRuntimeQaSelection.OFF,
      parsedProbe(emptyList()).requestedQaSelection(mapOf(tokens.QA_SELECTION_ENV to "off")),
    )
  }

  private fun parsedProbe(argv: List<String>): QaSelectionUsageProbeCommand {
    val command = QaSelectionUsageProbeCommand()
    CommandLineParser.parseAndRun(command, argv) { it.run() }
    return command
  }
}

private class QaSelectionUsageProbeCommand : FeatureTaskRuntimePhaseAgentCommand("probe", "probe") {
  override fun run() = Unit
}
