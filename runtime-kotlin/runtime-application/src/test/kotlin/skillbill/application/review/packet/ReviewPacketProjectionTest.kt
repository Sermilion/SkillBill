package skillbill.application.review.packet

import skillbill.contracts.JsonCodec
import skillbill.contracts.review.REVIEW_CONTEXT_CONTRACT_VERSION
import skillbill.error.shellcontent.InvalidReviewContextSchemaError
import skillbill.review.context.model.accounting.ReviewContextBudgetPolicy
import skillbill.review.context.model.bundle.ReviewLaneBundle
import skillbill.review.context.model.bundle.ReviewLaneBundleEntry
import skillbill.review.context.model.commit.ReviewAssignment
import skillbill.review.context.model.commit.ReviewCommitCoverageFact
import skillbill.review.context.model.commit.ReviewCommitLaneDecision
import skillbill.review.context.model.commit.ReviewCommitLaneDisposition
import skillbill.review.context.model.commit.ReviewCommitLaneRoutingMatrix
import skillbill.review.context.model.commit.ReviewCommitSource
import skillbill.review.context.model.commit.ReviewCommitUnit
import skillbill.review.context.model.commit.ReviewLaneDecision
import skillbill.review.context.model.hunk.REVIEW_RULE_EXCERPT_MAX_CHARS
import skillbill.review.context.model.hunk.ReviewBuildTestFact
import skillbill.review.context.model.hunk.ReviewChangedHunk
import skillbill.review.context.model.hunk.ReviewDependencyAllowlist
import skillbill.review.context.model.hunk.ReviewEvidenceTarget
import skillbill.review.context.model.hunk.ReviewExpansionRecord
import skillbill.review.context.model.hunk.ReviewLearningsReference
import skillbill.review.context.model.hunk.ReviewRevision
import skillbill.review.context.model.hunk.ReviewRuleReference
import skillbill.review.context.model.launch.GovernedReviewIntegrationLaunch
import skillbill.review.context.model.launch.GovernedReviewLaunch
import skillbill.review.context.model.launch.ReviewSpecialistSummary
import skillbill.review.context.model.packet.ReviewContextPacket
import skillbill.review.context.model.packet.ReviewPacketConsumerContract
import skillbill.review.model.ReviewLaneReviewDisposition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ReviewPacketProjectionTest {
  private fun includedDecision(
    lane: String,
    paths: List<String>,
  ) = ReviewLaneDecision(
    lane,
    true,
    "routed",
    ownedPaths = paths,
    originLayerChains = listOf(listOf("kotlin")),
    owningPack = "kotlin",
    specialistSkillName = "bill-kotlin-code-review-$lane",
  )

  private val hunkA = ReviewChangedHunk("src/A.kt", 1, 1, 1, 2, "+alpha")
  private val hunkB = ReviewChangedHunk("src/B.kt", 4, 1, 4, 1, "+beta")
  private val rule =
    ReviewRuleReference(
      "rule-1",
      "AGENTS.md",
      "Prefer named strategies.",
      ReviewRuleReference.digestOf("Prefer named strategies."),
    )
  private val learningRule = "Prefer named strategies over identity branching."
  private val learning =
    ReviewLearningsReference(
      learningId = "L-001",
      source = "repo:acme/repo",
      scope = "repo",
      title = "Name strategies",
      ruleText = learningRule,
      digest = ReviewLearningsReference.digestOf(learningRule),
    )
  private val revision = ReviewRevision("rvs-1", 3)

  private fun packet(
    hunks: List<ReviewChangedHunk> = listOf(hunkA, hunkB),
    lanes: List<String> = listOf("security", "testing"),
    decisions: List<ReviewLaneDecision> =
      lanes.map { lane ->
        includedDecision(lane, hunks.map { it.path }.distinct())
      },
    allowlist: ReviewDependencyAllowlist = ReviewDependencyAllowlist(listOf("src/Dep.kt")),
  ) = ReviewContextPacket(
    reviewId = "review",
    repositoryIdentity = "acme/repo",
    baseRevision = "base",
    headRevision = "head",
    status = "clean",
    stack = "kotlin",
    pack = "kotlin",
    addOns = listOf("z-addon", "a-addon"),
    selectedLanes = lanes,
    changedHunks = hunks,
    commitUnits = listOf(commitUnit(hunks)),
    coverageFact = ReviewCommitCoverageFact("base", "head", 1, chainVerified = true, pathCoverageVerified = true),
    routingMatrix = focusedMatrix(lanes),
    reviewRevision = revision,
    laneDecisions = decisions,
    matchedRules = listOf(rule),
    learningsReferences = listOf(learning),
    buildTestFacts = listOf(ReviewBuildTestFact("test", "gradle test", "passed")),
    dependencyAllowlist = allowlist,
    evidenceTargets = listOf(ReviewEvidenceTarget("src/A.kt", "src/A.kt", listOf(hunkA.hunkId))),
  )

  private fun commitUnit(hunks: List<ReviewChangedHunk>) =
    ReviewCommitUnit(
      commitSha = "head",
      parentSha = "base",
      subject = "single commit",
      orderIndex = 0,
      hunks = hunks.sortedBy { it.path },
      source = ReviewCommitSource.COMMIT_RANGE,
    )

  private fun focusedMatrix(lanes: List<String>) =
    ReviewCommitLaneRoutingMatrix(
      listOf("head"),
      lanes,
      lanes.map { ReviewCommitLaneDecision("head", 0, it, ReviewCommitLaneDisposition.FOCUSED, "focused") },
    )

  private fun bundle(vararg hunkIds: String) =
    ReviewLaneBundle(listOf(ReviewLaneBundleEntry("head", 0, hunkIds.toList())))

  @Test fun `hunk ids are content addressed not positional`() {
    assertEquals(hunkA.hunkId, ReviewChangedHunk("src/A.kt", 1, 1, 1, 2, "+alpha").hunkId)
    assertNotEquals(
      ReviewChangedHunk("src/A.kt", 1, 1, 1, 2, "+alpha\n").hunkId,
      ReviewChangedHunk("src\\A.kt", 1, 1, 1, 2, "+alpha\r\n").hunkId,
    )
    assertNotEquals(hunkA.hunkId, ReviewChangedHunk("src/A.kt", 1, 1, 1, 2, "+alphaX").hunkId)
    assertTrue(hunkA.hunkId.matches(Regex("[a-f0-9]{64}")))
  }

  @Test fun `packet digest is order insensitive and revision sensitive`() {
    assertEquals(packet(listOf(hunkA, hunkB)).digest, packet(listOf(hunkB, hunkA)).digest)
    assertNotEquals(
      packet().digest,
      packet().copy(reviewRevision = ReviewRevision("rvs-1", 4)).digest,
    )
    assertNotEquals(packet().digest, packet(allowlist = ReviewDependencyAllowlist.EMPTY).digest)
  }

  @Test fun `parent packet envelope is deterministic and schema shaped`() {
    val first = packet(listOf(hunkA, hunkB)).toParentPacketEnvelope().asWireMap()
    val second = packet(listOf(hunkB, hunkA)).toParentPacketEnvelope().asWireMap()
    assertEquals(first.keys.toList(), second.keys.toList())
    assertEquals(first, second)
    assertEquals(REVIEW_CONTEXT_CONTRACT_VERSION, first["contract_version"])
    assertEquals("parent_packet", first["kind"])
    assertEquals(listOf("a-addon", "z-addon"), first["add_ons"])
    assertEquals(listOf("security", "testing"), first["selected_lanes"])
    assertEquals(listOf("src/Dep.kt"), first["dependency_allowlist"])
    val hunks = requireNotNull(JsonCodec.anyToStringAnyMapList(first["changed_hunks"]))
    assertEquals(
      true,
      hunks.all {
        it.containsKey("hunk_id") && it.containsKey("content_digest") && it.containsKey("evidence_locator")
      },
    )
    assertEquals(false, hunks.any { it.containsKey("content") })
  }

  @Test fun `lane decisions must cover exactly the selected lanes`() {
    assertFailsWith<IllegalArgumentException> {
      packet(decisions = listOf(includedDecision("security", listOf("src/A.kt"))))
    }
    val withExclusion =
      packet(
        lanes = listOf("security"),
        decisions =
          listOf(
            includedDecision("security", listOf("src/A.kt")),
            ReviewLaneDecision("testing", false, "no test files changed"),
          ),
      )
    assertEquals(listOf("security"), withExclusion.selectedLanes)
  }

  @Test fun `lane decisions require a reason`() {
    assertFailsWith<IllegalArgumentException> { ReviewLaneDecision("security", false, "  ") }
  }

  @Test fun `expansion records require a reachability reason`() {
    assertFailsWith<InvalidReviewContextSchemaError> {
      ReviewExpansionRecord("exp-1", "d".repeat(64), "src/C.kt", " ", true, 0)
    }
  }

  @Test fun `rule excerpts are bounded and digested`() {
    assertFailsWith<IllegalArgumentException> {
      ReviewRuleReference("rule-1", "AGENTS.md", "x".repeat(REVIEW_RULE_EXCERPT_MAX_CHARS + 1), "b".repeat(64))
    }
    assertFailsWith<IllegalArgumentException> { ReviewRuleReference("rule-1", "AGENTS.md", "ok", "not-a-digest") }
    assertFailsWith<IllegalArgumentException> {
      ReviewRuleReference("rule-1", "AGENTS.md", "ok", "b".repeat(64))
    }
  }

  @Test fun `dependency allowlists reject traversal and duplicates`() {
    assertFailsWith<IllegalArgumentException> { ReviewDependencyAllowlist(listOf("../secret")) }
    assertFailsWith<IllegalArgumentException> { ReviewDependencyAllowlist(listOf("src/A.kt", "src/A.kt")) }
    assertEquals(
      listOf("src/A.kt", "src\\A.kt"),
      ReviewDependencyAllowlist(listOf("src/A.kt", "src\\A.kt")).normalized,
    )
  }

  @Test fun `assignment rejects dependency entries overlapping assigned paths`() {
    assertFailsWith<IllegalArgumentException> {
      ReviewAssignment(
        reviewId = "review",
        packetDigest = "a".repeat(64),
        lane = "security",
        baseRevision = "base",
        headRevision = "head",
        assignedPaths = listOf("src/A.kt"),
        assignedHunks = listOf(hunkA.hunkId),
        reviewRevision = revision,
        laneDecision = includedDecision("security", listOf("src/A.kt")),
        dependencyAllowlist = ReviewDependencyAllowlist(listOf("src/A.kt")),
      )
    }
  }

  @Test fun `assignment envelope omits its own digest from the digest input`() {
    val base = packet()
    val assignment =
      ReviewAssignment(
        reviewId = base.reviewId,
        packetDigest = base.digest,
        lane = "security",
        baseRevision = base.baseRevision,
        headRevision = base.headRevision,
        assignedPaths = listOf("src/A.kt", "src/B.kt"),
        assignedHunks = listOf(hunkB.hunkId, hunkA.hunkId),
        assignedBundle = bundle(hunkA.hunkId, hunkB.hunkId),
        matchedRules = listOf(rule),
        evidenceTargets = base.evidenceTargets,
        reviewRevision = revision,
        laneDecision = includedDecision("security", listOf("src/A.kt", "src/B.kt")),
        dependencyAllowlist = base.dependencyAllowlist,
      )
    val envelope = assignment.toAssignmentEnvelope().asWireMap()
    assertEquals(assignment.digest, envelope["assignment_digest"])
    assertEquals(listOf(hunkA.hunkId, hunkB.hunkId).sorted(), envelope["assigned_hunks"])
    val reordered = assignment.copy(assignedHunks = listOf(hunkA.hunkId, hunkB.hunkId))
    assertEquals(assignment.digest, reordered.digest)
  }

  @Test fun `launch envelope carries the closed world lane projection`() {
    val base = packet()
    val assignment =
      ReviewAssignment(
        reviewId = base.reviewId,
        packetDigest = base.digest,
        lane = "security",
        baseRevision = base.baseRevision,
        headRevision = base.headRevision,
        assignedPaths = listOf("src/A.kt", "src/B.kt"),
        assignedHunks = listOf(hunkA.hunkId, hunkB.hunkId),
        assignedBundle = bundle(hunkA.hunkId, hunkB.hunkId),
        laneRouting = base.routingMatrix.decisionsFor("security"),
        reviewRevision = revision,
        laneDecision = base.laneDecisions.first { it.lane == "security" },
        matchedRules = base.matchedRules,
        evidenceTargets = base.evidenceTargets,
        dependencyAllowlist = base.dependencyAllowlist,
      )
    val envelope =
      GovernedReviewLaunch(assignment, base, "contract", "rubric", "broker", ReviewContextBudgetPolicy.DEFAULT)
        .toLaunchEnvelope().asWireMap()
    assertEquals(ReviewPacketConsumerContract.FORBIDDEN_REDISCOVERY, envelope["forbidden_rediscovery"])
    assertEquals(ReviewPacketConsumerContract.CONSUMER_CONTRACT, envelope["consumer_contract"])
    assertEquals(ReviewPacketConsumerContract.EVIDENCE_SURFACE_RULES, envelope["evidence_surface_rules"])
    assertEquals(ReviewPacketConsumerContract.REPORT_STRUCTURE, envelope["report_structure"])
    val bundle = requireNotNull(JsonCodec.anyToStringAnyMap(envelope["bundle"]))
    val entries = requireNotNull(JsonCodec.anyToStringAnyMapList(bundle["entries"]))
    assertEquals(2, entries.size)
    assertEquals(
      true,
      entries.all {
        it.containsKey("hunk_id") && it.containsKey("content_digest") && it.containsKey("evidence_locator")
      },
    )
    assertEquals(false, entries.any { it.containsKey("content") })
    assertEquals(setOf(hunkA.hunkId, hunkB.hunkId), entries.map { it["hunk_id"] }.toSet())
    assertEquals(true, entries.all { it.containsKey("commit_sha") && it.containsKey("order_index") })
    assertEquals(false, envelope.containsKey("assigned_hunk_bodies"))
    assertEquals(false, envelope.containsKey("complete_diff"))
    assertEquals(false, envelope.containsKey("diff_path"))
    assertEquals(false, envelope.containsKey("parent_packet"))
    assertEquals("fresh", envelope["isolation"])
    assertEquals(mapOf("session_id" to "rvs-1", "run_revision" to 3), envelope["review_revision"])
  }

  @Test fun `launch envelope bundle matches canonical payload bundle content`() {
    val base = packet()
    val assignment =
      ReviewAssignment(
        reviewId = base.reviewId,
        packetDigest = base.digest,
        lane = "security",
        baseRevision = base.baseRevision,
        headRevision = base.headRevision,
        assignedPaths = listOf("src/A.kt", "src/B.kt"),
        assignedHunks = listOf(hunkA.hunkId, hunkB.hunkId),
        assignedBundle = bundle(hunkA.hunkId, hunkB.hunkId),
        laneRouting = base.routingMatrix.decisionsFor("security"),
        reviewRevision = revision,
        laneDecision = base.laneDecisions.first { it.lane == "security" },
        matchedRules = base.matchedRules,
        evidenceTargets = base.evidenceTargets,
        dependencyAllowlist = base.dependencyAllowlist,
      )
    val launch =
      GovernedReviewLaunch(assignment, base, "contract", "rubric", "broker", ReviewContextBudgetPolicy.DEFAULT)
    val envelope = launch.toLaunchEnvelope().asWireMap()
    val envelopeEntries =
      requireNotNull(
        JsonCodec.anyToStringAnyMapList(
          requireNotNull(JsonCodec.anyToStringAnyMap((envelope["bundle"])))["entries"],
        ),
      )
    val payloadHunkIds =
      Regex("""hunk_id: ([a-f0-9]{64})""")
        .findAll(launch.canonicalPayload)
        .map { it.groupValues[1] }
        .toSet()
    assertEquals(envelopeEntries.map { it["hunk_id"] }.toSet(), payloadHunkIds)
    assertEquals(launch.assembledBundle.compositionDigest, (envelope["bundle"] as Map<*, *>)["composition_digest"])
  }

  @Test fun `parent packet envelope carries the commit sequence digest`() {
    val base = packet()
    val envelope = base.toParentPacketEnvelope().asWireMap()

    assertEquals(base.commitSequenceDigest, envelope["commit_sequence_digest"])
    assertTrue(base.commitSequenceDigest.matches(Regex("[a-f0-9]{64}")))
  }

  @Test fun `integration launch envelope excludes lane bundles evidence and parent transcripts`() {
    val base = packet()
    val envelope = integrationLaunch(base).toIntegrationLaunchEnvelope().asWireMap()

    assertEquals(REVIEW_CONTEXT_CONTRACT_VERSION, envelope["contract_version"])
    assertEquals("integration_launch", envelope["kind"])
    assertEquals(base.commitSequenceDigest, envelope["commit_sequence_digest"])
    assertEquals(ReviewPacketConsumerContract.INTEGRATION_CONTRACT, envelope["integration_contract"])
    listOf("bundle", "brokered_evidence", "assigned_hunks", "parent_transcript", "complete_diff", "rubric")
      .forEach { excluded ->
        assertEquals(false, envelope.containsKey(excluded), "Integration launch must not carry '$excluded'.")
      }
    val summaries = requireNotNull(JsonCodec.anyToStringAnyMapList(envelope["specialist_summaries"]))
    assertEquals(listOf("security"), summaries.map { it["lane"] })
    assertEquals(false, summaries.single().containsKey("content"))
  }

  @Test fun `integration launch rejects summaries outside the packet selection`() {
    val base = packet()

    assertFailsWith<IllegalArgumentException> {
      integrationLaunch(base, lane = "performance")
    }
  }

  private fun integrationLaunch(
    base: ReviewContextPacket,
    lane: String = "security",
  ) = GovernedReviewIntegrationLaunch(
    packet = base,
    specialistSummaries =
      listOf(
        ReviewSpecialistSummary(
          lane = lane,
          assignmentDigest = "a".repeat(64),
          disposition = ReviewLaneReviewDisposition.COMPLETE,
          assignedPaths = listOf("src/A.kt"),
          commitShas = listOf("head"),
          findingCount = 1,
          summary = "Reviewed one bundle in one pass.",
        ),
      ),
    integrationContract = ReviewPacketConsumerContract.INTEGRATION_CONTRACT,
    brokerId = "broker",
    budget = ReviewContextBudgetPolicy.DEFAULT,
  )
}
