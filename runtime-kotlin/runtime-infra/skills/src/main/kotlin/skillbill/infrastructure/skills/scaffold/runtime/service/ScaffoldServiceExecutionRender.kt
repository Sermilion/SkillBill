
package skillbill.infrastructure.skills.scaffold.runtime.service

import skillbill.infrastructure.skills.scaffold.rendering.defaultAreaFocus
import skillbill.infrastructure.skills.scaffold.rendering.inferSkillDescription
import skillbill.infrastructure.skills.scaffold.rendering.renderContentBody
import skillbill.infrastructure.skills.scaffold.runtime.service.contract.TemplateContext
import skillbill.install.model.PACK_SIDECAR_PARENT_SKILL

internal fun renderAgentAddonManifest(plan: ScaffoldPlan): String =
  buildString {
    appendLine("contract_version: \"1.0\"")
    appendLine("slug: ${plan.skillName}")
    appendLine("description: ${yamlScalar(plan.description)}")
    appendLine("agent_ids:")
    plan.agentIds.forEach { appendLine("  - $it") }
    appendLine("consumers:")
    plan.agentAddonConsumers.forEach { appendLine("  - $it") }
  }

internal fun yamlScalar(value: String): String = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

internal fun renderContentSheet(plan: ScaffoldPlan): String =
  renderContentBody(
    skillContext(plan),
    description = effectiveDescription(plan),
    contentBody = plan.contentBody,
  )

internal fun renderDeclaredPackContentSheet(plan: ScaffoldPlan): String =
  renderContentBody(
    skillContext(plan),
    description = effectiveDescription(plan),
    contentBody = plan.contentBody,
    internalFor = PACK_SIDECAR_PARENT_SKILL,
  )

internal fun skillContext(plan: ScaffoldPlan): TemplateContext =
  TemplateContext(
    skillName = plan.skillName,
    family = plan.family,
    platform = plan.platform,
    area = plan.area,
    displayName = plan.displayName.ifBlank { deriveDisplayName(plan.platform) },
  )

internal fun areaFocus(plan: ScaffoldPlan): String =
  plan.area.takeIf { it.isNotBlank() }?.let(::defaultAreaFocus).orEmpty()

internal fun effectiveDescription(plan: ScaffoldPlan): String {
  val context = skillContext(plan)
  return plan.description.ifBlank { inferSkillDescription(context, areaFocus(plan)) }
}
