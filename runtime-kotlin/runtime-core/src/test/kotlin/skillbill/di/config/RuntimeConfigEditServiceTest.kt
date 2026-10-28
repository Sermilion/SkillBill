package skillbill.di.config

import org.junit.jupiter.api.io.TempDir
import skillbill.application.config.RuntimeConfigEditService
import skillbill.application.config.model.ConfigDocumentRead
import skillbill.application.config.model.ConfigWriteResult
import skillbill.application.config.model.MachineConfigEdit
import skillbill.application.config.model.ProjectConfigEdit
import skillbill.application.config.model.ProjectConfigValues
import skillbill.config.model.SpecType
import skillbill.contracts.JsonCodec
import skillbill.infrastructure.host.FileTelemetryConfigStore
import skillbill.infrastructure.host.system.FileConfigDocumentFiles
import skillbill.model.EnvironmentContext
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.projectstore.FixedProjectStorePort
import skillbill.telemetry.CONFIG_ENVIRONMENT_KEY
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RuntimeConfigEditServiceTest {
  @Test
  fun `machine edits reject invalid values and preserve unknown keys`(
    @TempDir tempDir: Path,
  ) {
    val configPath = tempDir.resolve("config.json")
    val original = """{"experimental":{"mode":"keep"},"telemetry":{"level":"anonymous"}}"""
    Files.writeString(configPath, original)
    val service = service(tempDir, configPath)
    val snapshot = assertIs<ConfigDocumentRead.Present<*>>(service.readMachineConfig())

    val invalid = service.writeMachineConfig(listOf(MachineConfigEdit.SetTelemetryBatchSize(0)), snapshot.fingerprint)

    assertEquals(
      ConfigWriteResult.Invalid("telemetry.batch_size", "telemetry.batch_size must be greater than zero."),
      invalid,
    )
    assertEquals(original, Files.readString(configPath))
    val written = service.writeMachineConfig(listOf(MachineConfigEdit.SetTelemetryBatchSize(100)), snapshot.fingerprint)

    assertIs<ConfigWriteResult.Written>(written)
    val payload =
      JsonCodec.anyToStringAnyMap(
        JsonCodec.jsonElementToValue(JsonCodec.parseObjectOrNull(Files.readString(configPath))!!),
      )!!
    assertEquals(mapOf("mode" to "keep"), payload["experimental"])
    assertEquals(100, ((payload["telemetry"] as Map<*, *>)["batch_size"] as Number).toInt())
  }

  @Test
  fun `an unreadable machine document cannot be overwritten`(
    @TempDir tempDir: Path,
  ) {
    val configPath = tempDir.resolve("config.json")
    val original = """{"execution_matrix":{"unknown":true}}"""
    Files.writeString(configPath, original)
    val service = service(tempDir, configPath)

    val read = assertIs<ConfigDocumentRead.Unreadable>(service.readMachineConfig())
    assertTrue(read.keyPath.orEmpty().startsWith("execution_matrix"))
    val written =
      service.writeMachineConfig(
        listOf(MachineConfigEdit.SetTelemetryProxyUrl("https://proxy.example")),
        null,
      )

    assertIs<ConfigWriteResult.Unreadable>(written)
    assertEquals(original, Files.readString(configPath))
  }

  @Test
  fun `legacy project config is carried into the store before an edit`(
    @TempDir tempDir: Path,
  ) {
    val configPath = tempDir.resolve("machine.json")
    val repoRoot = tempDir.resolve("repo")
    val legacy = repoRoot.resolve(".skill-bill/config.yaml")
    Files.createDirectories(legacy.parent)
    val legacyBytes = "validation_gate:\n  gradle_wrapper: runtime-kotlin/gradlew\n".toByteArray()
    Files.write(legacy, legacyBytes)
    val service = service(tempDir, configPath, repoRoot)
    val snapshot = assertIs<ConfigDocumentRead.Present<*>>(service.readProjectConfig(repoRoot))

    val result =
      service.writeProjectConfig(
        repoRoot,
        listOf(ProjectConfigEdit.SetSpecType(SpecType.LINEAR)),
        snapshot.fingerprint,
      )

    val written = assertIs<ConfigWriteResult.Written>(result)
    assertEquals(tempDir.resolve("project-store/config.yaml"), written.path)
    val updated = assertIs<ConfigDocumentRead.Present<ProjectConfigValues>>(service.readProjectConfig(repoRoot))
    assertEquals(SpecType.LINEAR, updated.values.specType)
    assertEquals("runtime-kotlin/gradlew", updated.values.gradleWrapper)
    assertTrue(legacyBytes.contentEquals(Files.readAllBytes(legacy)))
  }

  private fun service(
    tempDir: Path,
    machinePath: Path,
    projectRoot: Path = tempDir.resolve("repo"),
  ): RuntimeConfigEditService =
    RuntimeConfigEditService(
      FileConfigDocumentFiles(NoopRuntimeDiagnostics, FixedProjectStorePort(tempDir.resolve("project-store"))),
      FileTelemetryConfigStore(
        EnvironmentContext(
          environment = mapOf(CONFIG_ENVIRONMENT_KEY to machinePath.toString()),
          userHome = tempDir,
          repositoryRoot = projectRoot,
        ),
      ),
    )
}
