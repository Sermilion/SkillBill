package skillbill.infrastructure.workflow.review.broker

import skillbill.error.shellcontent.InvalidReviewContextSchemaError
import skillbill.ports.review.model.ReviewEvidenceBatchRequest
import skillbill.ports.review.model.ReviewEvidenceBrokerBinding
import skillbill.ports.review.model.ReviewEvidenceRequest
import skillbill.ports.review.model.ReviewExpansionAuthorizationRequest
import skillbill.ports.review.model.ReviewRefusedOperationRecord
import skillbill.ports.review.model.ReviewToolCall
import skillbill.review.context.model.accounting.REVIEW_CONTEXT_BUDGET_EXCEEDED
import skillbill.review.context.model.accounting.ReviewContextBudgetPolicy
import skillbill.review.context.model.commit.ReviewAssignment
import skillbill.review.context.model.commit.ReviewLaneDecision
import skillbill.review.context.model.execution.ReviewOperationKind
import skillbill.review.context.model.hunk.ReviewChangedHunk
import skillbill.review.context.model.hunk.ReviewDependencyAllowlist
import skillbill.review.context.model.hunk.ReviewExpansionRecord
import skillbill.review.context.model.hunk.ReviewRevision
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileSystemReviewEvidenceBrokerTest {
  @Test fun `an unchanged assigned target read twice is served again and charged once`() {
    val root = repo("A.kt" to "outside\nowned\noutside")
    val hunk = ReviewChangedHunk("A.kt", 2, 1, 2, 1, "@@ -2 +2 @@\n-owned\n+changed")
    val base = assignment(listOf("A.kt"))
    val scoped = base.copy(assignedHunks = listOf(hunk.hunkId))
    val broker =
      FileSystemReviewEvidenceBroker(
        ReviewEvidenceBrokerBinding(
          root,
          scoped,
          "security",
          policy(),
          projectedHunks = listOf(hunk),
        ),
      )

    val first = broker.readBatch(batch("A.kt")).results.single()
    val chargedAfterFirst = broker.accounting().evidenceBytes
    val repeated =
      broker.readBatch(
        ReviewEvidenceBatchRequest.of(
          ReviewEvidenceRequest("security", "A.kt", offset = 1, limit = 1, paginationToken = "next"),
        ),
      ).results.single()

    assertEquals(hunk.content, first.content)
    assertEquals(hunk.content.toByteArray(Charsets.UTF_8).size.toLong(), first.bytes)
    assertNull(repeated.forbidden)
    assertEquals(hunk.content, repeated.content)
    assertEquals(chargedAfterFirst, broker.accounting().evidenceBytes)
    assertEquals(0, broker.accounting().refusedOperationCount)
  }

  @Test fun `a repeat that widens projected hunks to a complete file is refused`() {
    val root = repo("A.kt" to "outside\nowned\noutside")
    val hunk = ReviewChangedHunk("A.kt", 2, 1, 2, 1, "@@ -2 +2 @@\n-owned\n+changed")
    val scoped = assignment(listOf("A.kt")).copy(assignedHunks = listOf(hunk.hunkId))
    val expansionRequest = expansionRequest(scoped, "A.kt", "needs the whole file")
    val broker =
      FileSystemReviewEvidenceBroker(
        ReviewEvidenceBrokerBinding(
          root,
          scoped,
          "security",
          policy(),
          projectedHunks = listOf(hunk),
          trustedExpansionLedger = listOf(requireNotNull(expansionRequest.authorizedExpansion)),
        ),
      )

    broker.readBatch(batch("A.kt"))
    val widened = broker.readBatch(ReviewEvidenceBatchRequest.of(expansionRequest)).results.single()

    assertEquals("evidence_scope_expansion_repeat", widened.forbidden?.category)
    assertNull(widened.content)
  }

  @Test fun `filesystem unsafe assigned path is rejected while broker is constructed`() {
    val root = repo("A.kt" to "assigned")
    assertFailsWith<IllegalArgumentException> {
      broker(root, assignment(listOf("missing/../A.kt")))
    }
  }

  @Test fun `supplementary Unicode path maps with exact Git identity`() {
    val path = "src/rocket-\uD83D\uDE80.kt"
    val root = repo(path to "assigned")
    val broker = projectedBroker(root, assignment(listOf(path)))

    val result =
      broker.readBatch(
        ReviewEvidenceBatchRequest("security", listOf(ReviewEvidenceRequest("security", path))),
      )

    assertEquals("assigned", result.results.single().content)
  }

  @Test fun `assigned path crossing a symlink is rejected before evidence access`() {
    val root = repo("target/A.kt" to "assigned")
    Files.createSymbolicLink(root.resolve("link"), root.resolve("target"))
    assertFailsWith<IllegalArgumentException> {
      broker(root, assignment(listOf("link/A.kt")))
    }
  }

  @Test fun `batched assigned reads are measured in one pass`() {
    val root = repo("A.kt" to "assigned", "B.kt" to "second")
    val broker = projectedBroker(root, assignment(listOf("A.kt", "B.kt")))
    val result =
      broker.readBatch(
        ReviewEvidenceBatchRequest(
          "security",
          listOf(ReviewEvidenceRequest("security", "A.kt"), ReviewEvidenceRequest("security", "B.kt")),
        ),
      )
    assertEquals(listOf("assigned", "second"), result.results.map { it.content })
    assertNull(result.terminalOutcome)
    assertEquals(14, result.cumulativeBytes)
    assertEquals(14, broker.accounting().evidenceBytes)
  }

  @Test fun `admitted assigned read with no projected hunks counts a zero-byte authorized read`() {
    val root = repo("A.kt" to "ignored")
    val broker =
      FileSystemReviewEvidenceBroker(
        ReviewEvidenceBrokerBinding(
          root,
          assignment(listOf("A.kt")),
          "security",
          policy(),
          projectedHunks = emptyList(),
        ),
      )

    val result = broker.readBatch(batch("A.kt")).results.single()

    assertEquals("", result.content)
    assertEquals(0, result.bytes)
    assertEquals(0, broker.accounting().evidenceBytes)
    assertEquals(1, broker.accounting().authorizedReadCount)
  }

  @Test fun `literal backslash and slash paths remain distinct evidence identities`() {
    val root = repo("dir/name.kt" to "slash", "dir\\name.kt" to "backslash")
    val backslashBroker = projectedBroker(root, assignment(listOf("dir\\name.kt")))

    assertEquals("backslash", backslashBroker.readBatch(batch("dir\\name.kt")).results.single().content)
    assertEquals(
      "unassigned_file_access",
      backslashBroker.readBatch(batch("dir/name.kt")).results.single().forbidden?.category,
    )
  }

  @Test fun `deleted assigned files return projected hunks without filesystem access`() {
    val root = repo("A.kt" to "assigned")
    val existing = ReviewChangedHunk("A.kt", 1, 1, 1, 1, "assigned")
    val deleted = ReviewChangedHunk("Deleted.kt", 1, 1, 0, 0, "@@ -1 +0,0 @@\n-deleted")
    val base = assignment(listOf("A.kt", "Deleted.kt"))
    val assigned = base.copy(assignedHunks = listOf(existing.hunkId, deleted.hunkId))
    val broker =
      FileSystemReviewEvidenceBroker(
        ReviewEvidenceBrokerBinding(root, assigned, "security", policy(), projectedHunks = listOf(existing, deleted)),
      )
    val result =
      broker.readBatch(
        ReviewEvidenceBatchRequest(
          "security",
          listOf(ReviewEvidenceRequest("security", "A.kt"), ReviewEvidenceRequest("security", "Deleted.kt")),
        ),
      )

    assertEquals(listOf(existing.content, deleted.content), result.results.map { it.content })
    val expectedBytes = listOf(existing, deleted).sumOf { it.content.toByteArray(Charsets.UTF_8).size.toLong() }
    assertEquals(expectedBytes, result.cumulativeBytes)
    assertEquals(expectedBytes, broker.accounting().evidenceBytes)
  }

  @Test fun `per-result excess on a complete-file expansion terminates the lane`() {
    val root = repo("A.kt" to "assigned", "B.kt" to "second")
    val assignment = assignment(listOf("A.kt"), listOf("B.kt"))
    val request = expansionRequest(assignment, "B.kt", "called by assigned hunk")
    val broker =
      broker(
        root,
        assignment,
        policy(result = 4, cumulative = 80),
        trustedExpansionLedger = listOf(requireNotNull(request.authorizedExpansion)),
      )
    val result = broker.readBatch(ReviewEvidenceBatchRequest.of(request))
    assertEquals(REVIEW_CONTEXT_BUDGET_EXCEEDED, result.terminalOutcome?.type)
    assertEquals("evidence_result_bytes", result.terminalOutcome?.budgetKind?.wireValue)
    assertTrue(result.results.all { it.content == null })
  }

  @Test fun `cumulative evidence excess terminates the lane`() {
    val root = repo("A.kt" to "12345", "B.kt" to "67890")
    val broker = projectedBroker(root, assignment(listOf("A.kt", "B.kt")), policy(result = 8, cumulative = 8))
    val result =
      broker.readBatch(
        ReviewEvidenceBatchRequest(
          "security",
          listOf(ReviewEvidenceRequest("security", "A.kt"), ReviewEvidenceRequest("security", "B.kt")),
        ),
      )
    assertEquals("lane_evidence_bytes", result.terminalOutcome?.budgetKind?.wireValue)
    assertEquals(10, result.terminalOutcome?.observedValue)
    assertEquals(1, broker.accounting().refusedOperationCount)
    assertEquals("lane_evidence_bytes", broker.accounting().terminalOutcome?.budgetKind?.wireValue)
    assertEquals(listOf("head@B.kt"), broker.accounting().unreviewedUnits)
    assertEquals("lane_evidence_bytes", broker.accounting().budgetDimension)
    val followOn =
      broker.readBatch(
        ReviewEvidenceBatchRequest(
          "security",
          listOf(ReviewEvidenceRequest("security", "A.kt"), ReviewEvidenceRequest("security", "B.kt")),
        ),
      )
    assertTrue(followOn.results.all { it.budgetExceeded != null })
    assertEquals(3, broker.accounting().refusedOperationCount)
  }

  @Test fun `authorized expansion is admitted and audited with its reachability reason`() {
    val root = repo("A.kt" to "assigned", "B.kt" to "dep")
    val assignment = assignment(listOf("A.kt"), listOf("B.kt"))
    val request = expansionRequest(assignment, "B.kt", "called by assigned symbol")
    val broker = broker(root, assignment, trustedExpansionLedger = listOf(requireNotNull(request.authorizedExpansion)))
    val result =
      broker.readBatch(
        ReviewEvidenceBatchRequest.of(request),
      )
    assertEquals("dep", result.results.single().content)
    val expansion = result.expansions.single()
    assertEquals("B.kt", expansion.requestedPath)
    assertEquals("called by assigned symbol", expansion.reachabilityReason)
    assertTrue(expansion.authorized)
    assertEquals(0, expansion.sequence)
    assertEquals("exp-1", expansion.expansionId)
    assertEquals(assignment.digest, expansion.assignmentDigest)
    assertEquals(result.expansions, broker.accounting().expansions)
  }

  @Test fun `authorized expansion of assigned path returns the complete file`() {
    val root = repo("A.kt" to "outside\nowned\noutside")
    val hunk = ReviewChangedHunk("A.kt", 2, 1, 2, 1, "@@ -2 +2 @@\n-owned\n+changed")
    val assigned = assignment(listOf("A.kt")).copy(assignedHunks = listOf(hunk.hunkId))
    val boundExpansion =
      ReviewExpansionRecord(
        "exp-assigned",
        assigned.digest,
        "A.kt",
        "caller is outside hunk",
        true,
        0,
      )
    val authorized = assigned.copy(expansions = listOf(boundExpansion))
    val broker =
      FileSystemReviewEvidenceBroker(
        ReviewEvidenceBrokerBinding(
          root,
          authorized,
          "security",
          policy(),
          trustedExpansionLedger = authorized.expansions,
          projectedHunks = listOf(hunk),
        ),
      )

    val result =
      broker.readBatch(
        ReviewEvidenceBatchRequest.of(
          ReviewEvidenceRequest("security", "A.kt", boundExpansion.reachabilityReason, boundExpansion),
        ),
      )

    assertEquals("outside\nowned\noutside", result.results.single().content)
    assertEquals(listOf(boundExpansion), result.expansions)
  }

  @Test fun `complete-file expansion rejects content drift after launch checkpoint binding`() {
    val root = repo("A.kt" to "checkpoint content")
    val assignment = assignment(listOf("A.kt"))
    val broker = broker(root, assignment)
    val expansion =
      broker.authorizeExpansion(
        ReviewExpansionAuthorizationRequest("security", "A.kt", "definition is outside the projected hunk"),
      )
    Files.writeString(root.resolve("A.kt"), "newer working-tree content")

    val failure =
      assertFailsWith<InvalidReviewContextSchemaError> {
        broker.readBatch(
          ReviewEvidenceBatchRequest.of(
            ReviewEvidenceRequest("security", "A.kt", expansion.reachabilityReason, expansion),
          ),
        )
      }

    assertTrue(failure.reason.contains("changed after the immutable launch checkpoint"))
    assertEquals(0, broker.accounting().evidenceBytes)
  }

  @Test fun `broker authorizes expansion with assignment digest and nonblank reason`() {
    val root = repo("A.kt" to "outside\nowned\noutside")
    val assignment = assignment(listOf("A.kt"))
    val broker = broker(root, assignment)

    val expansion =
      broker.authorizeExpansion(
        ReviewExpansionAuthorizationRequest("security", "A.kt", "definition reaches beyond projected hunk"),
      )
    val result =
      broker.readBatch(
        ReviewEvidenceBatchRequest.of(
          ReviewEvidenceRequest("security", "A.kt", expansion.reachabilityReason, expansion),
        ),
      )

    assertEquals(assignment.digest, expansion.assignmentDigest)
    assertEquals("outside\nowned\noutside", result.results.single().content)
    assertEquals(listOf(expansion), result.expansions)
  }

  @Test fun `absolute scratch rediscovery is typed before repository path validation`() {
    val root = repo("A.kt" to "assigned")
    val broker = broker(root, assignment(listOf("A.kt")))
    val result =
      broker.readBatch(
        ReviewEvidenceBatchRequest.of(ReviewEvidenceRequest("security", "/tmp/review.diff")),
      )

    assertEquals("diff_artifact_rediscovery", result.results.single().forbidden?.category)
    assertEquals(
      listOf("diff_artifact_rediscovery=/tmp/review.diff"),
      broker.accounting().refusals.map(ReviewRefusedOperationRecord::toString),
      "a refusal the accounting only counts cannot tell an operator which operation was turned down",
    )
  }

  @Test fun `lane accepts its expansion at a later global packet sequence`() {
    val root = repo("A.kt" to "assigned", "B.kt" to "dep")
    val assignment = assignment(listOf("A.kt"), listOf("B.kt"))
    val expansion =
      ReviewExpansionRecord(
        "exp-lane-2",
        assignment.digest,
        "B.kt",
        "called by assigned symbol",
        true,
        4,
      )
    val broker = broker(root, assignment, trustedExpansionLedger = listOf(expansion))

    val result =
      broker.readBatch(
        ReviewEvidenceBatchRequest.of(
          ReviewEvidenceRequest("security", "B.kt", expansion.reachabilityReason, expansion),
        ),
      )

    assertEquals("dep", result.results.single().content)
    assertEquals(4, result.expansions.single().sequence)
  }

  @Test fun `assigned symlink cannot expose another repository file`() {
    val root = repo("secret.txt" to "secret")
    Files.createSymbolicLink(root.resolve("assigned.txt"), root.resolve("secret.txt"))
    assertFailsWith<IllegalArgumentException> {
      broker(root, assignment(listOf("assigned.txt")))
    }
  }

  @Test fun `unauthorized expansion is rejected before filesystem resolution and is not recorded`() {
    val root = repo("A.kt" to "assigned")
    val assignment = assignment(listOf("A.kt"), listOf("missing.kt"))
    val broker = broker(root, assignment)
    val request =
      ReviewEvidenceRequest(
        "security",
        "missing.kt",
        "called by assigned symbol",
        ReviewExpansionRecord(
          "exp-denied",
          assignment.digest,
          "missing.kt",
          "called by assigned symbol",
          false,
          0,
        ),
      )

    assertFailsWith<IllegalArgumentException> {
      broker.readBatch(ReviewEvidenceBatchRequest.of(request))
    }
    assertTrue(broker.accounting().expansions.isEmpty())
    assertEquals(0, broker.accounting().evidenceBytes)
  }

  @Test fun `an expansion carrying another assignment's identity is rejected`() {
    val root = repo("A.kt" to "assigned", "B.kt" to "dep")
    val assignment = assignment(listOf("A.kt"), listOf("B.kt"))
    val foreign =
      ReviewExpansionRecord(
        "exp-foreign",
        "b".repeat(64),
        "B.kt",
        "called by assigned symbol",
        true,
        0,
      )
    val broker = broker(root, assignment, trustedExpansionLedger = listOf(foreign))

    val error =
      assertFailsWith<IllegalArgumentException> {
        broker.readBatch(
          ReviewEvidenceBatchRequest.of(
            ReviewEvidenceRequest("security", "B.kt", "called by assigned symbol", foreign),
          ),
        )
      }

    assertTrue("does not belong to this assignment" in error.message.orEmpty())
    assertEquals(0, broker.accounting().evidenceBytes)
    assertTrue(broker.accounting().expansions.isEmpty())
  }

  @Test fun `unassigned read without a reason is a forbidden operation, not an expansion`() {
    val root = repo("A.kt" to "assigned", "B.kt" to "dep")
    val broker = broker(root, assignment(listOf("A.kt")))
    val result = broker.readBatch(ReviewEvidenceBatchRequest.of(ReviewEvidenceRequest("security", "B.kt")))
    assertEquals("unassigned_file_access", result.results.single().forbidden?.category)
    assertNull(result.results.single().content)
    assertTrue(result.expansions.isEmpty())
  }

  @Test fun `expansion budget excess terminates the lane`() {
    val root = repo("A.kt" to "assigned", "B.kt" to "dep")
    val assignment = assignment(listOf("A.kt"), listOf("B.kt"))
    val request = expansionRequest(assignment, "B.kt", "reachable from assigned symbol")
    val broker =
      broker(
        root,
        assignment,
        policy(expansions = 0),
        trustedExpansionLedger = listOf(requireNotNull(request.authorizedExpansion)),
      )
    val result =
      broker.readBatch(
        ReviewEvidenceBatchRequest.of(request),
      )
    assertEquals("assignment_expansions", result.terminalOutcome?.budgetKind?.wireValue)
  }

  @Test fun `named dependency is still measured as an authorized expansion`() {
    val root = repo("A.kt" to "assigned", "B.kt" to "dep")
    val assignment = assignment(listOf("A.kt"), listOf("B.kt"))
    val request = expansionRequest(assignment, "B.kt", "called by assigned symbol")
    val broker =
      broker(
        root,
        assignment,
        namedDependencies = setOf("B.kt"),
        trustedExpansionLedger = listOf(requireNotNull(request.authorizedExpansion)),
      )
    val result =
      broker.readBatch(
        ReviewEvidenceBatchRequest.of(request),
      )
    assertEquals("dep", result.results.single().content)
    assertEquals(listOf("B.kt"), result.expansions.map { it.requestedPath })
  }

  @Test fun `forbidden tool calls are rejected and never counted`() {
    val root = repo("A.kt" to "assigned")
    val broker = broker(root, assignment(listOf("A.kt")))
    val rejected = broker.recordToolCall(ReviewToolCall("security", ReviewOperationKind.SHELL_COMMAND, "git status"))
    assertEquals("review_status", rejected.forbidden?.category)
    assertEquals(0, broker.accounting().toolCalls)
  }

  @Test fun `tool-call budget excess terminates the lane`() {
    val root = repo("A.kt" to "assigned")
    val broker = broker(root, assignment(listOf("A.kt")), policy(toolCalls = 1))
    assertTrue(broker.recordToolCall(ReviewToolCall("security", ReviewOperationKind.FILE_READ, "A.kt")).admitted)
    val second = broker.recordToolCall(ReviewToolCall("security", ReviewOperationKind.FILE_READ, "A.kt"))
    assertEquals("specialist_tool_calls", second.budgetExceeded?.budgetKind?.wireValue)
    assertEquals(REVIEW_CONTEXT_BUDGET_EXCEEDED, second.budgetExceeded?.type)
  }

  @Test fun `model-turn budget excess terminates the lane`() {
    val root = repo("A.kt" to "assigned")
    val broker = broker(root, assignment(listOf("A.kt")), policy(modelTurns = 1))
    assertNull(broker.recordModelTurn())
    assertEquals("specialist_model_turns", broker.recordModelTurn()?.budgetKind?.wireValue)
    assertEquals("specialist_model_turns", broker.accounting().terminalOutcome?.budgetKind?.wireValue)
  }

  @Test fun `lane result excess terminates subsequent evidence`() {
    val root = repo("A.kt" to "ok")
    val broker = broker(root, assignment(listOf("A.kt")))
    assertEquals("lane_result_bytes", broker.validateLaneResult("x".repeat(101))?.budgetKind?.wireValue)
    val followUp = broker.readBatch(ReviewEvidenceBatchRequest.of(ReviewEvidenceRequest("security", "A.kt")))
    assertEquals("lane_result_bytes", followUp.terminalOutcome?.budgetKind?.wireValue)
  }

  @Test fun `streamed lane result excess is typed before completion and stays terminal`() {
    val root = repo("A.kt" to "ok")
    val broker = broker(root, assignment(listOf("A.kt")))
    assertNull(broker.observeLaneResultChunk("x".repeat(60)))
    val exceeded = broker.observeLaneResultChunk("y".repeat(41))
    assertEquals(REVIEW_CONTEXT_BUDGET_EXCEEDED, exceeded?.type)
    assertEquals("lane_result_bytes", exceeded?.budgetKind?.wireValue)
    assertEquals(101, broker.accounting().resultBytes)
    assertEquals(exceeded, broker.terminalOutcome())
  }

  private fun batch(path: String) = ReviewEvidenceBatchRequest.of(ReviewEvidenceRequest("security", path))

  private fun expansionRequest(
    assignment: ReviewAssignment,
    path: String,
    reason: String,
  ) = ReviewEvidenceRequest(
    "security",
    path,
    reason,
    ReviewExpansionRecord("exp-1", assignment.digest, path, reason, true, 0),
  )

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
    namedDependencies: Set<String> = emptySet(),
    trustedExpansionLedger: List<ReviewExpansionRecord> = emptyList(),
  ) = FileSystemReviewEvidenceBroker(
    ReviewEvidenceBrokerBinding(root, assignment, "security", budget, namedDependencies, trustedExpansionLedger),
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
