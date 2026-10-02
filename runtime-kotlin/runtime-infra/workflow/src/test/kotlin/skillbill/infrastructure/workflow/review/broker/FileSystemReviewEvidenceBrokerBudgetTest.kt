package skillbill.infrastructure.workflow.review.broker

import skillbill.error.shellcontent.ReviewHunkEvidenceIntegrityError
import skillbill.ports.review.evidence.ReviewStoredHunkBodyExtractor
import skillbill.ports.review.model.ReviewEvidenceBatchRequest
import skillbill.ports.review.model.ReviewEvidenceBrokerBinding
import skillbill.ports.review.model.ReviewEvidenceRequest
import skillbill.ports.review.model.ReviewToolCall
import skillbill.ports.taskruntime.FeatureTaskRuntimeSharedEvidenceLocatorReadPort
import skillbill.review.context.model.accounting.ReviewContextBudgetPolicy
import skillbill.review.context.model.commit.ReviewAssignment
import skillbill.review.context.model.commit.ReviewLaneDecision
import skillbill.review.context.model.execution.ReviewOperationKind
import skillbill.review.context.model.hunk.ReviewChangedHunk
import skillbill.review.context.model.hunk.ReviewDependencyAllowlist
import skillbill.review.context.model.hunk.ReviewHunkEvidenceLocator
import skillbill.review.context.model.hunk.ReviewRevision
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileSystemReviewEvidenceBrokerBudgetTest {
  @Test fun `paths escaping the repository are rejected`() {
    val root = repo("A.kt" to "assigned")
    val broker = broker(root, assignment(listOf("A.kt")))
    assertFailsWith<IllegalArgumentException> { broker.readBatch(batch("../outside.kt")) }
  }

  @Test fun `assigned locator read returns the stored hunk body`() {
    val root = repo("A.kt" to "working-tree")
    val hunk = ReviewChangedHunk("A.kt", 2, 1, 2, 1, "@@ -2 +2 @@\n-owned\n+changed")
    val storePath = ".skill-bill/run-evidence/code-review/fp-assigned"
    val broker = locatorBroker(LocatorBrokerSpec(root, listOf(hunk), storePath, mapOf(storePath to hunk.content)))

    val first = broker.readBatch(batch("A.kt")).results.single()
    assertEquals(hunk.content, first.content)
    assertEquals(hunk.content.toByteArray(Charsets.UTF_8).size.toLong(), first.bytes)
  }

  @Test fun `overwriting stored body without updating content digest throws integrity error`() {
    val root = repo("A.kt" to "working-tree")
    val original = "@@ -2 +2 @@\n-owned\n+changed"
    val overwritten = "@@ -2 +2 @@\n-owned\n+tampered"
    val hunk = ReviewChangedHunk("A.kt", 2, 1, 2, 1, original)
    val storePath = ".skill-bill/run-evidence/code-review/fp-integrity"
    val payloads = mutableMapOf(storePath to original)
    val broker = locatorBroker(LocatorBrokerSpec(root, listOf(hunk), storePath, payloads))
    payloads[storePath] = overwritten

    val failure =
      assertFailsWith<ReviewHunkEvidenceIntegrityError> {
        broker.readBatch(batch("A.kt"))
      }
    assertEquals(storePath, failure.storePath)
    assertEquals(ReviewChangedHunk.digestOfBody(original), failure.expectedDigest)
    assertEquals(ReviewChangedHunk.digestOfBody(overwritten), failure.observedDigest)
    assertEquals(0, broker.accounting().evidenceBytes)
  }

  @Test fun `a hunk larger than remaining evidence budget is omitted entirely`() {
    val root = repo("A.kt" to "aa", "B.kt" to "bbbb")
    val small = ReviewChangedHunk("A.kt", 1, 1, 1, 1, "aa")
    val large = ReviewChangedHunk("B.kt", 1, 1, 1, 1, "bbbb")
    val storePath = ".skill-bill/run-evidence/code-review/fp-budget"
    val broker =
      locatorBroker(
        LocatorBrokerSpec(root, listOf(small, large), storePath, mapOf(storePath to "aa")),
        policy(result = 3, cumulative = 3),
        ReviewStoredHunkBodyExtractor { _, hunk -> if (hunk.path == "A.kt") "aa" else "bbbb" },
      )
    val first = broker.readBatch(batch("A.kt")).results.single()
    val second = broker.readBatch(batch("B.kt")).results.single()

    assertEquals("aa", first.content)
    assertEquals(null, second.content)
    assertEquals("lane_evidence_bytes", second.budgetExceeded?.budgetKind?.wireValue)
    assertTrue(second.bytes == 0L)
    assertEquals("lane_evidence_bytes", broker.accounting().terminalOutcome?.budgetKind?.wireValue)
    assertEquals(listOf("head@B.kt"), broker.accounting().unreviewedUnits)
    assertEquals(1, broker.accounting().refusedOperationCount)
  }

  @Test fun `mid batch lane evidence refusal records denied unit for refused target only`() {
    val root = repo("A.kt" to "12345", "B.kt" to "67890", "C.kt" to "abcde")
    val hunks =
      listOf("A.kt", "B.kt", "C.kt").map { path ->
        ReviewChangedHunk(path, 1, 1, 1, 1, Files.readString(root.resolve(path)))
      }
    val assigned =
      assignment(listOf("A.kt", "B.kt", "C.kt")).copy(
        assignedHunks = hunks.map { it.hunkId },
      )
    val broker =
      FileSystemReviewEvidenceBroker(
        ReviewEvidenceBrokerBinding(
          root,
          assigned,
          "security",
          policy(result = 8, cumulative = 8),
          projectedHunks = hunks,
        ),
      )
    broker.readBatch(batch("A.kt"))
    broker.readBatch(batch("B.kt"))
    val accounting = broker.accounting()
    assertEquals(listOf("head@B.kt"), accounting.unreviewedUnits)
    assertEquals("lane_evidence_bytes", accounting.terminalOutcome?.budgetKind?.wireValue)
    assertTrue(accounting.unreviewedUnits.none { it.endsWith("@C.kt") })
  }

  @Test fun `an ordinary bounded review completes with full accounting and no termination`() {
    val root = repo("A.kt" to "assigned")
    val broker = projectedBroker(root, assignment(listOf("A.kt")))
    broker.recordModelTurn()
    broker.recordToolCall(ReviewToolCall("security", ReviewOperationKind.FILE_READ, "A.kt"))
    broker.readBatch(batch("A.kt"))
    assertNull(broker.validateLaneResult("- [F-001] Minor | Low | A.kt:1 | bounded finding"))
    val accounting = broker.accounting()
    assertEquals(8, accounting.evidenceBytes)
    assertEquals(1, accounting.toolCalls)
    assertEquals(1, accounting.modelTurns)
    assertEquals(48, accounting.resultBytes)
    assertNull(accounting.terminalOutcome)
  }

  private fun batch(path: String) = ReviewEvidenceBatchRequest.of(ReviewEvidenceRequest("security", path))

  private fun repo(vararg files: Pair<String, String>): Path {
    val root = Files.createTempDirectory("review-evidence")
    files.forEach { (name, content) ->
      val target = root.resolve(name)
      target.parent?.let(Files::createDirectories)
      Files.writeString(target, content)
    }
    return root
  }

  private fun broker(
    root: Path,
    assignment: ReviewAssignment,
    budget: ReviewContextBudgetPolicy = policy(),
  ) = FileSystemReviewEvidenceBroker(
    ReviewEvidenceBrokerBinding(root, assignment, "security", budget),
  )

  private fun projectedBroker(
    root: Path,
    assignment: ReviewAssignment,
    budget: ReviewContextBudgetPolicy = policy(),
  ): FileSystemReviewEvidenceBroker {
    val hunks =
      assignment.assignedPaths.map { path ->
        ReviewChangedHunk(path, 1, 1, 1, 1, Files.readString(root.resolve(path)))
      }
    val projectedAssignment = assignment.copy(assignedHunks = hunks.map { it.hunkId })
    return FileSystemReviewEvidenceBroker(
      ReviewEvidenceBrokerBinding(root, projectedAssignment, "security", budget, projectedHunks = hunks),
    )
  }

  private data class LocatorBrokerSpec(
    val root: Path,
    val hunks: List<ReviewChangedHunk>,
    val storePath: String,
    val payloads: Map<String, String>,
  )

  private fun locatorBroker(
    spec: LocatorBrokerSpec,
    budget: ReviewContextBudgetPolicy = policy(),
    extractor: ReviewStoredHunkBodyExtractor? = null,
  ): FileSystemReviewEvidenceBroker {
    val indexed =
      spec.hunks.map { hunk ->
        hunk.asIndex(
          ReviewHunkEvidenceLocator.atStore(
            spec.storePath,
            hunk.oldStart,
            hunk.oldCount,
            hunk.newStart,
            hunk.newCount,
          ),
          hunk.content,
        )
      }
    val assigned = assignment(indexed.map { it.path }.distinct()).copy(assignedHunks = indexed.map { it.hunkId })
    return FileSystemReviewEvidenceBroker(
      ReviewEvidenceBrokerBinding(
        spec.root,
        assigned,
        "security",
        budget,
        projectedHunks = indexed,
        locatorReader =
          FeatureTaskRuntimeSharedEvidenceLocatorReadPort { request ->
            spec.payloads[request.storePath] ?: error("missing locator payload")
          },
        bodyExtractor =
          extractor ?: ReviewStoredHunkBodyExtractor { payload, _ ->
            payload.replace("\r\n", "\n")
          },
      ),
    )
  }

  private fun assignment(
    paths: List<String>,
    dependencies: List<String> = emptyList(),
  ) = ReviewAssignment(
    "review",
    "a".repeat(64),
    "security",
    "base",
    "head",
    paths,
    emptyList(),
    reviewRevision = ReviewRevision("rvs-1", 1),
    laneDecision =
      ReviewLaneDecision(
        "security",
        true,
        "routed",
        ownedPaths = paths.ifEmpty { listOf("A.kt") },
        originLayerChains = listOf(listOf("kotlin")),
        owningPack = "kotlin",
        specialistSkillName = "bill-kotlin-code-review-security",
      ),
    dependencyAllowlist = ReviewDependencyAllowlist(dependencies),
  )

  private fun policy(
    result: Long = 100,
    cumulative: Long = 200,
    expansions: Int = 1,
    toolCalls: Int = 40,
    modelTurns: Int = 24,
  ) = ReviewContextBudgetPolicy(
    maxParentPacketBytes = 1_000,
    maxLaneLaunchBytes = 500,
    maxLaneEvidenceBytes = cumulative,
    maxEvidenceResultBytes = result,
    maxLaneResultBytes = 100,
    maxAssignmentExpansions = expansions,
    maxSpecialistToolCalls = toolCalls,
    maxSpecialistModelTurns = modelTurns,
  )
}
