package skillbill.infrastructure.workflow.qa

import skillbill.model.EnvironmentContext
import skillbill.ports.qa.model.QaOwnedResource
import skillbill.ports.qa.model.QaOwnedResourceKind
import skillbill.ports.qa.model.qaEnvironmentName
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.time.Instant

private val DOCKER_IDENTITY = Regex("^[a-z0-9][a-z0-9_.-]{0,127}$")
private val NAME_SEPARATORS = listOf("-", "_", ".")
private const val MIN_PROCESS_ID = 2L

internal sealed interface QaResourceValidation {
  data class Valid(val processStartedAt: Instant? = null) : QaResourceValidation

  /** [reason] is fixed text: it never echoes the identity, a URL, a credential or an environment value. */
  data class Invalid(val reason: String) : QaResourceValidation
}

internal class QaOwnedResourceValidator(
  private val environment: EnvironmentContext,
) {
  fun validate(
    resource: QaOwnedResource,
    invocationId: String,
    scope: QaResourceScope,
  ): QaResourceValidation =
    when (resource.kind) {
      QaOwnedResourceKind.PROCESS -> process(resource.identity, scope.ownerProcessId)
      QaOwnedResourceKind.DIRECTORY -> directory(resource.identity, qaEnvironmentName(invocationId), scope)
      else -> dockerName(resource.identity, qaEnvironmentName(invocationId))
    }

  private fun dockerName(
    identity: String,
    environmentName: String,
  ): QaResourceValidation =
    when {
      !DOCKER_IDENTITY.matches(identity) -> invalid("the identity is not a valid Docker resource name")
      identity != environmentName && NAME_SEPARATORS.none { identity.startsWith(environmentName + it) } ->
        invalid("the identity does not start with the invocation environment name")
      else -> QaResourceValidation.Valid()
    }

  private fun process(
    identity: String,
    ownerProcessId: Long,
  ): QaResourceValidation {
    val pid = identity.toLongOrNull()?.takeIf { it >= MIN_PROCESS_ID }
    val handle = pid?.let { ProcessHandle.of(it).orElse(null) }?.takeIf(ProcessHandle::isAlive)
    val startedAt = handle?.info()?.startInstant()?.orElse(null)
    return when {
      pid == null -> invalid("the identity is not a process id of at least 2")
      handle == null || startedAt == null -> invalid("the process is not running or unreadable")
      pid == ownerProcessId || isCurrentOrAncestor(handle) -> invalid("the process is the runtime or an ancestor")
      else -> QaResourceValidation.Valid(startedAt)
    }
  }

  private fun invalid(reason: String): QaResourceValidation = QaResourceValidation.Invalid(reason)

  private fun isCurrentOrAncestor(handle: ProcessHandle): Boolean =
    generateSequence(ProcessHandle.current()) { it.parent().orElse(null) }.any { it.pid() == handle.pid() }

  private fun directory(
    identity: String,
    environmentName: String,
    scope: QaResourceScope,
  ): QaResourceValidation {
    val path =
      try {
        Path.of(identity)
      } catch (_: InvalidPathException) {
        return invalid("the identity is not a valid path")
      }
    val inScope =
      (path.startsWith(scope.invocationDirectory) && path != scope.invocationDirectory) ||
        path.fileName?.toString()?.contains(environmentName) == true
    val protectedPaths = listOf(environment.userHome, scope.repoRoot, scope.qaRoot).map(::normalized)
    return when {
      !path.isAbsolute || path != path.normalize() -> invalid("the path is not absolute and normalized")
      path.parent == null || protectedPaths.any { it.startsWith(path) } ->
        invalid("the path is a root, the home, the repository root or one of their ancestors")
      !inScope -> invalid("the path is outside the invocation directory and not environment named")
      Files.isSymbolicLink(path) -> invalid("the path is a symbolic link")
      Files.exists(path) && !Files.isDirectory(path) -> invalid("the path exists and is not a directory")
      else -> QaResourceValidation.Valid()
    }
  }

  private fun normalized(path: Path): Path = path.toAbsolutePath().normalize()
}

internal data class QaResourceScope(
  val repoRoot: Path,
  val qaRoot: Path,
  val invocationDirectory: Path,
  val ownerProcessId: Long,
)
