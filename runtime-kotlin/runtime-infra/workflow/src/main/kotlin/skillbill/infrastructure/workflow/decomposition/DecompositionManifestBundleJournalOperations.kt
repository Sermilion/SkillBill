package skillbill.infrastructure.workflow.decomposition

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import skillbill.contracts.SharedPayloadKeys
import skillbill.error.shellcontent.WorkflowFailureCode
import skillbill.error.shellcontent.invalidDecompositionManifestSchema
import skillbill.infrastructure.contracts.sha256Hex
import skillbill.infrastructure.host.jvm.atomicMoveReplacing
import skillbill.ports.workflow.decomposition.model.DecompositionManifestBundleProjection
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

internal object DecompositionManifestBundleJournalRecovery {
  fun recoverPending(
    parent: Path?,
    journal: DecompositionManifestBundleJournal,
  ) {
    withDecompositionManifestBundleLock(parent, journal.hostPlatform) { journal.recoverPendingUnlocked(parent) }
  }

  fun recoverPendingUnlocked(
    parent: Path?,
    journal: DecompositionManifestBundleJournal,
  ) {
    if (parent == null || !Files.isDirectory(parent)) return
    val markerGlob =
      "${DecompositionManifestBundleJournal.BUNDLE_PREFIX}*" +
        "${DecompositionManifestBundleJournal.MARKER_SUFFIX}"
    Files.newDirectoryStream(parent, markerGlob)
      .use { markers ->
        markers.toList().forEach { marker ->
          val transaction = journal.read(marker)
          journal.apply(transaction)
          journal.cleanup(transaction)
        }
      }
  }

  fun failIfPending(parent: Path?) {
    if (parent == null || !Files.isDirectory(parent)) return
    val markerGlob =
      "${DecompositionManifestBundleJournal.BUNDLE_PREFIX}*" +
        "${DecompositionManifestBundleJournal.MARKER_SUFFIX}"
    Files.newDirectoryStream(parent, markerGlob)
      .use { markers ->
        if (markers.iterator().hasNext()) {
          throw invalidDecompositionManifestSchema(
            sourceLabel = parent.toString(),
            reason = "decomposition manifest bundle has an incomplete journal; launch recovery is required.",
            code = WorkflowFailureCode.DECOMPOSITION_MANIFEST_INCOMPLETE_BUNDLE,
          )
        }
      }
  }
}

internal object DecompositionManifestBundleJournalCreate {
  fun create(
    parent: Path,
    writes: List<Pair<Path, String>>,
    projection: DecompositionManifestBundleProjection?,
    yamlMapper: YAMLMapper,
    journal: DecompositionManifestBundleJournal,
  ): DecompositionManifestBundleTransaction {
    val transactionId = UUID.randomUUID().toString()
    val stagingDirectory =
      parent.resolve(
        "${DecompositionManifestBundleJournal.BUNDLE_PREFIX}$transactionId" +
          DecompositionManifestBundleJournal.STAGING_SUFFIX,
      )
    Files.createDirectories(stagingDirectory)
    val entries =
      writes.mapIndexed { index, (path, content) ->
        val target = path.toAbsolutePath().normalize()
        val staged = stagingDirectory.resolve("entry-$index")
        journal.writeAtomically(staged, content)
        val before = projection?.expectedBefore?.firstOrNull { it.path.toAbsolutePath().normalize() == target }
        require(projection == null || before != null) {
          "Guarded bundle writes must declare each target's before identity."
        }
        DecompositionManifestBundleEntry(
          target,
          staged,
          sha256Hex(content.toByteArray(Charsets.UTF_8)),
          beforeExisted = before?.sha256 != null,
          beforeSha256 = before?.sha256,
        )
      }
    val contractVersion =
      if (projection == null) {
        DecompositionManifestBundleJournal.LEGACY_BUNDLE_CONTRACT_VERSION
      } else {
        DecompositionManifestBundleJournal.BUNDLE_CONTRACT_VERSION
      }
    val protectedFiles =
      projection?.protectedFiles.orEmpty().map { identity ->
        DecompositionManifestBundleProtectedFile(identity.path.toAbsolutePath().normalize(), identity.sha256)
      }
    val marker =
      parent.resolve(
        "${DecompositionManifestBundleJournal.BUNDLE_PREFIX}$transactionId" +
          DecompositionManifestBundleJournal.MARKER_SUFFIX,
      )
    val markerPayload = markerPayload(contractVersion, stagingDirectory, entries, projection != null, protectedFiles)
    journal.writeAtomically(marker, yamlMapper.writeValueAsString(markerPayload))
    return DecompositionManifestBundleTransaction(marker, stagingDirectory, entries, contractVersion, protectedFiles)
  }

  private fun markerPayload(
    contractVersion: String,
    stagingDirectory: Path,
    entries: List<DecompositionManifestBundleEntry>,
    guarded: Boolean,
    protectedFiles: List<DecompositionManifestBundleProtectedFile>,
  ): Map<String, Any?> {
    return linkedMapOf<String, Any?>(
      SharedPayloadKeys.CONTRACT_VERSION to contractVersion,
      DecompositionManifestBundleJournalPayloadKeys.STAGING_DIRECTORY to stagingDirectory.toString(),
      DecompositionManifestBundleJournalPayloadKeys.ENTRIES to
        entries.map { entry ->
          linkedMapOf<String, Any?>(
            DecompositionManifestBundleJournalPayloadKeys.TARGET to entry.target.toString(),
            DecompositionManifestBundleJournalPayloadKeys.STAGED to entry.staged.toString(),
            DecompositionManifestBundleJournalPayloadKeys.SHA256 to entry.sha256,
          ).apply {
            if (guarded) {
              put(DecompositionManifestBundleJournalPayloadKeys.BEFORE_EXISTS, entry.beforeExisted)
              put(DecompositionManifestBundleJournalPayloadKeys.BEFORE_SHA256, entry.beforeSha256)
            }
          }
        },
    ).apply {
      if (guarded) {
        put(
          DecompositionManifestBundleJournalPayloadKeys.PROTECTED_FILES,
          protectedFiles.map { file ->
            mapOf(
              DecompositionManifestBundleJournalPayloadKeys.PATH to file.path.toString(),
              DecompositionManifestBundleJournalPayloadKeys.SHA256 to file.sha256,
            )
          },
        )
      }
    }
  }
}

internal object DecompositionManifestBundleJournalIo {
  fun apply(transaction: DecompositionManifestBundleTransaction) {
    DecompositionManifestBundleJournalValidation.validateTransaction(transaction)
    DecompositionManifestBundleJournalValidation.validateProjectionPreconditions(transaction)
    transaction.entries.forEach { entry ->
      when {
        Files.isRegularFile(entry.target) &&
          sha256Hex(Files.readString(entry.target).toByteArray(Charsets.UTF_8)) == entry.sha256 -> Unit
        Files.isRegularFile(entry.staged) -> {
          Files.createDirectories(requireNotNull(entry.target.parent))
          atomicMoveReplacing(entry.staged, entry.target)
        }
        else -> error("Decomposition manifest bundle journal is incomplete for '${entry.target}'.")
      }
    }
  }

  fun cleanup(transaction: DecompositionManifestBundleTransaction) {
    DecompositionManifestBundleJournalValidation.validateTransaction(transaction)
    cleanupValidated(transaction)
  }

  fun cleanupValidated(transaction: DecompositionManifestBundleTransaction) {
    Files.deleteIfExists(transaction.marker)
    deleteRecursively(transaction.stagingDirectory)
  }

  fun read(marker: Path): DecompositionManifestBundleTransaction =
    DecompositionManifestBundleJournalValidation.readValidated(marker)

  private fun deleteRecursively(root: Path) {
    if (!Files.exists(root)) return
    Files.walk(root).use { paths ->
      paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
    }
  }
}
