package skillbill.infrastructure.workflow.qa

import me.tatarka.inject.annotations.Inject
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.rethrowUnless
import skillbill.error.featuretask.qa.isQaDeviceLeaseFailure
import skillbill.error.featuretask.qa.isQaOwnedResourceFailure
import skillbill.error.featuretask.qa.qaDeviceLeaseReason
import skillbill.error.featuretask.qa.qaOwnedResourceFailed
import skillbill.error.featuretask.qa.qaOwnedResourceReason
import skillbill.infrastructure.workflow.monitor.RecordedProcessLiveness
import skillbill.infrastructure.workflow.monitor.livenessOf
import skillbill.model.EnvironmentContext
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.monitor.MonitorProcessInspector
import skillbill.ports.qa.QaOwnedResourcePort
import skillbill.ports.qa.model.QaOwnedResource
import skillbill.ports.qa.model.QaOwnedResourceKind
import skillbill.ports.qa.model.QaResourceRegistration
import skillbill.ports.qa.model.QaResourceReleaseOutcome
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Clock
import kotlin.io.path.extension

private val TEARDOWN_ORDER =
  listOf(
    QaOwnedResourceKind.PROCESS,
    QaOwnedResourceKind.COMPOSE_PROJECT,
    QaOwnedResourceKind.CONTAINER,
    QaOwnedResourceKind.NETWORK,
    QaOwnedResourceKind.VOLUME,
    QaOwnedResourceKind.DIRECTORY,
  )

private data class StoredRecord(
  val file: Path,
  val record: QaOwnedResourceRecord,
)

class FileSystemQaOwnedResources internal constructor(
  private val inspector: MonitorProcessInspector,
  private val clock: Clock,
  private val diagnostics: RuntimeDiagnostics,
  private val environment: EnvironmentContext,
  private val teardown: QaOwnedResourceTeardown,
) : QaOwnedResourcePort {
  @Inject
  constructor(
    inspector: MonitorProcessInspector,
    clock: Clock,
    diagnostics: RuntimeDiagnostics,
    environment: EnvironmentContext,
  ) : this(inspector, clock, diagnostics, environment, QaOwnedResourceTeardown(ProcessQaResourceCommandRunner))

  private val validator = QaOwnedResourceValidator(environment)

  override fun register(
    repoRoot: Path,
    invocationId: String,
    resource: QaOwnedResource,
  ): QaResourceRegistration =
    ownedResourceFailures {
      val qaRoot = QaPaths.qaRoot(environment, repoRoot)
      val invocationDirectory = QaPaths.invocationDirectory(qaRoot, invocationId).toAbsolutePath().normalize()
      val ownerFile = QaPaths.ownerFile(invocationDirectory)
      val owner =
        readQaRecord(ownerFile)?.let { QaLeaseRecordCodec.decodeOwner(it, ownerFile.toString()) }
          ?: throw qaOwnedResourceFailed("QA invocation '$invocationId' is not running in this repository.")
      val scope = QaResourceScope(repoRoot, qaRoot, invocationDirectory, owner.process.pid)
      val file = QaPaths.resourceFile(qaRoot, resource)
      withResourceLock(qaRoot) {
        val current = readStored(file)?.record
        when {
          current == null -> store(file, invocationId, resource, validator.validate(resource, invocationId, scope))
          current.invocationId == invocationId -> QaResourceRegistration.Registered
          else -> QaResourceRegistration.Conflict(current.invocationId)
        }
      }
    }

  override fun release(
    repoRoot: Path,
    invocationId: String,
    resource: QaOwnedResource,
  ): QaResourceReleaseOutcome =
    ownedResourceFailures {
      val qaRoot = QaPaths.qaRoot(environment, repoRoot)
      val file = QaPaths.resourceFile(qaRoot, resource)
      val stored =
        withResourceLock(qaRoot) { readStored(file) }
          ?.takeIf { it.record.invocationId == invocationId && it.record.resource == resource }
      if (stored == null) QaResourceReleaseOutcome.NotHeld(resource) else tearDownAndDrop(qaRoot, stored)
    }

  override fun releaseAllFor(
    repoRoot: Path,
    invocationId: String,
  ): List<QaResourceReleaseOutcome> =
    ownedResourceFailures {
      val qaRoot = QaPaths.qaRoot(environment, repoRoot)
      storedRecords(qaRoot, reportUnreadable = false)
        .filter { it.record.invocationId == invocationId }
        .let { tearDownInOrder(qaRoot, it) }
    }

  override fun reclaimAbandoned(repoRoot: Path): List<QaResourceReleaseOutcome> =
    ownedResourceFailures {
      val qaRoot = QaPaths.qaRoot(environment, repoRoot)
      storedRecords(qaRoot, reportUnreadable = true)
        .groupBy { it.record.invocationId }
        .filterKeys { isAbandoned(qaRoot, it) }
        .values
        .flatMap { tearDownInOrder(qaRoot, it) }
    }

  private fun store(
    file: Path,
    invocationId: String,
    resource: QaOwnedResource,
    validation: QaResourceValidation,
  ): QaResourceRegistration =
    when (validation) {
      is QaResourceValidation.Invalid -> QaResourceRegistration.Rejected(validation.reason)
      is QaResourceValidation.Valid -> {
        val record = QaOwnedResourceRecord(invocationId, resource, clock.instant(), validation.processStartedAt)
        writeQaRecordAtomically(file, QaOwnedResourceRecordCodec.encode(record))
        QaResourceRegistration.Registered
      }
    }

  private fun isAbandoned(
    qaRoot: Path,
    invocationId: String,
  ): Boolean =
    try {
      val ownerFile = QaPaths.ownerFile(QaPaths.invocationDirectory(qaRoot, invocationId))
      val owner = readQaRecord(ownerFile)?.let { QaLeaseRecordCodec.decodeOwner(it, ownerFile.toString()) }
      owner == null || inspector.livenessOf(owner.process) == RecordedProcessLiveness.DEAD
    } catch (failure: SkillBillRuntimeException) {
      failure.rethrowUnless(failure.isQaDeviceLeaseFailure)
      warnSkipped("owner of an invocation")
      false
    }

  private fun tearDownInOrder(
    qaRoot: Path,
    records: List<StoredRecord>,
  ): List<QaResourceReleaseOutcome> =
    records
      .sortedWith(compareBy({ TEARDOWN_ORDER.indexOf(it.record.resource.kind) }, { it.record.resource.identity }))
      .map { tearDownAndDrop(qaRoot, it) }

  private fun tearDownAndDrop(
    qaRoot: Path,
    stored: StoredRecord,
  ): QaResourceReleaseOutcome {
    val resource = stored.record.resource
    val workingDirectory = QaPaths.resourcesDirectory(qaRoot)
    return when (val result = teardown.tearDown(resource, stored.record.processStartedAt, workingDirectory)) {
      is QaTeardownResult.Retained -> QaResourceReleaseOutcome.Failed(resource, result.detail)
      QaTeardownResult.Removed ->
        try {
          dropIfUnchanged(qaRoot, stored)
          QaResourceReleaseOutcome.Released(resource)
        } catch (failure: SkillBillRuntimeException) {
          failure.rethrowUnless(failure.isQaOwnedResourceFailure)
          QaResourceReleaseOutcome.Failed(resource, failure.qaOwnedResourceReason)
        }
    }
  }

  private fun dropIfUnchanged(
    qaRoot: Path,
    stored: StoredRecord,
  ) {
    withResourceLock(qaRoot) {
      val current = readStored(stored.file)?.record
      val unchanged =
        current != null &&
          current.invocationId == stored.record.invocationId &&
          current.registeredAt == stored.record.registeredAt
      if (unchanged) {
        try {
          Files.deleteIfExists(stored.file)
        } catch (failure: IOException) {
          throw qaOwnedResourceFailed("could not delete ${stored.file.fileName}: ${failure.message}", failure)
        }
      }
    }
  }

  private fun storedRecords(
    qaRoot: Path,
    reportUnreadable: Boolean,
  ): List<StoredRecord> {
    val directory = QaPaths.resourcesDirectory(qaRoot)
    if (!Files.isDirectory(directory)) return emptyList()
    return withResourceLock(qaRoot) {
      recordFiles(directory).mapNotNull { file ->
        try {
          readStored(file)
        } catch (failure: SkillBillRuntimeException) {
          failure.rethrowUnless(failure.isQaOwnedResourceFailure)
          if (reportUnreadable) warnSkipped(file.fileName.toString())
          null
        }
      }
    }
  }

  private fun readStored(file: Path): StoredRecord? =
    ownedResourceFailures {
      readQaRecord(file)?.let { StoredRecord(file, QaOwnedResourceRecordCodec.decode(it, file.fileName.toString())) }
    }

  private fun warnSkipped(record: String) {
    diagnostics.warning(
      "qa owned resource record unreadable: seam=qa_reconcile value_expected=valid_record " +
        "value_used=skipped record=$record",
    )
  }

  private fun recordFiles(directory: Path): List<Path> =
    try {
      Files.list(directory).use { entries -> entries.filter { it.extension == "json" }.sorted().toList() }
    } catch (failure: IOException) {
      throw qaOwnedResourceFailed("could not list $directory: ${failure.message}", failure)
    }

  private fun <T> withResourceLock(
    qaRoot: Path,
    body: () -> T,
  ): T {
    val directory = QaPaths.resourcesDirectory(qaRoot)
    val channel =
      try {
        Files.createDirectories(directory)
        FileChannel.open(directory.resolve("resources.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
      } catch (failure: IOException) {
        throw qaOwnedResourceFailed("could not lock $directory: ${failure.message}", failure)
      }
    return channel.use {
      val lock =
        try {
          it.lock()
        } catch (failure: IOException) {
          throw qaOwnedResourceFailed("could not lock $directory: ${failure.message}", failure)
        }
      lock.use { body() }
    }
  }

  private inline fun <T> ownedResourceFailures(body: () -> T): T =
    try {
      body()
    } catch (failure: SkillBillRuntimeException) {
      failure.rethrowUnless(failure.isQaDeviceLeaseFailure)
      throw qaOwnedResourceFailed(failure.qaDeviceLeaseReason, failure)
    }
}
