package skillbill.infrastructure.skills.install.opencode

import skillbill.contracts.system.AgentHomeEnvironmentKeys
import skillbill.contracts.system.OpenCodeEnvironmentKeys
import skillbill.contracts.system.OpenCodeLaunchIdentity
import skillbill.contracts.system.OpenCodeLaunchIdentityFailureCode
import skillbill.error.core.SkillBillRuntimeException
import skillbill.install.model.McpRegistrationFailureCode
import skillbill.ports.install.mcp.model.InstallMcpProfileEnvironment
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.channels.ClosedByInterruptException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

internal data class OpenCodeRoots(
  val globalDirectory: Path,
  val additionalDirectory: Path?,
  val configDirectory: Path,
  val skillDirectory: Path,
  val explicitConfigFile: Path?,
  val knownConfigDirectories: List<Path>,
  val knownConfigSources: List<Path>,
  val inlineContent: String?,
  val workingDirectory: Path?,
  val projectConfigEnabled: Boolean,
)

internal fun opencodeConfigRoots(
  home: Path,
  environment: Map<String, String>,
  workingDirectory: Path? = environment[InstallMcpProfileEnvironment.PWD_ENV]?.let(::parsePath),
): OpenCodeRoots {
  if (!home.isAbsolute) invalid("OpenCode home must be absolute; supply an absolute home path and retry.")
  val homeRoot = home.normalize()
  val resolvedWorkingDirectory =
    workingDirectory?.let { directory ->
      if (!directory.isAbsolute) {
        invalid("OpenCode working directory must be absolute; supply an absolute PWD and retry.")
      }
      directory.normalize()
    }
  requireWorkingDirectoryForRelativeOverrides(environment, resolvedWorkingDirectory)
  val launchIdentity = captureLaunchIdentity(environment, homeRoot, resolvedWorkingDirectory)
  val xdgRoot = Path.of(launchIdentity.effectiveConfigHome)
  val globalDirectory = xdgRoot.resolve("opencode").normalize()
  val additionalDirectory =
    launchIdentity.configDirectory.value?.takeIf(String::isNotBlank)
      ?.let { Path.of(launchIdentity.effectiveConfigDirectory) }
  val configDirectory = additionalDirectory ?: globalDirectory
  val explicitConfig =
    launchIdentity.effectiveConfigFile?.let(Path::of)
  val projectConfigDisabled =
    environment[InstallMcpProfileEnvironment.OPENCODE_DISABLE_PROJECT_CONFIG_ENV]
      ?.trim()
      ?.let { value -> value.equals("true", ignoreCase = true) || value == "1" } == true
  val projectConfigEnabled = !projectConfigDisabled
  val projectDirectories =
    if (projectConfigEnabled && resolvedWorkingDirectory != null) {
      ancestorDirectories(resolvedWorkingDirectory)
    } else {
      emptyList()
    }
  val projectFiles = projectDirectories.asReversed().flatMap(::rootConfigFiles)
  val directories =
    (
      projectDirectories.map { directory -> directory.resolve(".opencode") } +
        listOf(homeRoot.resolve(".opencode")) +
        listOfNotNull(additionalDirectory)
    )
      .distinctBy(::normalized)
      .map(::normalized)
  val sources =
    buildList {
      add(xdgRoot.resolve("opencode/config.json"))
      add(globalDirectory.resolve("opencode.json"))
      add(globalDirectory.resolve("opencode.jsonc"))
      explicitConfig?.let(::add)
      addAll(projectFiles)
      directories.forEach { directory -> addAll(rootConfigFiles(directory)) }
    }.map(::normalized)
  return OpenCodeRoots(
    globalDirectory = normalized(globalDirectory),
    additionalDirectory = additionalDirectory?.let(::normalized),
    configDirectory = normalized(configDirectory),
    skillDirectory = normalized(configDirectory.resolve("skills")),
    explicitConfigFile = explicitConfig?.let(::normalized),
    knownConfigDirectories = directories,
    knownConfigSources = sources,
    inlineContent = environment[InstallMcpProfileEnvironment.OPENCODE_CONFIG_CONTENT_ENV],
    workingDirectory = resolvedWorkingDirectory,
    projectConfigEnabled = projectConfigEnabled,
  )
}

private fun requireWorkingDirectoryForRelativeOverrides(
  environment: Map<String, String>,
  resolvedWorkingDirectory: Path?,
) {
  val relativeOverrides =
    listOf(
      OpenCodeEnvironmentKeys.XDG_CONFIG_HOME,
      AgentHomeEnvironmentKeys.OPENCODE_CONFIG_DIR,
      OpenCodeEnvironmentKeys.CONFIG_FILE,
    ).mapNotNull { key -> environment[key]?.takeIf(String::isNotBlank) }
  if (resolvedWorkingDirectory == null && relativeOverrides.any { !parsePath(it).isAbsolute }) {
    invalid("Relative OpenCode paths require an explicit working directory; supply PWD and retry.")
  }
}

private fun captureLaunchIdentity(
  environment: Map<String, String>,
  homeRoot: Path,
  workingDirectory: Path?,
): OpenCodeLaunchIdentity =
  try {
    OpenCodeLaunchIdentity.capture(
      environment + (OpenCodeEnvironmentKeys.HOME to homeRoot.toString()),
      homeRoot,
      workingDirectory ?: homeRoot,
    )
  } catch (failure: SkillBillRuntimeException) {
    if (failure.code != OpenCodeLaunchIdentityFailureCode.INVALID_PATH) throw failure
    invalid("Invalid OpenCode path value; correct the provider configuration and retry.", failure)
  }

private fun ancestorDirectories(start: Path): List<Path> {
  val result = mutableListOf<Path>()
  var current: Path? = start
  while (current != null) {
    result.add(current)
    if (isRepositoryBoundary(current)) break
    current = current.parent
  }
  return result
}

private fun isRepositoryBoundary(directory: Path): Boolean {
  val marker = directory.resolve(".git")
  val attributes =
    try {
      Files.readAttributes(marker, BasicFileAttributes::class.java)
    } catch (_: NoSuchFileException) {
      return false
    } catch (error: IOException) {
      rethrowInterruption(error)
      invalid("Could not inspect repository boundary '$marker'; correct access and retry.", error)
    } catch (error: SecurityException) {
      invalid("Could not inspect repository boundary '$marker'; correct access and retry.", error)
    }
  if (attributes.isDirectory) return true
  if (!attributes.isRegularFile) invalid("Repository boundary '$marker' has an unsupported file type.")
  val markerText = readBoundaryFile(marker)
  val gitDirValue =
    markerText.lineSequence().firstOrNull()?.takeIf { it.startsWith("gitdir:") }
      ?.removePrefix("gitdir:")?.trim()?.takeIf(String::isNotEmpty)
      ?: invalid("Repository boundary '$marker' is malformed; correct it and retry.")
  val gitDirPath = parsePath(gitDirValue)
  val gitDir = normalized(if (gitDirPath.isAbsolute) gitDirPath else directory.resolve(gitDirPath))
  try {
    if (!Files.readAttributes(gitDir, BasicFileAttributes::class.java).isDirectory) {
      invalid("Repository git-directory marker '$gitDir' is not a directory.")
    }
  } catch (error: IOException) {
    rethrowInterruption(error)
    invalid("Could not inspect repository git directory '$gitDir'; correct the marker and retry.", error)
  } catch (error: SecurityException) {
    invalid("Could not inspect repository git directory '$gitDir'; correct access and retry.", error)
  }
  inspectCommonDirectory(gitDir)
  return true
}

private fun inspectCommonDirectory(gitDir: Path) {
  val commonFile = gitDir.resolve("commondir")
  try {
    val commonAttributes = Files.readAttributes(commonFile, BasicFileAttributes::class.java)
    if (!commonAttributes.isRegularFile) invalid("Repository common-directory marker '$commonFile' is not a file.")
    val commonValue = readBoundaryFile(commonFile).trim()
    if (commonValue.isEmpty()) invalid("Repository common-directory marker '$commonFile' is empty.")
    val commonPath = parsePath(commonValue)
    normalized(if (commonPath.isAbsolute) commonPath else gitDir.resolve(commonPath))
  } catch (_: NoSuchFileException) {
    return
  } catch (error: IOException) {
    rethrowInterruption(error)
    invalid("Could not inspect repository common-directory marker '$commonFile'; correct access and retry.", error)
  } catch (error: SecurityException) {
    invalid("Could not inspect repository common-directory marker '$commonFile'; correct access and retry.", error)
  }
}

private fun readBoundaryFile(path: Path): String =
  try {
    Files.readString(path)
  } catch (error: IOException) {
    rethrowInterruption(error)
    invalid("Could not read repository boundary '$path'; correct access and retry.", error)
  } catch (error: SecurityException) {
    invalid("Could not read repository boundary '$path'; correct access and retry.", error)
  }

private fun rethrowInterruption(error: IOException) {
  if (error is InterruptedIOException || error is ClosedByInterruptException) throw error
}

private fun rootConfigFiles(directory: Path): List<Path> =
  listOf("opencode.json", "opencode.jsonc").map(directory::resolve)

private fun parsePath(value: String): Path =
  try {
    Path.of(value)
  } catch (error: InvalidPathException) {
    invalid("Invalid OpenCode path value; correct the provider configuration and retry.", error)
  }

private fun normalized(path: Path): Path = path.toAbsolutePath().normalize()

private fun invalid(
  message: String,
  cause: Throwable? = null,
): Nothing = throw SkillBillRuntimeException(McpRegistrationFailureCode.OPENCODE_CONFIG_INVALID, message, cause)
