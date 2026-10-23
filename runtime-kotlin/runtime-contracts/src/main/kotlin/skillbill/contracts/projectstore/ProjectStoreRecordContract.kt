package skillbill.contracts.projectstore

const val PROJECT_STORE_RECORD_CONTRACT_VERSION: String = "0.1"

object ProjectStoreRecordKeys {
  const val CONTRACT_VERSION: String = "contract_version"
  const val PROJECT_ID: String = "project_id"
  const val KEY_KIND: String = "key_kind"
  const val COMMON_DIR: String = "common_dir"
  const val ROOT_COMMITS: String = "root_commits"
  const val REMOTE_URL: String = "remote_url"
  const val LAST_SEEN: String = "last_seen"
  const val LINKED_TO: String = "linked_to"
}
