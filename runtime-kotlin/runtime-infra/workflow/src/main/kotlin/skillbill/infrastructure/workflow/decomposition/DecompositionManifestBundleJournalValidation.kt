package skillbill.infrastructure.workflow.decomposition

import skillbill.contracts.SharedPayloadKeys
import skillbill.error.shellcontent.WorkflowFailureCode
import skillbill.error.shellcontent.invalidDecompositionManifestBundleJournal
import skillbill.infrastructure.contracts.sha256Hex
import skillbill.infrastructure.contracts.workflow.decomposition.DecompositionManifestBundleJournalSchemaValidator
import skillbill.infrastructure.host.jvm.pathContainedIn
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

internal object DecompositionManifestBundleJournalValidation {
  fun readValidated(marker: Path): DecompositionManifestBundleTransaction {
    return try {
      val sourceLabel = marker.toString()
      val yamlText = Files.readString(marker)
      val parsed = DecompositionManifestBundleJournalSchemaValidator.validateYamlText(yamlText, sourceLabel)
      val markerParent = requireNotNull(marker.parent).toAbsolutePath().normalize()
      val transactionId = requireTransactionId(marker, sourceLabel)
      val contractVersion = parsed[SharedPayloadKeys.CONTRACT_VERSION] as String
      validateLegacyProjection(parsed, contractVersion, sourceLabel)
      val stagingDirectory = stagingDirectory(parsed)
      validateStagingDirectory(stagingDirectory, markerParent, transactionId, sourceLabel)
      val entries = parseEntries(parsed, contractVersion, sourceLabel, stagingDirectory, markerParent)
      val protectedFiles = parseProtectedFiles(parsed, contractVersion, sourceLabel, markerParent)
      val transaction =
        DecompositionManifestBundleTransaction(marker, stagingDirectory, entries, contractVersion, protectedFiles)
      validateTransaction(transaction)
      transaction
    } catch (error: IOException) {
      throw journalError(
        marker.toString(),
        error.message ?: "Journal could not be read.",
        WorkflowFailureCode.DECOMPOSITION_MANIFEST_BUNDLE_JOURNAL_JOURNAL_READ_ERROR,
        error,
      )
    } catch (error: SecurityException) {
      throw journalError(
        marker.toString(),
        error.message ?: "Journal could not be read or validated.",
        WorkflowFailureCode.DECOMPOSITION_MANIFEST_BUNDLE_JOURNAL_JOURNAL_READ_ERROR,
        error,
      )
    }
  }

  private fun validateLegacyProjection(
    parsed: Map<String, Any?>,
    contractVersion: String,
    sourceLabel: String,
  ) {
    if (contractVersion == DecompositionManifestBundleJournal.LEGACY_BUNDLE_CONTRACT_VERSION &&
      DecompositionManifestBundleJournalPayloadKeys.PROTECTED_FILES in parsed
    ) {
      throw journalError(
        sourceLabel,
        "Legacy journal markers cannot claim guarded projection identities.",
        WorkflowFailureCode.DECOMPOSITION_MANIFEST_BUNDLE_JOURNAL_SCHEMA_INVALID,
      )
    }
  }

  fun validateTransaction(transaction: DecompositionManifestBundleTransaction) {
    val sourceLabel = transaction.marker.toString()
    val markerParent = requireNotNull(transaction.marker.parent).toAbsolutePath().normalize()
    val transactionId = requireTransactionId(transaction.marker, sourceLabel)
    val stagingDirectory = transaction.stagingDirectory.toAbsolutePath().normalize()
    validateStagingDirectory(stagingDirectory, markerParent, transactionId, sourceLabel)
    transaction.entries.forEach { entry -> validateEntryPaths(entry, markerParent, stagingDirectory, sourceLabel) }
    transaction.protectedFiles.forEach { file ->
      if (file.path.parent != markerParent || !pathContainedIn(file.path, markerParent)) {
        throw journalError(
          sourceLabel,
          "Protected file '${file.path}' is outside the bundle parent.",
          WorkflowFailureCode.DECOMPOSITION_MANIFEST_BUNDLE_JOURNAL_TARGET_OUTSIDE_PARENT,
        )
      }
    }
    if (transaction.protectedFiles.map { it.path }.distinct().size != transaction.protectedFiles.size) {
      throw journalError(
        sourceLabel,
        "Protected file identities repeat a normalized path.",
        WorkflowFailureCode.DECOMPOSITION_MANIFEST_BUNDLE_JOURNAL_DUPLICATE_TARGET,
      )
    }
    validateUniqueTargets(transaction.entries, sourceLabel)
    validateUniqueStagedPaths(transaction.entries, sourceLabel)
    validateEntryDigests(transaction)
  }

  fun validateProjectionPreconditions(transaction: DecompositionManifestBundleTransaction) {
    if (transaction.contractVersion != DecompositionManifestBundleJournal.BUNDLE_CONTRACT_VERSION) return
    val sourceLabel = transaction.marker.toString()
    transaction.protectedFiles.forEach { file ->
      if (currentDigest(file.path) != file.sha256) {
        throw journalError(
          sourceLabel,
          "Protected file '${file.path}' changed while a bundle projection was pending.",
          WorkflowFailureCode.DECOMPOSITION_MANIFEST_BUNDLE_JOURNAL_TARGET_DIGEST_MISMATCH,
        )
      }
    }
    transaction.entries.forEach { entry ->
      val current = currentDigest(entry.target)
      val before = if (entry.beforeExisted == true) entry.beforeSha256 else null
      if (current != before && current != entry.sha256) {
        throw journalError(
          sourceLabel,
          "Target '${entry.target}' matches neither its recorded before nor intended after identity.",
          WorkflowFailureCode.DECOMPOSITION_MANIFEST_BUNDLE_JOURNAL_TARGET_DIGEST_MISMATCH,
        )
      }
    }
  }

  private fun currentDigest(path: Path): String? =
    if (Files.isRegularFile(path)) sha256Hex(Files.readString(path).toByteArray(Charsets.UTF_8)) else null

  private fun parseEntries(
    parsed: Map<String, Any?>,
    contractVersion: String,
    sourceLabel: String,
    stagingDirectory: Path,
    markerParent: Path,
  ): List<DecompositionManifestBundleEntry> {
    val rawEntries =
      parsed[DecompositionManifestBundleJournalPayloadKeys.ENTRIES] as? List<*>
        ?: throw journalError(
          sourceLabel,
          "entries must be a non-empty array.",
          WorkflowFailureCode.DECOMPOSITION_MANIFEST_BUNDLE_JOURNAL_ENTRIES_MISSING,
        )
    return rawEntries.map { rawEntry ->
      parseEntry(rawEntry, contractVersion, sourceLabel, stagingDirectory, markerParent)
    }
  }

  private fun parseEntry(
    rawEntry: Any?,
    contractVersion: String,
    sourceLabel: String,
    stagingDirectory: Path,
    markerParent: Path,
  ): DecompositionManifestBundleEntry {
    val entry =
      rawEntry as? Map<*, *>
        ?: throw journalError(
          sourceLabel,
          "Malformed bundle journal entry.",
          WorkflowFailureCode.DECOMPOSITION_MANIFEST_BUNDLE_JOURNAL_ENTRY_NOT_OBJECT,
        )
    val target =
      pathValue(
        entry[DecompositionManifestBundleJournalPayloadKeys.TARGET],
        sourceLabel,
        DecompositionManifestBundleJournalPayloadKeys.TARGET,
      ).toAbsolutePath().normalize()
    val staged =
      pathValue(
        entry[DecompositionManifestBundleJournalPayloadKeys.STAGED],
        sourceLabel,
        DecompositionManifestBundleJournalPayloadKeys.STAGED,
      ).toAbsolutePath().normalize()
    val sha256 =
      entry[DecompositionManifestBundleJournalPayloadKeys.SHA256] as? String
        ?: throw journalError(
          sourceLabel,
          "Journal entry field '${DecompositionManifestBundleJournalPayloadKeys.SHA256}' is missing or not a string.",
          WorkflowFailureCode.DECOMPOSITION_MANIFEST_BUNDLE_JOURNAL_ENTRY_FIELD_INVALID,
        )
    val beforeExists = entry[DecompositionManifestBundleJournalPayloadKeys.BEFORE_EXISTS] as? Boolean
    val beforeSha = entry[DecompositionManifestBundleJournalPayloadKeys.BEFORE_SHA256] as? String
    validateBeforeIdentity(contractVersion, beforeExists, beforeSha, sourceLabel)
    validateEntryPaths(
      DecompositionManifestBundleEntry(target, staged, sha256, beforeExists, beforeSha),
      markerParent,
      stagingDirectory,
      sourceLabel,
    )
    return DecompositionManifestBundleEntry(target, staged, sha256, beforeExists, beforeSha)
  }

  private fun validateBeforeIdentity(
    contractVersion: String,
    beforeExists: Boolean?,
    beforeSha: String?,
    sourceLabel: String,
  ) {
    if (contractVersion == DecompositionManifestBundleJournal.BUNDLE_CONTRACT_VERSION &&
      (beforeExists == null || (beforeExists != (beforeSha != null)))
    ) {
      throw journalError(
        sourceLabel,
        "Guarded journal entry is missing a consistent before identity.",
        WorkflowFailureCode.DECOMPOSITION_MANIFEST_BUNDLE_JOURNAL_ENTRY_FIELD_INVALID,
      )
    }
    if (contractVersion == DecompositionManifestBundleJournal.BUNDLE_CONTRACT_VERSION &&
      beforeSha?.matches(Regex("[0-9a-f]{64}")) == false
    ) {
      throw journalError(
        sourceLabel,
        "Guarded journal entry has an invalid before digest.",
        WorkflowFailureCode.DECOMPOSITION_MANIFEST_BUNDLE_JOURNAL_ENTRY_FIELD_INVALID,
      )
    }
  }

  private fun parseProtectedFiles(
    parsed: Map<String, Any?>,
    contractVersion: String,
    sourceLabel: String,
    markerParent: Path,
  ): List<DecompositionManifestBundleProtectedFile> {
    val raw = parsed[DecompositionManifestBundleJournalPayloadKeys.PROTECTED_FILES] as? List<*>
    if (contractVersion == DecompositionManifestBundleJournal.BUNDLE_CONTRACT_VERSION && raw == null) {
      throw journalError(
        sourceLabel,
        "Guarded journal marker is missing protected file identities.",
        WorkflowFailureCode.DECOMPOSITION_MANIFEST_BUNDLE_JOURNAL_ENTRY_FIELD_INVALID,
      )
    }
    return raw.orEmpty().map { value ->
      parseProtectedFile(value, sourceLabel, markerParent)
    }
  }

  private fun parseProtectedFile(
    value: Any?,
    sourceLabel: String,
    markerParent: Path,
  ): DecompositionManifestBundleProtectedFile {
    val file =
      value as? Map<*, *> ?: throw journalError(
        sourceLabel,
        "Malformed protected file identity.",
        WorkflowFailureCode.DECOMPOSITION_MANIFEST_BUNDLE_JOURNAL_ENTRY_NOT_OBJECT,
      )
    val path =
      pathValue(
        file[DecompositionManifestBundleJournalPayloadKeys.PATH],
        sourceLabel,
        DecompositionManifestBundleJournalPayloadKeys.PATH,
      ).toAbsolutePath().normalize()
    val digest = file[DecompositionManifestBundleJournalPayloadKeys.SHA256] as? String
    if (path.parent != markerParent || digest?.matches(Regex("[0-9a-f]{64}")) == false) {
      throw journalError(
        sourceLabel,
        "Protected file identity is outside the bundle or has an invalid digest.",
        WorkflowFailureCode.DECOMPOSITION_MANIFEST_BUNDLE_JOURNAL_TARGET_OUTSIDE_PARENT,
      )
    }
    return DecompositionManifestBundleProtectedFile(path, digest)
  }

  private fun validateEntryPaths(
    entry: DecompositionManifestBundleEntry,
    markerParent: Path,
    stagingDirectory: Path,
    sourceLabel: String,
  ) {
    validateTargetPath(entry.target, markerParent, sourceLabel)
    validateStagedPath(entry.staged, stagingDirectory, sourceLabel)
  }

  private fun validateTargetPath(
    target: Path,
    markerParent: Path,
    sourceLabel: String,
  ) {
    if (target.parent != markerParent) {
      throw journalError(
        sourceLabel,
        "Journal target '$target' is outside the marker parent.",
        WorkflowFailureCode.DECOMPOSITION_MANIFEST_BUNDLE_JOURNAL_TARGET_OUTSIDE_PARENT,
      )
    }
    if (!pathContainedIn(target, markerParent)) {
      throw journalError(
        sourceLabel,
        "Journal target '$target' escapes the marker parent.",
        WorkflowFailureCode.DECOMPOSITION_MANIFEST_BUNDLE_JOURNAL_TARGET_ESCAPE,
      )
    }
  }

  private fun validateStagedPath(
    staged: Path,
    stagingDirectory: Path,
    sourceLabel: String,
  ) {
    if (staged.parent != stagingDirectory.toAbsolutePath().normalize()) {
      throw journalError(
        sourceLabel,
        "Journal staged path '$staged' is outside staging_directory.",
        WorkflowFailureCode.DECOMPOSITION_MANIFEST_BUNDLE_JOURNAL_STAGED_OUTSIDE_STAGING,
      )
    }
    if (!pathContainedIn(staged, stagingDirectory)) {
      throw journalError(
        sourceLabel,
        "Journal staged path '$staged' escapes staging_directory.",
        WorkflowFailureCode.DECOMPOSITION_MANIFEST_BUNDLE_JOURNAL_STAGED_ESCAPE,
      )
    }
  }

  private fun pathValue(
    value: Any?,
    sourceLabel: String,
    fieldName: String,
  ): Path {
    val raw =
      value as? String
        ?: throw journalError(
          sourceLabel,
          "Journal entry field '$fieldName' is missing or not a string.",
          WorkflowFailureCode.DECOMPOSITION_MANIFEST_BUNDLE_JOURNAL_ENTRY_FIELD_INVALID,
        )
    return try {
      Path.of(raw)
    } catch (error: InvalidPathException) {
      throw journalError(
        sourceLabel,
        "Journal entry field '$fieldName' is not a valid path.",
        WorkflowFailureCode.DECOMPOSITION_MANIFEST_BUNDLE_JOURNAL_ENTRY_PATH_INVALID,
        error,
      )
    }
  }

  private fun validateUniqueTargets(
    entries: List<DecompositionManifestBundleEntry>,
    sourceLabel: String,
  ) {
    val normalizedTargets = entries.map { it.target }
    if (normalizedTargets.distinct().size != normalizedTargets.size) {
      throw journalError(
        sourceLabel,
        "Journal entries repeat the same normalized target path.",
        WorkflowFailureCode.DECOMPOSITION_MANIFEST_BUNDLE_JOURNAL_DUPLICATE_TARGET,
      )
    }
  }

  private fun validateUniqueStagedPaths(
    entries: List<DecompositionManifestBundleEntry>,
    sourceLabel: String,
  ) {
    val normalizedStagedPaths = entries.map { it.staged }
    if (normalizedStagedPaths.distinct().size != normalizedStagedPaths.size) {
      throw journalError(
        sourceLabel,
        "Journal entries repeat the same normalized staged path.",
        WorkflowFailureCode.DECOMPOSITION_MANIFEST_BUNDLE_JOURNAL_DUPLICATE_STAGED,
      )
    }
  }

  fun validateEntryDigests(transaction: DecompositionManifestBundleTransaction) {
    val sourceLabel = transaction.marker.toString()
    transaction.entries.forEach { entry -> validateEntryDigest(entry, sourceLabel) }
  }

  private fun validateEntryDigest(
    entry: DecompositionManifestBundleEntry,
    sourceLabel: String,
  ) {
    when {
      Files.isRegularFile(entry.staged) -> validateStagedDigest(entry, sourceLabel)
      Files.isRegularFile(entry.target) -> validateTargetDigest(entry, sourceLabel)
      else -> throw journalError(
        sourceLabel,
        "Bundle journal entry for '${entry.target}' is incomplete; neither staged nor target bytes are present.",
        WorkflowFailureCode.DECOMPOSITION_MANIFEST_BUNDLE_JOURNAL_ENTRY_INCOMPLETE,
      )
    }
  }

  private fun validateStagedDigest(
    entry: DecompositionManifestBundleEntry,
    sourceLabel: String,
  ) {
    val digest = sha256Hex(Files.readString(entry.staged).toByteArray(Charsets.UTF_8))
    if (digest != entry.sha256) {
      throw journalError(
        sourceLabel,
        "Staged file '${entry.staged}' does not match the recorded digest for '${entry.target}'.",
        WorkflowFailureCode.DECOMPOSITION_MANIFEST_BUNDLE_JOURNAL_STAGED_DIGEST_MISMATCH,
      )
    }
  }

  private fun validateTargetDigest(
    entry: DecompositionManifestBundleEntry,
    sourceLabel: String,
  ) {
    val digest = sha256Hex(Files.readString(entry.target).toByteArray(Charsets.UTF_8))
    if (digest != entry.sha256) {
      throw journalError(
        sourceLabel,
        "Applied target '${entry.target}' does not match the recorded digest.",
        WorkflowFailureCode.DECOMPOSITION_MANIFEST_BUNDLE_JOURNAL_TARGET_DIGEST_MISMATCH,
      )
    }
  }

  private fun requireTransactionId(
    marker: Path,
    sourceLabel: String,
  ): String =
    transactionIdFromMarker(marker) ?: throw journalError(
      sourceLabel,
      "Marker file name does not match the bundle journal naming contract.",
      WorkflowFailureCode.DECOMPOSITION_MANIFEST_BUNDLE_JOURNAL_INVALID_MARKER_NAME,
    )

  private fun stagingDirectory(parsed: Map<String, Any?>): Path =
    Path.of(
      requireNotNull(parsed[DecompositionManifestBundleJournalPayloadKeys.STAGING_DIRECTORY] as? String),
    )

  private fun validateStagingDirectory(
    stagingDirectory: Path,
    markerParent: Path,
    transactionId: String,
    sourceLabel: String,
  ) {
    val normalized = stagingDirectory.toAbsolutePath().normalize()
    if (normalized != expectedStagingDirectory(markerParent, transactionId)) {
      throw journalError(
        sourceLabel,
        "staging_directory does not match the transaction id encoded in the marker file name.",
        WorkflowFailureCode.DECOMPOSITION_MANIFEST_BUNDLE_JOURNAL_STAGING_DIRECTORY_MISMATCH,
      )
    }
    if (!pathContainedIn(stagingDirectory, markerParent)) {
      throw journalError(
        sourceLabel,
        "staging_directory escapes the marker parent directory.",
        WorkflowFailureCode.DECOMPOSITION_MANIFEST_BUNDLE_JOURNAL_STAGING_DIRECTORY_ESCAPE,
      )
    }
  }

  fun transactionIdFromMarker(marker: Path): String? {
    val name = marker.fileName.toString()
    if (!name.startsWith(DecompositionManifestBundleJournal.BUNDLE_PREFIX) ||
      !name.endsWith(DecompositionManifestBundleJournal.MARKER_SUFFIX)
    ) {
      return null
    }
    return name
      .removePrefix(DecompositionManifestBundleJournal.BUNDLE_PREFIX)
      .removeSuffix(DecompositionManifestBundleJournal.MARKER_SUFFIX)
      .takeIf { it.isNotEmpty() }
  }

  fun expectedStagingDirectory(
    markerParent: Path,
    transactionId: String,
  ): Path =
    markerParent.resolve(
      "${DecompositionManifestBundleJournal.BUNDLE_PREFIX}$transactionId" +
        DecompositionManifestBundleJournal.STAGING_SUFFIX,
    ).toAbsolutePath().normalize()

  private fun journalError(
    sourceLabel: String,
    reason: String,
    code: WorkflowFailureCode,
    cause: Throwable? = null,
  ) = invalidDecompositionManifestBundleJournal(
    sourceLabel = sourceLabel,
    reason = reason,
    code = code,
    cause = cause,
  )
}
