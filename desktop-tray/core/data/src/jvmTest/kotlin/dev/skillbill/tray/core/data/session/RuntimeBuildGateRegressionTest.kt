package dev.skillbill.tray.core.data.session

import dev.skillbill.tray.core.common.HostEnvironment
import dev.skillbill.tray.core.data.identity.BuildIdentityRecord
import dev.skillbill.tray.core.data.runtime.InstalledRuntimeBuildGate
import dev.skillbill.tray.core.data.workStack
import dev.skillbill.tray.core.domain.model.Retention
import dev.skillbill.tray.core.domain.model.RuntimeBuildReason
import dev.skillbill.tray.core.domain.model.RuntimeBuildRefusedException
import dev.skillbill.tray.core.domain.model.WorkSection
import dev.skillbill.tray.core.domain.repository.Outcome
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import skillbill.contracts.persistence.DesktopReadSchema
import skillbill.infrastructure.sqlite.establishTemporarySchemaReadiness
import java.nio.file.Files
import java.sql.Connection
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * The 2026-10-19 regression: a desktop opened a store through a runtime of another build, which migrated it. The gate must
 * refuse before any session exists, and a later selection must read the installed record again.
 */
class RuntimeBuildGateRegressionTest {
  private fun record(commit: String) = "commit=$commit\ndirty=false\nrelease_tag=\ninstall_run_id=\n"

  private fun Connection.userVersion(): Int =
    createStatement().use { statement -> statement.executeQuery("PRAGMA user_version").use { rows -> rows.next(); rows.getInt(1) } }

  @Test
  fun `another runtime build is refused before the store can migrate, and a matched reinstall is admitted`() = runBlocking {
    establishTemporarySchemaReadiness("desktop-runtime-build-gate").use { fixture ->
      fixture.connection.createStatement().use { statement ->
        statement.execute("DELETE FROM schema_migrations WHERE version = 56")
        statement.execute("PRAGMA user_version = 55")
      }
      val runtimeRoot = fixture.tempDir.resolve("runtime")
      val installed = Files.createDirectories(runtimeRoot.resolve("runtime-cli")).resolve(BuildIdentityRecord.FILE_NAME)
      Files.writeString(installed, record("aaaa"))
      val appResources = Files.createDirectories(fixture.tempDir.resolve("app-resources"))
      Files.writeString(appResources.resolve(BuildIdentityRecord.FILE_NAME), record("bbbb"))
      val home = fixture.tempDir.toString()
      val gate = InstalledRuntimeBuildGate(
        HostEnvironment(mapOf("SKILL_BILL_RUNTIME_DIR" to runtimeRoot.toString()), home, home, appResources.toString()),
      )
      val source = workStack(fixture.tempDir, gate = gate)
      try {
        val refused = assertFailsWith<RuntimeBuildRefusedException> { source.selectDatabase(fixture.dbPath.toString()) }
        assertEquals(RuntimeBuildReason.MISMATCH, refused.problem.reason)
        assertEquals("commit aaaa", refused.problem.installedIdentity)
        assertEquals("commit bbbb", refused.problem.desktopIdentity)
        assertEquals(0, source.sessionsCreated.get())
        assertEquals(55, fixture.connection.userVersion())

        Files.writeString(installed, record("bbbb"))
        source.selectDatabase(fixture.dbPath.toString())
        assertEquals(1, source.sessionsCreated.get())
        assertIs<Outcome.Value<*>>(source.board(WorkSection.NEEDS_YOU, Retention.DAY))
        assertEquals(DesktopReadSchema.VERSION, fixture.connection.userVersion())
      } finally {
        source.close()
      }
    }
  }
}
