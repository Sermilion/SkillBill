package skillbill.agent.buildrun

import skillbill.agent.model.BuildMatch
import skillbill.agent.model.BuildMatchContext
import skillbill.agent.model.BuildProcessSample
import skillbill.agent.model.BuildRepositoryFiles
import skillbill.scaffold.model.BuildToolDeclaration
import skillbill.scaffold.model.BuildToolVersionSource
import skillbill.scaffold.model.PlatformManifest

private val SHELL_WRAPPERS = setOf("sh", "bash", "zsh", "dash")

class BuildCommandMatcher(
  packs: List<PlatformManifest>,
  private val context: BuildMatchContext,
  private val files: BuildRepositoryFiles,
) {
  private val declarations: List<BuildToolDeclaration> = packs.flatMap(PlatformManifest::buildTools)
  private val chosenDeclarations = mutableMapOf<ProjectTool, BuildToolDeclaration>()
  private val toolLabels = mutableMapOf<ProjectTool, String>()

  fun match(samples: List<BuildProcessSample>): List<BuildMatch> {
    val hits = samples.mapNotNull { sample -> classify(sample)?.let { sample.pid to (sample to it) } }.toMap()
    val byPid = samples.associateBy(BuildProcessSample::pid)
    return hits.values
      .filterNot { (sample, _) -> hasMatchingAncestor(sample, byPid, hits.keys) }
      .map { (sample, hit) -> toMatch(sample, hit) }
  }

  private fun classify(sample: BuildProcessSample): Hit? {
    if (isShellWrapper(sample)) return null
    val arguments = sample.arguments
    val commandName = sample.command?.let(::basename)
    val firstArgument = arguments.firstOrNull()?.let(::basename)
    return declarations.firstNotNullOfOrNull { declaration ->
      when {
        commandName == declaration.executable -> Hit(declaration.executable, arguments)
        firstArgument == declaration.executable -> Hit(declaration.executable, arguments.drop(1))
        else ->
          arguments
            .indexOfFirst { it in declaration.processMarkers || basename(it) in declaration.processMarkers }
            .takeIf { it >= 0 }
            ?.let { index -> Hit(declaration.executable, arguments.drop(index + 1)) }
      }
    }
  }

  private fun isShellWrapper(sample: BuildProcessSample): Boolean {
    if (sample.command?.let(::basename) !in SHELL_WRAPPERS) return false
    val options = sample.arguments.takeWhile { it.startsWith("-") }
    return options.any { 'c' in it.drop(1) && !it.startsWith("--") }
  }

  private fun hasMatchingAncestor(
    sample: BuildProcessSample,
    byPid: Map<Long, BuildProcessSample>,
    matchingPids: Set<Long>,
  ): Boolean {
    val visited = mutableSetOf(sample.pid)
    var parentPid = sample.parentPid
    while (parentPid != null && visited.add(parentPid)) {
      if (parentPid in matchingPids) return true
      parentPid = byPid[parentPid]?.parentPid
    }
    return false
  }

  private fun toMatch(
    sample: BuildProcessSample,
    hit: Hit,
  ): BuildMatch {
    val projectRoot =
      if (declarations.any {
          it.executable == hit.executable && it.versionSource == BuildToolVersionSource.GRADLE_WRAPPER
        }
      ) {
        gradleWrapperProjectRoot(sample) ?: context.repositoryRoot
      } else {
        context.repositoryRoot
      }
    val project = ProjectTool(hit.executable, projectRoot)
    val declaration = chosenDeclarations.getOrPut(project) { chooseDeclaration(hit.executable, projectRoot) }
    val display = declaration.displayExecutable
    return BuildMatch(
      pid = sample.pid,
      startedAt = sample.startedAt,
      executable = declaration.executable,
      displayExecutable = display,
      displayCommand = (listOf(display) + hit.remainder).joinToString(" "),
      shortCommand = shortCommand(declaration, hit.remainder),
      toolLabel =
        toolLabels.getOrPut(project) {
          buildToolLabel(declaration, context.copy(repositoryRoot = projectRoot), files)
        },
      projectKind = declaration.projectKind,
    )
  }

  private fun chooseDeclaration(
    executable: String,
    projectRoot: String,
  ): BuildToolDeclaration {
    val candidates = declarations.filter { it.executable == executable }
    return candidates.firstOrNull { candidate ->
      candidate.projectMarkers.any { files.exists(underDirectory(projectRoot, it)) }
    } ?: candidates.firstOrNull { it.projectMarkers.isEmpty() } ?: candidates.first()
  }

  private fun shortCommand(
    declaration: BuildToolDeclaration,
    remainder: List<String>,
  ): String {
    val name = basename(declaration.displayExecutable)
    if (!declaration.shortSubcommand) return name
    val subcommand =
      remainder.firstOrNull { !it.startsWith("-") && it !in declaration.shortSkip } ?: return name
    return "$name ${subcommand.substringAfterLast(':')}"
  }

  private data class ProjectTool(val executable: String, val root: String)

  private data class Hit(
    val executable: String,
    val remainder: List<String>,
  )
}

internal fun basename(value: String): String = value.substringAfterLast('/').substringAfterLast('\\')
