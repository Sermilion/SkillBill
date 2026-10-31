package skillbill.infrastructure.host.projectstore

import skillbill.contracts.JsonCodec
import skillbill.contracts.projectstore.PROJECT_STORE_RECORD_CONTRACT_VERSION
import skillbill.contracts.projectstore.ProjectStoreRecordKeys
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.projectstore.ProjectStoreFailureCode
import java.nio.file.Path

internal enum class ProjectKeyKind(val wireValue: String) {
  LINEAGE("lineage"),
  NO_COMMITS_PATH("no_commits_path"),
  NON_GIT_PATH("non_git_path"),
}

internal data class ProjectStoreRecord(
  val projectId: String,
  val keyKind: ProjectKeyKind,
  val commonDir: String,
  val rootCommits: List<String>,
  val remoteUrl: String?,
  val lastSeen: String,
  val linkedTo: String? = null,
  val legacyStateNoticeAt: String? = null,
  val sharedCommonDirs: List<String> = emptyList(),
) {
  val checkoutCommonDirs: List<String> get() = listOf(commonDir) + sharedCommonDirs
}

internal data class StoredProject(
  val dir: Path,
  val record: ProjectStoreRecord,
) {
  val id: String = dir.fileName.toString()
}

internal object ProjectStoreRecordCodec {
  fun parse(raw: String): ProjectStoreRecord {
    val payload = objectPayload(raw) ?: throw invalid("parse error: not a JSON object")
    val version = payload[ProjectStoreRecordKeys.CONTRACT_VERSION]
    if (version != PROJECT_STORE_RECORD_CONTRACT_VERSION) throw invalid("contract_version ${version ?: "missing"}")
    return ProjectStoreRecord(
      projectId = payload.requiredString(ProjectStoreRecordKeys.PROJECT_ID),
      keyKind = payload.keyKind(),
      commonDir = payload.requiredString(ProjectStoreRecordKeys.COMMON_DIR),
      rootCommits = payload.rootCommits(),
      remoteUrl = payload[ProjectStoreRecordKeys.REMOTE_URL] as? String,
      lastSeen = payload.requiredString(ProjectStoreRecordKeys.LAST_SEEN),
      linkedTo = payload[ProjectStoreRecordKeys.LINKED_TO] as? String,
      legacyStateNoticeAt = payload[ProjectStoreRecordKeys.LEGACY_STATE_NOTICE_AT] as? String,
      sharedCommonDirs = JsonCodec.anyToStringList(payload[ProjectStoreRecordKeys.SHARED_COMMON_DIRS]).orEmpty(),
    )
  }

  fun encode(record: ProjectStoreRecord): String {
    val payload = LinkedHashMap<String, Any?>()
    payload[ProjectStoreRecordKeys.CONTRACT_VERSION] = PROJECT_STORE_RECORD_CONTRACT_VERSION
    payload[ProjectStoreRecordKeys.PROJECT_ID] = record.projectId
    payload[ProjectStoreRecordKeys.KEY_KIND] = record.keyKind.wireValue
    payload[ProjectStoreRecordKeys.COMMON_DIR] = record.commonDir
    payload[ProjectStoreRecordKeys.ROOT_COMMITS] = record.rootCommits
    payload[ProjectStoreRecordKeys.REMOTE_URL] = record.remoteUrl
    payload[ProjectStoreRecordKeys.LAST_SEEN] = record.lastSeen
    record.linkedTo?.let { payload[ProjectStoreRecordKeys.LINKED_TO] = it }
    record.legacyStateNoticeAt?.let { payload[ProjectStoreRecordKeys.LEGACY_STATE_NOTICE_AT] = it }
    record.sharedCommonDirs.takeIf(List<String>::isNotEmpty)?.let {
      payload[ProjectStoreRecordKeys.SHARED_COMMON_DIRS] = it
    }
    return JsonCodec.mapToJsonString(payload) + "\n"
  }

  private fun objectPayload(raw: String): Map<String, Any?>? =
    JsonCodec.parseObjectOrNull(raw)?.let { JsonCodec.anyToStringAnyMap(JsonCodec.jsonElementToValue(it)) }

  private fun Map<String, Any?>.keyKind(): ProjectKeyKind =
    ProjectKeyKind.entries.firstOrNull { it.wireValue == this[ProjectStoreRecordKeys.KEY_KIND] }
      ?: throw invalid("parse error: unknown key_kind")

  private fun Map<String, Any?>.rootCommits(): List<String> =
    JsonCodec.anyToStringList(this[ProjectStoreRecordKeys.ROOT_COMMITS])
      ?: throw invalid("parse error: root_commits is not a string list")

  private fun Map<String, Any?>.requiredString(key: String): String =
    (this[key] as? String)?.takeIf(String::isNotBlank) ?: throw invalid("parse error: missing $key")

  private fun invalid(reason: String): SkillBillRuntimeException =
    SkillBillRuntimeException(ProjectStoreFailureCode.PROJECT_STORE_RECORD_INVALID, reason)
}
