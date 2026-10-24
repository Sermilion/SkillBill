package skillbill.infrastructure.workflow.qa

import skillbill.infrastructure.host.process.BoundedExternalProcessRequest
import skillbill.infrastructure.host.process.BoundedExternalProcessResult
import skillbill.infrastructure.host.process.BoundedExternalProcessRunner
import skillbill.ports.qa.model.QaOwnedResource
import skillbill.ports.qa.model.QaOwnedResourceKind
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

private const val COMPOSE_DEADLINE_SECONDS = 120L
private const val DOCKER_DEADLINE_SECONDS = 60L
private const val COMPOSE_STOP_TIMEOUT_SECONDS = "10"
private const val GRACEFUL_EXIT_SECONDS = 10L
private const val FORCED_EXIT_SECONDS = 5L
private const val MAX_DETAIL_LENGTH = 200
private val DOCKER_NOT_FOUND = Regex("(?i)not found|no such (container|volume|network)")

internal fun interface QaResourceCommandRunner {
  fun run(
    argv: List<String>,
    workingDirectory: Path,
    deadlineSeconds: Long,
  ): BoundedExternalProcessResult
}

internal object ProcessQaResourceCommandRunner : QaResourceCommandRunner {
  override fun run(
    argv: List<String>,
    workingDirectory: Path,
    deadlineSeconds: Long,
  ): BoundedExternalProcessResult =
    BoundedExternalProcessRunner.run(
      BoundedExternalProcessRequest(
        argv = argv,
        workingDirectory = workingDirectory,
        deadlineSeconds = deadlineSeconds,
      ),
    )
}

internal sealed interface QaTeardownResult {
  data object Removed : QaTeardownResult

  data class Retained(val detail: String) : QaTeardownResult
}

internal class QaOwnedResourceTeardown(
  private val runner: QaResourceCommandRunner,
) {
  fun tearDown(
    resource: QaOwnedResource,
    processStartedAt: Instant?,
    workingDirectory: Path,
  ): QaTeardownResult =
    when (resource.kind) {
      QaOwnedResourceKind.COMPOSE_PROJECT ->
        docker(
          resource,
          workingDirectory,
          COMPOSE_DEADLINE_SECONDS,
          "compose",
          "-p",
          resource.identity,
          "down",
          "--volumes",
          "--remove-orphans",
          "--timeout",
          COMPOSE_STOP_TIMEOUT_SECONDS,
        )
      QaOwnedResourceKind.CONTAINER ->
        docker(resource, workingDirectory, DOCKER_DEADLINE_SECONDS, "rm", "--force", "--volumes", resource.identity)
      QaOwnedResourceKind.VOLUME ->
        docker(resource, workingDirectory, DOCKER_DEADLINE_SECONDS, "volume", "rm", "--force", resource.identity)
      QaOwnedResourceKind.NETWORK ->
        docker(resource, workingDirectory, DOCKER_DEADLINE_SECONDS, "network", "rm", resource.identity)
      QaOwnedResourceKind.PROCESS -> stopProcess(resource.identity, processStartedAt)
      QaOwnedResourceKind.DIRECTORY -> deleteDirectory(resource.identity)
    }

  private fun docker(
    resource: QaOwnedResource,
    workingDirectory: Path,
    deadlineSeconds: Long,
    vararg arguments: String,
  ): QaTeardownResult {
    val result = runner.run(listOf("docker") + arguments, workingDirectory, deadlineSeconds)
    return when {
      result.launchFailure -> QaTeardownResult.Retained("docker is not available")
      result.timedOut -> QaTeardownResult.Retained("docker command timed out after $deadlineSeconds s")
      result.exitCode == 0 || reportsMissing(result.output, resource.identity) -> QaTeardownResult.Removed
      else -> QaTeardownResult.Retained(redacted(result.output, resource.identity))
    }
  }

  private fun reportsMissing(
    output: String,
    identity: String,
  ): Boolean = output.lineSequence().any { identity in it && DOCKER_NOT_FOUND.containsMatchIn(it) }

  private fun redacted(
    output: String,
    identity: String,
  ): String =
    output
      .lineSequence()
      .firstOrNull(String::isNotBlank)
      .orEmpty()
      .trim()
      .replace(identity, "<resource>")
      .take(MAX_DETAIL_LENGTH)
      .ifEmpty { "docker command failed" }

  private fun stopProcess(
    identity: String,
    recordedStart: Instant?,
  ): QaTeardownResult {
    val handle = identity.toLongOrNull()?.let { ProcessHandle.of(it).orElse(null) }
    return if (handle == null || !handle.isAlive || handle.info().startInstant().orElse(null) != recordedStart) {
      QaTeardownResult.Removed
    } else {
      stop(handle)
    }
  }

  private fun stop(handle: ProcessHandle): QaTeardownResult {
    handle.descendants().forEach(ProcessHandle::destroy)
    if (!handle.destroy() && handle.isAlive) return QaTeardownResult.Retained("process could not be signalled")
    return try {
      handle.onExit().get(GRACEFUL_EXIT_SECONDS, TimeUnit.SECONDS)
      QaTeardownResult.Removed
    } catch (_: TimeoutException) {
      forceStop(handle)
    } catch (failure: ExecutionException) {
      QaTeardownResult.Retained("process exit could not be observed: ${failure.cause?.javaClass?.simpleName}")
    }
  }

  private fun forceStop(handle: ProcessHandle): QaTeardownResult {
    handle.descendants().forEach(ProcessHandle::destroyForcibly)
    handle.destroyForcibly()
    return try {
      handle.onExit().get(FORCED_EXIT_SECONDS, TimeUnit.SECONDS)
      QaTeardownResult.Removed
    } catch (_: TimeoutException) {
      QaTeardownResult.Retained("process did not exit")
    } catch (failure: ExecutionException) {
      QaTeardownResult.Retained("process exit could not be observed: ${failure.cause?.javaClass?.simpleName}")
    }
  }

  private fun deleteDirectory(identity: String): QaTeardownResult {
    val root = Path.of(identity)
    if (Files.notExists(root, LinkOption.NOFOLLOW_LINKS)) return QaTeardownResult.Removed
    return try {
      Files.walkFileTree(root, DeleteTreeVisitor)
      QaTeardownResult.Removed
    } catch (failure: IOException) {
      QaTeardownResult.Retained("could not delete directory: ${failure.javaClass.simpleName}")
    }
  }

  private object DeleteTreeVisitor : SimpleFileVisitor<Path>() {
    override fun visitFile(
      file: Path,
      attrs: BasicFileAttributes,
    ): FileVisitResult {
      Files.deleteIfExists(file)
      return FileVisitResult.CONTINUE
    }

    override fun postVisitDirectory(
      dir: Path,
      exc: IOException?,
    ): FileVisitResult {
      if (exc != null) throw exc
      Files.deleteIfExists(dir)
      return FileVisitResult.CONTINUE
    }
  }
}
