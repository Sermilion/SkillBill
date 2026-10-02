package skillbill.infrastructure.http
import me.tatarka.inject.annotations.Inject
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.TelemetryHttpFailureCode
import skillbill.error.core.failureCodeLabel
import skillbill.error.core.rethrowUnless
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.process.InstallerScriptFetchPort
import skillbill.ports.process.model.InstallerScriptFetchRequest
import skillbill.ports.process.model.InstallerScriptFetchResult
import skillbill.ports.telemetry.transport.RemoteTransportPort
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

@Inject
class HttpInstallerScriptFetchAdapter(
  private val requester: RemoteTransportPort,
  private val diagnostics: RuntimeDiagnostics,
) : InstallerScriptFetchPort {
  override fun fetch(request: InstallerScriptFetchRequest): InstallerScriptFetchResult {
    val stagingDirectory =
      try {
        Files.createTempDirectory(STAGING_DIRECTORY_PREFIX)
      } catch (error: IOException) {
        return InstallerScriptFetchResult.Failed(errorMessage(error))
      }
    val partialPath = stagingDirectory.resolve("install.sh.partial")
    val scriptPath = stagingDirectory.resolve("install.sh")
    var result: InstallerScriptFetchResult? = null
    try {
      val response =
        requester.execute(
          method = "GET",
          url = request.url,
          bodyJson = null,
          headers = mapOf(HttpHeaders.USER_AGENT to INSTALLER_USER_AGENT),
        )
      result =
        if (response.statusCode !in HTTP_SUCCESS_RANGE) {
          InstallerScriptFetchResult.Failed(
            "install script download failed with HTTP ${response.statusCode}",
          )
        } else {
          Files.write(partialPath, response.body.toByteArray())
          Files.move(
            partialPath,
            scriptPath,
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
          )
          InstallerScriptFetchResult.Ready(scriptPath)
        }
    } catch (interrupted: InterruptedException) {
      throw interrupted
    } catch (error: IOException) {
      result = InstallerScriptFetchResult.Failed(errorMessage(error))
    } catch (error: SkillBillRuntimeException) {
      error.rethrowUnless(error.code == TelemetryHttpFailureCode.PROXY_REQUEST_FAILED)
      result = InstallerScriptFetchResult.Failed(errorMessage(error))
    } finally {
      if (result !is InstallerScriptFetchResult.Ready) {
        teardown(stagingDirectory, partialPath, scriptPath)
      }
    }
    return checkNotNull(result)
  }

  override fun cleanup(scriptPath: Path) {
    val stagingDirectory = scriptPath.parent ?: return
    if (
      !Files.isDirectory(stagingDirectory) ||
      !stagingDirectory.fileName.toString().startsWith(STAGING_DIRECTORY_PREFIX) ||
      !Files.isRegularFile(scriptPath)
    ) {
      diagnostics.warning(
        "seam=installer.cleanup.refusal expected=owned staging directory containing script " +
          "used=${stagingDirectory.toAbsolutePath()}",
      )
      return
    }
    try {
      Files.walk(stagingDirectory).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach { path ->
          try {
            Files.deleteIfExists(path)
          } catch (error: IOException) {
            recordCleanupFailure(path, error)
          }
        }
      }
    } catch (error: IOException) {
      recordCleanupFailure(stagingDirectory, error)
    }
  }

  private fun teardown(
    stagingDirectory: Path,
    partialPath: Path,
    scriptPath: Path,
  ) {
    listOf(partialPath, scriptPath, stagingDirectory).forEach { path ->
      try {
        Files.deleteIfExists(path)
      } catch (error: IOException) {
        recordCleanupFailure(path, error)
      }
    }
  }

  private fun recordCleanupFailure(
    path: Path,
    error: IOException,
  ) {
    diagnostics.warning(
      "seam=installer.cleanup expected=path removable used=${path.toAbsolutePath()}",
      error,
    )
  }

  private fun errorMessage(error: Exception): String =
    error.message.orEmpty().ifBlank { error.failureCodeLabel() ?: error::class.simpleName.orEmpty() }

  private companion object {
    const val STAGING_DIRECTORY_PREFIX: String = "skill-bill-update-"
  }
}
