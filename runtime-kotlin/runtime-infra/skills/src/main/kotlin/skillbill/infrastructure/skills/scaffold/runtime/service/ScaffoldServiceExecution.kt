
package skillbill.infrastructure.skills.scaffold.runtime.service

import skillbill.error.shellcontent.skillAlreadyExistsError
import skillbill.infrastructure.skills.scaffold.rendering.renderAddonBody
import skillbill.scaffold.policy.SKILL_KIND_ADD_ON
import skillbill.scaffold.policy.SKILL_KIND_AGENT_ADDON
import skillbill.scaffold.policy.SKILL_KIND_CODE_REVIEW_AREA
import java.nio.file.Files
import java.nio.file.Path

internal fun createPlatformPack(
  txn: ScaffoldTransaction,
  plan: ScaffoldPlan,
  repoRoot: Path,
): ScaffoldExecutionResult {
  val manifestPath = plan.manifestPath ?: error("Platform pack plan missing manifest path.")
  val baselineSkillPath = plan.baselineSkillPath ?: error("Platform pack plan missing baseline skill path.")
  stageFile(
    txn,
    manifestPath,
    renderPlatformPackManifestContent(
      plan,
      repoRoot,
      baselineSkillPath,
    ),
  )
  val symlinks = stagePlatformPackSkills(txn, plan, baselineSkillPath)
  if (plan.shouldEmitSubagents()) {
    stageSubagentStubs(
      txn,
      orchestratorSkillPath = baselineSkillPath,
      specialists = plan.subagentSpecialists,
      descriptions = plan.subagentDescriptions,
      bodyNames = plan.bodyBasedSubagents,
    )
  }
  return ScaffoldExecutionResult(
    createdFiles = txn.createdPaths.toList(),
    manifestEdits = listOf(manifestPath),
    symlinks = symlinks,
    installTargets = emptyList(),
    notes = emptyList(),
  )
}

internal fun stageSingleScaffold(
  txn: ScaffoldTransaction,
  plan: ScaffoldPlan,
  repoRoot: Path,
): ScaffoldExecutionResult {
  stageScaffoldContent(txn, plan)
  val manifestEdits = applyManifestEdits(txn, plan, repoRoot)
  if (plan.shouldEmitSubagents()) {
    stageSubagentStubs(
      txn,
      orchestratorSkillPath = plan.skillPath,
      specialists = plan.subagentSpecialists,
      descriptions = plan.subagentDescriptions,
      bodyNames = plan.bodyBasedSubagents,
    )
  }
  return ScaffoldExecutionResult(
    createdFiles = txn.createdPaths.toList(),
    manifestEdits = manifestEdits,
    symlinks = emptyList(),
    installTargets = emptyList(),
    notes = emptyList(),
  )
}

private fun stageScaffoldContent(
  txn: ScaffoldTransaction,
  plan: ScaffoldPlan,
) {
  when (plan.kind) {
    SKILL_KIND_AGENT_ADDON -> {
      stageFile(txn, plan.skillFile, renderAgentAddonManifest(plan))
      stageFile(txn, requireNotNull(plan.contentFile), plan.contentBody ?: "# ${plan.skillName}\n")
    }
    SKILL_KIND_ADD_ON -> {
      stageFile(txn, plan.skillFile, renderAddonBody(plan.skillName, plan.description, plan.addonBody))
    }
    else -> {
      plan.contentFile?.let { content ->
        val contentText =
          if (plan.kind == SKILL_KIND_CODE_REVIEW_AREA || plan.isShelled) {
            renderDeclaredPackContentSheet(plan)
          } else {
            renderContentSheet(plan)
          }
        stageFile(txn, content, contentText)
      }
    }
  }
}

internal fun stageFile(
  txn: ScaffoldTransaction,
  path: Path,
  content: String,
) {
  if (Files.exists(path)) {
    throw skillAlreadyExistsError(
      "Skill target '$path' already exists. Remove it or pick a new name before retrying.",
    )
  }
  val parents = mutableListOf<Path>()
  var cursor = path.parent
  while (cursor != null && !Files.exists(cursor)) {
    parents.add(cursor)
    cursor = cursor.parent
  }
  parents.asReversed().forEach { dir ->
    Files.createDirectories(dir)
    txn.createdDirs.add(dir)
  }
  Files.writeString(path, content)
  txn.createdPaths.add(path)
}
