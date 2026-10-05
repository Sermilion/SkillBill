package skillbill.di.telemetry

import skillbill.application.telemetry.config.TelemetryConfigMutations
import skillbill.application.telemetry.lifecycle.LifecycleTelemetryService
import skillbill.application.telemetry.model.FeatureTaskRuntimeStartedRequest
import skillbill.application.telemetry.service.TelemetryLevelMutationService
import skillbill.application.telemetry.settings.DefaultTelemetrySettingsProvider
import skillbill.contracts.telemetry.TelemetryOutboxEvent
import skillbill.infrastructure.host.FileTelemetryConfigStore
import skillbill.model.EnvironmentContext
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.goalrunner.EmptyGoalPlanningPreparationRepository
import skillbill.ports.goalrunner.EmptyGoalRunnerControlRepository
import skillbill.ports.learning.LearningRepository
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.persistence.UnitOfWorkDefaults
import skillbill.ports.repository.toFileLocation
import skillbill.ports.review.repository.ReviewRepository
import skillbill.ports.telemetry.lifecycle.LifecycleTelemetryRepository
import skillbill.ports.telemetry.model.TelemetryConfigRead
import skillbill.ports.telemetry.model.TelemetryOutboxClaimRequest
import skillbill.ports.telemetry.model.TelemetryOutboxRecord
import skillbill.ports.telemetry.model.TelemetryOutboxSettlementResult
import skillbill.ports.telemetry.model.TelemetrySettingsLoad
import skillbill.ports.telemetry.transport.TelemetryConfigStore
import skillbill.ports.telemetry.transport.TelemetryOutboxRepository
import skillbill.ports.telemetry.transport.TelemetryReconciliationRepository
import skillbill.ports.telemetry.transport.TelemetrySettingsProvider
import skillbill.ports.work.EmptyWorkListRepository
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.telemetry.CONFIG_ENVIRONMENT_KEY
import skillbill.telemetry.model.TelemetryConfigDocument
import skillbill.telemetry.model.TelemetryOpenDocument
import skillbill.telemetry.model.TelemetrySettings
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TelemetryLevelMutationServiceTest {
  @Test
  fun `clears disabled outbox inside application transaction`() {
    val outboxRepository =
      MutationTelemetryOutboxRepository(
        mutableListOf(
          TelemetryOutboxRecord(
            id = 1,
            eventName = "skillbill_feature_implement_started",
            payloadJson = """{"name":"ok"}""",
            createdAt = "2026-04-24 00:00:00",
            syncedAt = null,
            lastError = "",
          ),
        ),
      )
    val database = FakeTelemetryDatabaseSessionFactory(outboxRepository)
    val configStore = FakeMutationTelemetryConfigStore()
    val service =
      TelemetryLevelMutationService(
        database = database,
        settingsProvider = DisabledMutationTelemetrySettingsProvider,
        configStore = configStore,
      )

    val result = service.setLevel("off")

    assertEquals(listOf("transaction"), database.calls)
    assertEquals(1, result.clearedEvents)
    assertEquals(0, outboxRepository.pendingCount())
    assertEquals("off", (configStore.document.payload["telemetry"] as Map<*, *>)["level"])
    assertEquals("existing", configStore.document.payload["install_id"])
  }

  @Test
  fun `downgrade from full to anonymous clears the outbox inside a transaction`() {
    val outboxRepository = MutationTelemetryOutboxRepository(mutableListOf(outboxRecord(1), outboxRecord(2)))
    val database = FakeTelemetryDatabaseSessionFactory(outboxRepository)
    val service =
      TelemetryLevelMutationService(
        database = database,
        settingsProvider = LeveledMutationTelemetrySettingsProvider("full"),
        configStore = FakeMutationTelemetryConfigStore(),
      )

    val result = service.setLevel("anonymous")

    assertEquals(listOf("transaction"), database.calls)
    assertEquals(2, result.clearedEvents, "full-level payloads must not survive a downgrade")
    assertEquals(0, outboxRepository.pendingCount())
  }

  @Test
  fun `upgrade and same level writes leave the outbox untouched`() {
    for ((current, next) in listOf("anonymous" to "full", "full" to "full")) {
      val outboxRepository = MutationTelemetryOutboxRepository(mutableListOf(outboxRecord(1)))
      val database = FakeTelemetryDatabaseSessionFactory(outboxRepository)
      val service =
        TelemetryLevelMutationService(
          database = database,
          settingsProvider = LeveledMutationTelemetrySettingsProvider(current),
          configStore = FakeMutationTelemetryConfigStore(),
        )

      val result = service.setLevel(next)

      assertEquals(emptyList<String>(), database.calls, "$current to $next must not open a clearing transaction")
      assertEquals(0, result.clearedEvents)
      assertEquals(1, outboxRepository.pendingCount())
    }
  }

  @Test
  fun `an unrecognized current level fails closed and clears the outbox`() {
    val outboxRepository = MutationTelemetryOutboxRepository(mutableListOf(outboxRecord(1)))
    val service =
      TelemetryLevelMutationService(
        database = FakeTelemetryDatabaseSessionFactory(outboxRepository),
        settingsProvider = LeveledMutationTelemetrySettingsProvider("bogus"),
        configStore = FakeMutationTelemetryConfigStore(),
      )

    assertEquals(1, service.setLevel("anonymous").clearedEvents)
    assertEquals(0, outboxRepository.pendingCount())
  }

  @Test
  fun `disable rewrites the config file in place instead of deleting it`() {
    val fixture =
      telemetryStoreFixture(
        seed = """{"install_id":"seeded-id","telemetry":{"level":"anonymous","proxy_url":"","batch_size":50}}""",
      )

    val (settings, cleared) =
      TelemetryConfigMutations.setTelemetryLevel(
        level = "off",
        configStore = fixture.configStore,
        settingsProvider = fixture.settingsProvider,
      )

    assertTrue(Files.exists(fixture.configPath), "disable must leave config.json on disk")
    assertContains(Files.readString(fixture.configPath), "\"level\":\"off\"")
    assertEquals("off", settings.level)
    assertFalse(settings.enabled)
    assertEquals(0, cleared)
  }

  @Test
  fun `off to anonymous round trip reuses the retained install_id`() {
    val fixture =
      telemetryStoreFixture(
        seed = """{"install_id":"seeded-id","telemetry":{"level":"anonymous","proxy_url":"","batch_size":50}}""",
      )

    TelemetryConfigMutations.setTelemetryLevel(
      level = "off",
      configStore = fixture.configStore,
      settingsProvider = fixture.settingsProvider,
    )
    assertContains(Files.readString(fixture.configPath), "\"install_id\":\"seeded-id\"")

    val (settings, _) =
      TelemetryConfigMutations.setTelemetryLevel(
        level = "anonymous",
        configStore = fixture.configStore,
        settingsProvider = fixture.settingsProvider,
      )

    assertEquals("seeded-id", settings.installId, "re-enable must not mint a fresh install id")
    assertTrue(settings.enabled)
  }

  @Test
  fun `disable preserves external addon sources and execution matrix`() {
    val fixture =
      telemetryStoreFixture(
        seed =
          """
      |{"install_id":"seeded-id","external_addon_sources":["/tmp/addons","/tmp/more"],
      |"execution_matrix":{"default":"claude","review":"codex"},
      |"telemetry":{"level":"anonymous","proxy_url":"","batch_size":50}}
          """.trimMargin(),
      )

    TelemetryConfigMutations.setTelemetryLevel(
      level = "off",
      configStore = fixture.configStore,
      settingsProvider = fixture.settingsProvider,
    )

    val payload =
      when (val read = fixture.configStore.read()) {
        TelemetryConfigRead.Absent -> emptyMap()
        is TelemetryConfigRead.Malformed -> error(read.reason)
        is TelemetryConfigRead.Present -> read.document.payload
      }
    assertEquals(listOf("/tmp/addons", "/tmp/more"), payload["external_addon_sources"])
    assertEquals(mapOf("default" to "claude", "review" to "codex"), payload["execution_matrix"])
  }

  @Test
  fun `disable clears queued outbox events and reports the cleared count`() {
    val fixture =
      telemetryStoreFixture(
        seed = """{"install_id":"seeded-id","telemetry":{"level":"anonymous","proxy_url":"","batch_size":50}}""",
      )
    val outbox =
      MutationTelemetryOutboxRepository(
        mutableListOf(
          outboxRecord(1),
          outboxRecord(2),
        ),
      )

    val (_, cleared) =
      TelemetryConfigMutations.setTelemetryLevel(
        level = "off",
        configStore = fixture.configStore,
        settingsProvider = fixture.settingsProvider,
        outbox = outbox,
      )

    assertEquals(2, cleared)
    assertEquals(0, outbox.pendingCount())
  }

  @Test
  fun `no events are queued while the level is off`() {
    val fixture =
      telemetryStoreFixture(
        seed = """{"install_id":"seeded-id","telemetry":{"level":"anonymous","proxy_url":"","batch_size":50}}""",
      )
    val outbox = MutationTelemetryOutboxRepository(mutableListOf())
    TelemetryConfigMutations.setTelemetryLevel(
      level = "off",
      configStore = fixture.configStore,
      settingsProvider = fixture.settingsProvider,
      outbox = outbox,
    )

    val service =
      LifecycleTelemetryService(
        database = FakeTelemetryDatabaseSessionFactory(outbox),
        settingsProvider = fixture.settingsProvider,
        clock = Clock.systemUTC(),
        diagnostics = NoopRuntimeDiagnostics,
      )

    val result =
      service.featureTaskRuntimeStarted(
        FeatureTaskRuntimeStartedRequest(featureSize = "MEDIUM", issueKey = "SKILL-163", featureName = "telemetry"),
      )

    assertEquals("skipped", result.toPayload()["status"], "level off must skip lifecycle emission")
    assertEquals(0, outbox.pendingCount(), "nothing may be queued while telemetry is off")
  }
}

private class TelemetryStoreFixture(
  val configPath: Path,
  val configStore: TelemetryConfigStore,
  val settingsProvider: TelemetrySettingsProvider,
)

private fun telemetryStoreFixture(seed: String): TelemetryStoreFixture {
  val home = Files.createTempDirectory("skill-bill-telemetry-disable")
  home.toFile().deleteOnExit()
  val configPath = home.resolve(".config").resolve("skill-bill").resolve("config.json")
  Files.createDirectories(configPath.parent)
  Files.writeString(configPath, seed)
  val context =
    EnvironmentContext(
      environment = mapOf(CONFIG_ENVIRONMENT_KEY to configPath.toString()),
      userHome = home,
    )
  val configStore = FileTelemetryConfigStore(context)
  return TelemetryStoreFixture(
    configPath = configPath,
    configStore = configStore,
    settingsProvider = DefaultTelemetrySettingsProvider(context, configStore),
  )
}

private fun outboxRecord(id: Long): TelemetryOutboxRecord =
  TelemetryOutboxRecord(
    id = id,
    eventName = "skillbill_feature_implement_started",
    payloadJson = """{"name":"ok"}""",
    createdAt = "2026-04-24 00:00:00",
    syncedAt = null,
    lastError = "",
  )

private class FakeTelemetryDatabaseSessionFactory(
  private val telemetryOutbox: TelemetryOutboxRepository,
) : DatabaseSessionFactory {
  val calls = mutableListOf<String>()
  private val dbPath = Path.of("/fake/metrics.db")

  override fun resolveDbPath(): Path = dbPath

  override fun databaseExists(): Boolean = true

  override fun <T> read(block: (UnitOfWork) -> T): T {
    calls += "read"
    return block(fakeUnitOfWork())
  }

  override fun <T> selfManagedWrite(block: (UnitOfWork) -> T): T = transaction(block)

  override fun <T> transaction(block: (UnitOfWork) -> T): T {
    calls += "transaction"
    return block(fakeUnitOfWork())
  }

  private fun fakeUnitOfWork(): UnitOfWork =
    object : UnitOfWorkDefaults() {
      override val dbPath: Path = this@FakeTelemetryDatabaseSessionFactory.dbPath
      override val reviews: ReviewRepository
        get() = error("Unexpected reviews")
      override val learnings: LearningRepository
        get() = error("Unexpected learnings")
      override val lifecycleTelemetry: LifecycleTelemetryRepository
        get() = error("Unexpected lifecycleTelemetry")
      override val telemetryReconciliation: TelemetryReconciliationRepository
        get() = error("Unexpected telemetryReconciliation")
      override val telemetryOutbox: TelemetryOutboxRepository = this@FakeTelemetryDatabaseSessionFactory.telemetryOutbox
      override val workflowStates: WorkflowStateRepository
        get() = error("Unexpected workflowStates")
      override val workList = EmptyWorkListRepository
      override val goalPlanningPreparations = EmptyGoalPlanningPreparationRepository
      override val goalRunnerControls = EmptyGoalRunnerControlRepository
    }
}

private class MutationTelemetryOutboxRepository(
  private val rows: MutableList<TelemetryOutboxRecord>,
) : TelemetryOutboxRepository {
  override fun enqueue(
    event: TelemetryOutboxEvent,
    payloadJson: String,
  ): Long {
    val id = (rows.maxOfOrNull(TelemetryOutboxRecord::id) ?: 0L) + 1
    rows +=
      TelemetryOutboxRecord(
        id = id,
        eventName = event.wireValue,
        payloadJson = payloadJson,
        createdAt = "2026-04-24 00:00:00",
        syncedAt = null,
        lastError = "",
      )
    return id
  }

  override fun claimPending(request: TelemetryOutboxClaimRequest): List<TelemetryOutboxRecord> =
    rows.take(request.limit)

  override fun pendingCount(): Int = rows.size

  override fun blockedCount(attemptBudget: Int): Int = 0

  override fun latestError(): String? = null

  override fun lastSyncedAt(): String? = rows.mapNotNull(TelemetryOutboxRecord::syncedAt).maxOrNull()

  override fun markSynced(
    eventIds: List<Long>,
    claimToken: String,
  ): TelemetryOutboxSettlementResult = TelemetryOutboxSettlementResult.forRequest(eventIds, updatedRows = 0)

  override fun markFailed(
    eventIds: List<Long>,
    claimToken: String,
    lastError: String,
  ): TelemetryOutboxSettlementResult = TelemetryOutboxSettlementResult.forRequest(eventIds, updatedRows = 0)

  override fun markUnconfirmed(
    eventIds: List<Long>,
    claimToken: String,
    lastError: String,
  ): TelemetryOutboxSettlementResult = TelemetryOutboxSettlementResult.forRequest(eventIds, updatedRows = 0)

  override fun clear(): Int {
    val count = rows.size
    rows.clear()
    return count
  }
}

private class LeveledMutationTelemetrySettingsProvider(private val level: String) : TelemetrySettingsProvider {
  override fun load(materialize: Boolean): TelemetrySettings =
    TelemetrySettings(
      configPath = Path.of("/fake/config.json").toFileLocation(),
      level = level,
      enabled = level != "off",
      installId = "existing",
      proxyUrl = "",
      customProxyUrl = null,
      batchSize = 50,
    )

  override fun loadOrUnavailable(materialize: Boolean): TelemetrySettingsLoad =
    TelemetrySettingsLoad.Loaded(load(materialize))
}

private object DisabledMutationTelemetrySettingsProvider : TelemetrySettingsProvider {
  override fun load(materialize: Boolean): TelemetrySettings =
    TelemetrySettings(
      configPath = Path.of("/fake/config.json").toFileLocation(),
      level = "off",
      enabled = false,
      installId = "",
      proxyUrl = "",
      customProxyUrl = null,
      batchSize = 50,
    )

  override fun loadOrUnavailable(materialize: Boolean): TelemetrySettingsLoad =
    TelemetrySettingsLoad.Loaded(load(materialize))
}

private class FakeMutationTelemetryConfigStore : TelemetryConfigStore {
  var document: TelemetryConfigDocument =
    TelemetryConfigDocument(
      TelemetryOpenDocument.from(
        mapOf(
          "install_id" to "existing",
          "telemetry" to mapOf("level" to "anonymous", "proxy_url" to "", "batch_size" to 50),
        ),
      ),
    )

  override fun stateDir(): Path = Path.of("/fake")

  override fun configPath(): Path = Path.of("/fake/config.json")

  override fun read(): TelemetryConfigRead = TelemetryConfigRead.Present(document)

  override fun ensure(): TelemetryConfigDocument = document

  override fun write(document: TelemetryConfigDocument) {
    this.document = document
  }
}
