package skillbill.infrastructure.skills.scaffold.validation.shape

import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.rethrowUnless
import skillbill.error.shellcontent.isShellContentContractFailure
import skillbill.infrastructure.skills.scaffold.authoring.AuthoringRenderResult
import skillbill.infrastructure.skills.scaffold.authoring.AuthoringTarget
import skillbill.infrastructure.skills.scaffold.authoring.discoverTargets
import skillbill.infrastructure.skills.scaffold.authoring.renderAuthoringTarget
import skillbill.infrastructure.skills.scaffold.platformpack.loader.loadPlatformManifest
import skillbill.infrastructure.skills.scaffold.pointer.renderPointer
import skillbill.model.toPath
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.relativeTo

internal data class GovernedSkillDriftReport(
  val issues: List<String>,
  val skillCount: Int,
) {
  val passed: Boolean = issues.isEmpty()
}

internal typealias AuthoringTargetRenderer = (Path, AuthoringTarget) -> AuthoringRenderResult

internal fun validateGovernedSkillDrift(repoRoot: Path): GovernedSkillDriftReport =
  validateGovernedSkillDrift(repoRoot, ::renderAuthoringTarget)

internal fun validateGovernedSkillDrift(
  repoRoot: Path,
  renderer: AuthoringTargetRenderer,
): GovernedSkillDriftReport {
  val root = repoRoot.toAbsolutePath().normalize()
  val targets =
    runCatching { discoverTargets(root) }.getOrElse { error ->
      return GovernedSkillDriftReport(
        issues = listOf("governed skill drift: cannot discover governed skills: ${error.message.orEmpty()}"),
        skillCount = 0,
      )
    }
  val issues = mutableListOf<String>()
  targets.values.sortedBy { target -> target.skillName }.forEach { target ->
    validateTargetRender(root, target, renderer, issues)
  }
  validatePointerRenderability(root, issues)
  return GovernedSkillDriftReport(issues.sorted(), targets.size)
}

private fun validateTargetRender(
  root: Path,
  target: AuthoringTarget,
  renderer: AuthoringTargetRenderer,
  issues: MutableList<String>,
) {
  val first = renderTarget(root, target, renderer, "first", issues) ?: return
  val second = renderTarget(root, target, renderer, "second", issues) ?: return
  if (first.stdout != second.stdout) {
    issues += "${driftDisplayPath(root, target.contentFile)}: governed skill '${target.skillName}' render is not " +
      "byte-identical when repeated in memory"
  }
  validateRenderOutput(root, target, first, issues)
}

private fun renderTarget(
  root: Path,
  target: AuthoringTarget,
  renderer: AuthoringTargetRenderer,
  pass: String,
  issues: MutableList<String>,
): AuthoringRenderResult? =
  runCatching { renderer(root, target) }
    .getOrElse { error ->
      issues += "${driftDisplayPath(root, target.contentFile)}: cannot render governed skill '${target.skillName}' " +
        "on $pass pass: ${error.message.orEmpty()}"
      null
    }

private fun validateRenderOutput(
  root: Path,
  target: AuthoringTarget,
  rendered: AuthoringRenderResult,
  issues: MutableList<String>,
) {
  val wrapper = rendered.blocks.firstOrNull { block -> block.header.startsWith("===== SKILL.md: ") }
  if (wrapper == null) {
    issues += "${driftDisplayPath(
      root,
      target.contentFile,
    )}: governed skill '${target.skillName}' render did not emit " +
      "a SKILL.md block"
  } else if (wrapper.content.isBlank()) {
    issues += "${driftDisplayPath(root, target.contentFile)}: governed skill '${target.skillName}' render emitted an " +
      "empty SKILL.md block"
  }
}

private fun validatePointerRenderability(
  root: Path,
  issues: MutableList<String>,
) {
  val packsRoot = root.resolve("platform-packs")
  if (!packsRoot.isDirectory()) {
    return
  }
  Files.list(packsRoot).use { stream ->
    stream
      .filter { packRoot -> packRoot.isDirectory() && !packRoot.fileName.toString().startsWith(".") }
      .sorted()
      .forEach { packRoot ->
        val pack =
          try {
            loadPlatformManifest(packRoot)
          } catch (error: SkillBillRuntimeException) {
            error.rethrowUnless(error.isShellContentContractFailure())
            issues += "${driftDisplayPath(root, packRoot)}: cannot parse platform.yaml for drift check: " +
              error.message.orEmpty()
            return@forEach
          }
        pack.pointers.forEach { spec ->
          runCatching { renderPointer(root, pack.packRoot.toPath(), spec) }
            .onFailure { error ->
              val pointerFile = pack.packRoot.resolve(spec.skillRelativeDir).resolve(spec.name)
              issues += "${driftDisplayPath(
                root,
                pointerFile.toPath(),
              )}: cannot resolve platform.yaml pointer target " +
                "'${spec.target}': ${error.message.orEmpty()}"
            }
        }
      }
  }
}

private fun driftDisplayPath(
  root: Path,
  path: Path,
): String {
  val resolvedRoot = root.toAbsolutePath().normalize()
  val resolvedPath = path.toAbsolutePath().normalize()
  return runCatching { resolvedPath.relativeTo(resolvedRoot).toString().replace('\\', '/') }
    .getOrDefault(resolvedPath.toString())
}
