package skillbill.infrastructure.skills.install.apply

import skillbill.error.core.failureCodeLabel
import skillbill.infrastructure.host.jvm.atomicMoveReplacing
import skillbill.infrastructure.skills.scaffold.platformpack.catalog.assertExternalPlatformPackDeclaredReads
import skillbill.infrastructure.skills.scaffold.platformpack.catalog.assertExternalPlatformPackTreeReads
import skillbill.install.model.InstallAppliedSkill
import skillbill.install.model.InstallApplyIssue
import skillbill.install.model.InstallApplyIssueKind
import skillbill.install.model.InstallPlan
import skillbill.install.model.InstallPlanSkillKind
import skillbill.install.model.InstallSkillStagingStatus
import skillbill.model.toPath
import skillbill.scaffold.model.PlatformManifest
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption

private const val MANAGED_INSTALL_MARKER = ".skill-bill-install"
private const val PLATFORM_PACKS_DIR = "platform-packs"

internal fun materializeAgentPlatformPackViews(
  plan: InstallPlan,
  platformManifests: List<PlatformManifest>,
  appliedSkills: List<InstallAppliedSkill>,
  failures: MutableList<InstallApplyIssue>,
) {
  if (plan.selectedPlatformSlugs.isEmpty()) {
    cleanupManagedPlatformPackViews(plan, failures)
    return
  }
  val selectedManifests =
    platformManifests
      .filter { manifest -> manifest.slug in plan.selectedPlatformSlugs }
      .sortedBy(PlatformManifest::slug)
  val stagedPlatformSkills =
    appliedSkills
      .filter { skill -> skill.kind == InstallPlanSkillKind.PLATFORM_PACK }
      .filter { skill -> skill.staging.status == InstallSkillStagingStatus.STAGED }
      .associateBy { skill -> skill.sourceDir.toPath().toAbsolutePath().normalize() }
  val internalPlatformSkillDirs =
    plan.skills
      .filter { skill -> skill.kind == InstallPlanSkillKind.PLATFORM_PACK && skill.internalFor != null }
      .map { skill -> skill.sourceDir.toPath().toAbsolutePath().normalize() }
      .toSet()
  val billSharedRoot = plan.request.repoRoot.toPath().toAbsolutePath().normalize().resolve(".bill-shared")
  plan.agents.forEach { agentTarget ->
    runCatching {
      selectedManifests.forEach { manifest ->
        val packRoot = manifest.packRoot.toPath().toAbsolutePath().normalize()
        assertExternalPlatformPackTreeReads(packRoot, billSharedRoot)
        assertExternalPlatformPackDeclaredReads(manifest, billSharedRoot)
      }
      val root = agentTarget.path.toPath().toAbsolutePath().normalize().resolve(PLATFORM_PACKS_DIR)
      materializeAgentPlatformPackView(
        root,
        selectedManifests,
        stagedPlatformSkills,
        internalPlatformSkillDirs,
      )
    }.getOrElse { error ->
      failures.add(
        InstallApplyIssue(
          kind = InstallApplyIssueKind.SKILL_LINK_FAILED,
          message = error.message.orEmpty(),
          agent = agentTarget.agent,
          path = agentTarget.path.resolve(PLATFORM_PACKS_DIR),
          causeClass = error.failureCodeLabel() ?: error::class.qualifiedName,
        ),
      )
    }
  }
}

internal fun cleanupManagedPlatformPackViews(
  plan: InstallPlan,
  failures: MutableList<InstallApplyIssue>,
) {
  plan.agents.forEach { agentTarget ->
    runCatching {
      val root = agentTarget.path.toPath().toAbsolutePath().normalize().resolve(PLATFORM_PACKS_DIR)
      if (Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) && Files.exists(root.resolve(MANAGED_INSTALL_MARKER))) {
        deleteTree(root)
      }
    }.getOrElse { error ->
      failures.add(
        InstallApplyIssue(
          kind = InstallApplyIssueKind.SKILL_LINK_FAILED,
          message = error.message.orEmpty(),
          agent = agentTarget.agent,
          path = agentTarget.path.resolve(PLATFORM_PACKS_DIR),
          causeClass = error.failureCodeLabel() ?: error::class.qualifiedName,
        ),
      )
    }
  }
}

private fun materializeAgentPlatformPackView(
  root: Path,
  manifests: List<PlatformManifest>,
  stagedPlatformSkills: Map<Path, InstallAppliedSkill>,
  internalPlatformSkillDirs: Set<Path>,
) {
  if (Files.isSymbolicLink(root)) {
    error("Existing symlink at $root was preserved.")
  }
  if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
    require(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) && Files.exists(root.resolve(MANAGED_INSTALL_MARKER))) {
      "Existing non-managed platform-packs path at $root was preserved."
    }
  }
  val parent = requireNotNull(root.parent) { "Managed platform-packs path '$root' has no parent." }
  Files.createDirectories(parent)
  val staging = parent.resolve(".${root.fileName}.platform-packs-staging")
  val superseded = parent.resolve(".${root.fileName}.platform-packs-superseded")
  deleteTree(staging)
  deleteTree(superseded)
  Files.createDirectories(staging)
  Files.writeString(staging.resolve(MANAGED_INSTALL_MARKER), "")
  var supersededMoved = false
  var preserveSuperseded = false
  try {
    manifests.forEach { manifest ->
      materializeOnePack(staging, manifest, stagedPlatformSkills, internalPlatformSkillDirs)
    }
    if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
      atomicMoveReplacing(root, superseded)
      supersededMoved = true
    }
    val publishResult = runCatching { atomicMoveReplacing(staging, root) }
    publishResult.exceptionOrNull()?.let { error ->
      if (supersededMoved && Files.exists(superseded, LinkOption.NOFOLLOW_LINKS)) {
        runCatching {
          if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            deleteTree(root)
          }
          atomicMoveReplacing(superseded, root)
        }.onFailure { restoreError ->
          preserveSuperseded = true
          error.addSuppressed(restoreError)
        }
      }
      publishResult.getOrThrow()
    }
  } finally {
    deleteTree(staging)
    if (!preserveSuperseded) {
      deleteTree(superseded)
    }
  }
}

private fun materializeOnePack(
  agentPacksRoot: Path,
  manifest: PlatformManifest,
  stagedPlatformSkills: Map<Path, InstallAppliedSkill>,
  internalPlatformSkillDirs: Set<Path>,
) {
  val packRoot = manifest.packRoot.toPath().toAbsolutePath().normalize()
  val destinationPackRoot = agentPacksRoot.resolve(manifest.slug).normalize()
  require(destinationPackRoot.startsWith(agentPacksRoot)) {
    "Platform pack '${manifest.slug}' escapes agent platform-packs root '$agentPacksRoot'."
  }
  val skillDirs = platformSkillDirs(manifest)
  copyPackNonSkillFiles(packRoot, destinationPackRoot, skillDirs)
  skillDirs.forEach { skillDir ->
    if (skillDir in internalPlatformSkillDirs) {
      return@forEach
    }
    val staged =
      stagedPlatformSkills[skillDir]?.staging?.stagingDir
        ?: error("Selected platform pack '${manifest.slug}' skill '$skillDir' was not staged.")
    val relative = packRoot.relativize(skillDir).toString()
    val linkPath = destinationPackRoot.resolve(relative).normalize()
    require(linkPath.startsWith(destinationPackRoot)) {
      "Platform pack '${manifest.slug}' skill link '$linkPath' escapes pack root '$destinationPackRoot'."
    }
    linkPath.parent?.let(Files::createDirectories)
    createOrReplaceManagedSkillSymlink(linkPath, staged.toPath())
  }
}

private fun platformSkillDirs(manifest: PlatformManifest): Set<Path> =
  (
    listOfNotNull(manifest.declaredFiles.baseline) + manifest.declaredFiles.areas.values
  )
    .map { contentFile -> contentFile.toPath().toAbsolutePath().normalize().parent }
    .toSet()

private fun copyPackNonSkillFiles(
  packRoot: Path,
  destinationPackRoot: Path,
  skillDirs: Set<Path>,
) {
  val agentDir = packRoot.resolve("agent").toAbsolutePath().normalize()
  Files.walk(packRoot).use { stream ->
    stream.forEach { source ->
      if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
        return@forEach
      }
      val resolvedSource = source.toAbsolutePath().normalize()
      if (resolvedSource.startsWith(agentDir)) {
        return@forEach
      }
      if (skillDirs.any { skillDir -> resolvedSource.startsWith(skillDir) }) {
        return@forEach
      }
      val destination = destinationPackRoot.resolve(packRoot.relativize(resolvedSource).toString()).normalize()
      require(destination.startsWith(destinationPackRoot)) {
        "Platform pack file '$source' escapes destination pack root '$destinationPackRoot'."
      }
      destination.parent?.let(Files::createDirectories)
      Files.copy(
        source,
        destination,
        StandardCopyOption.REPLACE_EXISTING,
        StandardCopyOption.COPY_ATTRIBUTES,
        LinkOption.NOFOLLOW_LINKS,
      )
    }
  }
}

private fun createOrReplaceManagedSkillSymlink(
  linkPath: Path,
  stagingDir: Path,
) {
  val target = stagingDir.toAbsolutePath().normalize()
  require(Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
    "Staged platform skill target '$target' is not a directory."
  }
  if (Files.isSymbolicLink(linkPath)) {
    createReplacementSymlinkWithGuidance(linkPath, target)
  } else if (Files.exists(linkPath, LinkOption.NOFOLLOW_LINKS)) {
    error("Existing non-symlink platform skill path at $linkPath was preserved.")
  } else {
    createNewSymlinkWithGuidance(linkPath, target)
  }
}

private fun deleteTree(root: Path) {
  if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
    return
  }
  Files.walk(root).use { stream ->
    stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
  }
}
