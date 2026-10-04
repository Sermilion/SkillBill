package skillbill.application.review.verification

import skillbill.application.review.model.ReviewClaimVerificationRunRequest
import skillbill.application.review.model.ReviewDelegatedStageLaunch
import skillbill.application.review.parallel.runner.finding
import skillbill.application.runner
import skillbill.application.testHarnessClock
import skillbill.install.model.SupportedAgent
import skillbill.ports.agentrun.agentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunTermination
import skillbill.ports.goalrunner.runner.GoalRunnerSubtaskLauncher
import skillbill.ports.goalrunner.runner.model.GoalRunnerSubtaskLaunchRequest
import skillbill.ports.review.ReviewContextEnvelopeValidator
import skillbill.review.context.model.accounting.ReviewContextBudgetPolicy
import skillbill.review.context.model.commit.ReviewCommitCoverageFact
import skillbill.review.context.model.commit.ReviewCommitLaneDecision
import skillbill.review.context.model.commit.ReviewCommitLaneDisposition
import skillbill.review.context.model.commit.ReviewCommitLaneRoutingMatrix
import skillbill.review.context.model.commit.ReviewCommitSource
import skillbill.review.context.model.commit.ReviewCommitUnit
import skillbill.review.context.model.commit.ReviewLaneDecision
import skillbill.review.context.model.execution.ResolvedReviewExecutionMode
import skillbill.review.context.model.hunk.ReviewChangedHunk
import skillbill.review.context.model.hunk.ReviewDependencyAllowlist
import skillbill.review.context.model.hunk.ReviewRevision
import skillbill.review.context.model.packet.ReviewContextPacket
import skillbill.review.context.model.packet.ReviewPacketConsumerContract
import skillbill.review.model.ParallelReviewMergedFinding
import skillbill.review.model.ParallelReviewSeverity
import skillbill.review.model.ReviewClaimVerdict
import skillbill.review.model.ReviewFindingVerdict
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ReviewClaimVerificationRunnerTest {
  @Test
  fun `each finding launches alone without siblings narrative or parent transcript`() {
    val launches = mutableListOf<GoalRunnerSubtaskLaunchRequest>()
    val envelopes = mutableListOf<Map<String, Any?>>()
    val outcome =
      runner(
        launcher = { request ->
          launches += request
          facts(request, CONFIRMED)
        },
        validator = { envelope, _ -> envelopes += envelope },
      ).run(
        verificationRequest(
          findings = listOf(finding("F-001"), finding("F-002", "src/B.kt:4", "other bug")),
          repoRoot = Files.createTempDirectory("verify-isolation"),
        ),
      )
    assertEquals(2, launches.size)
    assertEquals(2, envelopes.size)
    envelopes.forEach { envelope ->
      assertEquals("verification_launch", envelope["kind"])
      assertFalse(envelope.containsKey("spec_intent_projection"))
      assertFalse(envelope.containsKey("narrative"))
      assertFalse(envelope.containsKey("transcript"))
      assertFalse(envelope.containsKey("parent_transcript"))
      val finding = envelope["finding"] as Map<*, *>
      assertEquals(setOf("finding_ref", "severity", "location", "description", "confidence"), finding.keys)
    }
    val refs = envelopes.map { (it["finding"] as Map<*, *>)["finding_ref"] }
    assertEquals(listOf("F-001", "F-002"), refs)
    launches.forEachIndexed { index, launch ->
      val prompt = launch.skillRunRequest.promptOverride.orEmpty()
      val own = refs[index] as String
      val other = refs[1 - index] as String
      assertTrue(own in prompt)
      assertFalse(other in prompt)
    }
    assertEquals(listOf("F-001", "F-002"), outcome.verdicts.map { it.findingRef })
    assertTrue(outcome.verdicts.all { it.claimVerdict == ReviewClaimVerdict.CONFIRMED })
  }

  @Test
  fun `a worker that fails to spawn times out or returns unparseable output leaves the finding unresolved`() {
    val responses =
      ArrayDeque(
        listOf(
          factsFor { copy(termination = AgentRunTermination.SpawnFailed, stdout = "") },
          factsFor { copy(termination = AgentRunTermination.TimedOut, stdout = "") },
          factsFor { copy(stdout = "not a verdict") },
        ),
      )
    val outcome =
      runner(
        launcher = { request -> responses.removeFirst().invoke(request) },
      ).run(
        verificationRequest(
          findings =
            listOf(
              finding("F-001"),
              finding("F-002", "src/B.kt:4", "second"),
              finding("F-003", "src/C.kt:8", "third"),
            ),
          mode = ResolvedReviewExecutionMode.DELEGATED,
          repoRoot = Files.createTempDirectory("verify-failure"),
        ),
      )
    assertEquals(3, outcome.verdicts.size)
    assertTrue(outcome.verdicts.all { it.claimVerdict == ReviewClaimVerdict.UNRESOLVED })
    assertEquals("agent process failed to spawn", outcome.verdicts[0].rejectionReason)
    assertEquals("agent timed out", outcome.verdicts[1].rejectionReason)
    assertEquals("worker did not settle the claim", outcome.verdicts[2].rejectionReason)
  }

  @Test
  fun `prose naming more than one verdict is ambiguous and leaves the finding unresolved`() {
    val outcome = verifyProse("This is confirmed in one branch but refuted in src/A.kt:12 for the other.")

    assertEquals(ReviewClaimVerdict.UNRESOLVED, outcome.verdicts.single().claimVerdict)
  }

  @Test
  fun `prose refutation without a file line citation does not refute`() {
    val outcome = verifyProse("The finding is refuted; the null is handled upstream.")

    assertEquals(ReviewClaimVerdict.UNRESOLVED, outcome.verdicts.single().claimVerdict)
  }

  @Test
  fun `prose refutation with a file line citation refutes`() {
    val outcome = verifyProse("The finding is refuted: the null is handled at src/A.kt:12.")

    assertEquals(ReviewClaimVerdict.REFUTED, outcome.verdicts.single().claimVerdict)
  }

  @Test
  fun `blank verification prose leaves the finding unresolved`() {
    val outcome = verifyProse("   ")

    assertEquals(ReviewClaimVerdict.UNRESOLVED, outcome.verdicts.single().claimVerdict)
  }

  private fun verifyProse(stdout: String) =
    runner(launcher = { request -> facts(request, stdout) }).run(
      verificationRequest(
        findings = listOf(finding("F-001")),
        repoRoot = Files.createTempDirectory("verify-prose"),
      ),
    )

  @Test
  fun `zero worker citation lines are coerced while the finding verdict still settles`() {
    val stdout =
      """
      {
        "claim_verdict": "refuted",
        "citations": [
          {"path": "src/A.kt", "line": 12},
          {"path": "src/A.kt", "line": 0}
        ]
      }
      """.trimIndent()
    val outcome =
      runner(
        launcher = { request -> facts(request, stdout) },
      ).run(
        verificationRequest(
          findings = listOf(finding("F-001")),
          repoRoot = Files.createTempDirectory("verify-malformed-citation"),
        ),
      )
    assertEquals(ReviewClaimVerdict.REFUTED, outcome.verdicts.single().claimVerdict)
    assertEquals(
      listOf(12, 1),
      outcome.verdicts.single().citations.map { it.line },
    )
    assertTrue(outcome.citationDiagnostics.isEmpty())
  }

  @Test
  fun `inline bounds evidence to the cited region while delegated permits brokered expansion`() {
    val inline = envelopesFor(ResolvedReviewExecutionMode.INLINE)
    val delegated = envelopesFor(ResolvedReviewExecutionMode.DELEGATED)
    val inlineRules = inline.single()["evidence_surface_rules"] as String
    val delegatedRules = delegated.single()["evidence_surface_rules"] as String
    assertEquals(ReviewPacketConsumerContract.INLINE_VERIFICATION_EVIDENCE_SURFACE, inlineRules)
    assertEquals(ReviewPacketConsumerContract.DELEGATED_VERIFICATION_EVIDENCE_SURFACE, delegatedRules)
    assertTrue("Cited region and direct callers" in inlineRules)
    assertFalse("expansion ledger is permitted" in inlineRules)
    assertTrue("expansion ledger is permitted" in delegatedRules)
    assertEquals(inline.single()["finding"], delegated.single()["finding"])
    assertFalse(inline.single().containsKey("spec_intent_projection"))
    assertFalse(delegated.single().containsKey("spec_intent_projection"))
  }

  private fun envelopesFor(mode: ResolvedReviewExecutionMode): List<Map<String, Any?>> {
    val envelopes = mutableListOf<Map<String, Any?>>()
    runner(
      launcher = { request -> facts(request, CONFIRMED) },
      validator = { envelope, _ -> envelopes += envelope },
    ).run(
      verificationRequest(
        findings = listOf(finding("F-001")),
        mode = mode,
        repoRoot = Files.createTempDirectory("verify-depth"),
      ),
    )
    return envelopes
  }

  private fun verificationRequest(
    findings: List<ParallelReviewMergedFinding>,
    existingVerdicts: List<ReviewFindingVerdict> = emptyList(),
    mode: ResolvedReviewExecutionMode = ResolvedReviewExecutionMode.INLINE,
    repoRoot: Path = Files.createTempDirectory("verify"),
  ) = ReviewClaimVerificationRunRequest(
    packet = packet(),
    findings = findings,
    existingVerdicts = existingVerdicts,
    mode = mode,
    launch =
      ReviewDelegatedStageLaunch(
        budget = ReviewContextBudgetPolicy.DEFAULT,
        brokerId = "codex",
        repoRoot = repoRoot,
        timeout = 1.seconds,
      ),
  )

  private fun runner(
    launcher: GoalRunnerSubtaskLauncher,
    validator: ReviewContextEnvelopeValidator = ReviewContextEnvelopeValidator { _, _ -> },
  ) = ReviewClaimVerificationRunner(launcher, validator, testHarnessClock)

  private fun facts(
    request: GoalRunnerSubtaskLaunchRequest,
    stdout: String,
  ) = agentRunLaunchFacts(
    agent = SupportedAgent.fromNormalizedId(request.invokedAgentId, label = "agentId"),
    stdout = stdout,
  )

  private fun factsFor(
    mutate: AgentRunLaunchFacts.() -> AgentRunLaunchFacts,
  ): (GoalRunnerSubtaskLaunchRequest) -> AgentRunLaunchFacts =
    { request ->
      facts(request, CONFIRMED).mutate()
    }

  private fun finding(
    ref: String,
    location: String = "src/A.kt:12",
    description: String = "Null is not checked.",
  ) = ParallelReviewMergedFinding(
    fNumber = ref,
    agentIds = listOf("codex"),
    severity = ParallelReviewSeverity.MAJOR,
    confidence = "High",
    location = location,
    description = description,
    repositoryPath = location.substringBefore(':'),
    line = location.substringAfter(':').toInt(),
  )

  private fun packet(): ReviewContextPacket {
    val hunk = ReviewChangedHunk("src/A.kt", 1, 1, 1, 2, "+alpha")
    val lanes = listOf("security")
    return ReviewContextPacket(
      reviewId = "review",
      repositoryIdentity = "repo",
      baseRevision = "base",
      headRevision = "head",
      status = "clean",
      stack = "kotlin",
      pack = "kotlin",
      addOns = emptyList(),
      selectedLanes = lanes,
      changedHunks = listOf(hunk),
      commitUnits =
        listOf(
          ReviewCommitUnit("head", "base", "change", 0, listOf(hunk), ReviewCommitSource.COMMIT_RANGE),
        ),
      coverageFact = ReviewCommitCoverageFact("base", "head", 1, chainVerified = true, pathCoverageVerified = true),
      routingMatrix =
        ReviewCommitLaneRoutingMatrix(
          listOf("head"),
          lanes,
          listOf(ReviewCommitLaneDecision("head", 0, "security", ReviewCommitLaneDisposition.FOCUSED, "focused")),
        ),
      reviewRevision = ReviewRevision("rvs-1", 1),
      laneDecisions =
        listOf(
          ReviewLaneDecision(
            "security",
            true,
            "routed",
            ownedPaths = listOf("src/A.kt"),
            originLayerChains = listOf(listOf("kotlin")),
            owningPack = "kotlin",
            specialistSkillName = "bill-kotlin-code-review-security",
          ),
        ),
      dependencyAllowlist = ReviewDependencyAllowlist(listOf("src/Dep.kt")),
    )
  }

  private companion object {
    const val CONFIRMED: String = """{"claim_verdict":"confirmed"}"""
  }
}
