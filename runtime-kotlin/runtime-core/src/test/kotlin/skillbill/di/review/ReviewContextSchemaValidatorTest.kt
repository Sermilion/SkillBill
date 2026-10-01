package skillbill.di.review

import skillbill.application.review.model.ReviewPreparationRequest
import skillbill.application.review.packet.toAssignmentEnvelope
import skillbill.application.review.packet.toIntegrationLaunchEnvelope
import skillbill.application.review.packet.toLaunchEnvelope
import skillbill.application.review.packet.toParentPacketEnvelope
import skillbill.application.review.preparation.ReviewPreparationService
import skillbill.application.review.preparation.model.ReviewLaneSelection
import skillbill.application.review.preparation.model.ReviewPreparationFacts
import skillbill.application.review.preparation.model.ReviewScopeFacts
import skillbill.application.review.preparation.model.ReviewStackRoutingFacts
import skillbill.contracts.JsonCodec
import skillbill.contracts.review.REVIEW_CONTEXT_CONTRACT_VERSION
import skillbill.error.shellcontent.InvalidReviewContextSchemaError
import skillbill.infrastructure.contracts.review.ReviewContextSchemaValidator
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
import skillbill.review.context.model.hunk.ReviewBuildTestFact
import skillbill.review.context.model.hunk.ReviewChangedHunk
import skillbill.review.context.model.hunk.ReviewDependencyAllowlist
import skillbill.review.context.model.hunk.ReviewEvidenceTarget
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
import kotlin.test.assertTrue

class ReviewContextSchemaValidatorTest {
  private val hunkA = ReviewChangedHunk("src/A.kt", 1, 1, 1, 2, "+alpha")
  private val hunkB = ReviewChangedHunk("src/B.kt", 4, 1, 4, 1, "+beta")
  private val revision = ReviewRevision("rvs-1", 2)
  private val rule =
    ReviewRuleReference(
      "rule-1",
      "AGENTS.md",
      "Prefer named strategies.",
      ReviewRuleReference.digestOf("Prefer named strategies."),
    )

  private val commitUnit =
    ReviewCommitUnit(
      commitSha = "head",
      parentSha = "base",
      subject = "change A and B",
      orderIndex = 0,
      hunks = listOf(hunkA, hunkB),
      source = ReviewCommitSource.COMMIT_RANGE,
    )
  private val coverageFact =
    ReviewCommitCoverageFact("base", "head", 1, chainVerified = true, pathCoverageVerified = true)

  private val securityRouting =
    ReviewCommitLaneRoutingMatrix(
      listOf("head"),
      listOf("security"),
      listOf(
        ReviewCommitLaneDecision(
          "head",
          0,
          "security",
          ReviewCommitLaneDisposition.FOCUSED,
          "commit head changed evidence matching security signals [path:src/]",
          signals = listOf("path:src/"),
        ),
      ),
    )

  private val packet =
    ReviewContextPacket(
      reviewId = "review",
      repositoryIdentity = "acme/repo",
      baseRevision = "base",
      headRevision = "head",
      status = "clean",
      stack = "kotlin",
      pack = "kotlin",
      addOns = listOf("addon-a"),
      selectedLanes = listOf("security"),
      changedHunks = listOf(hunkA, hunkB),
      commitUnits = listOf(commitUnit),
      coverageFact = coverageFact,
      routingMatrix = securityRouting,
      reviewRevision = revision,
      laneDecisions =
        listOf(
          ReviewLaneDecision(
            "security",
            true,
            "auth surface changed",
            signals = listOf("auth"),
            ownedPaths = listOf("src/A.kt", "src/B.kt"),
            originLayerChains = listOf(listOf("kotlin")),
            owningPack = "kotlin",
            specialistSkillName = "bill-kotlin-code-review-security",
          ),
          ReviewLaneDecision("ui", false, "no UI files changed"),
        ),
      matchedRules = listOf(rule),
      learningsReferences = listOf(FIXTURE_LEARNING),
      buildTestFacts = listOf(ReviewBuildTestFact("test", "gradle test", "passed")),
      dependencyAllowlist = ReviewDependencyAllowlist(listOf("src/Dep.kt")),
      evidenceTargets = listOf(ReviewEvidenceTarget("src/A.kt", "src/A.kt", listOf(hunkA.hunkId))),
    )

  private val assignment =
    ReviewAssignment(
      reviewId = packet.reviewId,
      packetDigest = packet.digest,
      lane = "security",
      baseRevision = packet.baseRevision,
      headRevision = packet.headRevision,
      assignedPaths = listOf("src/A.kt", "src/B.kt"),
      assignedHunks = listOf(hunkA.hunkId, hunkB.hunkId),
      assignedBundle =
        ReviewLaneBundle(
          listOf(ReviewLaneBundleEntry("head", 0, listOf(hunkA.hunkId, hunkB.hunkId))),
        ),
      laneRouting = securityRouting.decisionsFor("security"),
      criteriaReferences = listOf("AC-002"),
      matchedRules = listOf(rule),
      evidenceTargets = packet.evidenceTargets,
      reviewRevision = revision,
      laneDecision = packet.laneDecisions.first { it.included },
      dependencyAllowlist = packet.dependencyAllowlist,
    )

  @Test fun `projected envelopes satisfy the canonical schema`() {
    ReviewContextSchemaValidator.validateParentPacket(packet.toParentPacketEnvelope().asWireMap(), "packet")
    ReviewContextSchemaValidator.validateAssignment(assignment.toAssignmentEnvelope().asWireMap(), "assignment")
    val launch =
      GovernedReviewLaunch(assignment, packet, "contract", "rubric", "broker", ReviewContextBudgetPolicy.DEFAULT)
    ReviewContextSchemaValidator.validateLaunch(launch.toLaunchEnvelope().asWireMap(), "launch")
    val parentHunks =
      requireNotNull(
        JsonCodec.anyToStringAnyMapList(packet.toParentPacketEnvelope().asWireMap()["changed_hunks"]),
      )
    parentHunks.forEach { hunk ->
      INDEX_HUNK_KEYS.forEach { key -> assertTrue(key in hunk.keys, "parent hunk missing $key") }
      assertEquals(false, hunk.containsKey("content"))
    }
    val bundle = requireNotNull(JsonCodec.anyToStringAnyMap(launch.toLaunchEnvelope().asWireMap()["bundle"]))
    val launchEntries = requireNotNull(JsonCodec.anyToStringAnyMapList(bundle["entries"]))
    launchEntries.forEach { entry ->
      INDEX_HUNK_KEYS.forEach { key -> assertTrue(key in entry.keys, "launch entry missing $key") }
      assertEquals(false, entry.containsKey("content"))
    }
  }

  @Test fun `launch envelope carries the governed forbidden rediscovery list`() {
    val launch =
      GovernedReviewLaunch(assignment, packet, "contract", "rubric", "broker", ReviewContextBudgetPolicy.DEFAULT)
    assertEquals(
      ReviewPacketConsumerContract.FORBIDDEN_REDISCOVERY,
      launch.toLaunchEnvelope().asWireMap()["forbidden_rediscovery"],
    )
  }

  @Test fun `integration launch envelope satisfies the canonical schema`() {
    val envelope = integrationLaunch().toIntegrationLaunchEnvelope().asWireMap()

    ReviewContextSchemaValidator.validateIntegrationLaunch(envelope, "integration")
    assertEquals(packet.commitSequenceDigest, envelope["commit_sequence_digest"])
  }

  @Test fun `integration launch envelope carrying raw lane evidence is rejected`() {
    val smuggled =
      integrationLaunch().toIntegrationLaunchEnvelope().asWireMap() +
        ("bundle" to mapOf("entries" to listOf(mapOf("content" to hunkA.content))))

    val failure =
      assertFailsWith<InvalidReviewContextSchemaError> {
        ReviewContextSchemaValidator.validateIntegrationLaunch(smuggled, "integration")
      }

    assertTrue("bundle" in failure.message.orEmpty())
  }

  @Test fun `a lane launch is not accepted as an integration launch`() {
    val failure =
      assertFailsWith<InvalidReviewContextSchemaError> {
        ReviewContextSchemaValidator.validateIntegrationLaunch(
          packet.toParentPacketEnvelope().asWireMap(),
          "packet",
        )
      }

    assertTrue("kind='parent_packet'" in failure.message.orEmpty())
  }

  private fun integrationLaunch() =
    GovernedReviewIntegrationLaunch(
      packet = packet,
      specialistSummaries =
        listOf(
          ReviewSpecialistSummary(
            lane = "security",
            assignmentDigest = "a".repeat(64),
            disposition = ReviewLaneReviewDisposition.COMPLETE,
            assignedPaths = listOf("src/A.kt"),
            commitShas = listOf("head"),
            findingCount = 2,
            summary = "Reviewed the assigned bundle in one pass.",
          ),
        ),
      integrationContract = ReviewPacketConsumerContract.INTEGRATION_CONTRACT,
      brokerId = "broker",
      budget = ReviewContextBudgetPolicy.DEFAULT,
    )

  @Test fun `wrong kind discriminator fails loudly`() {
    val failure =
      assertFailsWith<InvalidReviewContextSchemaError> {
        ReviewContextSchemaValidator.validateAssignment(packet.toParentPacketEnvelope().asWireMap(), "packet")
      }
    assertTrue("kind='parent_packet'" in failure.message.orEmpty())
  }

  @Test fun `missing required fields fail with a field path`() {
    val stripped = packet.toParentPacketEnvelope().asWireMap().toMutableMap().apply { remove("lane_decisions") }
    val failure =
      assertFailsWith<InvalidReviewContextSchemaError> {
        ReviewContextSchemaValidator.validate(stripped, "packet")
      }
    assertTrue("lane_decisions" in failure.message.orEmpty())
  }

  @Test fun `unknown additional properties are rejected`() {
    val extended = packet.toParentPacketEnvelope().asWireMap() + ("smuggled_diff" to "@@ -1 +1 @@")
    val failure =
      assertFailsWith<InvalidReviewContextSchemaError> {
        ReviewContextSchemaValidator.validate(extended, "packet")
      }
    assertTrue("smuggled_diff" in failure.message.orEmpty())
  }

  @Test fun `blank lane decision reasons are rejected`() {
    val envelope = packet.toParentPacketEnvelope().asWireMap().toMutableMap()
    envelope["lane_decisions"] =
      listOf(
        mapOf("lane" to "security", "included" to true, "reason" to "", "signals" to emptyList<String>()),
      )
    assertFailsWith<InvalidReviewContextSchemaError> { ReviewContextSchemaValidator.validate(envelope, "packet") }
  }

  @Test fun `included lane decisions require composition attribution and specialist ownership`() {
    val valid = packet.toParentPacketEnvelope().asWireMap()
    val baseDecision =
      (
        (valid.getValue("lane_decisions") as List<*>)
          .map { it as Map<*, *> }
          .single { it["included"] == true }
      )
        .entries.associate { (key, value) -> key as String to value }
    listOf("origin_layer_chains", "owning_pack", "specialist_skill_name").forEach { omitted ->
      val envelope = valid.toMutableMap()
      envelope["lane_decisions"] = listOf(baseDecision - omitted)
      assertFailsWith<InvalidReviewContextSchemaError> {
        ReviewContextSchemaValidator.validate(envelope, "packet")
      }
    }
    val envelope = valid.toMutableMap()
    envelope["lane_decisions"] = listOf(baseDecision + ("origin_layer_chains" to emptyList<List<String>>()))
    assertFailsWith<InvalidReviewContextSchemaError> {
      ReviewContextSchemaValidator.validate(envelope, "packet")
    }
  }

  @Test fun `blank expansion reachability reasons are rejected`() {
    val envelope = assignment.toAssignmentEnvelope().asWireMap().toMutableMap()
    envelope["expansions"] =
      listOf(
        mapOf(
          "expansion_id" to "exp-1",
          "assignment_digest" to assignment.digest,
          "requested_path" to "src/C.kt",
          "reachability_reason" to "",
          "authorized" to true,
          "sequence" to 0,
        ),
      )
    assertFailsWith<InvalidReviewContextSchemaError> { ReviewContextSchemaValidator.validate(envelope, "assignment") }
  }

  @Test fun `over long rule excerpts are rejected by the schema`() {
    val envelope = packet.toParentPacketEnvelope().asWireMap().toMutableMap()
    envelope["matched_rules"] =
      listOf(
        mapOf(
          "rule_id" to "rule-1",
          "source_path" to "AGENTS.md",
          "excerpt" to "x".repeat(2_001),
          "digest" to "b".repeat(64),
        ),
      )
    assertFailsWith<InvalidReviewContextSchemaError> { ReviewContextSchemaValidator.validate(envelope, "packet") }
  }

  @Test fun `traversal dependency paths are rejected by the schema`() {
    val envelope = assignment.toAssignmentEnvelope().asWireMap().toMutableMap()
    envelope["dependency_allowlist"] = listOf("../secret")
    assertFailsWith<InvalidReviewContextSchemaError> { ReviewContextSchemaValidator.validate(envelope, "assignment") }
  }

  @Test fun `backslash paths are rejected by the schema`() {
    val envelope = assignment.toAssignmentEnvelope().asWireMap().toMutableMap()
    envelope["dependency_allowlist"] = listOf("src\\..\\secret")
    assertFailsWith<InvalidReviewContextSchemaError> { ReviewContextSchemaValidator.validate(envelope, "assignment") }
  }

  @Test fun `whitespace only identifiers are rejected by the schema`() {
    val envelope = packet.toParentPacketEnvelope().asWireMap().toMutableMap()
    envelope["review_id"] = "   "
    assertFailsWith<InvalidReviewContextSchemaError> { ReviewContextSchemaValidator.validate(envelope, "packet") }
  }

  @Test fun `positional hunk identifiers are rejected`() {
    val envelope = assignment.toAssignmentEnvelope().asWireMap().toMutableMap()
    envelope["assigned_hunks"] = listOf("@@ -1 +1 @@")
    assertFailsWith<InvalidReviewContextSchemaError> { ReviewContextSchemaValidator.validate(envelope, "assignment") }
  }

  @Test fun `stale contract versions are rejected`() {
    val envelope = packet.toParentPacketEnvelope().asWireMap().toMutableMap()
    envelope["contract_version"] = "0.1"
    assertFailsWith<InvalidReviewContextSchemaError> { ReviewContextSchemaValidator.validate(envelope, "packet") }
  }

  @Test fun `an envelope declaring a non branch kind is rejected instead of validating permissively`() {
    val envelope = packet.toParentPacketEnvelope().asWireMap().toMutableMap()
    envelope["kind"] = "header"
    assertFailsWith<InvalidReviewContextSchemaError> { ReviewContextSchemaValidator.validate(envelope, "packet") }
  }

  @Test fun `schema violations never echo guidance excerpts or diff bodies`() {
    val envelope = packet.toParentPacketEnvelope().asWireMap().toMutableMap()
    val secret = "SENSITIVE-GUIDANCE-BODY"
    envelope["matched_rules"] =
      listOf(
        mapOf("rule_id" to "rule-1", "source_path" to "AGENTS.md", "excerpt" to secret, "digest" to 7),
      )
    val failure =
      assertFailsWith<InvalidReviewContextSchemaError> { ReviewContextSchemaValidator.validate(envelope, "packet") }
    assertTrue(secret !in failure.message.orEmpty())
  }

  @Test fun `bundled multi-segment launch envelope validates against the schema`() {
    val launch =
      GovernedReviewLaunch(assignment, packet, "contract", "rubric", "broker", ReviewContextBudgetPolicy.DEFAULT)
    val envelope = launch.toLaunchEnvelope().asWireMap().toMutableMap()
    val bundle = requireNotNull(JsonCodec.anyToStringAnyMap((envelope["bundle"]))).toMutableMap()
    val entries = requireNotNull(JsonCodec.anyToStringAnyMapList(bundle["entries"]))
    require(entries.size >= 2) { "Fixture needs at least two bundle entries to split." }
    val first = entries.first()
    val second = entries[1]
    bundle["segments"] =
      listOf(
        linkedMapOf(
          "segment_id" to "seg-000",
          "measured_bytes" to 1_024L,
          "composition_digest" to "a".repeat(64),
          "entries" to
            listOf(
              linkedMapOf(
                "commit_sha" to first["commit_sha"],
                "order_index" to first["order_index"],
                "hunk_id" to first["hunk_id"],
                "path" to first["path"],
              ),
            ),
        ),
        linkedMapOf(
          "segment_id" to "seg-001",
          "measured_bytes" to 2_048L,
          "composition_digest" to "b".repeat(64),
          "entries" to
            listOf(
              linkedMapOf(
                "commit_sha" to second["commit_sha"],
                "order_index" to second["order_index"],
                "hunk_id" to second["hunk_id"],
                "path" to second["path"],
              ),
            ),
        ),
      )
    envelope["bundle"] = bundle
    ReviewContextSchemaValidator.validateLaunch(envelope, "launch")
  }

  @Test fun `launch envelope bundle entry missing commit_sha or order_index is rejected`() {
    val launch =
      GovernedReviewLaunch(assignment, packet, "contract", "rubric", "broker", ReviewContextBudgetPolicy.DEFAULT)
    val envelope = launch.toLaunchEnvelope().asWireMap().toMutableMap()
    val bundle = requireNotNull(JsonCodec.anyToStringAnyMap((envelope["bundle"]))).toMutableMap()
    val entries = requireNotNull(JsonCodec.anyToStringAnyMapList((bundle["entries"]))).map { it.toMutableMap() }
    bundle["entries"] = listOf(entries.first() - "commit_sha")
    envelope["bundle"] = bundle
    assertFailsWith<InvalidReviewContextSchemaError> {
      ReviewContextSchemaValidator.validateLaunch(envelope, "launch")
    }

    bundle["entries"] = listOf(entries.first() - "order_index")
    envelope["bundle"] = bundle
    assertFailsWith<InvalidReviewContextSchemaError> {
      ReviewContextSchemaValidator.validateLaunch(envelope, "launch")
    }
  }

  @Test fun `projected envelopes carry contract version 2_4`() {
    val launch =
      GovernedReviewLaunch(assignment, packet, "contract", "rubric", "broker", ReviewContextBudgetPolicy.DEFAULT)
    assertEquals(REVIEW_CONTEXT_CONTRACT_VERSION, packet.toParentPacketEnvelope().asWireMap()["contract_version"])
    assertEquals(REVIEW_CONTEXT_CONTRACT_VERSION, assignment.toAssignmentEnvelope().asWireMap()["contract_version"])
    assertEquals(REVIEW_CONTEXT_CONTRACT_VERSION, launch.toLaunchEnvelope().asWireMap()["contract_version"])
    assertEquals("2.4", REVIEW_CONTEXT_CONTRACT_VERSION)
  }

  @Test fun `a 1_0 envelope fails with a typed version mismatch naming both versions`() {
    val envelope = packet.toParentPacketEnvelope().asWireMap().toMutableMap()
    envelope["contract_version"] = "1.0"
    val failure =
      assertFailsWith<InvalidReviewContextSchemaError> {
        ReviewContextSchemaValidator.validateParentPacket(envelope, "packet")
      }
    assertTrue("1.0" in failure.reason)
    assertTrue("2.4" in failure.reason)
  }

  @Test fun `incomplete launch bundle without budget dimension is rejected`() {
    val launch =
      GovernedReviewLaunch(assignment, packet, "contract", "rubric", "broker", ReviewContextBudgetPolicy.DEFAULT)
    val envelope = launch.toLaunchEnvelope().asWireMap().toMutableMap()
    val bundle = requireNotNull(JsonCodec.anyToStringAnyMap((envelope["bundle"]))).toMutableMap()
    bundle["lane_disposition"] = "incomplete"
    bundle["unreviewed_segment_ids"] = listOf("unreviewable")
    bundle.remove("budget_dimension")
    envelope["bundle"] = bundle
    assertFailsWith<InvalidReviewContextSchemaError> {
      ReviewContextSchemaValidator.validateLaunch(envelope, "launch")
    }
  }

  @Test fun `index hunks reject inlined diff bodies on parent assignment and launch`() {
    val parent = packet.toParentPacketEnvelope().asWireMap().toMutableMap()
    val hunks = requireNotNull(JsonCodec.anyToStringAnyMapList((parent["changed_hunks"]))).map { it.toMutableMap() }
    hunks[0]["content"] = "+smuggled"
    parent["changed_hunks"] = hunks
    assertFailsWith<InvalidReviewContextSchemaError> {
      ReviewContextSchemaValidator.validateParentPacket(parent, "packet")
    }

    val assignmentBody = assignment.toAssignmentEnvelope().asWireMap() + ("hunk_body" to "+smuggled")
    assertFailsWith<InvalidReviewContextSchemaError> {
      ReviewContextSchemaValidator.validateAssignment(assignmentBody, "assignment")
    }

    val launch =
      GovernedReviewLaunch(assignment, packet, "contract", "rubric", "broker", ReviewContextBudgetPolicy.DEFAULT)
    val launchBody =
      launch.toLaunchEnvelope().asWireMap() + (
        "brokered_evidence" to listOf(mapOf("path" to "src/A.kt", "content" to "+smuggled"))
      )
    assertFailsWith<InvalidReviewContextSchemaError> {
      ReviewContextSchemaValidator.validateLaunch(launchBody, "launch")
    }

    val launchEnvelope = launch.toLaunchEnvelope().asWireMap().toMutableMap()
    val bundle = requireNotNull(JsonCodec.anyToStringAnyMap(launchEnvelope["bundle"])).toMutableMap()
    val entries =
      (bundle["entries"] as? List<*>).orEmpty()
        .map { requireNotNull(JsonCodec.anyToStringAnyMap(it)).toMutableMap() }
    entries[0]["content"] = "+smuggled"
    bundle["entries"] = entries
    launchEnvelope["bundle"] = bundle
    assertFailsWith<InvalidReviewContextSchemaError> {
      ReviewContextSchemaValidator.validateLaunch(launchEnvelope, "launch")
    }
  }

  @Test fun `a spec intent projection missing provenance is rejected and a provenanced counterpart is accepted`() {
    ReviewContextSchemaValidator.validateSpecIntentProjection(specIntentProjection(), "projection")
    val missing = specIntentProjection() - "provenance"
    val failure =
      assertFailsWith<InvalidReviewContextSchemaError> {
        ReviewContextSchemaValidator.validateSpecIntentProjection(missing, "projection")
      }
    assertTrue("for definition 'spec_intent_projection'" in failure.message.orEmpty())
    val noDigest =
      specIntentProjection() + (
        "provenance" to mapOf("spec_path" to "spec.md")
      )
    val digestFailure =
      assertFailsWith<InvalidReviewContextSchemaError> {
        ReviewContextSchemaValidator.validateSpecIntentProjection(noDigest, "projection")
      }
    assertTrue("for definition 'spec_intent_projection'" in digestFailure.message.orEmpty())
  }

  @Test fun `a verification launch carrying a spec projection field is rejected and a clean launch is accepted`() {
    ReviewContextSchemaValidator.validateVerificationLaunch(verificationLaunch(), "verification")
    val contaminated = verificationLaunch() + ("spec_intent_projection" to specIntentProjection())
    val failure =
      assertFailsWith<InvalidReviewContextSchemaError> {
        ReviewContextSchemaValidator.validateVerificationLaunch(contaminated, "verification")
      }
    assertTrue("for definition 'verification_launch'" in failure.message.orEmpty())
  }

  @Test fun `a refuted finding verdict without a citation is rejected and a cited refutation is accepted`() {
    ReviewContextSchemaValidator.validateFindingVerdict(
      confirmedVerdict() + ("claim_verdict" to "refuted") + ("citations" to listOf(findingCitation())),
      "verdict",
    )
    val failure =
      assertFailsWith<InvalidReviewContextSchemaError> {
        ReviewContextSchemaValidator.validateFindingVerdict(
          confirmedVerdict() + ("claim_verdict" to "refuted"),
          "verdict",
        )
      }
    assertTrue("for definition 'finding_verdict'" in failure.message.orEmpty())
  }

  @Test
  fun `uncited downgrade or out of scope preexisting is rejected while cited counterparts are accepted`() {
    val cited = listOf(findingCitation())
    ReviewContextSchemaValidator.validateFindingVerdict(
      confirmedVerdict("adjudication") +
        ("severity_adjustment" to mapOf("direction" to "lower", "justification" to "listed non-goal")) +
        ("citations" to cited),
      "verdict",
    )
    ReviewContextSchemaValidator.validateFindingVerdict(
      confirmedVerdict("adjudication") +
        ("scope_disposition" to "out_of_scope_preexisting") +
        ("citations" to cited),
      "verdict",
    )
    val uncitedDowngrade =
      assertFailsWith<InvalidReviewContextSchemaError> {
        ReviewContextSchemaValidator.validateFindingVerdict(
          confirmedVerdict("adjudication") +
            ("severity_adjustment" to mapOf("direction" to "lower", "justification" to "listed non-goal")),
          "verdict",
        )
      }
    val uncitedOutOfScope =
      assertFailsWith<InvalidReviewContextSchemaError> {
        ReviewContextSchemaValidator.validateFindingVerdict(
          confirmedVerdict("adjudication") + ("scope_disposition" to "out_of_scope_preexisting"),
          "verdict",
        )
      }
    assertTrue("for definition 'finding_verdict'" in uncitedDowngrade.message.orEmpty())
    assertTrue("for definition 'finding_verdict'" in uncitedOutOfScope.message.orEmpty())
  }

  @Test fun `scope disposition at verification is rejected and the same disposition at adjudication is accepted`() {
    ReviewContextSchemaValidator.validateFindingVerdict(
      confirmedVerdict("adjudication") + ("scope_disposition" to "in_scope"),
      "verdict",
    )
    val failure =
      assertFailsWith<InvalidReviewContextSchemaError> {
        ReviewContextSchemaValidator.validateFindingVerdict(
          confirmedVerdict("verification") + ("scope_disposition" to "in_scope"),
          "verdict",
        )
      }
    assertTrue("for definition 'finding_verdict'" in failure.message.orEmpty())
  }

  @Test fun `the real service validates its own projections against the canonical schema`() {
    val prepared =
      ReviewPreparationService(
        facts,
        ReviewContextSchemaValidator(),
      ).prepare(
        ReviewPreparationRequest(
          reviewId = "review",
          reviewRevision = revision,
          criteriaReferences = mapOf("security" to listOf("AC-002")),
          dependencyAllowlist = ReviewDependencyAllowlist(listOf("src/Dep.kt")),
        ),
      )
    assertEquals("parent_packet", prepared.packetEnvelope.asWireMap()["kind"])
    assertEquals(listOf("src/A.kt"), prepared.assignments.single().assignedPaths)
  }

  private val facts =
    ReviewPreparationFacts(
      scope =
        ReviewScopeFacts("acme/repo", "base", "head", "clean", listOf(hunkA, hunkB), listOf(commitUnit), coverageFact),
      stackRouting = ReviewStackRoutingFacts("kotlin", "kotlin", listOf("addon-a"), listOf("kotlin")),
      laneSelection =
        ReviewLaneSelection(
          listOf(
            ReviewLaneDecision(
              "security",
              true,
              "auth surface changed",
              ownedPaths = listOf("src/A.kt"),
              originLayerChains = listOf(listOf("kotlin")),
              owningPack = "kotlin",
              specialistSkillName = "bill-kotlin-code-review-security",
            ),
            ReviewLaneDecision("ui", false, "no UI files changed"),
          ),
          securityRouting,
        ),
      matchedRules = listOf(rule),
      learningsReferences = listOf(FIXTURE_LEARNING),
      buildTestFacts = listOf(ReviewBuildTestFact("test", "gradle test", "passed")),
    )

  private fun verificationLaunch(): Map<String, Any?> =
    mapOf(
      "contract_version" to REVIEW_CONTEXT_CONTRACT_VERSION,
      "kind" to "verification_launch",
      "review_id" to "review",
      "packet_digest" to STAGE_DIGEST,
      "review_revision" to mapOf("session_id" to "rvs-1", "run_revision" to 1),
      "finding" to reviewFinding(),
      "cited_region" to mapOf("path" to "src/A.kt", "start_line" to 12, "end_line" to 14),
      "delta_reference" to mapOf("base_revision" to "base", "head_revision" to "head"),
      "evidence_surface_rules" to "Cited region and direct callers only.",
      "dependency_allowlist" to listOf("src/Dep.kt"),
      "forbidden_rediscovery" to listOf("diff_recomputation"),
      "broker_id" to "broker",
      "isolation" to "fresh",
      "budget" to stageBudget(),
    )

  private fun specIntentProjection(): Map<String, Any?> =
    mapOf(
      "intended_outcome" to "Stage contract ships first.",
      "acceptance_criteria" to listOf("AC-001"),
      "constraints" to listOf("No worker launch"),
      "non_goals" to listOf("Persistence"),
      "deferred_items" to listOf("Subtask 2"),
      "provenance" to mapOf("spec_path" to "spec.md", "content_digest" to STAGE_DIGEST),
      "declared_byte_budget" to 4096,
    )

  private fun confirmedVerdict(stage: String = "verification"): Map<String, Any?> =
    mapOf(
      "contract_version" to REVIEW_CONTEXT_CONTRACT_VERSION,
      "kind" to "finding_verdict",
      "stage" to stage,
      "finding_ref" to "F-1",
      "claim_verdict" to "confirmed",
      "recorded_at" to "2026-08-14T07:22:00Z",
    )

  private fun reviewFinding(): Map<String, Any?> =
    mapOf(
      "finding_ref" to "F-1",
      "severity" to "Major",
      "location" to "src/A.kt:12",
      "description" to "Null is not checked.",
    )

  private fun findingCitation(): Map<String, Any?> = mapOf("path" to "src/A.kt", "line" to 12)

  private fun stageBudget(): Map<String, Any?> =
    mapOf(
      "max_parent_packet_bytes" to 8,
      "max_lane_launch_bytes" to 4,
      "max_lane_evidence_bytes" to 4,
      "max_evidence_result_bytes" to 2,
      "max_lane_result_bytes" to 2,
      "max_assignment_expansions" to 0,
      "max_specialist_tool_calls" to 1,
      "max_specialist_model_turns" to 1,
      "max_routing_analysis_pairs" to 1,
      "max_routing_analysis_bytes" to 1,
    )

  private companion object {
    const val STAGE_DIGEST: String = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    val INDEX_HUNK_KEYS: Set<String> =
      setOf(
        "hunk_id",
        "path",
        "old_start",
        "old_count",
        "new_start",
        "new_count",
        "content_digest",
        "evidence_locator",
      )
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
