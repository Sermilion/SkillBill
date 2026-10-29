package skillbill.infrastructure.skills.nativeagent.support

import skillbill.error.shellcontent.agentDiscoveryFailed
import skillbill.install.model.SupportedAgent
import skillbill.ports.install.mcp.model.InstallMcpProfileEnvironment
import java.io.IOException
import java.io.InterruptedIOException
import java.io.UncheckedIOException
import java.nio.channels.ClosedByInterruptException
import java.nio.file.DirectoryIteratorException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

const val CLAUDE_CONFIG_DIR_ENV: String = InstallMcpProfileEnvironment.CLAUDE_CONFIG_DIR_ENV
const val CODEX_HOME_ENV: String = InstallMcpProfileEnvironment.CODEX_HOME_ENV

private val CLAUDE_PROFILE_MARKERS: List<String> =
  listOf(".claude.json", ".credentials.json", "commands", "agents", "history.jsonl")

private val CODEX_PROFILE_MARKERS: List<String> =
  listOf("config.toml", "history.jsonl", "installation_id", "router.config.toml")

internal data class NativeAgentHomeTarget(
  val name: String,
  val path: Path,
)

internal data class NativeAgentObservedPath(
  val path: Path,
  val fileKey: Any,
  val isDirectory: Boolean,
  val followLinks: Boolean,
)

internal data class NativeAgentConfigRoots(
  val paths: List<Path>,
  val observedRoots: Map<Path, NativeAgentObservedPath>,
)

fun claudeConfigRoots(
  home: Path,
  environment: Map<String, String>,
): List<Path> = discoverClaudeConfigRoots(home, environment).paths

internal fun discoverClaudeConfigRoots(
  home: Path,
  environment: Map<String, String>,
): NativeAgentConfigRoots {
  val ordered = mutableListOf<Path>()
  val seen = mutableSetOf<Path>()
  val observedRoots = mutableMapOf<Path, NativeAgentObservedPath>()

  fun add(
    path: Path,
    observation: NativeAgentObservedPath? = null,
  ) {
    val normalized = path.toAbsolutePath().normalize()
    if (seen.add(normalized)) {
      ordered.add(normalized)
    }
    observation?.let { observed ->
      val normalizedObservation = observed.copy(path = normalized)
      observedRoots[normalized] = normalizedObservation
    }
  }

  add(home.resolve(".claude"))
  discoveredProfiles(
    home = home,
    prefix = requireNotNull(SupportedAgent.CLAUDE.profileDirectoryPrefix),
    markers = CLAUDE_PROFILE_MARKERS,
    provider = "Claude",
  ).forEach { profile -> add(profile.path, profile) }
  environment[CLAUDE_CONFIG_DIR_ENV]?.takeIf { it.isNotBlank() }?.let { explicit -> add(Path.of(explicit)) }

  return NativeAgentConfigRoots(ordered, observedRoots)
}

internal fun codexConfigRoots(
  home: Path,
  environment: Map<String, String>,
): List<Path> = discoverCodexConfigRoots(home, environment).paths

internal fun discoverCodexConfigRoots(
  home: Path,
  environment: Map<String, String>,
): NativeAgentConfigRoots {
  val ordered = mutableListOf<Path>()
  val seen = mutableSetOf<Path>()
  val observedRoots = mutableMapOf<Path, NativeAgentObservedPath>()

  fun add(
    path: Path,
    observation: NativeAgentObservedPath? = null,
  ) {
    val normalized = path.toAbsolutePath().normalize()
    if (seen.add(normalized)) {
      ordered.add(normalized)
    }
    observation?.let { observed ->
      val normalizedObservation = observed.copy(path = normalized)
      observedRoots[normalized] = normalizedObservation
    }
  }

  val defaultCodex = home.resolve(".codex")
  observeAgentPath(defaultCodex, "inspect Codex default root")?.let { observation -> add(defaultCodex, observation) }
  discoveredProfiles(
    home = home,
    prefix = requireNotNull(SupportedAgent.CODEX.profileDirectoryPrefix),
    markers = CODEX_PROFILE_MARKERS,
    provider = "Codex",
  ).forEach { profile -> add(profile.path, profile) }
  environment[CODEX_HOME_ENV]?.takeIf { it.isNotBlank() }?.let { explicit -> add(Path.of(explicit)) }

  return NativeAgentConfigRoots(ordered, observedRoots)
}

internal fun codexConfigRoot(
  home: Path,
  environment: Map<String, String>,
): Path =
  environment[CODEX_HOME_ENV]?.takeIf { it.isNotBlank() }
    ?.let { Path.of(it).toAbsolutePath().normalize() }
    ?: defaultCodexRoot(home)

internal fun defaultCodexRoot(home: Path): Path {
  val codexRoot = home.resolve(".codex")
  return if (agentPathExists(codexRoot, "inspect Codex default root")) codexRoot else home.resolve(".agents")
}

internal fun claudeSkillTargets(
  home: Path,
  environment: Map<String, String>,
): List<Path> = claudeSkillTargets(claudeConfigRoots(home, environment))

internal fun claudeSkillTargets(roots: List<Path>): List<Path> = roots.map { root -> root.resolve("skills") }

internal fun codexSkillTargets(
  home: Path,
  environment: Map<String, String>,
): List<Path> = codexSkillTargets(home, codexConfigRoots(home, environment))

internal fun codexSkillTargets(
  home: Path,
  roots: List<Path>,
): List<Path> =
  (roots.map { root -> root.resolve("skills") }.plusElement(home.resolve(".agents/skills")))
    .distinctBy { path -> path.toAbsolutePath().normalize() }

internal fun codexAgentsTargets(
  home: Path,
  environment: Map<String, String>,
): List<Path> = codexAgentsTargets(home, codexConfigRoots(home, environment))

internal fun codexAgentsTargets(
  home: Path,
  roots: List<Path>,
): List<Path> =
  (roots.map { root -> root.resolve("agents") }.plusElement(home.resolve(".agents/agents")))
    .distinctBy { path -> path.toAbsolutePath().normalize() }

internal fun codexAgentIsPresent(
  home: Path,
  environment: Map<String, String>,
  roots: List<Path> = codexConfigRoots(home, environment),
): Boolean {
  return codexAgentIsPresent(home, environment, NativeAgentConfigRoots(roots, emptyMap()))
}

internal fun codexAgentIsPresent(
  home: Path,
  environment: Map<String, String>,
  roots: NativeAgentConfigRoots,
): Boolean {
  val selectedSkills = codexConfigRoot(home, environment).resolve("skills")
  return agentPathsHavePresence(
    paths = roots.paths.plusElement(selectedSkills).plusElement(home.resolve(".agents")),
    operation = "inspect Codex installation",
    roots = roots,
  )
}

internal fun detectCodexAgentsTargets(
  home: Path,
  environment: Map<String, String>,
): List<NativeAgentHomeTarget> {
  val roots = discoverCodexConfigRoots(home, environment)
  if (!codexAgentIsPresent(home, environment, roots)) return emptyList()
  return codexAgentsTargets(home, roots.paths)
    .map { path -> NativeAgentHomeTarget(SupportedAgent.CODEX.nativeAgentsKind, path) }
}

internal fun agentPathsHavePresence(
  paths: List<Path>,
  operation: String,
  roots: NativeAgentConfigRoots,
): Boolean =
  paths.map { path ->
    val normalized = path.toAbsolutePath().normalize()
    roots.observedRoots[normalized]?.let { observation ->
      if (observation.isDirectory) {
        verifyObservedDirectory(observation, operation)
      } else {
        verifyObservedPath(observation, operation)
      }
      true
    } ?: agentPathExists(path, operation)
  }.any { present -> present }

internal fun agentPathExists(
  path: Path,
  operation: String,
): Boolean = readAgentAttributes(path, operation) != null

internal fun agentObservedAttributes(
  path: Path,
  operation: String,
  followLinks: Boolean = true,
): BasicFileAttributes =
  readAgentAttributes(path, operation, followLinks, failOnMissing = true)
    ?: throwDiscoveryFailure(operation, path, NoSuchFileException(path.toString()))

internal fun agentObservedProfileAttributes(
  path: Path,
  operation: String,
): BasicFileAttributes? {
  val entryAttributes = agentObservedAttributes(path, operation, followLinks = false)
  if (!entryAttributes.isSymbolicLink) {
    return agentObservedAttributes(path, operation)
  }
  readAgentAttributes(path, operation)?.let { return it }
  val currentEntry = agentObservedAttributes(path, operation, followLinks = false)
  if (currentEntry.isSymbolicLink) return null
  throwDiscoveryFailure(operation, path, NoSuchFileException(path.toString()))
}

internal fun agentPathIsSymbolicLink(
  path: Path,
  operation: String,
): Boolean = readAgentAttributes(path, operation, followLinks = false)?.isSymbolicLink == true

internal fun agentDirectoryExists(
  path: Path,
  operation: String,
  followLinks: Boolean = true,
): Boolean = readAgentAttributes(path, operation, followLinks)?.isDirectory == true

internal fun agentDirectoryEntries(
  path: Path,
  operation: String,
  followLinks: Boolean = true,
): List<Path> {
  val attributes = readAgentAttributes(path, operation, followLinks) ?: return emptyList()
  if (!attributes.isDirectory) {
    throw agentDiscoveryFailed(operation, path.toString(), NotDirectoryException(path.toString()))
  }
  val initialObservation = observedPath(path, attributes, operation, followLinks)
  val entries =
    try {
      Files.newDirectoryStream(path).use { stream ->
        val listed = stream.toList()
        verifyObservedDirectory(initialObservation, operation)
        listed
      }
    } catch (error: NoSuchFileException) {
      throwDiscoveryFailure(operation, path, error)
    } catch (error: IOException) {
      throwDiscoveryFailure(operation, path, error)
    } catch (error: UncheckedIOException) {
      throwDiscoveryFailure(operation, path, error)
    } catch (error: DirectoryIteratorException) {
      throwDiscoveryFailure(operation, path, error)
    } catch (error: SecurityException) {
      throwDiscoveryFailure(operation, path, error)
    }
  return entries
}

private fun discoveredProfiles(
  home: Path,
  prefix: String,
  markers: List<String>,
  provider: String,
): List<NativeAgentObservedPath> {
  val homeAttributes = readAgentAttributes(home, "inspect $provider profile home") ?: return emptyList()
  if (!homeAttributes.isDirectory) {
    throw agentDiscoveryFailed("list $provider profiles", home.toString(), NotDirectoryException(home.toString()))
  }
  val operation = "list $provider profiles"
  val homeObservation = observedPath(home, homeAttributes, operation, followLinks = true)
  val profiles =
    try {
      Files.newDirectoryStream(home).use { entries ->
        val candidates =
          entries.filter { entry -> entry.fileName.toString().startsWith(prefix) }
            .sortedBy { entry -> entry.fileName.toString() }
        val discovered =
          candidates.mapNotNull { profile ->
            val profileOperation = "inspect $provider profile"
            val attributes = agentObservedProfileAttributes(profile, profileOperation)
            if (attributes?.isDirectory != true) return@mapNotNull null
            val profileObservation = observedPath(profile, attributes, profileOperation, followLinks = true)
            val marked =
              markers.map { marker ->
                agentPathExists(profile.resolve(marker), "inspect $provider profile marker")
              }.any { present -> present }
            verifyObservedDirectory(profileObservation, profileOperation)
            if (marked) profileObservation else null
          }
        verifyObservedDirectory(homeObservation, operation)
        discovered
      }
    } catch (error: NoSuchFileException) {
      throwDiscoveryFailure(operation, home, error)
    } catch (error: IOException) {
      throwDiscoveryFailure(operation, home, error)
    } catch (error: UncheckedIOException) {
      throwDiscoveryFailure(operation, home, error)
    } catch (error: DirectoryIteratorException) {
      throwDiscoveryFailure(operation, home, error)
    } catch (error: SecurityException) {
      throwDiscoveryFailure(operation, home, error)
    }
  return profiles
}

private fun observeAgentPath(
  path: Path,
  operation: String,
  followLinks: Boolean = true,
): NativeAgentObservedPath? {
  val attributes = readAgentAttributes(path, operation, followLinks) ?: return null
  return observedPath(path, attributes, operation, followLinks)
}

private fun observedPath(
  path: Path,
  attributes: BasicFileAttributes,
  operation: String,
  followLinks: Boolean,
): NativeAgentObservedPath {
  val fileKey =
    attributes.fileKey()
      ?: throwDiscoveryFailure(
        operation,
        path,
        IOException("filesystem did not provide a file key for the observed path"),
      )
  return NativeAgentObservedPath(path.toAbsolutePath().normalize(), fileKey, attributes.isDirectory, followLinks)
}

internal fun verifyObservedPath(
  observed: NativeAgentObservedPath,
  operation: String,
) {
  val attributes = agentObservedAttributes(observed.path, operation, observed.followLinks)
  if (attributes.isDirectory != observed.isDirectory) {
    throwDiscoveryFailure(operation, observed.path, IOException("observed path type changed during discovery"))
  }
  val current = observedPath(observed.path, attributes, operation, observed.followLinks)
  if (current.fileKey != observed.fileKey) {
    throwDiscoveryFailure(operation, observed.path, IOException("observed path identity changed during discovery"))
  }
}

internal fun verifyObservedDirectory(
  observed: NativeAgentObservedPath,
  operation: String,
) {
  if (!observed.isDirectory) {
    throw agentDiscoveryFailed(operation, observed.path.toString(), NotDirectoryException(observed.path.toString()))
  }
  verifyObservedPath(observed, operation)
}

private fun readAgentAttributes(
  path: Path,
  operation: String,
  followLinks: Boolean = true,
  failOnMissing: Boolean = false,
): BasicFileAttributes? =
  try {
    if (followLinks) {
      Files.readAttributes(path, BasicFileAttributes::class.java)
    } else {
      Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    }
  } catch (error: NoSuchFileException) {
    if (failOnMissing) throwDiscoveryFailure(operation, path, error)
    null
  } catch (error: IOException) {
    throwDiscoveryFailure(operation, path, error)
  } catch (error: UncheckedIOException) {
    throwDiscoveryFailure(operation, path, error)
  } catch (error: DirectoryIteratorException) {
    throwDiscoveryFailure(operation, path, error)
  } catch (error: SecurityException) {
    throwDiscoveryFailure(operation, path, error)
  }

private fun throwDiscoveryFailure(
  operation: String,
  path: Path,
  cause: Throwable,
): Nothing {
  rethrowAgentOperationInterruption(cause)
  throwDiscoveryFailure(operation, path.toString(), cause)
}

internal fun rethrowAgentOperationInterruption(cause: Throwable) {
  val interruption =
    generateSequence(cause) { error -> error.cause }
      .firstOrNull { error ->
        error is ClosedByInterruptException || error is InterruptedIOException || error is InterruptedException
      }
  if (interruption != null) {
    Thread.currentThread().interrupt()
    throw interruption
  }
}

private fun throwDiscoveryFailure(
  operation: String,
  path: String,
  cause: Throwable,
): Nothing = throw agentDiscoveryFailed(operation, path, cause)
