
package skillbill.review.context.model.commit

import skillbill.review.context.ReviewExecutionModePolicy
import skillbill.review.context.model.accounting.REVIEW_CONTEXT_BUDGET_EXCEEDED
import skillbill.review.context.model.accounting.ReviewContextBudgetPolicy
import skillbill.review.context.model.bundle.ReviewLaneBundle
import skillbill.review.context.model.bundle.ReviewLaneBundleEntry
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.review.context.model.execution.ResolvedReviewExecutionMode
import skillbill.review.context.model.hunk.ReviewBaselineUntrackedPolicy
import skillbill.review.context.model.hunk.ReviewChangedHunk
import skillbill.review.context.model.hunk.ReviewDependencyAllowlist
import skillbill.review.context.model.hunk.ReviewRevision
import skillbill.review.context.model.hunk.requireRepositoryRelativePath
import skillbill.review.context.model.launch.GovernedReviewLaunch
import skillbill.review.context.model.packet.ReviewContextPacket
import skillbill.review.context.model.packet.ReviewPacketConsumerContract
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ReviewContextModelsTest {
  @Test fun `path bearing digests are injective and launch paths are structured`() {
    val slash = ReviewChangedHunk("a/b.kt", 1, 1, 1, 1, "same")
    val backslash = ReviewChangedHunk("a\\b.kt", 1, 1, 1, 1, "same")
    val delimiter = ReviewChangedHunk("a\u001fb.kt", 1, 1, 1, 1, "same")
    assertNotEquals(slash.hunkId, backslash.hunkId)
    assertNotEquals(slash.hunkId, delimiter.hunkId)

    val packet = launchPacket(path = "odd|name\\tab\t.kt")
    val launch =
      GovernedReviewLaunch(
        launchAssignment(packet),
        packet,
        "contract",
        "rubric",
        "broker",
        ReviewContextBudgetPolicy.DEFAULT,
      )
    assertTrue("  - \"odd|name\\\\tab\\t.kt\"" in launch.canonicalPayload)
  }

  @Test fun `parent canonical bytes exclude hunk body size`() {
    val tiny = ReviewChangedHunk("A.kt", 1, 1, 1, 2, "+tiny")
    val huge = ReviewChangedHunk("A.kt", 1, 1, 1, 2, "+" + "x".repeat(1_048_576))

    fun packetFor(hunk: ReviewChangedHunk) =
      ReviewContextPacket(
        "review", "repo", "base", "head", "clean", "kotlin", "kotlin", emptyList(), listOf("security"),
        listOf(hunk),
        commitUnits = listOf(syntheticUnit(listOf(hunk))),
        coverageFact = fact(),
        routingMatrix = focusedMatrix(listOf(syntheticUnit(listOf(hunk))), listOf("security")),
        reviewRevision = revision(),
        laneDecisions = listOf(lane("security", listOf("A.kt"))),
      )
    val growth = packetFor(huge).canonicalBytes - packetFor(tiny).canonicalBytes
    assertTrue(growth < 1_048_576)
    assertNotEquals(tiny.hunkId, huge.hunkId)
  }

  @Test fun `repository paths accept supplementary Unicode and reject unpaired surrogates`() {
    requireRepositoryRelativePath("src/rocket-\uD83D\uDE80.kt")
    assertFailsWith<IllegalArgumentException> { requireRepositoryRelativePath("src/broken-\uD83D.kt") }
    assertFailsWith<IllegalArgumentException> { requireRepositoryRelativePath("src/broken-\uDE80.kt") }
  }

  @Test fun `repository paths reject backslash-separated traversal segments`() {
    assertFailsWith<IllegalArgumentException> { requireRepositoryRelativePath("runtime-kotlin\\..\\secret.kt") }
    requireRepositoryRelativePath("A|b\\c\\t.kt")
  }

  private fun lane(
    name: String,
    paths: List<String>,
    reason: String = "routed",
  ) = ReviewLaneDecision(
    name,
    true,
    reason,
    ownedPaths = paths,
    originLayerChains = listOf(listOf("kotlin")),
    owningPack = "kotlin",
    specialistSkillName = "bill-kotlin-code-review-$name",
  )

  private fun revision(
    session: String = "review",
    run: Int = 1,
  ) = ReviewRevision(session, run)

  private val launchHunk = ReviewChangedHunk("A.kt", 1, 1, 1, 2, "+alpha")

  private fun syntheticUnit(hunks: List<ReviewChangedHunk>) =
    ReviewCommitUnit.synthetic(ReviewCommitSource.SYNTHETIC_WORKING_TREE, hunks)

  private fun focusedMatrix(
    units: List<ReviewCommitUnit>,
    lanes: List<String>,
  ) = ReviewCommitLaneRoutingMatrix(
    units.sortedBy { it.orderIndex }.map { it.commitSha },
    lanes,
    units.sortedBy { it.orderIndex }.flatMap { unit ->
      lanes.map {
        ReviewCommitLaneDecision(unit.commitSha, unit.orderIndex, it, ReviewCommitLaneDisposition.FOCUSED, "focused")
      }
    },
  )

  private fun fact(count: Int = 1) =
    ReviewCommitCoverageFact(
      "base",
      "head",
      count,
      chainVerified = false,
      pathCoverageVerified = true,
      degradedReason = "synthetic working-tree unit",
    )

  private fun launchPacket(path: String = "A.kt") =
    ReviewContextPacket(
      "review", "repo", "base", "head", "clean", "kotlin", "kotlin", emptyList(), listOf("security"),
      listOf(if (path == launchHunk.path) launchHunk else launchHunk.copy(path = path)),
      commitUnits =
        listOf(
          syntheticUnit(listOf(if (path == launchHunk.path) launchHunk else launchHunk.copy(path = path))),
        ),
      coverageFact = fact(),
      routingMatrix =
        focusedMatrix(
          listOf(syntheticUnit(listOf(if (path == launchHunk.path) launchHunk else launchHunk.copy(path = path)))),
          listOf("security"),
        ),
      reviewRevision = revision(),
      laneDecisions = listOf(lane("security", listOf(path))),
    )

  private fun launchAssignment(
    packet: ReviewContextPacket,
    hunks: List<String> = packet.changedHunks.map { it.hunkId },
  ) = ReviewAssignment(
    "review", packet.digest, "security", "base", "head", packet.changedHunks.map { it.path }, hunks,
    assignedBundle =
      ReviewLaneBundle(
        packet.commitUnits.sortedBy { it.orderIndex }.mapNotNull { unit ->
          unit.hunkIds.filter { it in hunks }
            .takeIf { it.isNotEmpty() }
            ?.let { ReviewLaneBundleEntry(unit.commitSha, unit.orderIndex, it) }
        },
      ),
    laneRouting = packet.routingMatrix.decisionsFor("security"),
    reviewRevision = revision(), laneDecision = lane("security", packet.changedHunks.map { it.path }),
    baselineUntrackedPolicy = packet.baselineUntrackedPolicy,
  )

  @Test fun `default budget is governed`() {
    assertEquals(524_288, ReviewContextBudgetPolicy.DEFAULT.maxParentPacketBytes)
  }

  @Test fun `inconsistent budgets loud fail`() {
    assertFailsWith<IllegalArgumentException> {
      ReviewContextBudgetPolicy(maxLaneEvidenceBytes = 10, maxEvidenceResultBytes = 11)
    }
  }

  @Test fun `assignment digest is stable`() {
    fun value(
      paths: List<String>,
      criteria: List<String>,
    ) = ReviewAssignment(
      "review", "a".repeat(64), "security", "base", "head", paths, listOf("hunk"),
      criteriaReferences = criteria,
      reviewRevision = revision(), laneDecision = lane("security", paths),
    )
    assertEquals(
      value(listOf("b.kt", "a.kt"), listOf("2", "1")).digest,
      value(listOf("a.kt", "b.kt"), listOf("1", "2")).digest,
    )
  }

  @Test fun `assignment digest tracks revision lane decision and dependency allowlist`() {
    val base =
      ReviewAssignment(
        "review", "a".repeat(64), "security", "base", "head", listOf("A.kt"), listOf("b".repeat(64)),
        reviewRevision = revision(), laneDecision = lane("security", listOf("A.kt")),
      )
    assertEquals(base.digest, base.copy(assignedPaths = listOf("A.kt")).digest)
    assertTrue(base.digest != base.copy(reviewRevision = ReviewRevision("review", 2)).digest)
    assertTrue(
      base.digest != base.copy(dependencyAllowlist = ReviewDependencyAllowlist(listOf("Dep.kt"))).digest,
    )
    assertTrue(
      base.digest != base.copy(laneDecision = lane("security", listOf("A.kt"), "different reason")).digest,
    )
    assertTrue(base.digest != base.copy(laneDecision = lane("security", listOf("B.kt"))).digest)
  }

  @Test fun `assignments reject lane decisions that do not describe the lane`() {
    assertFailsWith<IllegalArgumentException> {
      ReviewAssignment(
        "review",
        "a".repeat(64),
        "security",
        "base",
        "head",
        listOf("A.kt"),
        emptyList(),
        reviewRevision = revision(),
        laneDecision = lane("testing", listOf("A.kt")),
      )
    }
    assertFailsWith<IllegalArgumentException> {
      ReviewAssignment(
        "review",
        "a".repeat(64),
        "security",
        "base",
        "head",
        listOf("A.kt"),
        emptyList(),
        reviewRevision = revision(),
        laneDecision = ReviewLaneDecision("security", false, "excluded"),
      )
    }
  }

  @Test fun `paths reject traversal`() {
    assertFailsWith<IllegalArgumentException> {
      ReviewAssignment(
        "review", "a".repeat(64), "security", "base", "head", listOf("../secret"), emptyList(),
        reviewRevision = revision(), laneDecision = lane("security", listOf("A.kt")),
      )
    }
  }

  @Test fun `packet digest preserves repository identity while normalizing ordering and line endings`() {
    fun packet(
      path: String,
      status: String,
    ) = ReviewContextPacket(
      "review", "repo", "base", "head", status, "kotlin", "kotlin", listOf("z", "a"), listOf("testing"),
      listOf(ReviewChangedHunk(path, 1, 1, 1, 1, "+line\r\n")),
      commitUnits = listOf(syntheticUnit(listOf(ReviewChangedHunk(path, 1, 1, 1, 1, "+line\r\n")))),
      coverageFact = fact(),
      routingMatrix =
        focusedMatrix(
          listOf(syntheticUnit(listOf(ReviewChangedHunk(path, 1, 1, 1, 1, "+line\r\n")))),
          listOf("testing"),
        ),
      reviewRevision = revision(),
      laneDecisions = listOf(lane("testing", listOf(path))),
    )
    assertEquals(packet("src/A.kt", "clean\r\n").digest, packet("src/A.kt", "clean\n").digest)
    assertNotEquals(packet("src\\A.kt", "clean\n").digest, packet("src/A.kt", "clean\n").digest)
  }

  @Test fun `packet digest is deterministic for hunks tied on path and new start`() {
    val first = ReviewChangedHunk("A.kt", 1, 1, 5, 1, "+first")
    val second = ReviewChangedHunk("A.kt", 3, 1, 5, 2, "+second")

    fun packet(hunks: List<ReviewChangedHunk>) =
      ReviewContextPacket(
        "review", "repo", "base", "head", "clean", "kotlin", "kotlin", emptyList(), listOf("security"), hunks,
        commitUnits = listOf(syntheticUnit(hunks.sortedBy { it.hunkId })),
        coverageFact = fact(),
        routingMatrix = focusedMatrix(listOf(syntheticUnit(hunks.sortedBy { it.hunkId })), listOf("security")),
        reviewRevision = revision(),
        laneDecisions = listOf(lane("security", listOf("A.kt"))),
      )
    assertEquals(packet(listOf(first, second)).digest, packet(listOf(second, first)).digest)
  }

  @Test fun `baseline untracked policy is digest bound and mutually exclusive`() {
    val included =
      ReviewBaselineUntrackedPolicy(
        includedPaths = listOf("src/New.kt"),
        excludedPaths = listOf("docs/old.md"),
      )
    val packet = launchPacket().copy(baselineUntrackedPolicy = included)
    val changedPolicy =
      packet.copy(
        baselineUntrackedPolicy = ReviewBaselineUntrackedPolicy(excludedPaths = listOf("src/New.kt")),
      )
    assertNotEquals(packet.digest, changedPolicy.digest)
    assertNotEquals(packet.digest, packet.copy(baseRevision = "other-base").digest)
    assertNotEquals(packet.digest, packet.copy(headRevision = "other-head").digest)
    val launch =
      GovernedReviewLaunch(
        launchAssignment(packet),
        packet,
        "contract",
        "rubric",
        "broker",
        ReviewContextBudgetPolicy.DEFAULT,
      )
    assertTrue("baseline_untracked_policy" in launch.canonicalPayload)
    assertTrue("src/New.kt" in launch.canonicalPayload)
    assertTrue("docs/old.md" in launch.canonicalPayload)
    assertFailsWith<IllegalArgumentException> {
      ReviewBaselineUntrackedPolicy(listOf("src/New.kt"), listOf("src/New.kt"))
    }
    val forgedAssignment =
      launchAssignment(packet).copy(
        baselineUntrackedPolicy = ReviewBaselineUntrackedPolicy.EMPTY,
      )
    assertFailsWith<IllegalArgumentException> {
      GovernedReviewLaunch(forgedAssignment, packet, "contract", "rubric", "broker", ReviewContextBudgetPolicy.DEFAULT)
    }
  }

  @Test fun `governed Codex launches reject inherited and omitted turns`() {
    val packet = launchPacket()
    val launch =
      GovernedReviewLaunch(
        launchAssignment(packet),
        packet,
        "contract",
        "rubric",
        "broker",
        ReviewContextBudgetPolicy.DEFAULT,
      )
    launch.requireCodexForkTurns("none")
    assertFailsWith<IllegalArgumentException> { launch.requireCodexForkTurns(null) }
    assertFailsWith<IllegalArgumentException> { launch.requireCodexForkTurns("all") }
    assertEquals(
      listOf(
        "contract_version", "kind", "review_id", "review_revision", "packet_digest", "assignment_digest",
        "lane", "base_revision",
        "head_revision", "broker_id", "specialist_contract", "rubric", "consumer_contract",
        "assigned_paths", "assigned_hunks", "coverage_fact", "assigned_commit_units", "lane_routing",
        "bundle",
        "criteria_references", "matched_rules", "learnings", "evidence_targets", "dependency_allowlist",
        "baseline_untracked_policy",
        "forbidden_rediscovery", "evidence_surface_rules", "report_structure", "budgets",
      ),
      launch.canonicalPayload.lines().filter { it.isNotBlank() && !it.startsWith("  ") }
        .map { it.substringBefore(':') },
    )
    assertTrue(ReviewPacketConsumerContract.FORBIDDEN_REDISCOVERY.all { it in launch.canonicalPayload })
  }

  @Test fun `a launch cannot be projected from an assignment the packet does not attest`() {
    val packet = launchPacket()
    val forged = launchAssignment(packet).copy(packetDigest = "a".repeat(64))
    assertFailsWith<IllegalArgumentException> {
      GovernedReviewLaunch(forged, packet, "contract", "rubric", "broker", ReviewContextBudgetPolicy.DEFAULT)
    }
    val widened = launchAssignment(packet).copy(assignedPaths = listOf("A.kt", "Elsewhere.kt"))
    assertFailsWith<IllegalArgumentException> {
      GovernedReviewLaunch(widened, packet, "contract", "rubric", "broker", ReviewContextBudgetPolicy.DEFAULT)
    }
    val stale = launchAssignment(packet).copy(reviewRevision = ReviewRevision("review", 2))
    assertFailsWith<IllegalArgumentException> {
      GovernedReviewLaunch(stale, packet, "contract", "rubric", "broker", ReviewContextBudgetPolicy.DEFAULT)
    }
    val changedDecision = launchAssignment(packet).copy(laneDecision = lane("security", listOf("A.kt"), "forged"))
    assertFailsWith<IllegalArgumentException> {
      GovernedReviewLaunch(changedDecision, packet, "contract", "rubric", "broker", ReviewContextBudgetPolicy.DEFAULT)
    }
    val escapingDependency =
      launchAssignment(packet).copy(
        dependencyAllowlist = ReviewDependencyAllowlist(listOf("Elsewhere.kt")),
      )
    assertFailsWith<IllegalArgumentException> {
      GovernedReviewLaunch(
        escapingDependency,
        packet,
        "contract",
        "rubric",
        "broker",
        ReviewContextBudgetPolicy.DEFAULT,
      )
    }
  }

  @Test fun `assignment-scaled lane evidence budgets differ when bundle content bytes differ`() {
    val small = ReviewChangedHunk("src/A.kt", 1, 1, 1, 2, "+a")
    val large = ReviewChangedHunk("src/B.kt", 1, 1, 1, 2, "+" + "x".repeat(100_000))
    val packet =
      ReviewContextPacket(
        "review", "repo", "base", "head", "clean", "kotlin", "kotlin", emptyList(),
        listOf("security", "testing"),
        listOf(small, large),
        commitUnits = listOf(syntheticUnit(listOf(small, large))),
        coverageFact = fact(),
        routingMatrix = focusedMatrix(listOf(syntheticUnit(listOf(small, large))), listOf("security", "testing")),
        reviewRevision = revision(),
        laneDecisions = listOf(lane("security", listOf("src/A.kt")), lane("testing", listOf("src/B.kt"))),
      )
    val narrow =
      launchAssignment(packet, listOf(small.hunkId)).copy(
        lane = "security",
        assignedPaths = listOf("src/A.kt"),
        laneDecision = lane("security", listOf("src/A.kt")),
        laneRouting = packet.routingMatrix.decisionsFor("security"),
      )
    val broad =
      launchAssignment(packet, listOf(large.hunkId)).copy(
        lane = "testing",
        assignedPaths = listOf("src/B.kt"),
        laneDecision = lane("testing", listOf("src/B.kt")),
        laneRouting = packet.routingMatrix.decisionsFor("testing"),
      )
    val base = ReviewContextBudgetPolicy.DEFAULT
    val narrowBudget = ReviewContextBudgetPolicy.deriveLaneEvidenceBytes(base, narrow, packet)
    val broadBudget = ReviewContextBudgetPolicy.deriveLaneEvidenceBytes(base, broad, packet)
    assertNotEquals(narrowBudget, broadBudget)
    assertTrue(broadBudget > narrowBudget)
  }

  @Test fun `derived lane evidence budget satisfies policy init`() {
    val small = ReviewChangedHunk("src/A.kt", 1, 1, 1, 2, "+a")
    val large = ReviewChangedHunk("src/B.kt", 1, 1, 1, 2, "+" + "x".repeat(100_000))
    val packet =
      ReviewContextPacket(
        "review", "repo", "base", "head", "clean", "kotlin", "kotlin", emptyList(),
        listOf("security"),
        listOf(small, large),
        commitUnits = listOf(syntheticUnit(listOf(small, large))),
        coverageFact = fact(),
        routingMatrix = focusedMatrix(listOf(syntheticUnit(listOf(small, large))), listOf("security")),
        reviewRevision = revision(),
        laneDecisions = listOf(lane("security", listOf("src/A.kt"))),
      )
    val assignment = launchAssignment(packet, listOf(small.hunkId))
    val base = ReviewContextBudgetPolicy.DEFAULT
    val derived = ReviewContextBudgetPolicy.deriveLaneEvidenceBytes(base, assignment, packet)
    ReviewContextBudgetPolicy(
      maxLaneEvidenceBytes = derived,
      maxEvidenceResultBytes = base.maxEvidenceResultBytes,
    )
  }

  @Test fun `parent merged evidence allowance matches sum of per-lane derived caps`() {
    val small = ReviewChangedHunk("src/A.kt", 1, 1, 1, 2, "+a")
    val large = ReviewChangedHunk("src/B.kt", 1, 1, 1, 2, "+" + "x".repeat(100_000))
    val packet =
      ReviewContextPacket(
        "review", "repo", "base", "head", "clean", "kotlin", "kotlin", emptyList(),
        listOf("security", "testing"),
        listOf(small, large),
        commitUnits = listOf(syntheticUnit(listOf(small, large))),
        coverageFact = fact(),
        routingMatrix = focusedMatrix(listOf(syntheticUnit(listOf(small, large))), listOf("security", "testing")),
        reviewRevision = revision(),
        laneDecisions = listOf(lane("security", listOf("src/A.kt")), lane("testing", listOf("src/B.kt"))),
      )
    val base = ReviewContextBudgetPolicy.DEFAULT
    val narrow =
      launchAssignment(packet, listOf(small.hunkId)).copy(
        lane = "security",
        assignedPaths = listOf("src/A.kt"),
        laneDecision = lane("security", listOf("src/A.kt")),
        laneRouting = packet.routingMatrix.decisionsFor("security"),
      )
    val broad =
      launchAssignment(packet, listOf(large.hunkId)).copy(
        lane = "testing",
        assignedPaths = listOf("src/B.kt"),
        laneDecision = lane("testing", listOf("src/B.kt")),
        laneRouting = packet.routingMatrix.decisionsFor("testing"),
      )
    val mergedSum =
      ReviewContextBudgetPolicy.deriveLaneEvidenceBytes(base, narrow, packet) +
        ReviewContextBudgetPolicy.deriveLaneEvidenceBytes(base, broad, packet)
    assertNotEquals(base.maxLaneEvidenceBytes * 2, mergedSum)
  }

  @Test fun `oversized compact launch segments instead of failing the whole payload`() {
    val packet = launchPacket()
    val assignment = launchAssignment(packet)
    val policy =
      ReviewContextBudgetPolicy(
        maxParentPacketBytes = 10_000,
        maxLaneLaunchBytes = 10,
        maxLaneEvidenceBytes = 100,
        maxEvidenceResultBytes = 50,
        maxLaneResultBytes = 50,
      )
    val launch = GovernedReviewLaunch(assignment, packet, "contract", "rubric", "broker", policy)

    assertEquals(REVIEW_CONTEXT_BUDGET_EXCEEDED, launch.budgetOutcomeOrNull()?.type)
  }

  @Test fun `explicit mode is authoritative`() {
    assertEquals(CodeReviewExecutionMode.INLINE, CodeReviewExecutionMode.DEFAULT)
    assertEquals(
      ResolvedReviewExecutionMode.INLINE,
      ReviewExecutionModePolicy.resolve(CodeReviewExecutionMode.INLINE),
    )
    assertEquals(
      ResolvedReviewExecutionMode.DELEGATED,
      ReviewExecutionModePolicy.resolve(CodeReviewExecutionMode.DELEGATED),
    )
    assertEquals(
      ResolvedReviewExecutionMode.INLINE,
      ReviewExecutionModePolicy.resolve(CodeReviewExecutionMode.AUTO),
    )
  }
}
