package skillbill.review.context.model.execution

import skillbill.review.context.model.commit.ReviewAssignment
import skillbill.review.context.model.commit.ReviewLaneDecision
import skillbill.review.context.model.hunk.ReviewDependencyAllowlist
import skillbill.review.context.model.hunk.ReviewRevision
import skillbill.review.context.model.packet.ReviewPacketConsumerContract
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ReviewOperationPolicyTest {
  private val policy =
    ReviewOperationPolicy(
      assignment = assignment(),
      laneRubricId = "security",
      namedDependencies = setOf("src/Dependency.kt"),
    )

  @Test fun `scope status and diff rediscovery are forbidden`() {
    assertEquals("review_status", category(ReviewOperationKind.SHELL_COMMAND, "git status --porcelain"))
    assertEquals("review_scope", category(ReviewOperationKind.SHELL_COMMAND, "gh pr diff 42"))
    assertEquals("diff_recomputation", category(ReviewOperationKind.SHELL_COMMAND, "git diff main...HEAD"))
    assertEquals(
      "base_head_revision_discovery",
      category(ReviewOperationKind.SHELL_COMMAND, "git merge-base HEAD main"),
    )
  }

  @Test fun `routing learnings telemetry and build fact rediscovery are forbidden`() {
    assertEquals(
      "platform_pack_and_addon_resolution",
      category(ReviewOperationKind.FILE_READ, "platform-packs/kotlin/platform.yaml"),
    )
    assertEquals("dominant_stack_routing", category(ReviewOperationKind.MCP_TOOL, "detect_stack"))
    assertEquals("learnings_resolution", category(ReviewOperationKind.MCP_TOOL, "resolve_learnings"))
    assertEquals("telemetry_ownership_determination", category(ReviewOperationKind.MCP_TOOL, "review_stats"))
    assertEquals("build_test_fact_discovery", category(ReviewOperationKind.SHELL_COMMAND, "./gradlew check"))
  }

  @Test fun `guidance traversal is forbidden`() {
    assertEquals("project_guidance_traversal", category(ReviewOperationKind.FILE_READ, "AGENTS.md"))
    assertEquals("project_guidance_traversal", category(ReviewOperationKind.FILE_READ, "src/nested/CLAUDE.md"))
  }

  @Test fun `broad searches are forbidden and assigned-scope searches are allowed`() {
    assertEquals("broad_repository_search", searchCategory("TODO", "."))
    assertEquals("broad_repository_search", searchCategory("secret", "src/Other.kt"))
    assertNull(
      policy.classify(
        ReviewRequestedOperation(
          ReviewOperationKind.SEARCH,
          "secret",
          searchScopes = listOf("src/Assigned.kt"),
        ),
      ),
    )
    assertEquals(
      "broad_repository_search",
      searchCategory("secret", "src/Assigned.kt", "src/Other.kt"),
    )
    assertNull(
      policy.classify(
        ReviewRequestedOperation(
          ReviewOperationKind.SEARCH,
          "secret",
          searchScopes = listOf("src/Assigned.kt", "src/Dependency.kt"),
        ),
      ),
    )
  }

  @Test fun `guidance prohibition overrides assignment but routing and diff-artifact prohibitions do not`() {
    val assignedForbiddenPolicy =
      ReviewOperationPolicy(
        assignment =
          assignment().copy(
            assignedPaths = listOf("AGENTS.md", "platform-packs/kotlin/platform.yaml", "fixtures/legacy.patch"),
          ),
        laneRubricId = "security",
      )

    assertEquals(
      "project_guidance_traversal",
      assignedForbiddenPolicy.classify(
        ReviewRequestedOperation(ReviewOperationKind.FILE_READ, "AGENTS.md"),
      )?.category,
    )
    assertNull(
      assignedForbiddenPolicy.classify(
        ReviewRequestedOperation(ReviewOperationKind.FILE_READ, "platform-packs/kotlin/platform.yaml"),
      ),
    )
    assertNull(
      assignedForbiddenPolicy.classify(
        ReviewRequestedOperation(ReviewOperationKind.FILE_READ, "fixtures/legacy.patch"),
      ),
    )
    assertEquals(
      "platform_pack_and_addon_resolution",
      assignedForbiddenPolicy.classify(
        ReviewRequestedOperation(ReviewOperationKind.FILE_READ, "platform-packs/kmp/platform.yaml"),
      )?.category,
    )
  }

  @Test fun `every structured search scope is checked including root paths and absolute prohibitions`() {
    assertEquals(
      "broad_repository_search",
      searchCategory("secret", "src/Assigned.kt", "README.md"),
    )
    val assignedForbiddenPolicy =
      ReviewOperationPolicy(
        assignment = assignment().copy(assignedPaths = listOf("src/Assigned.kt", "AGENTS.md")),
        laneRubricId = "security",
      )
    assertEquals(
      "project_guidance_traversal",
      assignedForbiddenPolicy.classify(
        ReviewRequestedOperation(
          ReviewOperationKind.SEARCH,
          "rule",
          searchScopes = listOf("src/Assigned.kt", "AGENTS.md"),
        ),
      )?.category,
    )
    assertNull(
      ReviewOperationPolicy(
        assignment = assignment().copy(assignedPaths = listOf("platform-packs/kmp/platform.yaml")),
        laneRubricId = "security",
      ).classify(
        ReviewRequestedOperation(
          ReviewOperationKind.SEARCH,
          "declared_code_review_areas",
          searchScopes = listOf("platform-packs/kmp/platform.yaml"),
        ),
      ),
    )
    assertFailsWith<IllegalArgumentException> {
      ReviewRequestedOperation(ReviewOperationKind.SEARCH, "rg secret src/Assigned.kt")
    }
  }

  @Test fun `every rubric read is forbidden because the launch projection is authoritative`() {
    assertEquals("rubric_rediscovery", category(ReviewOperationKind.RUBRIC_READ, "security"))
    assertEquals("rubric_rediscovery", category(ReviewOperationKind.RUBRIC_READ, "performance"))
  }

  @Test fun `unselected mcp tools and unscoped shell are forbidden`() {
    assertEquals("unselected_mcp_tool_call", category(ReviewOperationKind.MCP_TOOL, "notion_search"))
    assertEquals("unscoped_shell_command", category(ReviewOperationKind.SHELL_COMMAND, "curl https://example.test"))
  }

  @Test fun `assigned paths are direct and dependencies require an attested reason`() {
    assertNull(policy.classify(ReviewRequestedOperation(ReviewOperationKind.FILE_READ, "src/Assigned.kt")))
    assertEquals("unassigned_file_access", category(ReviewOperationKind.FILE_READ, "src/Dependency.kt"))
    assertNull(
      policy.classify(
        ReviewRequestedOperation(ReviewOperationKind.FILE_READ, "src/Dependency.kt", "called by assigned symbol"),
      ),
    )
  }

  @Test fun `a reason cannot widen access beyond the assignment allowlist`() {
    assertEquals("unassigned_file_access", category(ReviewOperationKind.FILE_READ, "src/Elsewhere.kt"))
    assertEquals(
      "unassigned_file_access",
      policy.classify(
        ReviewRequestedOperation(ReviewOperationKind.FILE_READ, "src/Elsewhere.kt", "called by assigned symbol"),
      )?.category,
    )
  }

  @Test fun `every classifier category is declared by the packet consumer contract`() {
    val categories =
      listOf(
        category(ReviewOperationKind.SHELL_COMMAND, "git status"),
        searchCategory("TODO", "."),
        category(ReviewOperationKind.RUBRIC_READ, "performance"),
        category(ReviewOperationKind.FILE_READ, "src/Elsewhere.kt"),
        category(ReviewOperationKind.MCP_TOOL, "notion_search"),
        category(ReviewOperationKind.SHELL_COMMAND, "curl https://example.test"),
        category(ReviewOperationKind.CONTRACT_READ, "specialist-contract"),
        category(ReviewOperationKind.RULES_READ, "review-rules"),
        category(ReviewOperationKind.FILE_READ, ".scratch/review.diff"),
      )
    categories.forEach { category ->
      assertEquals(true, category in ReviewPacketConsumerContract.FORBIDDEN_REDISCOVERY, "undeclared: $category")
    }
  }

  private fun category(
    kind: ReviewOperationKind,
    target: String,
  ): String? = policy.classify(ReviewRequestedOperation(kind, target))?.category

  private fun searchCategory(
    pattern: String,
    vararg scopes: String,
  ): String? =
    policy.classify(
      ReviewRequestedOperation(ReviewOperationKind.SEARCH, pattern, searchScopes = scopes.toList()),
    )?.category

  private fun assignment() =
    ReviewAssignment(
      reviewId = "review",
      packetDigest = "a".repeat(64),
      lane = "security",
      baseRevision = "base",
      headRevision = "head",
      assignedPaths = listOf("src/Assigned.kt"),
      assignedHunks = emptyList(),
      reviewRevision = ReviewRevision("rvs-1", 1),
      laneDecision =
        ReviewLaneDecision(
          "security",
          true,
          "routed",
          ownedPaths = listOf("src/Assigned.kt"),
          originLayerChains = listOf(listOf("kotlin")),
          owningPack = "kotlin",
          specialistSkillName = "bill-kotlin-code-review-security",
        ),
      dependencyAllowlist = ReviewDependencyAllowlist(listOf("src/Dependency.kt")),
    )
}
