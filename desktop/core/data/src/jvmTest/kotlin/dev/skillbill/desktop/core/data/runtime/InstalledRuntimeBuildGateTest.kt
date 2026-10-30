package dev.skillbill.desktop.core.data.runtime

import dev.skillbill.desktop.core.domain.model.RuntimeAvailability
import dev.skillbill.desktop.core.domain.model.RuntimeBuildProblem
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class InstalledRuntimeBuildGateTest {
  @TempDir
  lateinit var directory: Path

  @Test
  fun `the same commit at another read schema is a mismatch`() {
    installRuntime(record(readSchema = 7))
    val appRecord = write(directory.resolve("app/build-identity.properties"), record(readSchema = 8))

    val installation = InstalledRuntimeBuildGate(directory.resolve("runtime"), appRecord).check()

    val unavailable = assertIs<RuntimeAvailability.Unavailable>(installation.availability)
    assertIs<RuntimeBuildProblem.Mismatched>(unavailable.problem)
  }

  @Test
  fun `runtime jars without a build identity record are not installed`() {
    installRuntime(record = null)
    val appRecord = write(directory.resolve("app/build-identity.properties"), record(readSchema = 7))

    val installation = InstalledRuntimeBuildGate(directory.resolve("runtime"), appRecord).check()

    assertEquals(
      RuntimeAvailability.Unavailable(RuntimeBuildProblem.NotInstalled("./install.sh --from-source")),
      installation.availability,
    )
  }

  private fun installRuntime(record: String?) {
    val runtimeCli = directory.resolve("runtime/runtime-cli")
    write(runtimeCli.resolve("lib/runtime-core-0.1.0.jar"), "")
    if (record != null) write(runtimeCli.resolve("build-identity.properties"), record)
  }

  private fun record(readSchema: Int) =
    "commit=abc123\ndirty=false\nrelease_tag=\ninstall_run_id=\nread_schema=$readSchema\nincident_detail_version=1\nmin_incident_detail_read_schema=1\n"

  private fun write(path: Path, text: String): Path {
    Files.createDirectories(path.parent)
    return Files.writeString(path, text)
  }
}
