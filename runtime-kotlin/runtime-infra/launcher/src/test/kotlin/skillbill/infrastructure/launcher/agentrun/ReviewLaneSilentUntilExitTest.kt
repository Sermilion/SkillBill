package skillbill.infrastructure.launcher.agentrun

import skillbill.contracts.time.JvmSystemClock
import skillbill.infrastructure.host.jvm.testGateJvmResolver
import skillbill.infrastructure.launcher.process.launch.AgentRunProcessRequest
import skillbill.infrastructure.launcher.process.launch.AgentRunProcessResult
import skillbill.infrastructure.launcher.process.launch.AgentRunProcessRunner
import skillbill.infrastructure.launcher.process.launch.JvmAgentRunProcessRunner
import skillbill.install.model.SupportedAgent
import skillbill.ports.agentrun.model.AgentRunProgressProbe
import skillbill.ports.agentrun.model.AgentRunTermination
import skillbill.ports.agentrun.model.SkillRunRequest
import skillbill.ports.agentrun.model.withBoundedLaneProgress
import skillbill.ports.review.evidence.ReviewEvidenceBroker
import skillbill.ports.review.model.ReviewEvidenceBatchRequest
import skillbill.ports.review.model.ReviewExpansionAuthorizationRequest
import skillbill.ports.review.model.ReviewLaneAccounting
import skillbill.ports.review.model.ReviewToolCall
import skillbill.review.context.model.hunk.ReviewExpansionRecord
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class ReviewLaneSilentUntilExitTest {
  @Test
  fun `an unbounded silent review lane is not stopped and returns its register once it exits`() {
    val started = TimeSource.Monotonic.markNow()

    val facts = launch(laneRequest(), SILENT_PERIOD)

    assertEquals(AgentRunTermination.Exited(0), facts.termination)
    assertTrue(facts.stdout.contains(REGISTER_LINE), facts.stdout)
    assertTrue(started.elapsedNow() >= SILENT_PERIOD)
  }

  @Test
  fun `a bounded review lane whose probe never advances times out before the silent period ends`() {
    val started = TimeSource.Monotonic.markNow()

    val facts =
      launch(
        laneRequest().withBoundedLaneProgress(200.milliseconds, AgentRunProgressProbe { "constant" }),
        BOUNDED_SILENT_PERIOD,
      )

    assertEquals(AgentRunTermination.TimedOut, facts.termination)
    assertTrue(started.elapsedNow() < BOUNDED_SILENT_PERIOD)
  }

  private fun laneRequest(): SkillRunRequest =
    governedReviewRequest().copy(
      repoRoot = Files.createTempDirectory("review-lane-silent"),
      timeout = null,
      reviewEvidenceBroker = SilentLaneBroker,
      reviewFanOut = true,
      readOnlyPhase = false,
      progressIdleTimeout = null,
      progressProbe = AgentRunProgressProbe.NONE,
      streamProviderOutput = false,
      streamOutputForLiveness = false,
    )

  private fun launch(
    request: SkillRunRequest,
    silentPeriod: Duration,
  ) = ProcessAgentRunAdapter(
    agent = SupportedAgent.CLAUDE,
    commandBuilder = ClaudeAgentRunCommandBuilder(),
    processRunner = SilentScriptRunner(silentPeriod),
    executableLookup = ALL_EXECUTABLES_AVAILABLE,
  ).launchFacts(request)

  private class SilentScriptRunner(
    private val silentPeriod: Duration,
  ) : AgentRunProcessRunner {
    private val delegate = JvmAgentRunProcessRunner(JvmSystemClock, testGateJvmResolver())

    override fun run(request: AgentRunProcessRequest): AgentRunProcessResult =
      delegate.run(
        request.copy(
          launch =
            request.launch.copy(
              command =
                listOf(
                  bashExecutable().toString(),
                  "-c",
                  "sleep ${silentPeriod.inWholeSeconds}; echo '$REGISTER_LINE'",
                ),
            ),
        ),
      )
  }

  private object SilentLaneBroker : ReviewEvidenceBroker {
    override fun authorizeExpansion(request: ReviewExpansionAuthorizationRequest): ReviewExpansionRecord =
      error("unused")

    override fun readBatch(request: ReviewEvidenceBatchRequest) = error("unused")

    override fun recordToolCall(call: ReviewToolCall) = error("unused")

    override fun recordModelTurn() = null

    override fun validateLaneResult(result: String) = null

    override fun observeLaneResultChunk(chunk: String) = null

    override fun accounting() =
      ReviewLaneAccounting(
        lane = "architecture",
        evidenceBytes = 0,
        expansions = emptyList(),
        toolCalls = 0,
        modelTurns = 0,
        resultBytes = 0,
      )

    override fun terminalOutcome() = null
  }

  private companion object {
    val SILENT_PERIOD = 2.seconds
    val BOUNDED_SILENT_PERIOD = 30.seconds
    const val REGISTER_LINE = "[F-001] Major | High | path=\"src/A.kt\" | line=1 | silent lane register"
  }
}
