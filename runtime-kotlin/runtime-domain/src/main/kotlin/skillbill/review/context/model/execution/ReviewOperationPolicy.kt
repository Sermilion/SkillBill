package skillbill.review.context.model.execution

import skillbill.review.context.model.commit.ReviewAssignment

enum class ReviewOperationKind(val wireValue: String) {
  FILE_READ("file_read"),
  SHELL_COMMAND("shell_command"),
  SEARCH("search"),
  MCP_TOOL("mcp_tool"),
  RUBRIC_READ("rubric_read"),
  CONTRACT_READ("contract_read"),
  RULES_READ("rules_read"),
  ;

  companion object {
    fun fromWire(value: String): ReviewOperationKind? = entries.firstOrNull { it.wireValue == value }
  }
}

data class ReviewRequestedOperation(
  val kind: ReviewOperationKind,
  val target: String,
  val reachabilityReason: String? = null,
  val searchScopes: List<String> = emptyList(),
) {
  init {
    require(target.isNotBlank()) { "Requested review operation target must not be blank." }
  }

  init {
    require(kind != ReviewOperationKind.SEARCH || searchScopes.isNotEmpty()) {
      "A review search requires at least one explicit scope; opaque search commands are forbidden."
    }
  }
}

data class ForbiddenReviewOperation(val category: String, val target: String, val reason: String) {
  init {
    require(category.isNotBlank() && target.isNotBlank() && reason.isNotBlank()) {
      "Forbidden review operation must carry a category, target, and reason."
    }
  }
}

class ReviewOperationPolicy(
  private val assignment: ReviewAssignment,
  private val laneRubricId: String,
  private val namedDependencies: Set<String> = emptySet(),
) {
  private val assignedPaths: Set<String> = assignment.assignedPaths.toSet()

  private val reachablePaths: Set<String> =
    assignedPaths +
      assignment.dependencyAllowlist.normalized +
      namedDependencies

  fun classify(operation: ReviewRequestedOperation): ForbiddenReviewOperation? =
    when (operation.kind) {
      ReviewOperationKind.SHELL_COMMAND -> classifyShell(operation.target)
      ReviewOperationKind.SEARCH -> classifySearch(operation)
      ReviewOperationKind.MCP_TOOL -> classifyMcpTool(operation.target)
      ReviewOperationKind.RUBRIC_READ -> classifyRubric(operation.target)
      ReviewOperationKind.CONTRACT_READ ->
        forbidden("contract_rediscovery", operation.target, "Contracts are supplied directly in the lane projection.")
      ReviewOperationKind.RULES_READ ->
        forbidden("rules_rediscovery", operation.target, "Review rules are supplied directly in the lane projection.")
      ReviewOperationKind.FILE_READ -> classifyFileRead(operation)
    }

  fun isReachable(path: String): Boolean = path in reachablePaths

  fun isAssigned(path: String): Boolean = path in assignedPaths

  private fun classifyShell(command: String): ForbiddenReviewOperation? {
    val normalized = command.trim().lowercase()
    SHELL_REDISCOVERY.forEach { (prefixes, category) ->
      if (prefixes.any { normalized == it || normalized.startsWith("$it ") }) {
        return forbidden(category, command, "The parent packet already carries this fact; rediscovery is forbidden.")
      }
    }
    if (SEARCH_COMMANDS.any { normalized == it || normalized.startsWith("$it ") }) {
      return forbidden(
        "broad_repository_search",
        command,
        "Opaque shell searches are forbidden; use a structured search with explicit scopes.",
      )
    }
    return forbidden(
      "unscoped_shell_command",
      command,
      "A bounded specialist runs no shell command outside its measured evidence surface.",
    )
  }

  private fun classifySearch(operation: ReviewRequestedOperation): ForbiddenReviewOperation? {
    val normalizedScopes = operation.searchScopes
    normalizedScopes.forEach { scope ->
      classifyAbsoluteProhibitions(scope, isAssigned(scope))?.let { return it }
      if (!isReachable(scope)) {
        return forbidden(
          "broad_repository_search",
          scope,
          "Every explicit search scope must be assigned or a named dependency.",
        )
      }
    }
    return null
  }

  private fun classifyMcpTool(tool: String): ForbiddenReviewOperation? {
    val normalized = tool.trim().lowercase()
    MCP_REDISCOVERY.forEach { (fragments, category) ->
      if (fragments.any { it in normalized }) {
        return forbidden(category, tool, "The parent packet already resolved this; rediscovery is forbidden.")
      }
    }
    return forbidden(
      "unselected_mcp_tool_call",
      tool,
      "A bounded specialist calls no tool the parent did not project into its assignment.",
    )
  }

  private fun classifyRubric(rubricId: String): ForbiddenReviewOperation =
    forbidden(
      "rubric_rediscovery",
      rubricId,
      "Lane '${assignment.lane}' receives rubric '$laneRubricId' directly in its launch projection.",
    )

  private fun classifyFileRead(operation: ReviewRequestedOperation): ForbiddenReviewOperation? {
    val path = operation.target
    val assigned = path in assignedPaths
    classifyAbsoluteProhibitions(path, assigned)?.let { return it }
    return when {
      assigned -> null
      isReachable(path) && !operation.reachabilityReason.isNullOrBlank() -> null
      else ->
        forbidden(
          "unassigned_file_access",
          path,
          "A reachability reason documents an assignment-authorized dependency; it cannot authorize a new path.",
        )
    }
  }

  private fun classifyAbsoluteProhibitions(
    path: String,
    assigned: Boolean,
  ): ForbiddenReviewOperation? {
    if (!assigned && DIFF_ARTIFACT_FRAGMENTS.any { it in path.lowercase() }) {
      return forbidden(
        "diff_artifact_rediscovery",
        path,
        "Complete-diff files and references are forbidden. This is not a substitute for reading: " +
          "re-request the owned repository-relative path this hunk belongs to, never a store_path " +
          "or payload_file from an evidence_locator.",
      )
    }
    if (SCRATCH_PATH_FRAGMENTS.any { it in path.lowercase() }) {
      return forbidden(
        "scratch_path_rediscovery",
        path,
        "Scratch review artifacts are outside the governed evidence surface.",
      )
    }
    if (CONTRACT_PATH_FRAGMENTS.any { it in path.lowercase() }) {
      return forbidden(
        "contract_rediscovery",
        path,
        "Rubric and contract bodies are supplied directly in the lane projection.",
      )
    }
    val guidanceViolation =
      GUIDANCE_FILE_NAMES.firstOrNull { path == it || path.endsWith("/$it") }?.let {
        forbidden(
          "project_guidance_traversal",
          path,
          "Project guidance reaches a specialist only as packet-attested matched rules.",
        )
      }
    val routingViolation =
      if (assigned) {
        null
      } else {
        ROUTING_PATH_FRAGMENTS.firstNotNullOfOrNull { (fragments, category) ->
          category.takeIf { fragments.any { it in path } }?.let {
            forbidden(it, path, "The parent packet already resolved this routing decision.")
          }
        }
      }
    return when {
      guidanceViolation != null -> guidanceViolation
      routingViolation != null -> routingViolation
      else -> null
    }
  }

  private fun forbidden(
    category: String,
    target: String,
    reason: String,
  ) = ForbiddenReviewOperation(category, target, reason)

  private companion object {
    val SHELL_REDISCOVERY: List<Pair<List<String>, String>> =
      listOf(
        listOf("git status", "git stash list") to "review_status",
        listOf("gh pr diff", "gh pr view", "gh pr list") to "review_scope",
        listOf("git merge-base", "git rev-parse", "git symbolic-ref", "git branch") to "base_head_revision_discovery",
        listOf("git diff", "git show", "git log") to "diff_recomputation",
        listOf("./gradlew", "gradle", "npm test", "npm run", "cargo test", "cargo build", "pytest", "go test")
          to "build_test_fact_discovery",
        listOf(
          "skill-bill validate",
          "skill-bill show",
          "skill-bill explain",
          "sona validate",
          "sona show",
          "sona explain",
        ) to "platform_pack_and_addon_resolution",
      )

    val MCP_REDISCOVERY: List<Pair<List<String>, String>> =
      listOf(
        listOf("resolve_learnings", "learnings") to "learnings_resolution",
        listOf("telemetry", "review_stats") to "telemetry_ownership_determination",
        listOf("stack_routing", "detect_stack") to "dominant_stack_routing",
      )

    val ROUTING_PATH_FRAGMENTS: List<Pair<List<String>, String>> =
      listOf(
        listOf("platform-packs/", "platform.yaml") to "platform_pack_and_addon_resolution",
        listOf("stack-routing", "orchestration/routing") to "dominant_stack_routing",
        listOf("telemetry-contract") to "telemetry_ownership_determination",
      )

    val GUIDANCE_FILE_NAMES: List<String> =
      listOf("AGENTS.md", "CLAUDE.md", "AGENT.md", "GEMINI.md", ".cursorrules", "CONVENTIONS.md")

    val SEARCH_COMMANDS: List<String> = listOf("grep", "rg", "find", "fd", "ls", "glob", "ack")
    val DIFF_ARTIFACT_FRAGMENTS: List<String> =
      listOf(".diff", ".patch", "complete-diff", "review-diff", "diff-artifact")
    val SCRATCH_PATH_FRAGMENTS: List<String> = listOf(".scratch/", ".tmp/", "/tmp/", "scratch-diff")
    val CONTRACT_PATH_FRAGMENTS: List<String> =
      listOf("specialist-contract.md", "packet-consumer-contract", "review-rubric", "/rubrics/")
  }
}
