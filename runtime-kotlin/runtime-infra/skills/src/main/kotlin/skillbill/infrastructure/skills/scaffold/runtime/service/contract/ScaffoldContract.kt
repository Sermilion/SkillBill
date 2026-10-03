package skillbill.infrastructure.skills.scaffold.runtime.service.contract

import java.nio.file.Path
import kotlin.io.path.relativeTo
import skillbill.error.shellcontent.missingSupportingFileTargetError
import skillbill.infrastructure.skills.scaffold.platformpack.selectedPlatformManifests
import skillbill.infrastructure.skills.scaffold.runtime.service.support.featureAddonPointerSpecsFor
import skillbill.scaffold.model.PlatformManifest
import skillbill.scaffold.policy.APPROVED_CODE_REVIEW_AREAS as POLICY_APPROVED_CODE_REVIEW_AREAS
import skillbill.scaffold.policy.PLATFORM_PACK_PRESETS as POLICY_PLATFORM_PACK_PRESETS
import skillbill.scaffold.policy.SCAFFOLD_PAYLOAD_VERSION as POLICY_SCAFFOLD_PAYLOAD_VERSION
import skillbill.scaffold.policy.displayNameFromSlug as policyDisplayNameFromSlug
import skillbill.scaffold.policy.platformpack.PLATFORM_PACK_SHELL_CONTRACT_VERSION as POLICY_SHELL_CONTRACT_VERSION

internal val SHELL_CONTRACT_VERSION: String get() = POLICY_SHELL_CONTRACT_VERSION

internal val SCAFFOLD_PAYLOAD_VERSION: String get() = POLICY_SCAFFOLD_PAYLOAD_VERSION
internal const val CONTENT_BODY_FILENAME: String = "content.md"

internal val APPROVED_CODE_REVIEW_AREAS: Set<String> get() = POLICY_APPROVED_CODE_REVIEW_AREAS

internal val SHELLED_FAMILIES: Set<String> = setOf("code-review", "quality-check")
internal val PRE_SHELL_FAMILIES: Set<String> = setOf("feature-task", "feature-verify")

internal val PLATFORM_PACK_PRESETS: Map<String, String> get() = POLICY_PLATFORM_PACK_PRESETS

internal val REQUIRED_GOVERNED_SECTIONS: List<String> =
  listOf("## Descriptor", "## Execution", "## Ceremony")

internal data class TemplateContext(
  val skillName: String,
  val family: String,
  val platform: String,
  val area: String,
  val displayName: String,
)

internal fun displayNameFromSlug(slug: String): String = policyDisplayNameFromSlug(slug)

internal val ORCHESTRATION_PLAYBOOKS: Map<String, String> =
  mapOf(
    "review-scope" to "orchestration/review-scope/PLAYBOOK.md",
    "stack-routing" to "orchestration/stack-routing/PLAYBOOK.md",
    "review-orchestrator" to "orchestration/review-orchestrator/PLAYBOOK.md",
    "review-specialist-contract" to "orchestration/review-orchestrator/specialist-contract.md",
    "review-delegation" to "orchestration/review-delegation/PLAYBOOK.md",
    "telemetry-contract" to "orchestration/telemetry-contract/PLAYBOOK.md",
    "shell-content-contract" to "orchestration/shell-content-contract/PLAYBOOK.md",
  )

internal val ORCHESTRATION_SIDECARS: Map<String, String> =
  mapOf(
    "shell-ceremony" to "orchestration/shell-content-contract/shell-ceremony.md",
    "peak-hours-warner" to "orchestration/shell-content-contract/peak-hours-warner.md",
  )

internal fun supportingFileTargets(repoRoot: Path): Map<String, Path> =
  mapOf(
    "review-scope.md" to repoRoot.resolve(ORCHESTRATION_PLAYBOOKS.getValue("review-scope")),
    "stack-routing.md" to repoRoot.resolve(ORCHESTRATION_PLAYBOOKS.getValue("stack-routing")),
    "review-orchestrator.md" to repoRoot.resolve(ORCHESTRATION_PLAYBOOKS.getValue("review-orchestrator")),
    "specialist-contract.md" to repoRoot.resolve(ORCHESTRATION_PLAYBOOKS.getValue("review-specialist-contract")),
    "review-delegation.md" to repoRoot.resolve(ORCHESTRATION_PLAYBOOKS.getValue("review-delegation")),
    "telemetry-contract.md" to repoRoot.resolve(ORCHESTRATION_PLAYBOOKS.getValue("telemetry-contract")),
    "shell-content-contract.md" to repoRoot.resolve(ORCHESTRATION_PLAYBOOKS.getValue("shell-content-contract")),
    "shell-ceremony.md" to repoRoot.resolve(ORCHESTRATION_SIDECARS.getValue("shell-ceremony")),
    "peak-hours-warner.md" to repoRoot.resolve(ORCHESTRATION_SIDECARS.getValue("peak-hours-warner")),
  )

internal fun requireSupportingFileTarget(
  skillName: String,
  fileName: String,
  repoRoot: Path,
  selectedPlatformManifests: List<PlatformManifest> = emptyList(),
): Path =
  supportingFileTargets(repoRoot)[fileName]
    ?: featureAddonPointerSpecsFor(skillName, selectedPlatformManifests)
      .firstOrNull { spec -> spec.name == fileName }
      ?.let { spec -> repoRoot.toAbsolutePath().normalize().resolve(spec.target).normalize() }
    ?: throw missingSupportingFileTargetError(
      "Runtime supporting file '$fileName' is not registered for '$skillName'.",
    )

internal fun validatePointerTargetParity(
  repoRoot: Path,
  packs: List<PlatformManifest>,
): List<String> {
  val staticTargets = supportingFileTargets(repoRoot)
  val resolvedRoot = repoRoot.toAbsolutePath().normalize()
  val issues = mutableListOf<String>()
  packs.forEach { pack ->
    pack.pointers.forEach { spec ->
      val staticTarget = staticTargets[spec.name] ?: return@forEach
      val staticAbs = staticTarget.toAbsolutePath().normalize()
      val pointerAbs = resolvedRoot.resolve(spec.target).normalize()
      if (staticAbs != pointerAbs) {
        issues += "platform-packs/${pack.slug}: pointer '${spec.name}' target '${spec.target}' " +
          "disagrees with static supportingFileTargets which points at " +
          "'${runCatching { staticAbs.relativeTo(resolvedRoot) }.getOrDefault(staticAbs)}'"
      }
    }
  }
  return issues.sorted()
}
