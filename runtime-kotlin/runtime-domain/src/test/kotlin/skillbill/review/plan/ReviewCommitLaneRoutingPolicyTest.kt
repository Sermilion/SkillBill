package skillbill.review.plan

import skillbill.review.context.model.accounting.ReviewContextBudgetPolicy
import skillbill.review.context.model.commit.REVIEW_ROUTING_REASON_MAX_CHARS
import skillbill.review.context.model.commit.ReviewCommitLaneDecision
import skillbill.review.context.model.commit.ReviewCommitLaneDisposition
import skillbill.review.context.model.commit.ReviewCommitLaneRoutingMatrix
import skillbill.review.context.model.commit.ReviewCommitSource
import skillbill.review.context.model.commit.ReviewCommitUnit
import skillbill.review.context.model.hunk.ReviewChangedHunk
import skillbill.review.plan.model.ReviewLaunchLane
import skillbill.review.plan.model.ReviewRoutedLane
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReviewCommitLaneRoutingPolicyTest {
  private fun lane(
    area: String,
    pathSignals: List<String> = emptyList(),
    contentSignals: List<String> = emptyList(),
    required: Boolean = false,
  ) = ReviewRoutedLane(
    "claude:bill-kotlin-code-review-$area",
    ReviewLaunchLane(
      skillName = "bill-kotlin-code-review-$area",
      packSlug = "kotlin",
      area = area,
      depth = 0,
      originLayerChain = listOf("kotlin"),
      required = required,
      addOns = emptyList(),
      orderIndex = 0,
      inclusionReason = "declared",
      pathSignals = pathSignals,
      contentSignals = contentSignals,
    ),
  )

  private val ui = lane("ui", pathSignals = listOf("ui/", "*.compose.kt"), contentSignals = listOf("@Composable"))
  private val uxAccessibility =
    lane(
      "ux-accessibility",
      pathSignals = listOf("ui/"),
      contentSignals = listOf("contentDescription", "semantics"),
    )
  private val persistence =
    lane(
      "persistence",
      pathSignals = listOf("db/", "migrations/"),
      contentSignals = listOf("CREATE TABLE", "@Entity"),
    )
  private val apiContracts =
    lane(
      "api-contracts",
      pathSignals = listOf("api/", "contracts/"),
      contentSignals = listOf("@Serializable", "openapi"),
    )
  private val security =
    lane(
      "security",
      pathSignals = listOf("auth/"),
      contentSignals = listOf("authorize", "jwt", "tenantId"),
    )
  private val testing = lane("testing", pathSignals = listOf("src/test/"), contentSignals = listOf("@Test"))
  private val architecture =
    lane(
      "architecture",
      pathSignals = listOf("ports/"),
      contentSignals = listOf("interface ", "internal "),
    )
  private val baseline = lane("platform-correctness", required = true)

  private val allLanes = listOf(ui, uxAccessibility, persistence, apiContracts, security, testing, architecture)

  private fun commit(
    order: Int,
    vararg hunks: ReviewChangedHunk,
  ) = ReviewCommitUnit.ofCommit(
    commitSha = "c$order",
    parentSha = if (order == 0) "base" else "c${order - 1}",
    subject = "commit $order",
    orderIndex = order,
    hunks = hunks.toList(),
  )

  private fun hunk(
    path: String,
    content: String,
  ) = ReviewChangedHunk(path, 1, 1, 1, 2, content)

  private fun disposition(
    matrix: ReviewCommitLaneRoutingMatrix,
    commitSha: String,
    lane: ReviewRoutedLane,
  ) = matrix.decisions.single { it.commitSha == commitSha && it.lane == lane.laneKey }.disposition

  @Test fun `a pure UI commit never enters the security lane`() {
    val uiCommit = commit(0, hunk("ui/ProfileScreen.kt", "+@Composable fun Profile() {}"))
    val matrix = ReviewCommitLaneRoutingPolicy.route(listOf(uiCommit), allLanes)

    assertEquals(ReviewCommitLaneDisposition.FOCUSED, disposition(matrix, "c0", ui))
    assertEquals(ReviewCommitLaneDisposition.SKIPPED, disposition(matrix, "c0", security))
    assertEquals(ReviewCommitLaneDisposition.SKIPPED, disposition(matrix, "c0", persistence))
    assertEquals(ReviewCommitLaneDisposition.SKIPPED, disposition(matrix, "c0", apiContracts))
  }

  @Test fun `a pure UX accessibility commit focuses UX and skips persistence and security`() {
    val uxCommit = commit(0, hunk("ui/AccessibleButton.kt", "+Modifier.semantics { contentDescription = \"Save\" }"))
    val matrix = ReviewCommitLaneRoutingPolicy.route(listOf(uxCommit), allLanes)

    assertEquals(ReviewCommitLaneDisposition.FOCUSED, disposition(matrix, "c0", uxAccessibility))
    assertEquals(ReviewCommitLaneDisposition.FOCUSED, disposition(matrix, "c0", ui))
    assertEquals(ReviewCommitLaneDisposition.SKIPPED, disposition(matrix, "c0", persistence))
    assertEquals(ReviewCommitLaneDisposition.SKIPPED, disposition(matrix, "c0", security))
  }

  @Test fun `a pure architecture ports commit focuses architecture and skips UI`() {
    val archCommit = commit(0, hunk("ports/BillingPort.kt", "+interface BillingPort { fun charge(): Boolean }"))
    val matrix = ReviewCommitLaneRoutingPolicy.route(listOf(archCommit), allLanes)

    assertEquals(ReviewCommitLaneDisposition.FOCUSED, disposition(matrix, "c0", architecture))
    assertEquals(ReviewCommitLaneDisposition.SKIPPED, disposition(matrix, "c0", ui))
    assertEquals(ReviewCommitLaneDisposition.SKIPPED, disposition(matrix, "c0", uxAccessibility))
  }

  @Test fun `a pure testing commit focuses testing and skips security`() {
    val testCommit = commit(0, hunk("src/test/ScreenTest.kt", "+@Test fun renders() {}"))
    val matrix = ReviewCommitLaneRoutingPolicy.route(listOf(testCommit), allLanes)

    assertEquals(ReviewCommitLaneDisposition.FOCUSED, disposition(matrix, "c0", testing))
    assertEquals(ReviewCommitLaneDisposition.SKIPPED, disposition(matrix, "c0", security))
    assertEquals(ReviewCommitLaneDisposition.SKIPPED, disposition(matrix, "c0", persistence))
  }

  @Test fun `a pure persistence commit never enters the UI lane`() {
    val dbCommit = commit(0, hunk("db/migrations/003_add_tenant.sql", "+CREATE TABLE tenant (id uuid);"))
    val matrix = ReviewCommitLaneRoutingPolicy.route(listOf(dbCommit), allLanes)

    assertEquals(ReviewCommitLaneDisposition.FOCUSED, disposition(matrix, "c0", persistence))
    assertEquals(ReviewCommitLaneDisposition.SKIPPED, disposition(matrix, "c0", ui))
    assertEquals(ReviewCommitLaneDisposition.SKIPPED, disposition(matrix, "c0", uxAccessibility))
  }

  @Test fun `a shared contract change under an unrelated path still reaches api-contracts`() {
    val disguised = commit(0, hunk("misc/WireFormat.kt", "+@Serializable data class WireFormat(val openapi: String)"))
    val matrix = ReviewCommitLaneRoutingPolicy.route(listOf(disguised), allLanes)

    assertEquals(ReviewCommitLaneDisposition.FOCUSED, disposition(matrix, "c0", apiContracts))
  }

  @Test fun `an authentication and API commit reaches both the security and api-contract lanes`() {
    val authCommit =
      commit(
        0,
        hunk("auth/TokenVerifier.kt", "+fun authorize(jwt: String): Boolean"),
        hunk("api/SessionResponse.kt", "+@Serializable data class SessionResponse(val token: String)"),
      )
    val matrix = ReviewCommitLaneRoutingPolicy.route(listOf(authCommit), allLanes)

    assertEquals(ReviewCommitLaneDisposition.FOCUSED, disposition(matrix, "c0", security))
    assertEquals(ReviewCommitLaneDisposition.FOCUSED, disposition(matrix, "c0", apiContracts))
    assertEquals(ReviewCommitLaneDisposition.SKIPPED, disposition(matrix, "c0", ui))
  }

  @Test fun `a cross-cutting commit enters several lanes and each inclusion cites changed evidence`() {
    val crossCutting =
      commit(
        0,
        hunk("ports/TenantPort.kt", "+interface TenantPort { fun authorize(tenantId: String): Boolean }"),
        hunk("db/TenantEntity.kt", "+@Entity data class TenantEntity(val tenantId: String)"),
      )
    val matrix = ReviewCommitLaneRoutingPolicy.route(listOf(crossCutting), allLanes)

    listOf(security, persistence, architecture).forEach { entered ->
      val decision = matrix.decisions.single { it.commitSha == "c0" && it.lane == entered.laneKey }
      assertEquals(ReviewCommitLaneDisposition.FOCUSED, decision.disposition, entered.laneKey)
      assertTrue(decision.signals.isNotEmpty(), "inclusion into ${entered.laneKey} cites no changed evidence")
      assertTrue(
        decision.signals.all { signal ->
          val value = signal.substringAfter(':')
          crossCutting.hunks.any {
            ReviewPathMatcher.matches(it.path, value) || ReviewContentMatcher.contains(it.content, value)
          }
        },
        "inclusion into ${entered.laneKey} cites a signal absent from the changed hunks",
      )
    }
  }

  @Test fun `disguised risk still reaches its owning lane despite an unrelated path and misleading subject`() {
    val disguised =
      ReviewCommitUnit.ofCommit(
        commitSha = "c0",
        parentSha = "base",
        subject = "chore: tidy up whitespace in docs",
        orderIndex = 0,
        hunks = listOf(hunk("misc/Helpers.kt", "+fun check(tenantId: String) = authorize(tenantId)")),
      )
    val matrix = ReviewCommitLaneRoutingPolicy.route(listOf(disguised), allLanes)

    assertEquals(ReviewCommitLaneDisposition.FOCUSED, disposition(matrix, "c0", security))
  }

  @Test fun `a durable state shape change under an unrelated path still reaches persistence`() {
    val disguised = commit(0, hunk("util/Bootstrap.kt", "+@Entity data class AuditRow(val id: Long)"))
    val matrix = ReviewCommitLaneRoutingPolicy.route(listOf(disguised), allLanes)

    assertEquals(ReviewCommitLaneDisposition.FOCUSED, disposition(matrix, "c0", persistence))
  }

  @Test fun `a commit subject alone can never focus a lane`() {
    val subjectOnly =
      ReviewCommitUnit.ofCommit(
        commitSha = "c0",
        parentSha = "base",
        subject = "security: authorize every jwt against the tenantId in api/contracts",
        orderIndex = 0,
        hunks = listOf(hunk("docs/README.md", "+Some prose about nothing in particular.")),
      )
    val matrix = ReviewCommitLaneRoutingPolicy.route(listOf(subjectOnly), allLanes)

    assertTrue(matrix.decisions.none { it.focused }, "a commit message alone focused a lane")
  }

  @Test fun `required baseline lanes are focused for every commit and never skipped`() {
    val commits =
      listOf(
        commit(0, hunk("ui/Screen.kt", "+@Composable fun Screen() {}")),
        commit(1, hunk("db/Migration.sql", "+CREATE TABLE t (id int);")),
        commit(2, hunk("docs/NOTES.md", "+prose")),
      )
    val matrix = ReviewCommitLaneRoutingPolicy.route(commits, allLanes + baseline)

    assertEquals(commits.size, matrix.focusedCommits(baseline.laneKey).size)
    assertTrue(
      matrix.decisionsFor(baseline.laneKey).all {
        it.focused && it.signals == listOf(ReviewCommitLaneRoutingPolicy.REQUIRED_BASELINE_SIGNAL)
      },
    )
  }

  @Test fun `optional lanes do not receive clearly irrelevant commit bodies`() {
    val docsOnly = commit(0, hunk("docs/CHANGELOG.md", "+released 1.2.3"))
    val matrix = ReviewCommitLaneRoutingPolicy.route(listOf(docsOnly), allLanes)

    assertTrue(matrix.decisions.none { it.focused })
  }

  @Test fun `sparse routing shrinks the commit-by-lane matrix while preserving required and cross-cutting coverage`() {
    val commits =
      listOf(
        commit(0, hunk("ui/Screen.kt", "+@Composable fun Screen() {}")),
        commit(1, hunk("db/Migration.sql", "+CREATE TABLE t (id int);")),
        commit(2, hunk("src/test/ScreenTest.kt", "+@Test fun renders() {}")),
        commit(3, hunk("auth/Guard.kt", "+fun authorize() = true"), hunk("api/Dto.kt", "+@Serializable class Dto")),
      )
    val lanes = allLanes + baseline
    val matrix = ReviewCommitLaneRoutingPolicy.route(commits, lanes)

    val fullProduct = commits.size * lanes.size
    assertEquals(fullProduct, matrix.analyzedPairCount)
    assertTrue(
      matrix.focusedPairCount < fullProduct,
      "sparse routing focused $fullProduct of $fullProduct pairs, so it reduced nothing",
    )
    assertEquals(commits.size, matrix.focusedCommits(baseline.laneKey).size)
    assertEquals(listOf("c3"), matrix.focusedCommits(security.laneKey))
    assertEquals(listOf("c3"), matrix.focusedCommits(apiContracts.laneKey))
    assertEquals(listOf("c0"), matrix.focusedCommits(ui.laneKey))
    assertEquals(listOf("c1"), matrix.focusedCommits(persistence.laneKey))
    assertEquals(listOf("c2"), matrix.focusedCommits(testing.laneKey))
  }

  @Test fun `every skip reason is falsifiable against the commit's own changed hunks`() {
    val commits =
      listOf(
        commit(0, hunk("ui/Screen.kt", "+@Composable fun Screen() {}")),
        commit(1, hunk("db/Migration.sql", "+CREATE TABLE t (id int);")),
      )
    val matrix = ReviewCommitLaneRoutingPolicy.route(commits, allLanes)
    val unitsBySha = commits.associateBy { it.commitSha }

    matrix.decisions.filterNot { it.focused }.forEach { decision ->
      val unit = unitsBySha.getValue(decision.commitSha)
      assertTrue(decision.reason.isNotBlank())
      assertTrue(decision.reason.length <= REVIEW_ROUTING_REASON_MAX_CHARS)
      assertTrue(decision.signals.isEmpty(), "a skip cited a matching signal")
      unit.hunks.map { it.path }.distinct().forEach { path ->
        assertTrue(path in decision.reason, "skip reason omits the changed path '$path' it must be checkable against")
      }
      val descriptor = allLanes.single { it.laneKey == decision.lane }.descriptor
      assertFalse(
        descriptor.pathSignals.any { signal -> unit.hunks.any { ReviewPathMatcher.matches(it.path, signal) } },
        "skip reason claims no path signal matched, but one does",
      )
      assertFalse(
        descriptor.contentSignals.any { signal ->
          unit.hunks.any { ReviewContentMatcher.contains(it.content, signal) }
        },
        "skip reason claims no content signal matched, but one does",
      )
    }
  }

  @Test fun `routing decides every commit-lane pair exactly once and in commit order`() {
    val commits =
      listOf(
        commit(0, hunk("ui/Screen.kt", "+@Composable fun A() {}")),
        commit(1, hunk("auth/Guard.kt", "+fun authorize() = true")),
      )
    val matrix = ReviewCommitLaneRoutingPolicy.route(commits, allLanes)

    assertEquals(commits.size * allLanes.size, matrix.decisions.size)
    assertEquals(listOf("c0", "c1"), matrix.commitShas)
    assertEquals(matrix.decisions.size, matrix.decisions.map { it.commitSha to it.lane }.distinct().size)
    assertTrue(matrix.decisions.all { it.reason.isNotBlank() })
  }

  @Test fun `a single synthetic unit resolves each lane to exactly one decision without commit identity`() {
    val synthetic =
      ReviewCommitUnit.synthetic(
        ReviewCommitSource.SYNTHETIC_WORKING_TREE,
        listOf(hunk("ui/Screen.kt", "+@Composable fun Screen() {}")),
      )
    val matrix = ReviewCommitLaneRoutingPolicy.route(listOf(synthetic), allLanes + baseline)

    assertEquals(allLanes.size + 1, matrix.decisions.size)
    assertEquals(listOf(synthetic.commitSha), matrix.commitShas)
    assertTrue(matrix.commitShas.single().startsWith("synthetic:"))
    assertEquals(listOf(synthetic.commitSha), matrix.focusedCommits(ui.laneKey))
    assertEquals(listOf(synthetic.commitSha), matrix.focusedCommits(baseline.laneKey))
    assertTrue(matrix.focusedCommits(persistence.laneKey).isEmpty())
  }

  @Test fun `routing analysis no longer hard-fails on former pair or byte prep budgets`() {
    val commits = (0..3).map { commit(it, hunk("ui/Screen$it.kt", "+@Composable fun S() {}")) }
    val matrix = ReviewCommitLaneRoutingPolicy.route(commits, allLanes)
    assertEquals(commits.size * allLanes.size, matrix.decisions.size)
  }

  @Test fun `routing analysis accepts unique hunk material regardless of lane count`() {
    val content = "+" + "a".repeat(100)
    val uniqueBytes = content.toByteArray(Charsets.UTF_8).size.toLong()
    require(uniqueBytes * allLanes.size > uniqueBytes) {
      "fixture needs more than one lane so a per-lane multiplier would differ"
    }
    val matrix =
      ReviewCommitLaneRoutingPolicy.route(
        listOf(commit(0, hunk("ui/Screen.kt", content))),
        allLanes,
      )
    assertEquals(allLanes.size, matrix.decisions.size)
  }

  @Test fun `non-positive routing budgets are rejected at construction`() {
    assertFailsWith<IllegalArgumentException> {
      ReviewContextBudgetPolicy.DEFAULT.copy(maxRoutingAnalysisPairs = 0)
    }
    assertFailsWith<IllegalArgumentException> {
      ReviewContextBudgetPolicy.DEFAULT.copy(maxRoutingAnalysisBytes = 0)
    }
  }

  @Test fun `the routing matrix rejects a missing pair a duplicate pair and a bad reason`() {
    fun decision(
      sha: String,
      order: Int,
      lane: String,
      disposition: ReviewCommitLaneDisposition = ReviewCommitLaneDisposition.FOCUSED,
      reason: String = "focused",
    ) = ReviewCommitLaneDecision(sha, order, lane, disposition, reason)

    assertFailsWith<IllegalArgumentException> {
      ReviewCommitLaneRoutingMatrix(listOf("c0", "c1"), listOf("ui"), listOf(decision("c0", 0, "ui")))
    }
    assertFailsWith<IllegalArgumentException> {
      ReviewCommitLaneRoutingMatrix(
        listOf("c0"),
        listOf("ui"),
        listOf(decision("c0", 0, "ui"), decision("c0", 0, "ui", reason = "again")),
      )
    }
    assertFailsWith<IllegalArgumentException> {
      decision("c0", 0, "ui", ReviewCommitLaneDisposition.SKIPPED, reason = "   ")
    }
    assertFailsWith<IllegalArgumentException> {
      decision("c0", 0, "ui", reason = "x".repeat(REVIEW_ROUTING_REASON_MAX_CHARS + 1))
    }
    assertFailsWith<IllegalArgumentException> {
      ReviewCommitLaneRoutingMatrix(listOf("c0", "c1"), listOf("ui"), listOf(decision("c0", 1, "ui")))
    }
  }

  @Test fun `the disposition vocabulary admits no deferred candidate state`() {
    assertEquals(
      listOf("FOCUSED", "SKIPPED"),
      ReviewCommitLaneDisposition.entries.map { it.name },
    )
  }

  @Test fun `the routing digest moves with a disposition and with a skip reason`() {
    val commits = listOf(commit(0, hunk("ui/Screen.kt", "+@Composable fun S() {}")))
    val matrix = ReviewCommitLaneRoutingPolicy.route(commits, allLanes)
    val flipped =
      matrix.copy(
        decisions =
          matrix.decisions.map {
            if (it.lane == security.laneKey) it.copy(disposition = ReviewCommitLaneDisposition.FOCUSED) else it
          },
      )
    val rephrased =
      matrix.copy(
        decisions =
          matrix.decisions.map {
            if (it.lane == security.laneKey) it.copy(reason = "a different but still falsifiable reason") else it
          },
      )

    assertTrue(matrix.routingDigest != flipped.routingDigest)
    assertTrue(matrix.routingDigest != rephrased.routingDigest)
    assertEquals(matrix.routingDigest, matrix.copy().routingDigest)
  }
}
