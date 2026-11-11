package skillbill.infrastructure.sqlite

import skillbill.infrastructure.sqlite.workflow.MonitorCiCycleStore
import skillbill.ports.idestatus.model.MonitorCiCheckBucket
import skillbill.ports.idestatus.model.MonitorCiCycleCheck
import skillbill.ports.idestatus.model.MonitorCiCycleOutcome
import skillbill.ports.idestatus.model.MonitorCiCycleRecord
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MonitorCiCycleStoreTest {
  private val factory =
    sqliteDatabaseSessionFactory(
      userHome = Files.createTempDirectory("monitor-ci-cycle-store"),
      environment = emptyMap(),
    )

  @Test
  fun `an upsert replaces its cycle and cycles returns the newest rows ascending`() {
    val pending = MonitorCiCycleCheck("unit", MonitorCiCheckBucket.PENDING, "u", startedAt = at(1))
    val failed = pending.copy(bucket = MonitorCiCheckBucket.FAIL, completedAt = at(3))
    factory.selfManagedWrite { unitOfWork ->
      unitOfWork.monitorCiCycles.upsert(WORKFLOW, record(1, at(0), listOf(pending)))
      unitOfWork.monitorCiCycles.upsert(
        WORKFLOW,
        record(1, at(0), listOf(failed)).copy(outcome = MonitorCiCycleOutcome.FAILED, finishedAt = at(4)),
      )
      unitOfWork.monitorCiCycles.upsert(WORKFLOW, record(2, at(10), listOf(pending)))
      unitOfWork.monitorCiCycles.upsert(WORKFLOW, record(3, at(20), emptyList()))
    }

    factory.read { unitOfWork ->
      val all = unitOfWork.monitorCiCycles.cycles(WORKFLOW, 10)
      assertEquals(listOf(1, 2, 3), all.map { it.cycle })
      assertEquals(listOf(failed), all.first().checks)
      assertEquals(MonitorCiCycleOutcome.FAILED, all.first().outcome)
      assertNull(all[1].checks.single().completedAt)
      assertEquals(listOf(2, 3), unitOfWork.monitorCiCycles.cycles(WORKFLOW, 2).map { it.cycle })
    }
  }

  @Test
  fun `latest follows the newest start after a restarted run rewrites cycle 1`() {
    factory.selfManagedWrite { unitOfWork ->
      (1..3).forEach { cycle ->
        unitOfWork.monitorCiCycles.upsert(WORKFLOW, record(cycle, at(cycle * 10L), emptyList()))
      }
      unitOfWork.monitorCiCycles.upsert(WORKFLOW, record(1, Instant.parse("2026-11-08T11:00:00.5Z"), emptyList()))
    }

    factory.read { unitOfWork ->
      assertEquals(1, unitOfWork.monitorCiCycles.latest(WORKFLOW)?.cycle)
      assertEquals(listOf(2, 3, 1), unitOfWork.monitorCiCycles.cycles(WORKFLOW, 3).map { it.cycle })
    }
  }

  @Test
  fun `an unknown stored outcome reads back as null and a row keeps at most 200 checks`() {
    val dbPath = Files.createTempDirectory("monitor-ci-cycle-store-contract").resolve("store.db")
    val checks = List(201) { index -> MonitorCiCycleCheck("check-$index", MonitorCiCheckBucket.PASS, "l$index") }
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val store = MonitorCiCycleStore(connection)
      store.upsert(WORKFLOW, record(1, at(0), checks).copy(outcome = MonitorCiCycleOutcome.PASSED))
      connection.createStatement().use { statement ->
        statement.executeUpdate("UPDATE monitor_ci_cycles SET outcome = 'retried_elsewhere'")
      }

      val latest = assertNotNull(store.latest(WORKFLOW))
      assertNull(latest.outcome)
      assertEquals(checks.take(200), latest.checks)
    }
  }

  private fun record(
    cycle: Int,
    startedAt: Instant,
    checks: List<MonitorCiCycleCheck>,
  ) = MonitorCiCycleRecord(
    cycle = cycle,
    phaseId = "monitor",
    startedAt = startedAt,
    observedAt = startedAt,
    checks = checks,
  )

  private fun at(seconds: Long): Instant = Instant.parse("2026-11-08T10:00:00Z").plusSeconds(seconds)

  private companion object {
    const val WORKFLOW = "wftr-cycles"
  }
}
