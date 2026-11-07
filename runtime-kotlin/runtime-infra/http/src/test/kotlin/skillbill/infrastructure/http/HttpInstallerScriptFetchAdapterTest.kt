package skillbill.infrastructure.http
import org.junit.jupiter.api.io.TempDir
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.process.model.InstallerScriptFetchRequest
import skillbill.ports.process.model.InstallerScriptFetchResult
import skillbill.ports.system.HostPlatformPort
import skillbill.ports.telemetry.model.RemoteTransportResponse
import skillbill.ports.telemetry.transport.RemoteTransportPort
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HttpInstallerScriptFetchAdapterTest {
  @TempDir
  lateinit var stagingRoot: Path

  @Test
  fun `non-2xx response tears down staging`() {
    val transport = TrackingTransport(stagingRoot) { RemoteTransportResponse(503, "unavailable") }

    val result = adapter(transport).fetch(request())

    assertTrue(result is InstallerScriptFetchResult.Failed)
    assertStagingRemoved(transport)
  }

  @Test
  fun `IO failure tears down staging`() {
    val transport = TrackingTransport(stagingRoot) { throw IOException("network failure") }

    val result = adapter(transport).fetch(request())

    assertTrue(result is InstallerScriptFetchResult.Failed)
    assertStagingRemoved(transport)
  }

  @Test
  fun `interruption tears down staging and is rethrown`() {
    val transport = TrackingTransport(stagingRoot) { throw InterruptedException("cancelled") }

    assertFailsWith<InterruptedException> {
      adapter(transport).fetch(request())
    }
    assertStagingRemoved(transport)
  }

  @Test
  fun `successful response atomically promotes an existing script`() {
    val transport = TrackingTransport(stagingRoot) { RemoteTransportResponse(200, "#!/bin/sh\n") }

    val result = adapter(transport).fetch(request())

    assertTrue(result is InstallerScriptFetchResult.Ready)
    val ready = result
    assertTrue(Files.isRegularFile(ready.scriptPath))
    assertEquals("#!/bin/sh\n", Files.readString(ready.scriptPath))
    adapter(transport).cleanup(ready.scriptPath)
    assertFalse(Files.exists(checkNotNull(ready.scriptPath.parent)))
  }

  @Test
  fun `cleanup refuses a foreign directory and records the refusal`() {
    val diagnostics = InstallerRecordingDiagnostics()
    val foreignDirectory = Files.createTempDirectory("foreign-installer-")
    val script = foreignDirectory.resolve("install.sh")
    Files.writeString(script, "#!/bin/sh\n")

    adapter(TrackingTransport(stagingRoot) { RemoteTransportResponse(200, "") }, diagnostics).cleanup(script)

    assertTrue(Files.exists(foreignDirectory))
    assertTrue(Files.exists(script))
    assertEquals(1, diagnostics.warnings.size)
    assertTrue(diagnostics.warnings.single().contains("seam=installer.cleanup.refusal"))
    assertTrue(diagnostics.warnings.single().contains("expected=owned staging directory"))
    assertTrue(diagnostics.warnings.single().contains("used="))
    Files.deleteIfExists(script)
    Files.deleteIfExists(foreignDirectory)
  }

  private fun assertStagingRemoved(transport: TrackingTransport) {
    assertTrue(transport.stagingDirectory != null)
    assertFalse(Files.exists(transport.stagingDirectory!!))
  }
}

private fun adapter(
  transport: TrackingTransport,
  diagnostics: RuntimeDiagnostics = SilentInstallerDiagnostics,
): HttpInstallerScriptFetchAdapter =
  HttpInstallerScriptFetchAdapter(transport, diagnostics, FixedTempRootHostPlatform(transport.stagingRoot))

private fun request(): InstallerScriptFetchRequest =
  InstallerScriptFetchRequest("https://raw.githubusercontent.com/Sermilion/skill-bill/main/install.sh")

private class FixedTempRootHostPlatform(private val tempRoot: Path) : HostPlatformPort {
  override val osName: String get() = error("unused")
  override val jvmClassPath: String get() = error("unused")
  override val javaCommand: String? get() = error("unused")
  override val pathSeparator: String get() = error("unused")

  override fun resolveUserHome(): Path = error("unused")

  override fun resolveEnvironment(): Map<String, String> = error("unused")

  override fun resolveJavaHome(): Path = error("unused")

  override fun resolveWorkingDirectory(): Path = error("unused")

  override fun resolveTemporaryDirectory(): Path = tempRoot
}

private class TrackingTransport(
  val stagingRoot: Path,
  private val response: () -> RemoteTransportResponse,
) : RemoteTransportPort {
  var stagingDirectory: Path? = null

  override fun execute(
    method: String,
    url: String,
    bodyJson: String?,
    headers: Map<String, String>,
  ): RemoteTransportResponse {
    stagingDirectory =
      Files.list(stagingRoot).use { paths ->
        paths
          .filter { path -> path.fileName.toString().startsWith("skill-bill-update-") }
          .findFirst()
          .orElse(null)
      }
    return response()
  }
}

private class InstallerRecordingDiagnostics : RuntimeDiagnostics {
  val warnings = mutableListOf<String>()

  override fun info(message: String) = Unit

  override fun warning(
    message: String,
    error: Throwable?,
  ) {
    warnings += message
  }

  override fun error(
    message: String,
    error: Throwable?,
  ) = Unit
}

private object SilentInstallerDiagnostics : RuntimeDiagnostics {
  override fun info(message: String) = Unit

  override fun warning(
    message: String,
    error: Throwable?,
  ) = Unit

  override fun error(
    message: String,
    error: Throwable?,
  ) = Unit
}
