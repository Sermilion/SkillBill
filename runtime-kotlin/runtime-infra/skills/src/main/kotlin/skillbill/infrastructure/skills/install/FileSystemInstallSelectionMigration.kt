package skillbill.infrastructure.skills.install

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.error.core.RuntimeFailureCode
import skillbill.error.core.SkillBillRuntimeException
import skillbill.infrastructure.contracts.sha256Hex
import skillbill.infrastructure.host.jvm.atomicWriteString
import skillbill.ports.diagnostics.RuntimeDiagnostics
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

internal fun migrateRetiredInstallSelection(
  path: Path,
  rawPayload: String,
  diagnostics: RuntimeDiagnostics,
): String {
  val payload =
    JsonCodec.anyToStringAnyMap(JsonCodec.parseObjectOrNull(rawPayload)?.let(JsonCodec::jsonElementToValue))
      ?: return rawPayload
  if (payload[SharedPayloadKeys.CONTRACT_VERSION] != INSTALL_SELECTION_CONTRACT_VERSION) return rawPayload
  val agents = payload.requireStringList(path, InstallSelectionKeys.SELECTED_AGENTS)
  val retained = agents.filterNot { it.trim().lowercase() == RETIRED_INSTALL_AGENT }
  if (agents.size == retained.size) return rawPayload
  val migrated = JsonCodec.mapToJsonString(payload + (InstallSelectionKeys.SELECTED_AGENTS to retained))
  parseInstallSelectionPayload(path, migrated)
  if (retained.isEmpty()) {
    throw malformedInstallSelection(
      path,
      "Saved selection contains only retired agents. Original record is unchanged. " +
        "Run ./install.sh without --reuse-last-selection to select supported agents.",
    )
  }
  val backup = path.resolveSibling("${path.fileName}.before-agent-migration-${sha256Hex(rawPayload)}.json")
  preserveInstallSelection(path, backup, rawPayload)
  diagnostics.warning(
    "seam=install_selection_migration record_kind=migration value_expected=supported_agents " +
      "value_used=retained_supported_agents " +
      "removed_count=${agents.size - retained.size} cause=retired_agent " +
      "Retired agent '$RETIRED_INSTALL_AGENT' omitted from saved selections. Original preserved at '$backup'.",
  )
  return migrated
}

private fun preserveInstallSelection(
  path: Path,
  backup: Path,
  payload: String,
) {
  try {
    if (Files.exists(backup)) {
      validateInstallSelectionBackup(path, backup, payload)
    } else {
      atomicWriteString(backup, payload, strictAtomic = true)
    }
  } catch (error: IOException) {
    throw installSelectionMigrationError(path, backup, error)
  } catch (error: SecurityException) {
    throw installSelectionMigrationError(path, backup, error)
  }
}

private fun validateInstallSelectionBackup(
  path: Path,
  backup: Path,
  payload: String,
) {
  if (Files.readString(backup) != payload) {
    throw installSelectionMigrationError(path, backup)
  }
}

private fun installSelectionMigrationError(
  path: Path,
  backup: Path,
  cause: Throwable? = null,
): SkillBillRuntimeException =
  SkillBillRuntimeException(
    InstallSelectionMigrationFailureCode.BACKUP_FAILED,
    "Cannot preserve install selection '$path' at '$backup'. Original selection is unchanged. " +
      "Inspect the backup and directory access before retrying.",
    cause,
  )

private enum class InstallSelectionMigrationFailureCode : RuntimeFailureCode {
  BACKUP_FAILED,
}

private const val RETIRED_INSTALL_AGENT = "junie"
