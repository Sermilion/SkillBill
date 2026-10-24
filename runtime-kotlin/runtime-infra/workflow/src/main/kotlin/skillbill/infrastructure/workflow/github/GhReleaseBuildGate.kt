package skillbill.infrastructure.workflow.github

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import skillbill.ports.operation.ReleaseBuildGate
import skillbill.ports.operation.model.ReleaseBuildReadiness
import java.nio.file.Path

class GhReleaseBuildGate internal constructor(private val gh: GhCommandRunner) : ReleaseBuildGate {
  constructor() : this(ProcessGhCommandRunner())

  override fun inspect(
    repoRoot: Path,
    commitSha: String,
  ): ReleaseBuildReadiness {
    val repository = gh.run(repoRoot, listOf("repo", "view", "--json", GhReleaseBuildKeys.DEFAULT_BRANCH))
    if (repository.exitCode != 0) return unavailable(repository)
    val branch =
      parse(repository.stdout)?.path(GhReleaseBuildKeys.DEFAULT_BRANCH)?.text(GhReleaseBuildKeys.NAME)
        ?: return ReleaseBuildReadiness.Unavailable("GitHub returned no default branch for the release repository.")
    val result =
      gh.run(
        repoRoot,
        listOf(
          "run", "list", "--branch", branch, "--commit", commitSha, "--event", "push",
          "--limit", RUN_LIMIT.toString(), "--json", RUN_FIELDS,
        ),
      )
    if (result.exitCode != 0) return unavailable(result)
    return evaluateRuns(result.stdout, branch, commitSha)
  }

  private fun evaluateRuns(
    raw: String,
    branch: String,
    commitSha: String,
  ): ReleaseBuildReadiness {
    val runs = parse(raw)
    if (runs == null || !runs.isArray || runs.size() >= RUN_LIMIT) {
      return ReleaseBuildReadiness.Unavailable("GitHub returned incomplete or invalid release build results.")
    }
    if (runs.isEmpty) return ReleaseBuildReadiness.NotReady("No builds were reported for $branch at $commitSha.")
    val parsed = runs.map { parseRun(it, branch, commitSha) }
    if (parsed.any { it == null }) {
      return ReleaseBuildReadiness.Unavailable("GitHub returned a build with missing or mismatched release identity.")
    }
    val latest = parsed.filterNotNull().groupBy { it.workflowId }.values.map { workflow -> workflow.maxBy { it.id } }
    val unsettled = latest.filter { it.status != "completed" || it.conclusion != "success" }
    return if (unsettled.isEmpty()) {
      ReleaseBuildReadiness.Passed(branch)
    } else {
      val evidence =
        unsettled.joinToString("\n") {
          "${it.name}: ${it.status}/${it.conclusion.ifBlank { "pending" }} ${it.url}"
        }
      ReleaseBuildReadiness.NotReady("Release requires successful builds for $branch at $commitSha.\n$evidence")
    }
  }

  private fun parseRun(
    node: JsonNode,
    branch: String,
    commitSha: String,
  ): BuildRun? {
    if (node.text(GhReleaseBuildKeys.BRANCH) != branch || node.text(GhReleaseBuildKeys.SHA) != commitSha) return null
    val id = node.positiveLong(GhReleaseBuildKeys.ID)
    val workflowId = node.positiveLong(GhReleaseBuildKeys.WORKFLOW_ID)
    val name = node.text(GhReleaseBuildKeys.WORKFLOW_NAME)
    val status = node.text(GhReleaseBuildKeys.STATUS)
    val conclusion = node.path(GhReleaseBuildKeys.CONCLUSION).takeIf(JsonNode::isTextual)?.asText()
    val url = node.text(GhReleaseBuildKeys.URL)
    if (id == null || workflowId == null || name == null) return null
    if (status == null || conclusion == null || url == null) return null
    return BuildRun(
      id,
      workflowId,
      name,
      status,
      conclusion,
      url,
    )
  }

  private fun parse(value: String): JsonNode? =
    try {
      ObjectMapper().readTree(value)
    } catch (_: JsonProcessingException) {
      null
    }

  private fun JsonNode.text(key: String): String? =
    path(key).takeIf(JsonNode::isTextual)?.asText()?.takeIf(String::isNotBlank)

  private fun JsonNode.positiveLong(key: String): Long? =
    path(key).takeIf { it.isIntegralNumber && it.canConvertToLong() && it.asLong() > 0 }?.asLong()

  private fun unavailable(result: GhCommandResult): ReleaseBuildReadiness.Unavailable =
    ReleaseBuildReadiness.Unavailable("Release build results are unavailable: ${result.describeFailure()}")

  private data class BuildRun(
    val id: Long,
    val workflowId: Long,
    val name: String,
    val status: String,
    val conclusion: String,
    val url: String,
  )
}

private const val RUN_LIMIT = 100
private val RUN_FIELDS =
  listOf(
    GhReleaseBuildKeys.ID,
    GhReleaseBuildKeys.WORKFLOW_ID,
    GhReleaseBuildKeys.WORKFLOW_NAME,
    GhReleaseBuildKeys.STATUS,
    GhReleaseBuildKeys.CONCLUSION,
    GhReleaseBuildKeys.URL,
    GhReleaseBuildKeys.BRANCH,
    GhReleaseBuildKeys.SHA,
  ).joinToString(",")

private object GhReleaseBuildKeys {
  const val DEFAULT_BRANCH = "defaultBranchRef"
  const val NAME = "name"
  const val ID = "databaseId"
  const val WORKFLOW_ID = "workflowDatabaseId"
  const val WORKFLOW_NAME = "workflowName"
  const val STATUS = "status"
  const val CONCLUSION = "conclusion"
  const val URL = "url"
  const val BRANCH = "headBranch"
  const val SHA = "headSha"
}
