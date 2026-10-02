package skillbill.infrastructure.skills.scaffold.runtime.validation

import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.rethrowUnless
import skillbill.error.shellcontent.isShellContentContractFailure
import skillbill.infrastructure.skills.agentaddon.discoverAgentAddons
import skillbill.infrastructure.skills.nativeagent.composition.NATIVE_AGENT_SOURCE_DIR
import skillbill.infrastructure.skills.nativeagent.rendering.NativeAgentProvider
import skillbill.infrastructure.skills.scaffold.authoring.parseInternalForFrontmatter
import skillbill.infrastructure.skills.scaffold.platformpack.loader.skillclass.SKILL_CLASSES_DIR
import skillbill.infrastructure.skills.scaffold.platformpack.loader.skillclass.discoverSkillClasses
import skillbill.infrastructure.skills.scaffold.platformpack.loader.skillclass.resolveSkillClass
import skillbill.infrastructure.skills.scaffold.runtime.service.contract.supportingFileTargets
import skillbill.infrastructure.skills.scaffold.runtime.service.support.requiredSupportingFilesForSkill
import skillbill.install.model.ListedSkillNames
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.relativeTo

internal val repoValidationBoundaryLedgerDir: Path = Path.of("skills", "agent")
internal val repoValidationSkillReferencePattern =
  Regex(
    """(?<![A-Za-z0-9.-])(bill-[a-z0-9-]+)(?![A-Za-z0-9-])""" +
      """|(?<![A-Za-z0-9./-])/(${ListedSkillNames.DISPATCHER})(?![A-Za-z0-9-])""",
  )
internal val repoValidationOrchestrationPathPattern = Regex("""orchestration/[\w/.-]+""")
internal val repoValidationReadmeSkillRowPattern =
  Regex("""^\| `/(bill-[a-z0-9-]+|${ListedSkillNames.DISPATCHER})` \|""")
internal val repoValidationOverrideSectionPattern = Regex("""^## (bill-[a-z0-9-]+)$""")
internal val repoValidationExternalPlaybookReferencePatterns =
  listOf(
    Regex("""\.bill-shared/orchestration/""") to
      "must reference skill-local supporting files instead of install-local playbook paths",
  )
internal val repoValidationInlineTelemetryContractMarkers =
  listOf(
    "Standalone-first contract",
    "child_steps aggregation",
    "Graceful degradation",
    "Routers never emit",
  )

internal fun validateAgentAddons(
  root: Path,
  issues: MutableList<String>,
) {
  try {
    discoverAgentAddons(root)
  } catch (error: SkillBillRuntimeException) {
    error.rethrowUnless(error.isShellContentContractFailure())
    issues += "agent-addons: ${error.message}"
  }
}

internal fun validateReadme(
  readme: Path,
  skillNames: Set<String>,
  internalSkills: Set<String>,
  issues: MutableList<String>,
) {
  if (!readme.isRegularFile()) {
    issues += "README.md is missing"
    return
  }
  val catalogSkills =
    Files.readAllLines(readme)
      .mapNotNull { line -> repoValidationReadmeSkillRowPattern.find(line)?.groupValues?.get(1) }
      .toSet()
  val missing = (skillNames - internalSkills) - catalogSkills
  if (missing.isNotEmpty()) {
    issues += "README.md catalog is missing skills: ${missing.sorted()}"
  }
}

internal fun internalSkillNames(skillFiles: Map<String, Path>): Set<String> =
  skillFiles.entries.mapNotNull { (skillName, contentFile) ->
    if (parseInternalForFrontmatter(contentFile)?.isNotBlank() == true) skillName else null
  }.toSet()

internal fun validateSkillReferences(
  root: Path,
  skillNames: Set<String>,
  issues: MutableList<String>,
) {
  val scanRoots = listOf("skills", "platform-packs", "orchestration", ".agents").map(root::resolve)
  scanRoots.filter(Path::isDirectory).forEach { scanRoot ->
    Files.walk(scanRoot).use { stream ->
      stream
        .filter {
          it.isRegularFile() &&
            it.fileName.toString().endsWith(".md") &&
            isSkillReferenceScanTarget(it.relativeTo(root))
        }
        .forEach { file -> validateSkillReferencesInFile(file, root, skillNames, issues) }
    }
  }
}

internal fun isSkillReferenceScanTarget(relativePath: Path): Boolean {
  val parts = relativePath.map(Path::toString)
  val inBoundaryLedger =
    relativePath.parent?.fileName?.toString() == "agent" &&
      relativePath.fileName.toString() in BOUNDARY_LEDGER_FILE_NAMES
  if (relativePath.startsWith(repoValidationBoundaryLedgerDir) || inBoundaryLedger) {
    return false
  }
  if (NATIVE_AGENT_SOURCE_DIR in parts) {
    return false
  }
  return NativeAgentProvider.entries.none { provider -> provider.directoryName in parts }
}

internal fun validateSkillReferencesInFile(
  file: Path,
  root: Path,
  skillNames: Set<String>,
  issues: MutableList<String>,
) {
  val text = Files.readString(file)
  repoValidationSkillReferencePattern.findAll(text).forEach { match ->
    val referenced = match.groupValues[1].ifEmpty { match.groupValues[2] }
    if (referenced !in skillNames && !isDocumentedExampleReference(file, root, referenced)) {
      issues += "${file.relativeTo(root)}: references unknown skill '$referenced'"
    }
  }
}

internal fun validateSkillOverrides(
  overrideFile: Path,
  skillNames: Set<String>,
  required: Boolean,
  issues: MutableList<String>,
) {
  if (!overrideFile.isRegularFile()) {
    if (required) {
      issues += "${overrideFile.fileName}: required skill override file is missing"
    }
    return
  }
  Files.readAllLines(overrideFile).forEachIndexed { index, line ->
    val section = repoValidationOverrideSectionPattern.find(line)?.groupValues?.get(1) ?: return@forEachIndexed
    if (section !in skillNames) {
      issues += "${overrideFile.fileName}:${index + 1}: override section references unknown skill '$section'"
    }
  }
}

internal fun validateSupportingTargets(
  root: Path,
  skillNames: Set<String>,
  issues: MutableList<String>,
) {
  skillNames.flatMap { name -> requiredSupportingFilesForSkill(name, root) }.toSet().forEach { fileName ->
    val target = supportingFileTargets(root)[fileName]
    if (target == null || !Files.exists(target)) {
      issues += "supporting file '$fileName' target is missing"
    }
  }
}

internal fun validateFeatureAddonDeclarations(
  root: Path,
  issues: MutableList<String>,
) {
  val staticTargets = supportingFileTargets(root).keys
  val classes = if (root.resolve(SKILL_CLASSES_DIR).isDirectory()) discoverSkillClasses(root) else emptyList()
  val featureClassPointers =
    resolveSkillClass("skill-bill", classes)
      ?.pointers
      ?.map { pointer -> "$pointer.md" }
      .orEmpty()
      .filter { pointer -> pointer !in staticTargets }
  featureClassPointers.forEach { pointer ->
    issues += "orchestration/skill-classes/feature-task.yaml: feature-task support pointer '$pointer' " +
      "must be declared by a selected platform pack's feature_addon_usage instead of the global skill class."
  }

  loadFeatureAddonValidationPacks(root).forEach { pack ->
    val declaredPointers =
      pack.featureAddonUsage
        .filter { usage -> usage.consumer == "feature-task" }
        .flatMap { usage -> usage.addons.flatMap { addon -> listOf(addon.entrypoint) + addon.companionPointers } }
        .toSet()
    pack.pointers
      .filter { pointer ->
        pointer.skillRelativeDir == "feature-task" &&
          pointer.target.startsWith("platform-packs/${pack.slug}/addons/") &&
          pointer.target.endsWith(".md")
      }
      .filter { pointer -> pointer.name !in declaredPointers }
      .forEach { pointer ->
        issues += "platform-packs/${pack.slug}/platform.yaml: feature-task pointer '${pointer.name}' targets " +
          "'${pointer.target}' but is missing from feature_addon_usage.feature-task."
      }
  }
}

private val BOUNDARY_LEDGER_FILE_NAMES = setOf("history.md", "decisions.md")
