package skillbill.infrastructure.launcher.agentrun

import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.SkillRunGoalContinuationContext
import skillbill.ports.agentrun.model.SkillRunRequest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

internal const val AGENT_RUN_LAUNCHER_PHASE_PROMPT =
  "Phase: plan\nTask: produce an ordered plan.\nRequired final output: one raw JSON object."

internal fun ProcessAgentRunAdapter.launchFacts(request: SkillRunRequest): AgentRunLaunchFacts =
  assertIs<AgentRunLaunchFacts>(launch(request))

internal fun skillRunRequest(
  issueKey: String = "SKILL-56",
  goalContinuation: SkillRunGoalContinuationContext? = goalContinuationContext(),
): SkillRunRequest =
  SkillRunRequest(
    issueKey = issueKey,
    repoRoot = Path.of("/tmp/skillbill-agent-run"),
    subtaskId = 2,
    timeout = 3.seconds,
    goalContinuation = goalContinuation,
  )

internal fun goalContinuationContext(childWorkflowId: String? = "wfl-child-runtime"): SkillRunGoalContinuationContext =
  SkillRunGoalContinuationContext(
    parentIssueKey = "SKILL-56",
    subtaskId = 2,
    goalBranch = "feat/SKILL-56-goal",
    suppressPr = true,
    specPath = ".feature-specs/SKILL-56-goal/spec_subtask_2.md",
    parentWorkflowId = "wfl-parent",
    lastResumableStep = "implement",
    childWorkflowId = childWorkflowId,
  )

internal fun repoRoot(): Path {
  var current: Path? = Path.of("").toAbsolutePath().normalize()
  while (current != null) {
    if (Files.isRegularFile(current.resolve("install.sh")) && Files.isDirectory(current.resolve("runtime-kotlin"))) {
      return current
    }
    current = current.parent
  }
  error("Could not locate repository root from ${Path.of("").toAbsolutePath().normalize()}")
}

internal fun bashExecutable(): Path =
  listOf(Path.of("/usr/bin/bash"), Path.of("/bin/bash")).firstOrNull(Files::isExecutable)
    ?: error("Could not locate bash executable")
