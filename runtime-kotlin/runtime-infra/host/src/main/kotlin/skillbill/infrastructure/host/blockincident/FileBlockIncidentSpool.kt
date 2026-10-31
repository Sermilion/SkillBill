package skillbill.infrastructure.host.blockincident

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.JsonCodec
import skillbill.error.blockincident.BlockIncidentFailureCode
import skillbill.ports.blockincident.BlockIncidentSpool
import skillbill.ports.blockincident.model.BlockIncidentSpoolAppend
import skillbill.ports.blockincident.model.BlockIncidentSpoolEntry
import skillbill.ports.blockincident.model.BlockIncidentSpoolRecord
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.telemetry.transport.TelemetryConfigStore
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentAlias
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentAliasKind
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentOrigin
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.DateTimeException
import java.time.Instant
import skillbill.contracts.workflow.identity.blockincident.BlockIncidentSpoolKeys as Keys

@Inject
class FileBlockIncidentSpool(
  private val state: TelemetryConfigStore,
  private val diagnostics: RuntimeDiagnostics,
) : BlockIncidentSpool {
  override fun append(record: BlockIncidentSpoolRecord): BlockIncidentSpoolAppend =
    try {
      val directory = directory()
      Files.createDirectories(directory)
      val fileName = "%020d_%s.json".format(record.observedAt.toEpochMilli(), record.occurrenceToken)
      val target = directory.resolve(fileName)
      if (!Files.exists(target) && fileCount(directory) >= MAX_RECORDS) return BlockIncidentSpoolAppend.DroppedFull
      var bounded = record
      var payload = encode(bounded)
      while (payload.toByteArray(Charsets.UTF_8).size > MAX_RECORD_BYTES && bounded.reason.isNotEmpty()) {
        bounded = bounded.copy(reason = bounded.reason.dropLast(256.coerceAtMost(bounded.reason.length)))
        payload = encode(bounded)
      }
      if (payload.toByteArray(Charsets.UTF_8).size > MAX_RECORD_BYTES) {
        return BlockIncidentSpoolAppend.Failed("Encoded spool record exceeds $MAX_RECORD_BYTES bytes.")
      }
      val temporary = Files.createTempFile(directory, ".block-incident-", ".tmp")
      try {
        Files.writeString(temporary, payload)
        try {
          Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
          Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
        }
      } finally {
        Files.deleteIfExists(temporary)
      }
      BlockIncidentSpoolAppend.Appended(fileName.removeSuffix(".json"))
    } catch (failure: IOException) {
      appendFailed(failure)
    } catch (failure: UncheckedIOException) {
      appendFailed(failure)
    }

  private fun appendFailed(failure: Exception): BlockIncidentSpoolAppend {
    diagnostics.warning("Block incident spool append failed.", failure)
    return BlockIncidentSpoolAppend.Failed(failure.message.orEmpty())
  }

  override fun pending(limit: Int): List<BlockIncidentSpoolEntry> {
    require(limit > 0)
    return try {
      val directory = directory()
      if (!Files.isDirectory(directory)) return emptyList()
      Files.list(directory).use { paths ->
        paths.filter { it.fileName.toString().endsWith(".json") }
          .sorted()
          .toList()
      }.asSequence()
        .mapNotNull { path -> path.toEntry() }
        .take(limit)
        .toList()
    } catch (failure: IOException) {
      readFailed(failure)
    } catch (failure: UncheckedIOException) {
      readFailed(failure)
    }
  }

  private fun readFailed(failure: Exception): List<BlockIncidentSpoolEntry> {
    diagnostics.warning("Block incident spool read failed.", failure)
    return emptyList()
  }

  override fun remove(recordId: String) {
    Files.deleteIfExists(directory().resolve("$recordId.json"))
  }

  private fun Path.toEntry(): BlockIncidentSpoolEntry? =
    try {
      parseEntry()
    } catch (failure: IOException) {
      discardUnreadable(failure)
    } catch (failure: IllegalArgumentException) {
      discardUnreadable(failure)
    } catch (failure: NoSuchElementException) {
      discardUnreadable(failure)
    } catch (failure: DateTimeException) {
      discardUnreadable(failure)
    }

  private fun Path.parseEntry(): BlockIncidentSpoolEntry? {
    val id = fileName.toString().removeSuffix(".json")
    val objectValue = JsonCodec.parseObjectOrNull(Files.readString(this)) ?: return null
    val fields = JsonCodec.anyToStringAnyMap(JsonCodec.jsonElementToValue(objectValue)) ?: return null

    fun text(key: String): String? = fields[key] as? String

    fun required(key: String): String = requireNotNull(text(key))

    fun nullableInt(key: String): Int? = (fields[key] as? Number)?.toInt()
    val record =
      BlockIncidentSpoolRecord(
        occurrenceToken = required(Keys.OCCURRENCE_TOKEN),
        alias =
          BlockIncidentAlias(
            BlockIncidentAliasKind.entries.first { it.wireValue == required(Keys.ALIAS_KIND) },
            required(Keys.ALIAS_VALUE),
          ),
        workflowId = text(Keys.WORKFLOW_ID),
        parentWorkflowId = text(Keys.PARENT_WORKFLOW_ID),
        phaseId = text(Keys.PHASE_ID),
        invocationId = text(Keys.INVOCATION_ID),
        attempt = nullableInt(Keys.ATTEMPT),
        origin = BlockIncidentOrigin.entries.first { it.wireValue == required(Keys.ORIGIN) },
        reason = required(Keys.REASON),
        failureCode = text(Keys.FAILURE_CODE),
        failureDisposition = text(Keys.FAILURE_DISPOSITION),
        throwableType = text(Keys.THROWABLE_TYPE),
        exitCode = nullableInt(Keys.EXIT_CODE),
        observedAt = Instant.parse(required(Keys.OBSERVED_AT)),
        captureFailureCode = BlockIncidentFailureCode.valueOf(required(Keys.CAPTURE_FAILURE_CODE)),
        additionalAliases =
          fields[Keys.ADDITIONAL_ALIASES]?.let { value ->
            requireNotNull(JsonCodec.anyToStringAnyMapList(value)).map { alias ->
              BlockIncidentAlias(
                BlockIncidentAliasKind.entries.first { it.wireValue == alias[Keys.ALIAS_KIND] },
                requireNotNull(alias[Keys.ALIAS_VALUE] as? String),
              )
            }
          }.orEmpty(),
      )
    return BlockIncidentSpoolEntry(id, record)
  }

  private fun Path.discardUnreadable(failure: Exception): BlockIncidentSpoolEntry? {
    diagnostics.warning(
      "Block incident evidence availability UNSAVED. " +
        "Unreadable block incident spool record was discarded: $fileName.",
      failure,
    )
    try {
      Files.deleteIfExists(this)
    } catch (cleanup: IOException) {
      diagnostics.warning("Block incident spool could not delete unreadable record $fileName.", cleanup)
    }
    return null
  }

  private fun encode(record: BlockIncidentSpoolRecord): String =
    JsonCodec.mapToJsonString(
      linkedMapOf(
        Keys.OCCURRENCE_TOKEN to record.occurrenceToken,
        Keys.ALIAS_KIND to record.alias.kind.wireValue,
        Keys.ALIAS_VALUE to record.alias.value,
        Keys.WORKFLOW_ID to record.workflowId,
        Keys.PARENT_WORKFLOW_ID to record.parentWorkflowId,
        Keys.PHASE_ID to record.phaseId,
        Keys.INVOCATION_ID to record.invocationId,
        Keys.ATTEMPT to record.attempt,
        Keys.ORIGIN to record.origin.wireValue,
        Keys.REASON to record.reason,
        Keys.FAILURE_CODE to record.failureCode,
        Keys.FAILURE_DISPOSITION to record.failureDisposition,
        Keys.THROWABLE_TYPE to record.throwableType,
        Keys.EXIT_CODE to record.exitCode,
        Keys.OBSERVED_AT to record.observedAt.toString(),
        Keys.CAPTURE_FAILURE_CODE to record.captureFailureCode.name,
        Keys.ADDITIONAL_ALIASES to
          record.additionalAliases.map { alias ->
            mapOf(Keys.ALIAS_KIND to alias.kind.wireValue, Keys.ALIAS_VALUE to alias.value)
          },
      ),
    )

  private fun directory(): Path = state.stateDir().resolve("block-incident-spool")

  private fun fileCount(directory: Path): Long =
    Files.list(directory).use { paths ->
      paths.filter { it.fileName.toString().endsWith(".json") }.count()
    }

  private companion object {
    const val MAX_RECORDS = 64
    const val MAX_RECORD_BYTES = 16 * 1024
  }
}
