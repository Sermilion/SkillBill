package skillbill.infrastructure.sqlite

import skillbill.contracts.persistence.DesktopReadSchema
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OperatorApprovalMigrationTest {
  @Test
  fun `operator approval migration creates durable request and exact-scope rule tables`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-approval-store").resolve("runtime.db")

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      assertEquals(DesktopReadSchema.VERSION, scalarInt(connection, "PRAGMA user_version"))
      assertTrue(tableExists(connection, "approval_requests"))
      assertTrue(tableExists(connection, "approval_rules"))
      assertTrue(
        columnNames(connection, "approval_requests").containsAll(
          listOf(
            "record_json",
            "dispatch_claimed_at",
            "delivery_state",
            "action_class",
            "write_capable",
            "input_complete",
            "provider_disposition",
          ),
        ),
      )
      assertTrue(
        columnNames(connection, "approval_rules").containsAll(
          listOf("profile_identity", "protocol_revision", "working_directory", "action_fingerprint", "revoked_at"),
        ),
      )
      assertTrue(
        columnNames(connection, "approval_owners").containsAll(listOf("nonce", "execution_mode", "expires_at")),
      )
      assertTrue(columnNames(connection, "approval_consumers").containsAll(listOf("nonce", "expires_at")))
    }
  }
}
