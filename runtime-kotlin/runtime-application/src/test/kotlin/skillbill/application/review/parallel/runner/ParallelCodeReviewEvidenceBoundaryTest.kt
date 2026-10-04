package skillbill.application.review.parallel.runner

import skillbill.application.review.snapshot.RecordedWorkerResponse
import skillbill.application.review.snapshot.ReviewHarnessConfig
import skillbill.application.review.snapshot.ReviewRecorder
import skillbill.application.review.snapshot.brokerDenyingUnit
import skillbill.application.review.snapshot.diffForChanges
import skillbill.application.review.snapshot.diffForPaths
import skillbill.application.review.snapshot.harnessRequest
import skillbill.application.review.snapshot.reviewHarness
import skillbill.application.review.snapshot.reviewPack
import skillbill.application.review.snapshot.reviewed
import skillbill.application.runner
import skillbill.goalrunner.terminalStatus
import skillbill.install.model.SupportedAgent
import skillbill.ports.agentrun.model.AgentRunTermination
import skillbill.ports.agentrun.model.UnsupportedAgentRunLaunch
import skillbill.ports.review.evidence.GovernedReviewEvidenceEndpointBinder
import skillbill.ports.review.evidence.GovernedReviewEvidenceEndpointHandle
import skillbill.ports.review.evidence.ReviewEvidenceBroker
import skillbill.ports.review.evidence.ReviewEvidenceBrokerFactory
import skillbill.ports.review.model.GovernedReviewEvidenceEndpointDescriptor
import skillbill.ports.review.model.ReviewEvidenceBrokerBinding
import skillbill.review.context.model.accounting.ReviewContextBudgetPolicy
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.review.context.model.packet.LANE_EVIDENCE_BYTES_DIMENSION
import skillbill.review.model.ReviewEvidenceBoundaryAccounting
import skillbill.review.model.ReviewLaneReviewDisposition
import skillbill.review.model.ReviewStageDegradationReason
import skillbill.scaffold.model.ReviewLaneCondition
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ParallelCodeReviewEvidenceBoundaryTest {
  @Test
  fun `broker bind failure emits unbound degradation and a non-success lane`() {
    val recorder = ReviewRecorder()
    val result =
      reviewHarness(
        ReviewHarnessConfig(
          manifests = listOf(reviewPack("kotlin", listOf("architecture"), routingSignals = listOf("*.kt"))),
          diff = diffForPaths("src/Repo.kt"),
          evidenceBrokerFactory = ReviewEvidenceBrokerFactory { error("broker construction failed") },
        ),
        recorder,
      ).reviewed(
        harnessRequest(
          reviewRunId = "rvw-195-unbound",
          codeReviewMode = CodeReviewExecutionMode.DELEGATED,
        ),
      )

    assertFalse(result.lane1.success)
    assertEquals("governed evidence broker construction failed", result.lane1.failureReason)
    val unbound =
      recorder.stageDegradations.filter {
        it.reason == ReviewStageDegradationReason.EVIDENCE_BOUNDARY_UNBOUND_BROKER
      }
    assertEquals(1, unbound.size)
    assertEquals(ReviewEvidenceBoundaryAccounting.GOVERNED_EVIDENCE_SEAM, unbound.single().seam)
    assertEquals("unbound", unbound.single().actual)
  }

  @Test
  fun `endpoint bind failure emits the unbound degradation and launches nothing`() {
    val recorder = ReviewRecorder()
    val result =
      reviewHarness(
        ReviewHarnessConfig(
          manifests = listOf(reviewPack("kotlin", listOf("architecture"), routingSignals = listOf("*.kt"))),
          diff = diffForPaths("src/Repo.kt"),
          evidenceEndpointBinder =
            object : GovernedReviewEvidenceEndpointBinder {
              override fun bind(
                lane: String,
                broker: ReviewEvidenceBroker,
                onEvidenceRead: (() -> Unit)?,
              ): GovernedReviewEvidenceEndpointHandle = error("endpoint bind failed")
            },
          parentLaunch = { error("a governed review must not launch when its evidence endpoint is unbound") },
        ),
        recorder,
      ).reviewed(
        harnessRequest(
          reviewRunId = "rvw-195-endpoint-unbound",
          codeReviewMode = CodeReviewExecutionMode.DELEGATED,
        ),
      )

    assertFalse(result.lane1.success)
    assertEquals("governed evidence broker endpoint failed", result.lane1.failureReason)
    val unbound =
      recorder.stageDegradations.filter {
        it.reason == ReviewStageDegradationReason.EVIDENCE_BOUNDARY_UNBOUND_BROKER
      }
    assertEquals(1, unbound.size)
    assertEquals(ReviewEvidenceBoundaryAccounting.GOVERNED_EVIDENCE_SEAM, unbound.single().seam)
  }

  @Test
  fun `governed launch with locators and zero authorized reads emits one unexercised-boundary record`() {
    val recorder = ReviewRecorder()
    reviewHarness(
      ReviewHarnessConfig(
        manifests = listOf(reviewPack("kotlin", listOf("architecture"), routingSignals = listOf("*.kt"))),
        diff = diffForPaths("src/Repo.kt"),
        simulateEvidenceReads = false,
      ),
      recorder,
    ).reviewed(
      harnessRequest(
        reviewRunId = "rvw-195-unexercised",
        codeReviewMode = CodeReviewExecutionMode.DELEGATED,
      ),
    )

    val unexercised =
      recorder.stageDegradations.filter {
        it.reason == ReviewStageDegradationReason.EVIDENCE_BOUNDARY_UNEXERCISED
      }
    assertEquals(1, unexercised.size)
    assertEquals(ReviewEvidenceBoundaryAccounting.GOVERNED_EVIDENCE_SEAM, unexercised.single().seam)
    assertEquals("authorized_reads=0", unexercised.single().actual)
    assertEquals(1, recorder.parentLaunches.size)
    assertNotNull(recorder.parentLaunches.single().skillRunRequest.reviewEvidenceBroker)
  }

  @Test
  fun `a lane reporting an authorized read with zero evidence bytes suppresses the unexercised-boundary record`() {
    val recorder = ReviewRecorder()
    val defaults =
      ReviewHarnessConfig(
        manifests = listOf(reviewPack("kotlin", listOf("architecture"), routingSignals = listOf("*.kt"))),
        diff = diffForPaths("src/Repo.kt"),
      )
    val result =
      reviewHarness(
        defaults.copy(
          evidenceBrokerFactory =
            ReviewEvidenceBrokerFactory { binding ->
              val inner = defaults.evidenceBrokerFactory.brokerFor(binding)
              object : ReviewEvidenceBroker by inner {
                override fun accounting() = inner.accounting().copy(authorizedReadCount = 1, evidenceBytes = 0)
              }
            },
        ),
        recorder,
      ).reviewed(
        harnessRequest(
          reviewRunId = "rvw-195-zero-byte",
          codeReviewMode = CodeReviewExecutionMode.DELEGATED,
        ),
      )

    assertEquals(1, result.lane1.accounting?.authorizedReadCount)
    assertEquals(0L, result.lane1.accounting?.evidenceBytes)
    assertTrue(
      recorder.stageDegradations.none {
        it.reason == ReviewStageDegradationReason.EVIDENCE_BOUNDARY_UNEXERCISED
      },
    )
  }

  @Test
  fun `parse rejection of an inadmissible path emits one rejected-candidate record carrying the count`() {
    val recorder = ReviewRecorder()
    val rejectedLocation =
      "Outside path noted; no register gate.\nverdict: approved"
    val result =
      reviewHarness(
        ReviewHarnessConfig(
          manifests = listOf(reviewPack("kotlin", listOf("architecture"), routingSignals = listOf("*.kt"))),
          diff = diffForPaths("src/Repo.kt"),
          response = { RecordedWorkerResponse(stdout = rejectedLocation) },
        ),
        recorder,
      ).reviewed(
        harnessRequest(
          reviewRunId = "rvw-195-rejected",
          codeReviewMode = CodeReviewExecutionMode.DELEGATED,
        ),
      )

    assertTrue(result.lane1.success)
    assertEquals(emptyList(), result.mergeResult.findings)
    assertEquals(rejectedLocation, result.mergeResult.formattedOutput)
    assertTrue(
      recorder.stageDegradations.none {
        it.reason == ReviewStageDegradationReason.REGISTER_CANDIDATES_REJECTED
      },
    )
  }

  @Test
  fun `a governed review whose launcher never starts still tears down the bound endpoint`() {
    val directory = Files.createTempDirectory("skill-bill-review-evidence-")
    val socketPath = directory.resolve("evidence.sock")
    val configPath = directory.resolve("mcp.json")
    Files.createFile(socketPath)
    Files.createFile(configPath)

    val result =
      reviewHarness(
        ReviewHarnessConfig(
          manifests = listOf(reviewPack("kotlin", listOf("architecture"), routingSignals = listOf("*.kt"))),
          diff = diffForPaths("src/Repo.kt"),
          evidenceEndpointBinder =
            object : GovernedReviewEvidenceEndpointBinder {
              override fun bind(
                lane: String,
                broker: ReviewEvidenceBroker,
                onEvidenceRead: (() -> Unit)?,
              ): GovernedReviewEvidenceEndpointHandle =
                object : GovernedReviewEvidenceEndpointHandle {
                  override val descriptor =
                    GovernedReviewEvidenceEndpointDescriptor(
                      lane = lane,
                      socketPath = socketPath,
                      mcpConfigPath = configPath,
                      token = "unavailable-cli",
                    )

                  override fun close() {
                    Files.deleteIfExists(socketPath)
                    Files.deleteIfExists(configPath)
                    Files.deleteIfExists(directory)
                  }
                }
            },
          response = { RecordedWorkerResponse(termination = AgentRunTermination.SpawnFailed, processStarted = false) },
        ),
        ReviewRecorder(),
      ).reviewed(
        harnessRequest(
          reviewRunId = "rvw-195-unavailable-cli",
          codeReviewMode = CodeReviewExecutionMode.DELEGATED,
        ),
      )

    assertFalse(result.lane1.success)
    assertFalse(Files.exists(socketPath))
    assertFalse(Files.exists(directory))
  }

  @Test
  fun `unsupported provider omits unbound-broker and unexercised evidence records`() {
    val recorder = ReviewRecorder()
    val result =
      reviewHarness(
        ReviewHarnessConfig(
          manifests = listOf(reviewPack("kotlin", listOf("architecture"), routingSignals = listOf("*.kt"))),
          diff = diffForPaths("src/Repo.kt"),
          parentLaunch = { request ->
            UnsupportedAgentRunLaunch(
              agent = SupportedAgent.fromNormalizedId(request.invokedAgentId, label = "agentId"),
              reason = "not configured for this repo",
            )
          },
        ),
        recorder,
      ).reviewed(
        harnessRequest(
          reviewRunId = "rvw-195-unsupported",
          codeReviewMode = CodeReviewExecutionMode.DELEGATED,
        ),
      )

    assertFalse(result.lane1.success)
    assertEquals(UNSUPPORTED_PROVIDER_TERMINAL_STATUS.wireValue, result.lane1.accounting?.terminalStatus)
    assertTrue(
      recorder.stageDegradations.none {
        it.reason == ReviewStageDegradationReason.EVIDENCE_BOUNDARY_UNBOUND_BROKER ||
          it.reason == ReviewStageDegradationReason.EVIDENCE_BOUNDARY_UNEXERCISED
      },
    )
  }

  @Test
  fun `bound unread parent lane fails without claiming the broker was unbound`() {
    val recorder = ReviewRecorder()
    val binds = AtomicInteger()
    val defaults =
      ReviewHarnessConfig(
        manifests = listOf(reviewPack("kotlin", listOf("architecture"), routingSignals = listOf("*.kt"))),
        diff = diffForPaths("src/Repo.kt"),
        simulateEvidenceReads = false,
        response = { RecordedWorkerResponse(stdout = "verdict: approved") },
      )
    val result =
      reviewHarness(
        defaults.copy(
          evidenceBrokerFactory =
            ReviewEvidenceBrokerFactory { binding ->
              binds.incrementAndGet()
              defaults.evidenceBrokerFactory.brokerFor(binding)
            },
        ),
        recorder,
      ).reviewed(
        harnessRequest(
          reviewRunId = "rvw-195-mixed-lanes",
          codeReviewMode = CodeReviewExecutionMode.DELEGATED,
        ),
      )

    assertEquals(1, binds.get(), "single-agent review binds one parent evidence surface")
    assertFalse(result.lane1.success)
    assertTrue(
      recorder.stageDegradations.none {
        it.reason == ReviewStageDegradationReason.EVIDENCE_BOUNDARY_UNBOUND_BROKER
      },
    )
  }

  @Test
  fun `a lane reporting approval after reading no evidence fails`() {
    val recorder = ReviewRecorder()
    val result =
      reviewHarness(
        ReviewHarnessConfig(
          manifests = listOf(reviewPack("kotlin", listOf("architecture"), routingSignals = listOf("*.kt"))),
          diff = diffForPaths("src/Repo.kt"),
          simulateEvidenceReads = false,
          response = { RecordedWorkerResponse(stdout = "No issues noted.\nverdict: approved") },
        ),
        recorder,
      ).reviewed(
        harnessRequest(
          reviewRunId = "rvw-198-unread-clean",
          codeReviewMode = CodeReviewExecutionMode.DELEGATED,
        ),
      )

    assertEquals(0, result.lane1.accounting?.authorizedReadCount)
    assertFalse(result.lane1.success)
    assertFalse(assertNotNull(result.coverage).isCleanCoverage)
    assertEquals(emptyList(), result.mergeResult.findings)
  }

  @Test
  fun `the parent binds one evidence surface covering every routed area it was selected for`() {
    val recorder = ReviewRecorder()
    val bound = mutableListOf<Pair<ReviewEvidenceBrokerBinding, ReviewEvidenceBroker>>()
    val defaults =
      ReviewHarnessConfig(
        manifests =
          listOf(
            reviewPack("kotlin", listOf("architecture", "security"), routingSignals = listOf("*.kt")).copy(
              laneConditions =
                mapOf(
                  "architecture" to ReviewLaneCondition(path = listOf("src/core/")),
                  "security" to ReviewLaneCondition(path = listOf("src/secure/")),
                ),
            ),
          ),
        diff = diffForPaths("src/core/Repo.kt", "src/secure/Auth.kt"),
      )
    reviewHarness(
      defaults.copy(
        evidenceBrokerFactory =
          ReviewEvidenceBrokerFactory { binding ->
            defaults.evidenceBrokerFactory.brokerFor(binding).also { bound += binding to it }
          },
      ),
      recorder,
    ).reviewed(
      harnessRequest(
        reviewRunId = "rvw-198-inline-union",
        codeReviewMode = CodeReviewExecutionMode.DELEGATED,
      ),
    )

    val (binding, broker) = bound.single()
    assertTrue(
      binding.assignment.assignedPaths.containsAll(listOf("src/core/Repo.kt", "src/secure/Auth.kt")),
      "the parent reaches the broker through one endpoint that stamps one lane, so its " +
        "surface must cover every routed area; it covered ${binding.assignment.assignedPaths}",
    )

    assertEquals(
      setOf("src/core/Repo.kt", "src/secure/Auth.kt"),
      binding.projectedHunks.map { it.path }.toSet(),
      "the merged surface must carry every routed area's hunks, not just its paths",
    )
    assertEquals(
      binding.assignment.assignedHunks.toSet(),
      binding.projectedHunks.map { it.hunkId }.toSet(),
    )
  }

  @Test
  fun `the parent evidence allowance equals sum of per-lane derived caps not base times lane count`() {
    val recorder = ReviewRecorder()
    val bound = mutableListOf<ReviewEvidenceBrokerBinding>()
    val defaults =
      ReviewHarnessConfig(
        manifests =
          listOf(
            reviewPack("kotlin", listOf("architecture", "security"), routingSignals = listOf("*.kt")).copy(
              laneConditions =
                mapOf(
                  "architecture" to ReviewLaneCondition(path = listOf("src/core/")),
                  "security" to ReviewLaneCondition(path = listOf("src/secure/")),
                ),
            ),
          ),
        diff =
          diffForChanges(
            "src/core/Repo.kt" to "x".repeat(200_000),
            "src/secure/Auth.kt" to "ok",
          ),
      )
    reviewHarness(
      defaults.copy(
        evidenceBrokerFactory =
          ReviewEvidenceBrokerFactory { binding ->
            defaults.evidenceBrokerFactory.brokerFor(binding).also { bound += binding }
          },
      ),
      recorder,
    ).reviewed(
      harnessRequest(
        reviewRunId = "rvw-201-parent-derived-budget",
        codeReviewMode = CodeReviewExecutionMode.DELEGATED,
      ),
    )

    val parentBinding = bound.single()
    val base = ReviewContextBudgetPolicy.DEFAULT.maxLaneEvidenceBytes
    assertNotEquals(base * 2, parentBinding.budget.maxLaneEvidenceBytes)
    assertTrue(parentBinding.budget.maxLaneEvidenceBytes < base * 2)
  }

  @Test
  fun `broker lane evidence refusal reports incomplete naming only denied units`() {
    val recorder = ReviewRecorder()
    val result =
      reviewHarness(
        ReviewHarnessConfig(
          manifests = listOf(reviewPack("kotlin", listOf("architecture"), routingSignals = listOf("*.kt"))),
          diff =
            diffForChanges(
              "src/A.kt" to "a",
              "src/B.kt" to "b".repeat(200),
            ),
          evidenceBrokerFactory = brokerDenyingUnit("src/B.kt"),
        ),
        recorder,
      ).reviewed(
        harnessRequest(
          reviewRunId = "rvw-201-broker-refusal",
          codeReviewMode = CodeReviewExecutionMode.DELEGATED,
        ),
      )

    assertTrue(result.lane1.success)
    val accounting = assertNotNull(result.lane1.accounting)
    assertEquals(ReviewLaneReviewDisposition.INCOMPLETE, accounting.reviewDisposition)
    assertEquals(LANE_EVIDENCE_BYTES_DIMENSION, accounting.budgetDimension)
    assertEquals(1, accounting.unreviewedUnits.size)
    assertTrue(accounting.unreviewedUnits.single().endsWith("@src/B.kt"))
    assertFalse(accounting.unreviewedUnits.any { it.endsWith("@src/A.kt") })
    val coverage = assertNotNull(result.coverage)
    assertFalse(coverage.isCleanCoverage)
  }

  @Test
  fun `successful governed run with no broker refusal stays complete for lane evidence bytes`() {
    val recorder = ReviewRecorder()
    val result =
      reviewHarness(
        ReviewHarnessConfig(
          manifests = listOf(reviewPack("kotlin", listOf("architecture"), routingSignals = listOf("*.kt"))),
          diff = diffForPaths("src/Repo.kt"),
        ),
        recorder,
      ).reviewed(
        harnessRequest(
          reviewRunId = "rvw-201-broker-clean",
          codeReviewMode = CodeReviewExecutionMode.DELEGATED,
        ),
      )

    assertTrue(result.lane1.success)
    val accounting = assertNotNull(result.lane1.accounting)
    assertEquals(ReviewLaneReviewDisposition.COMPLETE, accounting.reviewDisposition)
    assertTrue(accounting.unreviewedUnits.isEmpty())
    assertEquals(null, accounting.budgetDimension)
    assertTrue(assertNotNull(result.coverage).isCleanCoverage)
  }
}
