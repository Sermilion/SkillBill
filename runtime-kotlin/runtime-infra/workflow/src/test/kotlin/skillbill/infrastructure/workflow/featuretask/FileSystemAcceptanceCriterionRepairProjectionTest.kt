package skillbill.infrastructure.workflow.featuretask

import org.junit.jupiter.api.io.TempDir
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.taskruntime.model.AcceptanceCriterionRepairProjectionRequest
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairAdmission
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairCriterion
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairState
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionSourceDisposition
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionValidationObligation
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FileSystemAcceptanceCriterionRepairProjectionTest {
  @TempDir lateinit var root: Path

  @Test
  fun `older recovery and conflicting same revision cannot replace an accepted projection`() {
    val projector = FileSystemAcceptanceCriterionRepairProjection(NoopRuntimeDiagnostics)
    val initial = state()
    val newer = assertNotNull(initial.reserve("reservation-1"))
    projector.write(request(newer))
    val target = target()
    val accepted = Files.readString(target)
    assertFailsWith<IOException> { projector.write(request(initial)) }
    assertEquals(accepted, Files.readString(target))
    projector.write(request(newer))
    assertEquals(accepted, Files.readString(target))
    val conflicting = assertNotNull(initial.reserve("reservation-2"))
    assertFailsWith<IOException> { projector.write(request(conflicting)) }
    assertEquals(accepted, Files.readString(target))
    assertNoTemporaryFiles()
  }

  @Test
  fun `malformed cached projection regenerates from committed state with a recovery record`() {
    val warnings = mutableListOf<String>()
    val diagnostics =
      object : RuntimeDiagnostics by NoopRuntimeDiagnostics {
        override fun warning(
          message: String,
          error: Throwable?,
        ) {
          warnings += message
        }
      }
    val projector = FileSystemAcceptanceCriterionRepairProjection(diagnostics)
    val committed = state()
    projector.write(request(committed))
    val target = target()
    val accepted = Files.readString(target)
    Files.writeString(target, "{")
    projector.write(request(committed))
    assertEquals(accepted, Files.readString(target))
    assertTrue(warnings.any { "record_kind=recovery" in it })
    assertNoTemporaryFiles()
  }

  @Test
  fun `local projection lock contention reports recoverable IO failure without leaking a temporary file`() {
    val projector = FileSystemAcceptanceCriterionRepairProjection(NoopRuntimeDiagnostics)
    val committed = state()
    projector.write(request(committed))
    val target = target()
    val accepted = Files.readString(target)
    val lock = target.resolveSibling(target.fileName.toString().removeSuffix(".json") + ".lock")
    FileChannel.open(lock, StandardOpenOption.WRITE).use { channel ->
      channel.lock().use {
        assertFailsWith<IOException> { projector.write(request(committed)) }
        assertEquals(accepted, Files.readString(target))
        assertNoTemporaryFiles()
      }
    }
    projector.write(request(committed))
    assertEquals(accepted, Files.readString(target))
  }

  private fun state(): AcceptanceCriterionRepairState =
    assertNotNull(
      AcceptanceCriterionRepairState.admit(
        AcceptanceCriterionRepairAdmission(
          "catalog-1",
          "workflow-1",
          "a".repeat(64),
          listOf("first"),
          listOf(
            AcceptanceCriterionRepairCriterion(
              "AC-001",
              null,
              "first",
              1,
              AcceptanceCriterionSourceDisposition.SOURCE_UNRESOLVED,
              AcceptanceCriterionValidationObligation.NOT_REQUIRED,
              true,
              "discovery:1",
            ),
          ),
          emptyList(),
        ),
      ),
    )

  private fun request(state: AcceptanceCriterionRepairState) =
    AcceptanceCriterionRepairProjectionRequest(root, "workflow-1", state)

  private fun target(): Path =
    Files.list(root.resolve(".skill-bill/acceptance-repair")).use { files ->
      files.filter { it.fileName.toString().endsWith(".json") }.findFirst().orElseThrow()
    }

  private fun assertNoTemporaryFiles() =
    Files.list(root.resolve(".skill-bill/acceptance-repair")).use { files ->
      assertEquals(0L, files.filter { it.fileName.toString().endsWith(".tmp") }.count())
    }
}
