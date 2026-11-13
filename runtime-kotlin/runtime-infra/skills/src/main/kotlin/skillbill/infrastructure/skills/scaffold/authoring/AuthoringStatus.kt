package skillbill.infrastructure.skills.scaffold.authoring

import skillbill.install.model.ListedSkillNames
import skillbill.ports.scaffold.model.ScaffoldBaselineLayer
import skillbill.ports.scaffold.model.ScaffoldCompletionStatus
import skillbill.ports.scaffold.model.ScaffoldReviewComposition
import skillbill.ports.scaffold.model.ScaffoldSkillStatus
import skillbill.review.model.CodeReviewBaselineLayer
import java.nio.file.Files
import java.nio.file.Path

private const val CONTENT_PREVIEW_LIMIT = 400

internal fun skillStatus(
  repoRoot: Path,
  target: AuthoringTarget,
  contentMode: String,
  issues: List<String> = emptyList(),
): ScaffoldSkillStatus {
  val contentText = Files.readString(target.contentFile)
  val completionStatus = contentCompletionStatus(contentText)
  return ScaffoldSkillStatus(
    skillName = ListedSkillNames.publicName(target.skillName),
    packageName = target.packageName,
    platform = target.platform,
    family = target.family,
    area = target.area,
    contentFile = target.contentFile.toString(),
    renderCommand = "sona render ${ListedSkillNames.publicName(target.skillName)} --repo-root $repoRoot",
    completionStatus = completionStatus,
    sectionCount = parseContentSections(contentText).second.size,
    sections = sectionStatuses(contentText),
    recommendedCommands = recommendedCommands(repoRoot, target, completionStatus, issues),
    reviewComposition =
      target.codeReviewComposition
        ?.baselineLayers
        ?.takeIf { it.isNotEmpty() }
        ?.let { baselineLayers ->
          ScaffoldReviewComposition(
            source = "platform.yaml",
            summary = baselineLayerSummary(baselineLayers),
            baselineLayers = baselineLayers.map(::baselineLayer),
          )
        },
    contentPreview = if (contentMode == "preview") previewText(contentText, CONTENT_PREVIEW_LIMIT) else null,
    content = if (contentMode == "full") contentText else null,
    issues = issues.takeIf { it.isNotEmpty() },
  )
}

private fun baselineLayerSummary(baselineLayers: List<CodeReviewBaselineLayer>): String {
  val requiredCount = baselineLayers.count { it.required }
  val optionalCount = baselineLayers.size - requiredCount
  val layerText =
    buildList {
      if (requiredCount > 0) add("$requiredCount required")
      if (optionalCount > 0) add("$optionalCount optional")
    }.joinToString(" and ")
  return "Run $layerText baseline layer(s) before pack-local specialists."
}

private fun baselineLayer(layer: CodeReviewBaselineLayer): ScaffoldBaselineLayer =
  ScaffoldBaselineLayer(
    platform = layer.platform,
    skill = layer.skill,
    scope = layer.scope.wireValue,
    required = layer.required,
    mode = layer.mode.wireValue,
  )

internal fun recommendedCommands(
  repoRoot: Path,
  target: AuthoringTarget,
  completionStatus: ScaffoldCompletionStatus,
  issues: List<String>,
): List<String> {
  val publicName = ListedSkillNames.publicName(target.skillName)
  val commands = mutableListOf("sona show $publicName --repo-root $repoRoot")
  if (completionStatus != ScaffoldCompletionStatus.COMPLETE) {
    commands += "sona fill $publicName --repo-root $repoRoot --body-file <file>"
  }
  if (issues.isNotEmpty()) {
    commands += "sona validate --repo-root $repoRoot --skill-name $publicName"
  }
  return commands.distinct()
}
