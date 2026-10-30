package skillbill.application.config

import skillbill.application.config.model.AuthoredReviewContextBudget
import skillbill.application.config.model.ConfigDocumentRead
import skillbill.application.config.model.ProjectConfigEdit
import skillbill.application.config.model.ProjectConfigValues
import skillbill.config.model.RepoLocalConfigFileKeys
import skillbill.config.model.RepoLocalConfigKey
import skillbill.config.model.ReviewContextBudgetParse
import skillbill.config.model.SpecType
import skillbill.config.model.ValidationGateRepoConfigParse
import skillbill.config.model.parseReviewContextBudget
import skillbill.config.model.parseSpecType
import skillbill.config.model.parseValidationGateRepoConfig
import skillbill.ports.config.model.ConfigDocumentTree
import skillbill.ports.config.model.ConfigFingerprint
import skillbill.review.context.model.accounting.ReviewContextBudgetPolicy
import java.nio.file.Path
import java.util.Collections
import java.util.LinkedHashMap
import java.util.LinkedHashSet

internal fun parseProjectConfigValues(
  path: Path,
  fingerprint: ConfigFingerprint,
  tree: ConfigDocumentTree,
  hasComments: Boolean,
): ConfigDocumentRead<ProjectConfigValues> {
  return removedAgentError(path, tree) ?: parseProjectValues(path, fingerprint, tree, hasComments)
}

private fun parseProjectValues(
  path: Path,
  fingerprint: ConfigFingerprint,
  tree: ConfigDocumentTree,
  hasComments: Boolean,
): ConfigDocumentRead<ProjectConfigValues> {
  val specType =
    if (!tree.contains(RepoLocalConfigKey.SPEC_TYPE.key)) {
      SpecType.LOCAL
    } else {
      parseSpecType(tree.value(RepoLocalConfigKey.SPEC_TYPE.key)?.toString())
        ?: return ConfigDocumentRead.Unreadable(
          path,
          RepoLocalConfigKey.SPEC_TYPE.key,
          "is not a recognized value for this key.",
        )
    }
  val reviewBudget =
    if (!tree.contains(RepoLocalConfigFileKeys.REVIEW_CONTEXT_BUDGET)) {
      ReviewContextBudgetPolicy.DEFAULT
    } else {
      when (val parsed = parseReviewContextBudget(tree.value(RepoLocalConfigFileKeys.REVIEW_CONTEXT_BUDGET))) {
        is ReviewContextBudgetParse.Valid -> parsed.policy
        is ReviewContextBudgetParse.Invalid -> return ConfigDocumentRead.Unreadable(path, parsed.keyPath, parsed.reason)
      }
    }
  val authoredReviewBudget =
    if (tree.contains(RepoLocalConfigFileKeys.REVIEW_CONTEXT_BUDGET)) {
      val section = tree.value(RepoLocalConfigFileKeys.REVIEW_CONTEXT_BUDGET) as Map<*, *>
      AuthoredReviewContextBudget(true, ConfigDocumentTree(immutableSection(section)))
    } else {
      AuthoredReviewContextBudget.EMPTY
    }
  val validationGate =
    if (!tree.contains(RepoLocalConfigFileKeys.VALIDATION_GATE)) {
      null
    } else {
      when (val parsed = parseValidationGateRepoConfig(tree.value(RepoLocalConfigFileKeys.VALIDATION_GATE))) {
        is ValidationGateRepoConfigParse.Valid -> parsed.config.gradleWrapper
        is ValidationGateRepoConfigParse.Invalid ->
          return ConfigDocumentRead.Unreadable(path, parsed.keyPath, parsed.reason)
      }
    }
  return ConfigDocumentRead.Present(
    path,
    fingerprint,
    ProjectConfigValues(
      specType,
      validationGate,
      reviewBudget,
      authoredReviewBudget,
    ),
    hasComments,
  )
}

private fun removedAgentError(
  path: Path,
  tree: ConfigDocumentTree,
): ConfigDocumentRead.Unreadable? {
  if (tree.contains(RepoLocalConfigFileKeys.REMOVED_PARALLEL_AGENT)) {
    val value = tree.value(RepoLocalConfigFileKeys.REMOVED_PARALLEL_AGENT)
    val normalized = value?.toString()?.trim()?.lowercase()
    if (!normalized.isNullOrBlank() && normalized != "none") {
      return ConfigDocumentRead.Unreadable(
        path,
        RepoLocalConfigFileKeys.REMOVED_PARALLEL_AGENT,
        "names a removed capability; delete this key or set it to none.",
      )
    }
  }
  return null
}

internal fun applyProjectConfigEdits(
  original: ConfigDocumentTree,
  edits: List<ProjectConfigEdit>,
): ProjectConfigTreeResult {
  var tree = original
  for (edit in edits) {
    when (edit) {
      is ProjectConfigEdit.SetSpecType -> tree = tree.with(RepoLocalConfigKey.SPEC_TYPE.key, edit.specType.id)
      is ProjectConfigEdit.SetGradleWrapper ->
        when (val result = applyGradleWrapper(tree, edit)) {
          is ProjectConfigTreeResult.Invalid -> return result
          is ProjectConfigTreeResult.Valid -> tree = result.tree
        }
      is ProjectConfigEdit.SetReviewContextBudget -> {
        if (edit.section.isEmpty()) {
          tree = tree.without(RepoLocalConfigFileKeys.REVIEW_CONTEXT_BUDGET)
        } else {
          when (val parsed = parseReviewContextBudget(edit.section)) {
            is ReviewContextBudgetParse.Valid ->
              tree = tree.with(RepoLocalConfigFileKeys.REVIEW_CONTEXT_BUDGET, edit.section)
            is ReviewContextBudgetParse.Invalid -> return ProjectConfigTreeResult.Invalid(parsed.keyPath, parsed.reason)
          }
        }
      }
    }
  }
  return ProjectConfigTreeResult.Valid(tree)
}

private fun applyGradleWrapper(
  tree: ConfigDocumentTree,
  edit: ProjectConfigEdit.SetGradleWrapper,
): ProjectConfigTreeResult {
  val value = edit.value?.takeIf(String::isNotBlank)
  val raw = tree.value(RepoLocalConfigFileKeys.VALIDATION_GATE)
  val gate = LinkedHashMap<String, Any?>()
  if (raw is Map<*, *>) raw.forEach { (key, item) -> if (key is String) gate[key] = item }
  if (value == null) {
    gate.remove(RepoLocalConfigFileKeys.GRADLE_WRAPPER)
  } else {
    if (hasDotPathSegment(value)) {
      return ProjectConfigTreeResult.Invalid(
        "${RepoLocalConfigFileKeys.VALIDATION_GATE}.${RepoLocalConfigFileKeys.GRADLE_WRAPPER}",
        "must not contain '.' or '..' path segments.",
      )
    }
    gate[RepoLocalConfigFileKeys.GRADLE_WRAPPER] = value
  }
  if (gate.isEmpty() && !tree.contains(RepoLocalConfigFileKeys.VALIDATION_GATE)) {
    return ProjectConfigTreeResult.Valid(tree)
  }
  when (val parsed = parseValidationGateRepoConfig(gate)) {
    is ValidationGateRepoConfigParse.Valid ->
      return ProjectConfigTreeResult.Valid(tree.with(RepoLocalConfigFileKeys.VALIDATION_GATE, gate))
    is ValidationGateRepoConfigParse.Invalid ->
      return ProjectConfigTreeResult.Invalid(parsed.keyPath, parsed.reason)
  }
}

private fun immutableSection(section: Map<*, *>): Map<String, Any?> =
  Collections.unmodifiableMap(
    LinkedHashMap<String, Any?>().apply {
      section.forEach { (key, value) ->
        if (key is String) put(key, immutableConfigValue(value))
      }
    },
  )

private fun immutableConfigValue(value: Any?): Any? =
  when (value) {
    is Map<*, *> ->
      Collections.unmodifiableMap(
        LinkedHashMap<Any?, Any?>().apply {
          value.forEach { (key, nested) -> put(key, immutableConfigValue(nested)) }
        },
      )
    is List<*> -> Collections.unmodifiableList(value.map(::immutableConfigValue))
    is Set<*> -> Collections.unmodifiableSet(LinkedHashSet(value.map(::immutableConfigValue)))
    else -> value
  }

private fun hasDotPathSegment(value: String): Boolean =
  value.trim().replace('\\', '/').split('/').any { segment -> segment == "." || segment == ".." }

internal sealed interface ProjectConfigTreeResult {
  data class Valid(val tree: ConfigDocumentTree) : ProjectConfigTreeResult

  data class Invalid(val keyPath: String, val reason: String) : ProjectConfigTreeResult
}
