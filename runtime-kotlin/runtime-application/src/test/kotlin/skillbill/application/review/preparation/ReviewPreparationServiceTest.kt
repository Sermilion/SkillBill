package skillbill.application.review.preparation

import skillbill.application.review.model.ReviewPreparationRequest
import skillbill.application.review.model.ReviewPreparationResult
import skillbill.application.review.parallel.planning.criteriaReferences
import skillbill.application.review.preparation.model.ReviewLaneSelection
import skillbill.application.review.preparation.model.ReviewPreparationFacts
import skillbill.application.review.preparation.model.ReviewScopeFacts
import skillbill.application.review.preparation.model.ReviewStackRoutingFacts
import skillbill.application.reviewevidence.RawCommitDiff
import skillbill.application.reviewevidence.SharedReviewEvidenceCodec
import skillbill.application.reviewevidence.SharedReviewEvidenceCommits
import skillbill.application.reviewevidence.SharedReviewEvidenceRecord
import skillbill.application.reviewevidence.model.ReviewDiffEvidence
import skillbill.error.featuretask.FeatureTaskRuntimeSharedEvidenceFingerprintContradictionError
import skillbill.error.shellcontent.InvalidReviewContextSchemaError
import skillbill.error.shellcontent.REVIEW_HUNK_EVIDENCE_INTEGRITY
import skillbill.error.shellcontent.ReviewHunkEvidenceIntegrityError
import skillbill.error.shellcontent.ReviewHunkEvidenceLocatorMissingError
import skillbill.error.shellcontent.ReviewHunkEvidenceLocatorUnreadableError
import skillbill.ports.review.ReviewContextEnvelopeValidator
import skillbill.ports.taskruntime.FeatureTaskRuntimeSharedEvidenceLocatorReadPort
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeSharedEvidenceLocatorReadRequest
import skillbill.review.context.ReviewContextWireMap
import skillbill.review.context.model.accounting.ReviewContextBudgetPolicy
import skillbill.review.context.model.bundle.ReviewLaneBundle
import skillbill.review.context.model.commit.ReviewAssignment
import skillbill.review.context.model.commit.ReviewCommitCoverageFact
import skillbill.review.context.model.commit.ReviewCommitLaneDecision
import skillbill.review.context.model.commit.ReviewCommitLaneDisposition
import skillbill.review.context.model.commit.ReviewCommitLaneRoutingMatrix
import skillbill.review.context.model.commit.ReviewCommitSource
import skillbill.review.context.model.commit.ReviewCommitUnit
import skillbill.review.context.model.commit.ReviewLaneDecision
import skillbill.review.context.model.hunk.ReviewBaselineUntrackedPolicy
import skillbill.review.context.model.hunk.ReviewBuildTestFact
import skillbill.review.context.model.hunk.ReviewChangedHunk
import skillbill.review.context.model.hunk.ReviewDependencyAllowlist
import skillbill.review.context.model.hunk.ReviewExpansionRecord
import skillbill.review.context.model.hunk.ReviewHunkEvidenceLocator
import skillbill.review.context.model.hunk.ReviewLearningsReference
import skillbill.review.context.model.hunk.ReviewRevision
import skillbill.review.context.model.hunk.ReviewRuleReference
import skillbill.review.context.model.launch.GovernedReviewLaunch
import skillbill.review.model.ReviewLaneReviewDisposition
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private fun focusedMatrix(
  scope: ReviewScopeFacts,
  lanes: List<String>,
) = ReviewCommitLaneRoutingMatrix(
  scope.commitUnits.sortedBy { it.orderIndex }.map { it.commitSha },
  lanes,
  scope.commitUnits.sortedBy { it.orderIndex }.flatMap { unit ->
    lanes.map {
      ReviewCommitLaneDecision(unit.commitSha, unit.orderIndex, it, ReviewCommitLaneDisposition.FOCUSED, "focused")
    }
  },
)

private fun laneSelection(
  scope: ReviewScopeFacts,
  decisions: List<ReviewLaneDecision>,
): ReviewLaneSelection {
  val included = decisions.filter { it.included }.map { it.lane }
  return ReviewLaneSelection(decisions, focusedMatrix(scope, included.ifEmpty { decisions.map { it.lane } }))
}

private class RecordingValidator : ReviewContextEnvelopeValidator {
  val labels: MutableList<String> = mutableListOf()

  override fun validate(
    envelope: ReviewContextWireMap,
    sourceLabel: String,
  ) {
    labels += sourceLabel
  }
}

private class PayloadLocatorReader(private val payloadByPath: Map<String, String>) :
  FeatureTaskRuntimeSharedEvidenceLocatorReadPort {
  override fun readDiffPayload(request: FeatureTaskRuntimeSharedEvidenceLocatorReadRequest): String =
    payloadByPath[request.storePath]
      ?: throw ReviewHunkEvidenceLocatorMissingError(request.storePath)
}

private class ThrowingLocatorReader(private val error: () -> Nothing) :
  FeatureTaskRuntimeSharedEvidenceLocatorReadPort {
  override fun readDiffPayload(request: FeatureTaskRuntimeSharedEvidenceLocatorReadRequest): String = error()
}

class ReviewPreparationServiceTest {
  private val hunkA = ReviewChangedHunk("src/A.kt", 1, 1, 1, 2, "+alpha")
  private val hunkB = ReviewChangedHunk("src/B.kt", 4, 1, 4, 1, "+beta")

  internal fun includedDecision(
    lane: String,
    reason: String,
    path: String,
  ) = ReviewLaneDecision(
    lane = lane,
    included = true,
    reason = reason,
    ownedPaths = listOf(path),
    originLayerChains = listOf(listOf("kotlin")),
    owningPack = "kotlin",
    specialistSkillName = "bill-kotlin-code-review-$lane",
  )

  private fun facts(
    hunks: List<ReviewChangedHunk> = listOf(hunkB, hunkA),
    decisions: List<ReviewLaneDecision> =
      listOf(
        includedDecision("testing", "test sources changed", "src/A.kt"),
        includedDecision("security", "auth surface changed", "src/B.kt"),
        ReviewLaneDecision("ui", false, "no UI files changed"),
      ),
  ): ReviewPreparationFacts {
    val scope =
      ReviewScopeFacts(
        "acme/repo",
        "base",
        "head",
        "clean",
        hunks,
        listOf(ReviewCommitUnit("head", "base", "one commit", 0, hunks, ReviewCommitSource.COMMIT_RANGE)),
        ReviewCommitCoverageFact("base", "head", 1, chainVerified = true, pathCoverageVerified = true),
      )
    return ReviewPreparationFacts(
      scope = scope,
      stackRouting = ReviewStackRoutingFacts("kotlin", "kotlin", listOf("addon-b", "addon-a"), listOf("kotlin")),
      laneSelection = laneSelection(scope, decisions),
      matchedRules =
        listOf(
          ReviewRuleReference(
            "rule-1",
            "AGENTS.md",
            "Prefer named strategies.",
            ReviewRuleReference.digestOf("Prefer named strategies."),
          ),
        ),
      learningsReferences = listOf(FIXTURE_LEARNING),
      buildTestFacts = listOf(ReviewBuildTestFact("test", "gradle test", "passed")),
    )
  }

  private fun service(
    facts: ReviewPreparationFacts,
    validator: ReviewContextEnvelopeValidator = RecordingValidator(),
  ) = ReviewPreparationService(facts, validator)

  private fun request(allowlist: ReviewDependencyAllowlist = ReviewDependencyAllowlist(listOf("src/Dep.kt"))) =
    ReviewPreparationRequest(
      reviewId = "review",
      reviewRevision = ReviewRevision("rvs-1", 2),
      criteriaReferences = mapOf("security" to listOf("AC-002")),
      dependencyAllowlist = allowlist,
    )

  @Test fun `baseline untracked policy is packet and assignment authority`() {
    val policy =
      ReviewBaselineUntrackedPolicy(
        includedPaths = listOf("baseline/kept.kt"),
        excludedPaths = listOf("baseline/ignored.kt"),
      )
    val result =
      service(facts()).prepare(request()).let { baseline ->
        service(facts()).prepare(
          request().copy(baselineUntrackedPolicy = policy),
        ) to baseline
      }

    val withPolicy = result.first
    val withoutPolicy = result.second
    assertEquals(policy, withPolicy.packet.baselineUntrackedPolicy)
    assertTrue(withPolicy.assignments.all { it.baselineUntrackedPolicy == policy })
    assertTrue(withPolicy.packet.digest != withoutPolicy.packet.digest)
    assertTrue(withPolicy.assignments.map { it.digest } != withoutPolicy.assignments.map { it.digest })
  }

  @Test fun `a multi lane prepare projects every supplied fact onto the packet`() {
    val supplied = facts()
    val result = service(supplied).prepare(request())
    assertEquals(supplied.matchedRules, result.packet.matchedRules)
    assertEquals(supplied.learningsReferences, result.packet.learningsReferences)
    assertEquals(supplied.buildTestFacts, result.packet.buildTestFacts)
    assertEquals(2, result.assignments.size)
  }

  @Test fun `lane projection covers selected lanes and excludes rejected lanes`() {
    val result = service(facts()).prepare(request())
    assertEquals(listOf("security", "testing"), result.packet.selectedLanes)
    assertEquals(listOf("security", "testing"), result.assignments.map { it.lane })
    assertTrue(result.packet.laneDecisions.any { it.lane == "ui" && !it.included })
    assertEquals("no UI files changed", result.packet.laneDecisions.first { it.lane == "ui" }.reason)
  }

  @Test fun `preparation is deterministic across shuffled port ordering`() {
    val first = service(facts(hunks = listOf(hunkA, hunkB))).prepare(request())
    val second = service(facts(hunks = listOf(hunkB, hunkA))).prepare(request())
    assertEquals(first.packet.digest, second.packet.digest)
    assertEquals(first.packetEnvelope, second.packetEnvelope)
    assertEquals(first.assignmentEnvelopes, second.assignmentEnvelopes)
  }

  @Test fun `projection emits sorted values regardless of the order the supplied facts hold them`() {
    val unsorted =
      service(
        facts(
          hunks = listOf(hunkB, hunkA),
          decisions =
            listOf(
              includedDecision("testing", "test sources changed", "src/A.kt"),
              includedDecision("security", "auth surface changed", "src/B.kt"),
              ReviewLaneDecision("ui", false, "no UI files changed"),
            ),
        ),
      ).prepare(request()).packetEnvelope.asWireMap()

    assertEquals(listOf("security", "testing"), unsorted["selected_lanes"])
    assertEquals(listOf("addon-a", "addon-b"), unsorted["add_ons"])
    assertEquals(
      listOf("security", "testing", "ui"),
      (unsorted["lane_decisions"] as List<*>).map { (it as Map<*, *>)["lane"] },
    )
    assertEquals(
      listOf("src/A.kt", "src/B.kt"),
      (unsorted["changed_hunks"] as List<*>).map { (it as Map<*, *>)["path"] },
    )
    assertEquals(
      listOf("src/A.kt", "src/B.kt"),
      (unsorted["evidence_targets"] as List<*>).map { (it as Map<*, *>)["path"] },
    )
  }

  @Test fun `every projected envelope is schema validated before launch`() {
    val validator = RecordingValidator()
    service(facts(), validator).prepare(request())
    assertEquals(
      listOf("review-packet:review", "review-assignment:review:security", "review-assignment:review:testing"),
      validator.labels,
    )
  }

  @Test fun `assignments carry the packet digest revision and dependency allowlist`() {
    val result = service(facts()).prepare(request())
    result.assignments.forEach { assignment ->
      assertEquals(result.packet.digest, assignment.packetDigest)
      assertEquals(ReviewRevision("rvs-1", 2), assignment.reviewRevision)
      assertEquals(listOf("src/Dep.kt"), assignment.dependencyAllowlist.normalized)
    }
    assertEquals(listOf("AC-002"), result.assignments.first { it.lane == "security" }.criteriaReferences)
  }

  @Test fun `each lane receives only the hunks its decision owns`() {
    val result = service(facts()).prepare(request())
    val testing = result.assignments.first { it.lane == "testing" }
    val security = result.assignments.first { it.lane == "security" }
    assertEquals(listOf("src/A.kt"), testing.assignedPaths)
    assertEquals(listOf("src/B.kt"), security.assignedPaths)
    assertEquals(listOf(hunkA.hunkId), testing.assignedHunks)
    assertEquals(listOf(hunkB.hunkId), security.assignedHunks)
    assertTrue(testing.assignedHunks.none { it in security.assignedHunks })
    assertEquals(listOf("src/A.kt"), testing.evidenceTargets.map { it.path })
  }

  @Test fun `a lane claiming a path the packet does not own is rejected`() {
    val supplied =
      facts(
        decisions =
          listOf(
            includedDecision("testing", "test sources changed", "src/Absent.kt"),
          ),
      )
    val failure = assertFailsWith<InvalidReviewContextSchemaError> { service(supplied).prepare(request()) }
    assertTrue("claims paths the packet does not own" in failure.message.orEmpty())
  }

  @Test fun `no included lane is rejected before launch`() {
    val supplied = facts(decisions = listOf(ReviewLaneDecision("ui", false, "no UI files changed")))
    val failure = assertFailsWith<InvalidReviewContextSchemaError> { service(supplied).prepare(request()) }
    assertTrue("no included lane" in failure.message.orEmpty())
  }

  @Test fun `dependency allowlist overlapping a changed path is rejected`() {
    val failure =
      assertFailsWith<InvalidReviewContextSchemaError> {
        service(facts()).prepare(request(ReviewDependencyAllowlist(listOf("src/A.kt"))))
      }
    assertTrue("overlap changed paths" in failure.message.orEmpty())
  }

  @Test fun `assignment claiming an unowned path is rejected`() {
    val prepared = service(facts()).prepare(request())
    val foreign = prepared.assignments.first().copy(assignedPaths = listOf("src/Elsewhere.kt"))
    val failure =
      assertFailsWith<InvalidReviewContextSchemaError> {
        service(facts()).validateAgainstPacket(prepared.packet, listOf(foreign) + prepared.assignments.drop(1))
      }
    assertTrue("paths not owned by the packet" in failure.message.orEmpty())
  }

  @Test fun `assignment claiming an unowned hunk id is rejected`() {
    val prepared = service(facts()).prepare(request())
    val forged = "f".repeat(64)

    val owning = prepared.assignments.first().assignedBundle.entries.first()
    val foreign =
      prepared.assignments.first().copy(
        assignedHunks = listOf(forged),
        assignedBundle = ReviewLaneBundle(listOf(owning.copy(hunkIds = listOf(forged)))),
      )
    val failure =
      assertFailsWith<InvalidReviewContextSchemaError> {
        service(facts()).validateAgainstPacket(prepared.packet, listOf(foreign) + prepared.assignments.drop(1))
      }
    assertTrue("hunk ids not owned by the packet" in failure.message.orEmpty())
  }

  @Test fun `cross revision assignments are rejected before launch`() {
    val prepared = service(facts()).prepare(request())
    val staleDigest = prepared.assignments.first().copy(packetDigest = "a".repeat(64))
    assertTrue(
      "different review revision" in
        assertFailsWith<InvalidReviewContextSchemaError> {
          service(facts()).validateAgainstPacket(prepared.packet, listOf(staleDigest) + prepared.assignments.drop(1))
        }.message.orEmpty(),
    )
    val staleRevision = prepared.assignments.first().copy(reviewRevision = ReviewRevision("rvs-1", 9))
    assertTrue(
      "does not match packet revision" in
        assertFailsWith<InvalidReviewContextSchemaError> {
          service(facts()).validateAgainstPacket(prepared.packet, listOf(staleRevision) + prepared.assignments.drop(1))
        }.message.orEmpty(),
    )
  }

  @Test fun `assignment baseline-untracked policy is immutable`() {
    val prepared = service(facts()).prepare(request())
    val forged =
      prepared.assignments.first().copy(
        baselineUntrackedPolicy = ReviewBaselineUntrackedPolicy(includedPaths = listOf("src/New.kt")),
      )
    val failure =
      assertFailsWith<InvalidReviewContextSchemaError> {
        service(facts()).validateAgainstPacket(prepared.packet, listOf(forged) + prepared.assignments.drop(1))
      }
    assertTrue("baseline-untracked policy differs" in failure.message.orEmpty())
  }

  @Test fun `duplicate lane assignments are rejected`() {
    val prepared = service(facts()).prepare(request())
    val duplicated: List<ReviewAssignment> = listOf(prepared.assignments.first(), prepared.assignments.first())
    val failure =
      assertFailsWith<InvalidReviewContextSchemaError> {
        service(facts()).validateAgainstPacket(prepared.packet, duplicated)
      }
    assertTrue("duplicate lanes" in failure.message.orEmpty())
  }

  @Test fun `missing selected lane assignment is rejected`() {
    val prepared = service(facts()).prepare(request())
    val failure =
      assertFailsWith<InvalidReviewContextSchemaError> {
        service(facts()).validateAgainstPacket(prepared.packet, prepared.assignments.dropLast(1))
      }

    assertTrue("exactly one specialist lane per selected lane" in failure.message.orEmpty(), failure.message.orEmpty())
  }

  @Test fun `assignment must match its packet lane decision paths and hunks`() {
    val prepared = service(facts()).prepare(request())
    val first = prepared.assignments.first()
    val other = prepared.assignments.last()
    val changedDecision = first.copy(laneDecision = first.laneDecision.copy(reason = "forged"))
    assertTrue(
      "lane decision differs" in
        assertFailsWith<InvalidReviewContextSchemaError> {
          service(facts()).validateAgainstPacket(prepared.packet, listOf(changedDecision, other))
        }.message.orEmpty(),
    )
    val crossLaneHunk = first.copy(assignedHunks = other.assignedHunks, assignedBundle = other.assignedBundle)
    assertTrue(
      "focused-commit hunks" in
        assertFailsWith<InvalidReviewContextSchemaError> {
          service(facts()).validateAgainstPacket(prepared.packet, listOf(crossLaneHunk, other))
        }.message.orEmpty(),
    )
    val missingRules = first.copy(matchedRules = emptyList())
    assertTrue(
      "matched rules differ" in
        assertFailsWith<InvalidReviewContextSchemaError> {
          service(facts()).validateAgainstPacket(prepared.packet, listOf(missingRules, other))
        }.message.orEmpty(),
    )
  }

  @Test fun `assignment dependency entries outside the packet allowlist are rejected`() {
    val prepared = service(facts()).prepare(request())
    val escaping =
      prepared.assignments.first()
        .copy(dependencyAllowlist = ReviewDependencyAllowlist(listOf("src/Other.kt")))
    val failure =
      assertFailsWith<InvalidReviewContextSchemaError> {
        service(facts()).validateAgainstPacket(prepared.packet, listOf(escaping) + prepared.assignments.drop(1))
      }
    assertTrue("escapes the packet allowlist" in failure.message.orEmpty())
  }

  private fun expansion(
    assignmentDigest: String,
    id: String = "exp-1",
    sequence: Int = 0,
  ) = ReviewExpansionRecord(
    expansionId = id,
    assignmentDigest = assignmentDigest,
    requestedPath = "src/Dep.kt",
    reachabilityReason = "Assigned hunk calls into this helper.",
    authorized = true,
    sequence = sequence,
  )

  @Test fun `an expansion referencing its own assignment is accepted`() {
    val prepared = service(facts()).prepare(request())
    val assignment = prepared.assignments.first()
    val expanded = assignment.copy(expansions = listOf(expansion(assignment.digest)))
    val packet = prepared.packet.copy(expansionLedger = listOf(expansion(assignment.digest)))
    service(facts()).validateAgainstPacket(packet, listOf(expanded) + prepared.assignments.drop(1))
  }

  @Test fun `recording an expansion leaves the assignment and packet digests unchanged`() {
    val prepared = service(facts()).prepare(request())
    val assignment = prepared.assignments.first()
    val record = expansion(assignment.digest)
    assertEquals(assignment.digest, assignment.copy(expansions = listOf(record)).digest)
    assertEquals(prepared.packet.digest, prepared.packet.copy(expansionLedger = listOf(record)).digest)
  }

  @Test fun `an expansion referencing an unrelated assignment digest is rejected`() {
    val prepared = service(facts()).prepare(request())
    val stray = expansion("f".repeat(64))
    val failure =
      assertFailsWith<IllegalArgumentException> {
        prepared.assignments.first().copy(expansions = listOf(stray))
      }
    assertTrue("enclosing assignment digest" in failure.message.orEmpty())
  }

  @Test fun `an expansion cannot reference another assignment in the same review`() {
    val prepared = service(facts()).prepare(request())
    val first = prepared.assignments.first()
    val second = prepared.assignments.last()
    assertFailsWith<IllegalArgumentException> {
      first.copy(expansions = listOf(expansion(second.digest)))
    }
  }

  @Test fun `a packet ledger entry referencing an unknown assignment digest is rejected`() {
    val prepared = service(facts()).prepare(request())
    val failure =
      assertFailsWith<InvalidReviewContextSchemaError> {
        service(facts()).validateAgainstPacket(
          prepared.packet.copy(expansionLedger = listOf(expansion("e".repeat(64)))),
          prepared.assignments,
        )
      }
    assertTrue("Packet expansion ledger records" in failure.message.orEmpty())
  }

  @Test fun `an assignment over the former expansion bound still validates at preparation`() {
    val service = ReviewPreparationService(facts(), RecordingValidator())
    val prepared = service.prepare(request())
    val assignment = prepared.assignments.first()
    val overBound =
      assignment.copy(
        expansions =
          listOf(
            expansion(assignment.digest, id = "exp-1", sequence = 0),
            expansion(assignment.digest, id = "exp-2", sequence = 1),
          ),
      )
    service.validateAgainstPacket(prepared.packet, listOf(overBound) + prepared.assignments.drop(1))
  }

  @Test fun `an oversized parent packet still prepares when the former byte bound would reject it`() {
    val service = ReviewPreparationService(facts(), RecordingValidator())
    val prepared = service.prepare(request())
    assertTrue(prepared.packet.canonicalBytes > 0)
  }

  internal fun oversizedPatch(path: String = "src/Huge.kt"): String {
    val body = "+" + "x".repeat(700 * 1024)
    return "diff --git a/$path b/$path\n--- a/$path\n+++ b/$path\n@@ -1,1 +1,2 @@\n$body\n"
  }

  internal fun storePrepare(
    hunks: List<ReviewChangedHunk>,
    payload: String,
    storePath: String = ".skill-bill/run-evidence/code-review/fp-store",
    reader: FeatureTaskRuntimeSharedEvidenceLocatorReadPort = PayloadLocatorReader(mapOf(storePath to payload)),
    decisions: List<ReviewLaneDecision> =
      listOf(
        includedDecision("testing", "test sources changed", hunks.first().path),
      ),
  ): ReviewPreparationResult {
    return ReviewPreparationService(
      facts(hunks = hunks, decisions = decisions),
      RecordingValidator(),
      hunkLocatorReader = reader,
    ).prepare(
      request().copy(evidenceStorePath = storePath, repoRoot = Path.of(".")),
    )
  }

  @Test fun `blank store path with a live locator reader fails compose without launching workers`() {
    val hunk = hunkA
    var workerLaunches = 0
    val failure =
      assertFailsWith<ReviewHunkEvidenceLocatorMissingError> {
        ReviewPreparationService(
          facts(
            hunks = listOf(hunk),
            decisions = listOf(includedDecision("testing", "test sources changed", hunk.path)),
          ),
          RecordingValidator(),
          hunkLocatorReader = PayloadLocatorReader(emptyMap()),
        ).prepare(request().copy(evidenceStorePath = "", repoRoot = Path.of(".")))
      }
    assertTrue(failure.message.orEmpty().contains("review_hunk_evidence_locator_missing"))
    assertEquals(0, workerLaunches)
  }

  @Test fun `missing locator store path fails compose without launching workers`() {
    val hunk = hunkA
    var workerLaunches = 0
    val failure =
      assertFailsWith<ReviewHunkEvidenceLocatorMissingError> {
        storePrepare(
          listOf(hunk),
          "unused",
          storePath = ".skill-bill/run-evidence/code-review/missing",
          reader = PayloadLocatorReader(emptyMap()),
        )
      }
    assertTrue(failure.message.orEmpty().contains("review_hunk_evidence_locator_missing"))
    assertEquals(0, workerLaunches)
  }

  @Test fun `a store path without a locator reader fails compose instead of skipping hunk locators`() {
    val hunk = hunkA
    val storePath = ".skill-bill/run-evidence/code-review/fp-no-reader"
    val failure =
      assertFailsWith<ReviewHunkEvidenceLocatorMissingError> {
        ReviewPreparationService(
          facts(
            hunks = listOf(hunk),
            decisions = listOf(includedDecision("testing", "test sources changed", hunk.path)),
          ),
          RecordingValidator(),
          hunkLocatorReader = null,
        ).prepare(request().copy(evidenceStorePath = storePath, repoRoot = Path.of(".")))
      }
    assertEquals(storePath, failure.storePath)
  }

  @Test fun `unreadable stored payload fails compose without launching workers`() {
    val hunk = hunkA
    var workerLaunches = 0
    val storePath = ".skill-bill/run-evidence/code-review/fp-unreadable"
    val failure =
      assertFailsWith<ReviewHunkEvidenceLocatorUnreadableError> {
        storePrepare(
          listOf(hunk),
          "not-a-diff",
          storePath,
          reader = PayloadLocatorReader(mapOf(storePath to "not-a-diff")),
        )
      }
    assertTrue(failure.message.orEmpty().contains("review_hunk_evidence_locator_unreadable"))
    assertEquals(0, workerLaunches)
  }

  @Test fun `fingerprint contradiction at compose-time locator dereference is not composed`() {
    var workerLaunches = 0
    val storePath = ".skill-bill/run-evidence/code-review/fp-wrong"
    val failure =
      assertFailsWith<FeatureTaskRuntimeSharedEvidenceFingerprintContradictionError> {
        storePrepare(
          listOf(hunkA),
          oversizedPatch("src/A.kt"),
          storePath,
          reader =
            ThrowingLocatorReader {
              throw FeatureTaskRuntimeSharedEvidenceFingerprintContradictionError(
                addressedFingerprint = "fp-wrong",
                recordedFingerprint = "fp-other",
                sourceLabel = storePath,
              )
            },
        )
      }
    assertEquals("fp-wrong", failure.addressedFingerprint)
    assertEquals("fp-other", failure.recordedFingerprint)
    assertEquals(0, workerLaunches)
  }

  @Test fun `overwriting stored body without updating digest fails compose with integrity error`() {
    val original = "diff --git a/src/A.kt b/src/A.kt\n--- a/src/A.kt\n+++ b/src/A.kt\n@@ -1,1 +1,2 @@\n+alpha\n"
    val overwritten = "diff --git a/src/A.kt b/src/A.kt\n--- a/src/A.kt\n+++ b/src/A.kt\n@@ -1,1 +1,2 @@\n+omega\n"
    val hunk = ReviewDiffEvidence.parse(original).hunks.single()
    val storePath = ".skill-bill/run-evidence/code-review/fp-integrity"
    val indexed =
      hunk.asIndex(
        ReviewHunkEvidenceLocator.atStore(
          storePath,
          hunk.oldStart,
          hunk.oldCount,
          hunk.newStart,
          hunk.newCount,
        ),
        hunk.content,
      )
    assertEquals("", indexed.content)
    val expected = indexed.contentDigest
    val observed = ReviewChangedHunk.digestOfBody(ReviewDiffEvidence.parse(overwritten).hunks.single().content)
    val failure =
      assertFailsWith<ReviewHunkEvidenceIntegrityError> {
        storePrepare(listOf(indexed), overwritten, storePath)
      }
    assertEquals(storePath, failure.storePath)
    assertEquals(expected, failure.expectedDigest)
    assertEquals(observed, failure.observedDigest)
    assertTrue(failure.message.orEmpty().contains(REVIEW_HUNK_EVIDENCE_INTEGRITY))
    val expectedId = ReviewChangedHunk.idFor(hunk)
    assertEquals(indexed.hunkId, expectedId)
  }

  @Test fun `commit-scoped hunk missing from that commit does not bind the aggregate body`() {
    val aggregate =
      "diff --git a/src/A.kt b/src/A.kt\n--- a/src/A.kt\n+++ b/src/A.kt\n@@ -1,1 +1,2 @@\n+alpha\n"
    val commitDiff =
      "diff --git a/src/Other.kt b/src/Other.kt\n--- a/src/Other.kt\n+++ b/src/Other.kt\n@@ -1,1 +1,2 @@\n+other\n"
    val hunk =
      ReviewDiffEvidence.parse(aggregate).hunks.single().copy(
        commitScope = ReviewCommitUnit.commitScopeKey("head", 0),
      )
    val storePath = ".skill-bill/run-evidence/code-review/fp-commit-scope"
    val payload =
      SharedReviewEvidenceCodec.encode(
        SharedReviewEvidenceRecord(
          aggregateDiff = aggregate,
          sequence =
            SharedReviewEvidenceCommits(
              baseRevision = "base",
              headRevision = "head",
              commits = listOf(RawCommitDiff("head", "base", "one commit", commitDiff)),
              syntheticSource = null,
              syntheticReason = null,
            ),
        ),
      )
    val failure =
      assertFailsWith<ReviewHunkEvidenceLocatorUnreadableError> {
        storePrepare(listOf(hunk), payload, storePath)
      }
    assertEquals(storePath, failure.storePath)
    assertTrue(failure.message.orEmpty().contains("review_hunk_evidence_locator_unreadable"))
  }

  @Test fun `commit-scoped hunk present in that commit's stored incremental composes from those bytes`() {
    val aggregate = "diff --git a/src/A.kt b/src/A.kt\n--- a/src/A.kt\n+++ b/src/A.kt\n@@ -1,1 +1,2 @@\n+alpha\n"
    val commitDiff = "diff --git a/src/A.kt b/src/A.kt\n--- a/src/A.kt\n+++ b/src/A.kt\n@@ -1,1 +1,2 @@\n+commit-only\n"
    val storedHunk = ReviewDiffEvidence.parse(commitDiff).hunks.single()
    val hunk = storedHunk.copy(commitScope = ReviewCommitUnit.commitScopeKey("head", 0))
    val aggregateHunk = ReviewDiffEvidence.parse(aggregate).hunks.single()
    val storePath = ".skill-bill/run-evidence/code-review/fp-commit-scope-hit"
    val payload =
      SharedReviewEvidenceCodec.encode(
        SharedReviewEvidenceRecord(
          aggregateDiff = aggregate,
          sequence =
            SharedReviewEvidenceCommits(
              baseRevision = "base",
              headRevision = "head",
              commits = listOf(RawCommitDiff("head", "base", "one commit", commitDiff)),
              syntheticSource = null,
              syntheticReason = null,
            ),
        ),
      )
    val result = storePrepare(listOf(hunk), payload, storePath)
    val first = (result.packetEnvelope.asWireMap()["changed_hunks"] as List<*>).single() as Map<*, *>
    val fromStored = ReviewChangedHunk.idFor(storedHunk.copy(commitScope = hunk.commitScope))
    val fromAggregate = ReviewChangedHunk.idFor(aggregateHunk.copy(commitScope = hunk.commitScope))
    assertEquals(fromStored, first["hunk_id"])
    assertNotEquals(fromAggregate, first["hunk_id"])
    assertEquals(ReviewChangedHunk.digestOfBody(storedHunk.content), first["content_digest"])
    assertNotEquals(ReviewChangedHunk.digestOfBody(aggregateHunk.content), first["content_digest"])
    assertFalse(first.containsKey("content"))
    assertEquals("", result.packet.changedHunks.single().content)
  }
}

class ReviewPreparationServiceBudgetTest {
  private val fixture = ReviewPreparationServiceTest()

  @Test fun `oversized stored patch composes an index-only parent under the default budget`() {
    val patch = fixture.oversizedPatch()
    val hunk = ReviewDiffEvidence.parse(patch).hunks.single()
    val storePath = ".skill-bill/run-evidence/code-review/fp-oversize"
    val result = fixture.storePrepare(listOf(hunk), patch, storePath)
    val parentBytes = result.packet.canonicalBytes
    val wireHunks = result.packetEnvelope.asWireMap()["changed_hunks"] as List<*>
    val first = wireHunks.single() as Map<*, *>
    assertTrue(parentBytes < 524_288, "parent canonicalBytes $parentBytes")
    assertTrue(parentBytes < patch.toByteArray().size)
    assertEquals(hunk.hunkId, first["hunk_id"])
    assertEquals(ReviewChangedHunk.digestOfBody(hunk.content), first["content_digest"])
    assertFalse(first.containsKey("content"))
    val locator = first["evidence_locator"] as Map<*, *>
    assertEquals(storePath, locator["store_path"])
    assertEquals("diff.patch", locator["payload_file"])
    assertEquals(1, result.assignments.size)
    assertEquals(result.packet.ownedHunkIds, result.assignments.single().assignedHunks.toSet())
    assertTrue(result.assignments.single().assignedHunks.all { it in result.packet.ownedHunkIds })
    val assignmentWire = result.assignmentEnvelopes.single().asWireMap()
    assertFalse(assignmentWire.containsKey("changed_hunks"))
    assertTrue((assignmentWire["assigned_hunks"] as List<*>).all { it is String })
    val assignmentBytes = assignmentWire.toString().toByteArray().size
    assertTrue(assignmentBytes < patch.toByteArray().size / 10, "assignment envelope $assignmentBytes")
    result.packet.changedHunks.forEach { assertEquals("", it.content) }
    val launch =
      GovernedReviewLaunch(
        result.assignments.single(),
        result.packet,
        "contract",
        "rubric",
        "broker",
        ReviewContextBudgetPolicy.DEFAULT,
      )
    assertEquals(ReviewLaneReviewDisposition.COMPLETE, launch.completionState.disposition)
    assertEquals(null, launch.completionState.budgetDimension)
    assertTrue(launch.completionState.unreviewedUnits.isEmpty())
  }

  @Test fun `evidence overflow on one assignment leaves the sibling selected and complete`() {
    val hugePatch = fixture.oversizedPatch("src/A.kt")
    val smallPatch = "diff --git a/src/B.kt b/src/B.kt\n--- a/src/B.kt\n+++ b/src/B.kt\n@@ -1,1 +1,2 @@\n+beta\n"
    val stored = hugePatch + smallPatch
    val parsed = ReviewDiffEvidence.parse(stored).hunks
    val huge = parsed.single { it.path == "src/A.kt" }
    val small = parsed.single { it.path == "src/B.kt" }
    val storePath = ".skill-bill/run-evidence/code-review/fp-sibling"
    val result =
      fixture.storePrepare(
        listOf(huge, small),
        stored,
        storePath,
        decisions =
          listOf(
            fixture.includedDecision("testing", "test sources changed", "src/A.kt").copy(required = true),
            fixture.includedDecision("security", "auth surface changed", "src/B.kt"),
          ),
      )
    assertEquals(listOf("security", "testing"), result.packet.selectedLanes)
    assertTrue(result.packet.canonicalBytes < 524_288)
    val testing =
      GovernedReviewLaunch(
        result.assignments.single { it.lane == "testing" },
        result.packet,
        "contract",
        "rubric",
        "broker",
        ReviewContextBudgetPolicy.DEFAULT,
      )
    val security =
      GovernedReviewLaunch(
        result.assignments.single { it.lane == "security" },
        result.packet,
        "contract",
        "rubric",
        "broker",
        ReviewContextBudgetPolicy.DEFAULT,
      )
    assertEquals(ReviewLaneReviewDisposition.COMPLETE, testing.completionState.disposition)
    assertEquals(null, testing.completionState.budgetDimension)
    assertTrue(testing.completionState.unreviewedUnits.isEmpty())
    assertEquals(ReviewLaneReviewDisposition.COMPLETE, security.completionState.disposition)
    assertTrue(result.packet.laneDecisions.single { it.lane == "testing" }.required)
  }

  @Test fun `assignment-scaled specialist budgets differ when lane assignments differ in breadth`() {
    val hugePatch = fixture.oversizedPatch("src/A.kt")
    val smallPatch = "diff --git a/src/B.kt b/src/B.kt\n--- a/src/B.kt\n+++ b/src/B.kt\n@@ -1,1 +1,2 @@\n+beta\n"
    val stored = hugePatch + smallPatch
    val parsed = ReviewDiffEvidence.parse(stored).hunks
    val result =
      fixture.storePrepare(
        parsed,
        stored,
        ".skill-bill/run-evidence/code-review/fp-budget-scale",
        decisions =
          listOf(
            fixture.includedDecision("testing", "test sources changed", "src/A.kt").copy(required = true),
            fixture.includedDecision("security", "auth surface changed", "src/B.kt"),
          ),
      )
    val base = ReviewContextBudgetPolicy.DEFAULT
    val testingBudget =
      deriveSpecialistBudget(
        base,
        result.assignments.single { it.lane == "testing" },
        result.packet,
      ).maxLaneEvidenceBytes
    val securityBudget =
      deriveSpecialistBudget(
        base,
        result.assignments.single { it.lane == "security" },
        result.packet,
      ).maxLaneEvidenceBytes
    assertNotEquals(testingBudget, securityBudget)
    assertTrue(testingBudget > securityBudget)
  }
}

private const val FIXTURE_LEARNING_RULE = "Prefer named strategies over identity branching."

private val FIXTURE_LEARNING =
  ReviewLearningsReference(
    learningId = "L-001",
    source = "repo:acme/repo",
    scope = "repo",
    title = "Name strategies",
    ruleText = FIXTURE_LEARNING_RULE,
    digest = ReviewLearningsReference.digestOf(FIXTURE_LEARNING_RULE),
  )
