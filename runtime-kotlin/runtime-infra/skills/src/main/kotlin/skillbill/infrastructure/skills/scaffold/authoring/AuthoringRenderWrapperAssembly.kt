package skillbill.infrastructure.skills.scaffold.authoring

import skillbill.infrastructure.skills.scaffold.platformpack.loader.skillclass.resolveSkillClassForSkill
import skillbill.infrastructure.skills.scaffold.rendering.defaultAreaFocus
import skillbill.infrastructure.skills.scaffold.rendering.renderCeremonySection
import skillbill.infrastructure.skills.scaffold.rendering.renderClassSections
import skillbill.infrastructure.skills.scaffold.rendering.renderDescriptorSection
import skillbill.infrastructure.skills.scaffold.runtime.service.contract.TemplateContext
import skillbill.install.model.ListedSkillNames
import skillbill.scaffold.model.SkillClassManifest

internal fun assembleRenderedWrapper(
  target: AuthoringTarget,
  frontmatter: String,
  executionBody: String,
  context: TemplateContext,
  skillClass: SkillClassManifest?,
): String =
  buildString {
    append(frontmatter.trimEnd())
    appendLine()
    appendLine()
    append(renderDescriptorSection(context, defaultAreaFocus(target.area)).trimEnd())
    appendLine()
    appendLine()
    if (skillClass != null && skillClass.sections.isNotEmpty()) {
      append(renderClassSections(skillClass.sections).trimEnd())
      appendLine()
      appendLine()
    }
    val reviewComposition = renderReviewCompositionSection(target)
    if (reviewComposition.isNotBlank()) {
      append(reviewComposition.trimEnd())
      appendLine()
      appendLine()
    }
    val governedAddons = renderGovernedAddonsSection(target)
    if (governedAddons.isNotBlank()) {
      append(governedAddons.trimEnd())
      appendLine()
      appendLine()
    }
    appendLine("## Execution")
    if (executionBody.isNotBlank()) {
      appendLine()
      append(executionBody.trimEnd())
    }
    val subagentRuntimeNotes = renderGeneratedSubagentSpawnRuntimeNotes(target)
    if (subagentRuntimeNotes.isNotBlank()) {
      appendLine()
      appendLine()
      append(subagentRuntimeNotes.trimEnd())
    }
    appendLine()
    appendLine()
    append(renderCeremonySection(skillClass).trimEnd())
    appendLine()
  }

internal fun renderedWrapperTemplateContext(target: AuthoringTarget): TemplateContext =
  TemplateContext(
    skillName = ListedSkillNames.publicName(target.skillName),
    family = target.family,
    platform = target.platform,
    area = target.area,
    displayName = target.displayName,
  )

internal fun resolvedWrapperSkillClass(target: AuthoringTarget) =
  resolveSkillClassForSkill(target.skillName, target.contentFile)
