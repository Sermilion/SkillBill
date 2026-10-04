package skillbill.infrastructure.skills.scaffold.runtime.validation

import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.rethrowUnless
import skillbill.error.shellcontent.SkillStagingFailureCode
import skillbill.error.shellcontent.isShellContentContractFailure
import skillbill.infrastructure.skills.nativeagent.composition.NATIVE_AGENT_SOURCE_DIR
import skillbill.infrastructure.skills.scaffold.runtime.service.contract.supportingFileTargets
import skillbill.infrastructure.skills.scaffold.runtime.service.support.requiredSupportingFilesForSkill
import skillbill.infrastructure.skills.scaffold.validation.review.contentFiles
import skillbill.infrastructure.skills.scaffold.validation.shape.validateAuthoredContent
import skillbill.infrastructure.skills.scaffold.validation.shape.validateSkillMdShape
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.relativeTo

internal val repoValidationNonPortableReviewPatterns =
  listOf(
    Regex("""`task`""") to "must not hardcode the `task` tool in shared review orchestration",
    Regex("""\bspawn_agent\b""") to "must not hardcode the `spawn_agent` tool in shared review orchestration",
    Regex("""\bsub-agent(s)?\b""") to "must not describe review delegation as sub-agents",
    Regex("""\bAgent to spawn\b""") to "must use portable specialist-review wording",
    Regex("""\bAgents spawned\b""") to "must use portable specialist-review summary wording",
  )
internal const val REPO_VALIDATION_GOVERNED_ADDON_PATH_PART_COUNT = 4
internal val repoValidationAddonSlugPattern = Regex("""^[a-z0-9]+(?:-[a-z0-9]+)*$""")
internal val repoValidationSidecarReferencePattern = Regex("`([a-z0-9][a-z0-9-]*)\\.md`")

internal data class ValidateInstallableSkillArgs(
  val skillName: String,
  val contentFile: Path,
  val root: Path,
  val issues: MutableList<String>,
  val validateSourceSidecars: Boolean,
  val portableReviewSkills: Set<String>,
)

internal fun validateInstallableSkill(args: ValidateInstallableSkillArgs) {
  val text = Files.readString(args.contentFile)
  val frontmatter = parseFrontmatter(text)
  if (frontmatter["name"] != args.skillName) {
    args.issues += "${args.contentFile}: frontmatter name '${frontmatter["name"].orEmpty()}' does not match " +
      "directory '${args.skillName}'"
  }
  if (frontmatter["description"].isNullOrBlank()) {
    args.issues += "${args.contentFile}: frontmatter description is missing"
  }
  try {
    validateSkillMdShape(args.contentFile, validateBodyShape = false)
  } catch (error: SkillBillRuntimeException) {
    error.rethrowUnless(
      error.isShellContentContractFailure() && error.code == SkillStagingFailureCode.INVALID_SKILL_MD_SHAPE,
    )
    args.issues += error.message.orEmpty()
  }
  requiredSupportingFilesForSkill(args.skillName, args.root).forEach { fileName ->
    val expectedTarget = supportingFileTargets(args.root)[fileName]
    if (expectedTarget == null) {
      args.issues += "${args.contentFile}: supporting file '$fileName' has no registered target"
    } else if (!Files.exists(expectedTarget)) {
      args.issues += "${args.contentFile}: supporting file '$fileName' target is missing at " +
        "${expectedTarget.relativeTo(args.root)}"
    }
    if (args.validateSourceSidecars && isAuthoredSourceSidecar(args.contentFile, fileName, expectedTarget)) {
      validateSupportingSidecar(args.contentFile, fileName, expectedTarget, args.root, args.issues)
    }
  }
  validatePortableReviewWording(args.skillName, text, args.contentFile, args.issues, args.portableReviewSkills)
  validateGovernedContentFile(args.contentFile, args.issues)
}

internal fun validateSkillSourceShape(
  contentFiles: Collection<Path>,
  root: Path,
  issues: MutableList<String>,
) {
  contentFiles.forEach { contentFile ->
    val skillDir = contentFile.parent
    Files.walk(skillDir).use { stream ->
      stream
        .filter { path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path) }
        .filter { path ->
          val rel = skillDir.relativize(path).toString().replace('\\', '/')
          rel != "content.md" && !rel.startsWith("$NATIVE_AGENT_SOURCE_DIR/")
        }
        .sorted()
        .forEach { path ->
          issues += "${path.relativeTo(root)}: skill source directories may contain only content.md " +
            "and native-agents/ source files"
        }
    }
  }
}

internal fun parseFrontmatter(text: String): Map<String, String> {
  val match = Regex("""(?s)\A---\n(.*?)\n---\n""").find(text) ?: return emptyMap()
  return match.groupValues[1].lineSequence().mapNotNull { line ->
    val separator = line.indexOf(':')
    if (separator < 0) {
      null
    } else {
      line.substring(0, separator).trim() to line.substring(separator + 1).trim().trim('"', '\'')
    }
  }.toMap()
}

internal fun validateAddonFile(
  addonFile: Path,
  root: Path,
  issues: MutableList<String>,
) {
  val relative = addonFile.relativeTo(root)
  val parts = relative.map(Path::toString)
  if (
    parts.size != REPO_VALIDATION_GOVERNED_ADDON_PATH_PART_COUNT ||
    parts[0] != "platform-packs" ||
    parts[2] != "addons"
  ) {
    issues += "$relative: governed add-ons must live directly in platform-packs/<pack>/addons/"
  } else if (!root.resolve("platform-packs").resolve(parts[1]).isDirectory()) {
    issues += "$relative: governed add-on owner pack '${parts[1]}' is missing"
  }
  val name = addonFile.fileName.toString()
  if (!name.endsWith(".md")) {
    issues += "$relative: governed add-on must be markdown"
  }
  val slug =
    name.removeSuffix(".md")
      .removeSuffix("-implementation")
      .removeSuffix("-review")
  if (!repoValidationAddonSlugPattern.matches(slug)) {
    issues += "$relative: governed add-on slug '$slug' must be lowercase kebab-case"
  }
}

internal fun validateSupportingSidecar(
  contentFile: Path,
  fileName: String,
  expectedTarget: Path?,
  root: Path,
  issues: MutableList<String>,
) {
  if (expectedTarget == null) {
    return
  }
  val sidecar = contentFile.parent.resolve(fileName)
  val sidecarPath = sidecar.normalize().toAbsolutePath()
  val expectedPath = expectedTarget.normalize().toAbsolutePath()
  when {
    !Files.exists(sidecar, LinkOption.NOFOLLOW_LINKS) ->
      issues += "$contentFile: required supporting sidecar '$fileName' is missing beside the skill"
    sidecarPath == expectedPath -> Unit
    !Files.isSymbolicLink(sidecar) && !isGitSymlinkPlaceholder(sidecar, expectedTarget) ->
      issues += "$contentFile: required supporting sidecar '$fileName' must be a symlink or git symlink placeholder"
    !Files.isSymbolicLink(sidecar) -> Unit
    else -> supportingSymlinkTargetIssue(contentFile, fileName, sidecar, expectedTarget, root)?.let(issues::add)
  }
}

internal fun isAuthoredSourceSidecar(
  contentFile: Path,
  fileName: String,
  expectedTarget: Path?,
): Boolean {
  if (expectedTarget == null) {
    return false
  }
  val sidecar = contentFile.parent.resolve(fileName).normalize().toAbsolutePath()
  val expected = expectedTarget.normalize().toAbsolutePath()
  return sidecar == expected
}

internal fun isGitSymlinkPlaceholder(
  sidecar: Path,
  expectedTarget: Path,
): Boolean {
  var matches = false
  if (Files.isRegularFile(sidecar, LinkOption.NOFOLLOW_LINKS)) {
    val rawTarget = Files.readString(sidecar).trim()
    if (rawTarget.isNotBlank()) {
      val actualTarget = sidecar.parent.resolve(rawTarget).normalize().toAbsolutePath()
      val expected = expectedTarget.normalize().toAbsolutePath()
      matches = actualTarget == expected
    }
  }
  return matches
}

internal fun supportingSymlinkTargetIssue(
  contentFile: Path,
  fileName: String,
  sidecar: Path,
  expectedTarget: Path,
  root: Path,
): String? {
  val actualTarget = sidecar.toRealPath()
  val expected = expectedTarget.toRealPath()
  return if (actualTarget == expected) {
    null
  } else {
    val realRoot = root.toRealPath()
    "$contentFile: supporting sidecar '$fileName' points to ${actualTarget.relativeTo(realRoot)} " +
      "instead of ${expected.relativeTo(realRoot)}"
  }
}

internal fun validateGovernedContentFile(
  contentFile: Path,
  issues: MutableList<String>,
) {
  if (!contentFile.isRegularFile()) {
    return
  }
  issues += validateAuthoredContent(contentFile, Files.readString(contentFile))
}
