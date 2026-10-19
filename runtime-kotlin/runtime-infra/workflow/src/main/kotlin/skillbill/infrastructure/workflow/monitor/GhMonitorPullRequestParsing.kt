package skillbill.infrastructure.workflow.monitor

import com.fasterxml.jackson.databind.JsonNode
import skillbill.infrastructure.workflow.github.pullRequestEditableFieldsDigest
import skillbill.ports.monitor.model.MonitorBranchTip
import skillbill.ports.monitor.model.MonitorPullRequest
import skillbill.ports.monitor.model.MonitorPullRequestHead
import skillbill.ports.monitor.model.MonitorPullRequestIdentity
import skillbill.ports.monitor.model.MonitorPullRequestState
import skillbill.ports.monitor.model.MonitorPullRequestSummary
import skillbill.ports.monitor.model.MonitorRepositoryIdentity
import skillbill.ports.monitor.model.MonitorWriteEvidence

private const val REPOSITORY_SLUG_PARTS = 2

internal fun parseMonitorPullRequest(
  node: JsonNode,
  repository: MonitorRepositoryIdentity,
): MonitorPullRequest? {
  val identity = identityOf(node, repository) ?: return null
  val headNode = node.path("head").takeIf(JsonNode::isObject) ?: return null
  val head =
    if (headNode.path("repo").isObject) {
      headOf(node, headNode, repository.host) ?: return null
    } else {
      null
    }
  val state = stateOf(node)
  val base = tipOf(node.path("base"), repository.host)
  val conflicting = node.path("mergeable_state").asText() == "dirty" || node.path("mergeable").asText() == "false"
  return if (state == null || base == null) {
    null
  } else {
    MonitorPullRequest(identity, state, head, base, conflicting, pullRequestEditableFieldsDigest(node))
  }
}

internal fun parseMonitorPullRequestSummary(
  node: JsonNode,
  repository: MonitorRepositoryIdentity,
): MonitorPullRequestSummary? {
  val headNode = node.path("head").takeIf(JsonNode::isObject) ?: return null
  val headRepositoryNode = headNode.path("repo")
  val headRepository =
    if (headRepositoryNode.isObject) {
      repositoryOf(headRepositoryNode.path("full_name"), repository.host) ?: return null
    } else {
      null
    }
  val identity = identityOf(node, repository)
  val state = stateOf(node)
  val headRef = text(headNode, "ref")
  return if (identity == null || state == null || headRef == null) {
    null
  } else {
    MonitorPullRequestSummary(
      identity = identity,
      state = state,
      title = node.path("title").takeIf(JsonNode::isTextual)?.asText().orEmpty(),
      body = node.path("body").takeIf(JsonNode::isTextual)?.asText().orEmpty(),
      headRef = headRef,
      headRepository = headRepository,
    )
  }
}

private fun identityOf(
  node: JsonNode,
  repository: MonitorRepositoryIdentity,
): MonitorPullRequestIdentity? {
  val number = node.path("number").takeIf(JsonNode::isIntegralNumber)?.asInt()?.takeIf { it > 0 }
  return number?.let { MonitorPullRequestIdentity(repository, it) }
}

private fun stateOf(node: JsonNode): MonitorPullRequestState? =
  when (node.path("state").takeIf(JsonNode::isTextual)?.asText()) {
    "open" -> MonitorPullRequestState.OPEN
    "closed" ->
      if (node.path("merged").asBoolean(false) || node.path("merged_at").isTextual) {
        MonitorPullRequestState.MERGED
      } else {
        MonitorPullRequestState.CLOSED
      }
    else -> null
  }

private fun tipOf(
  node: JsonNode,
  host: String,
): MonitorBranchTip? {
  val repository = repositoryOf(node.path("repo").path("full_name"), host) ?: return null
  val ref = text(node, "ref") ?: return null
  val sha = text(node, "sha") ?: return null
  return MonitorBranchTip(repository, ref, sha)
}

private fun headOf(
  pullRequest: JsonNode,
  headNode: JsonNode,
  host: String,
): MonitorPullRequestHead? {
  val tip = tipOf(headNode, host) ?: return null
  val cloneUrl = text(headNode.path("repo"), "clone_url") ?: return null
  val evidence =
    MonitorWriteEvidence(
      repositoryPush = flag(headNode.path("repo").path("permissions"), "push"),
      maintainerCanModify = flag(pullRequest, "maintainer_can_modify"),
    )
  return MonitorPullRequestHead(tip, cloneUrl, cloneUrl, evidence)
}

private fun repositoryOf(
  fullName: JsonNode,
  host: String,
): MonitorRepositoryIdentity? {
  val parts = fullName.takeIf(JsonNode::isTextual)?.asText()?.split('/').orEmpty()
  return if (parts.size == REPOSITORY_SLUG_PARTS && parts.none(String::isBlank)) {
    MonitorRepositoryIdentity.canonical(host, parts[0], parts[1])
  } else {
    null
  }
}

private fun text(
  node: JsonNode,
  field: String,
): String? = node.path(field).takeIf(JsonNode::isTextual)?.asText()?.takeIf(String::isNotBlank)

private fun flag(
  node: JsonNode,
  field: String,
): Boolean? = node.path(field).takeIf(JsonNode::isBoolean)?.asBoolean()
