package skillbill.di.config

import org.junit.jupiter.api.io.TempDir
import skillbill.application.config.RuntimeConfigEditService
import skillbill.application.config.model.ConfigDocumentRead
import skillbill.application.config.model.ConfigWriteResult
import skillbill.application.config.model.MachineConfigEdit
import skillbill.application.config.model.MachineConfigValues
import skillbill.application.config.model.ProjectConfigEdit
import skillbill.application.config.model.ProjectConfigValues
import skillbill.config.model.SpecType
import skillbill.contracts.JsonCodec
import skillbill.infrastructure.host.FileTelemetryConfigStore
import skillbill.infrastructure.host.system.FileConfigDocumentFiles
import skillbill.model.EnvironmentContext
import skillbill.ports.config.ConfigDocumentFiles
import skillbill.ports.config.MachineConfigMigrationPort
import skillbill.ports.config.model.ConfigDocumentFileRead
import skillbill.ports.config.model.ConfigDocumentFormat
import skillbill.ports.config.model.ConfigDocumentReplaceRequest
import skillbill.ports.config.model.ConfigDocumentReplaceResult
import skillbill.ports.config.model.ProjectConfigLocation
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.projectstore.FixedProjectStorePort
import skillbill.ports.telemetry.model.TelemetryConfigSnapshotRead
import skillbill.ports.telemetry.model.TelemetryConfigWriteRequest
import skillbill.telemetry.CONFIG_ENVIRONMENT_KEY
import skillbill.telemetry.STATE_DIR_ENVIRONMENT_KEY
import skillbill.telemetry.TelemetryConfigFileKeys
import skillbill.telemetry.model.TelemetryConfigDocument
import skillbill.telemetry.model.TelemetryOpenDocument
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
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

  @Test
  fun `machine snapshot rejects a same-byte move from legacy to durable config`(
    @TempDir tempDir: Path,
  ) {
    val legacy = tempDir.resolve(".skill-bill/config.json")
    val durable = tempDir.resolve(".config/skill-bill/config.json")
    Files.createDirectories(legacy.parent)
    val original = "{}\n".toByteArray()
    Files.write(legacy, original)
    val service = unpinnedService(tempDir)
    val first = assertIs<ConfigDocumentRead.Present<*>>(service.readMachineConfigSnapshot().document)

    assertEquals(legacy.toRealPath(), first.path)
    Files.createDirectories(durable.parent)
    Files.write(durable, original)
    assertTrue(original.contentEquals(Files.readAllBytes(legacy)))

    val staleWrite =
      service.writeMachineConfigFromSnapshot(
        first.path,
        listOf(MachineConfigEdit.SetTelemetryBatchSize(100)),
        first.fingerprint,
      )

    assertIs<ConfigWriteResult.ChangedOnDisk>(staleWrite)
    val refreshed = assertIs<ConfigDocumentRead.Present<*>>(service.readMachineConfigSnapshot().document)
    assertEquals(durable.toRealPath(), refreshed.path)
    assertIs<ConfigWriteResult.Written>(
      service.writeMachineConfigFromSnapshot(
        refreshed.path,
        listOf(MachineConfigEdit.SetTelemetryBatchSize(100)),
        refreshed.fingerprint,
      ),
    )
    assertTrue(original.contentEquals(Files.readAllBytes(legacy)))
    assertTrue(Files.readString(durable).contains("batch_size"))
  }

  @Test
  fun `machine snapshot carries only the pinned config candidate`(
    @TempDir tempDir: Path,
  ) {
    val pinned = tempDir.resolve("custom/config.json")
    val snapshot = service(tempDir, pinned).readMachineConfigSnapshot()

    assertEquals(listOf(pinned.toAbsolutePath().normalize()), snapshot.candidatePaths)
  }

  @Test
  fun `project snapshot rejects a same-byte move from the legacy fallback to the store`(
    @TempDir tempDir: Path,
  ) {
    val repoRoot = tempDir.resolve("repo")
    val legacy = repoRoot.resolve(".skill-bill/config.yaml")
    val store = tempDir.resolve("project-store/config.yaml")
    Files.createDirectories(legacy.parent)
    val original = "spec_type: local\n".toByteArray()
    Files.write(legacy, original)
    val backing =
      FileConfigDocumentFiles(NoopRuntimeDiagnostics, FixedProjectStorePort(tempDir.resolve("project-store")))
    val documents = ControlledProjectConfigFiles(backing, store, legacy)
    val service =
      RuntimeConfigEditService(
        documents,
        FileTelemetryConfigStore(
          EnvironmentContext(
            environment = mapOf(CONFIG_ENVIRONMENT_KEY to tempDir.resolve("machine.json").toString()),
            userHome = tempDir,
            repositoryRoot = repoRoot,
          ),
        ),
        MachineConfigMigrationPort { false },
      )
    val first = assertIs<ConfigDocumentRead.Present<*>>(service.readProjectConfigSnapshot(repoRoot).document)

    assertEquals(legacy.toAbsolutePath().normalize(), first.path)
    Files.createDirectories(store.parent)
    Files.write(store, original)
    documents.readable = store

    assertIs<ConfigWriteResult.ChangedOnDisk>(
      service.writeProjectConfigFromSnapshot(
        repoRoot,
        first.path,
        listOf(ProjectConfigEdit.SetSpecType(SpecType.LINEAR)),
        first.fingerprint,
      ),
    )
    assertTrue(original.contentEquals(Files.readAllBytes(legacy)))
    val refreshed = assertIs<ConfigDocumentRead.Present<*>>(service.readProjectConfigSnapshot(repoRoot).document)
    assertEquals(store.toAbsolutePath().normalize(), refreshed.path)
    assertIs<ConfigWriteResult.Written>(
      service.writeProjectConfigFromSnapshot(
        repoRoot,
        refreshed.path,
        listOf(ProjectConfigEdit.SetSpecType(SpecType.LINEAR)),
        refreshed.fingerprint,
      ),
    )
    assertTrue(original.contentEquals(Files.readAllBytes(legacy)))
    assertTrue(Files.readString(store).contains("linear"))
  }

  @Test
  fun `desktop snapshot write and telemetry store write retain both updates under the shared lock`(
    @TempDir tempDir: Path,
  ) {
    val configPath = tempDir.resolve("machine.json")
    Files.writeString(
      configPath,
      """{"install_id":"seed","telemetry":{"level":"anonymous","proxy_url":"","batch_size":50}}""",
    )
    val backing =
      FileConfigDocumentFiles(NoopRuntimeDiagnostics, FixedProjectStorePort(tempDir.resolve("project-store")))
    val snapshotRead = CountDownLatch(1)
    val publishSnapshot = CountDownLatch(1)
    val documents = BlockingConfigDocumentFiles(backing, 2, snapshotRead, publishSnapshot)
    val context =
      EnvironmentContext(
        environment = mapOf(CONFIG_ENVIRONMENT_KEY to configPath.toString()),
        userHome = tempDir,
        repositoryRoot = tempDir.resolve("repo"),
      )
    val store = FileTelemetryConfigStore(context)
    val service = RuntimeConfigEditService(documents, store, store)
    val snapshot = assertIs<ConfigDocumentRead.Present<*>>(service.readMachineConfigSnapshot().document)
    val writerAttempted = CountDownLatch(1)
    val executor = Executors.newFixedThreadPool(2)
    try {
      val desktop =
        executor.submit<ConfigWriteResult> {
          service.writeMachineConfigFromSnapshot(
            snapshot.path,
            listOf(MachineConfigEdit.SetTelemetryBatchSize(75)),
            snapshot.fingerprint,
          )
        }
      snapshotRead.await()
      val telemetry =
        executor.submit {
          writerAttempted.countDown()
          store.withConfigTransaction {
            val current =
              assertIs<TelemetryConfigSnapshotRead.Present>(store.readSnapshot())
            val payload = LinkedHashMap<String, Any?>(current.document.payload)
            val currentTelemetry = payload[TelemetryConfigFileKeys.TELEMETRY] as Map<*, *>
            val updatedTelemetry =
              currentTelemetry.entries
                .filter { it.key is String }
                .associate { it.key as String to it.value }
                .toMutableMap()
            updatedTelemetry[TelemetryConfigFileKeys.PROXY_URL] = "https://proxy.example"
            payload[TelemetryConfigFileKeys.TELEMETRY] = updatedTelemetry
            store.write(
              TelemetryConfigWriteRequest(
                current.path,
                current.fingerprint,
                TelemetryConfigDocument(TelemetryOpenDocument.from(payload)),
              ),
            )
          }
        }
      writerAttempted.await()
      publishSnapshot.countDown()

      assertIs<ConfigWriteResult.Written>(desktop.get())
      telemetry.get()
      val refreshed =
        assertIs<ConfigDocumentRead.Present<MachineConfigValues>>(service.readMachineConfigSnapshot().document)
      assertEquals(75, refreshed.values.telemetryBatchSize)
      assertEquals("https://proxy.example", refreshed.values.telemetryProxyUrl)
    } finally {
      publishSnapshot.countDown()
      executor.shutdownNow()
    }
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
      MachineConfigMigrationPort { false },
    )

  private fun unpinnedService(tempDir: Path): RuntimeConfigEditService =
    RuntimeConfigEditService(
      FileConfigDocumentFiles(NoopRuntimeDiagnostics, FixedProjectStorePort(tempDir.resolve("project-store"))),
      FileTelemetryConfigStore(
        EnvironmentContext(
          environment = mapOf(STATE_DIR_ENVIRONMENT_KEY to tempDir.resolve(".skill-bill").toString()),
          userHome = tempDir,
          repositoryRoot = tempDir.resolve("repo"),
        ),
      ),
      MachineConfigMigrationPort { false },
    )

  private class ControlledProjectConfigFiles(
    private val backing: FileConfigDocumentFiles,
    private val store: Path,
    private val legacy: Path,
  ) : ConfigDocumentFiles {
    var readable: Path = legacy

    override fun <T> withLocks(
      paths: Collection<Path>,
      action: () -> T,
    ): T = backing.withLocks(paths, action)

    override fun projectConfigLockPaths(repoRoot: Path): List<Path> = backing.projectConfigLockPaths(repoRoot)

    override fun projectConfigLocation(repoRoot: Path): ProjectConfigLocation =
      ProjectConfigLocation(
        target = store.toAbsolutePath().normalize(),
        readable = readable.toAbsolutePath().normalize(),
        candidatePaths = listOf(store, legacy).map { it.toAbsolutePath().normalize() },
      )

    override fun read(
      path: Path,
      format: ConfigDocumentFormat,
    ): ConfigDocumentFileRead = backing.read(path, format)

    override fun replace(request: ConfigDocumentReplaceRequest): ConfigDocumentReplaceResult = backing.replace(request)
  }

  private class BlockingConfigDocumentFiles(
    private val backing: ConfigDocumentFiles,
    private val blockedRead: Int,
    private val blocked: CountDownLatch,
    private val resume: CountDownLatch,
  ) : ConfigDocumentFiles {
    private val reads = AtomicInteger()

    override fun <T> withLocks(
      paths: Collection<Path>,
      action: () -> T,
    ): T = backing.withLocks(paths, action)

    override fun projectConfigLockPaths(repoRoot: Path): List<Path> = backing.projectConfigLockPaths(repoRoot)

    override fun projectConfigLocation(repoRoot: Path): ProjectConfigLocation = backing.projectConfigLocation(repoRoot)

    override fun read(
      path: Path,
      format: ConfigDocumentFormat,
    ): ConfigDocumentFileRead {
      if (reads.incrementAndGet() == blockedRead) {
        blocked.countDown()
        resume.await()
      }
      return backing.read(path, format)
    }

    override fun replace(request: ConfigDocumentReplaceRequest): ConfigDocumentReplaceResult = backing.replace(request)
  }
}
