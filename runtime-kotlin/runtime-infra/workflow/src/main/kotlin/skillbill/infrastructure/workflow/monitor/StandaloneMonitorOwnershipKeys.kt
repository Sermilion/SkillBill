package skillbill.infrastructure.workflow.monitor

import skillbill.error.core.RuntimeFailureCode

enum class StandaloneMonitorFailureCode : RuntimeFailureCode {
  INVALID_OWNERSHIP_SCHEMA,
  UNSUPPORTED_OWNERSHIP_VERSION,
  INCOHERENT_OWNERSHIP,
  OWNERSHIP_IO_FAILED,
  GIT_COMMAND_FAILED,
  FENCE_LOST,
}

object StandaloneMonitorOwnershipKeys {
  const val CONTRACT_VERSION = "contract_version"
  const val TARGET = "target"
  const val TARGET_HOST = "host"
  const val TARGET_REPOSITORY = "repository"
  const val TARGET_NUMBER = "number"
  const val TARGET_URL = "url"
  const val INVOCATION_ID = "invocation_id"
  const val OWNER_TOKEN = "owner_token"
  const val GENERATION = "generation"
  const val PROCESS = "process"
  const val HOST_IDENTITY = "host_identity"
  const val BOOT_IDENTITY = "boot_identity"
  const val PID = "pid"
  const val PROCESS_BIRTH_TOKEN = "process_birth_token"
  const val CALLER_ROOT = "caller_root"
  const val PRIMARY_ROOT = "primary_root"
  const val COMMON_DIR = "common_dir"
  const val STATE = "state"
  const val EXECUTION_PATH = "execution_path"
  const val EXECUTION_BRANCH = "execution_branch"
  const val OWNED_REFS = "owned_refs"
  const val PINNED_HEAD_SHA = "pinned_head_sha"
  const val PINNED_BASE_SHA = "pinned_base_sha"
  const val LAST_PUBLISHED_SHA = "last_published_sha"
  const val ACQUIRED_AT = "acquired_at"
  const val UPDATED_AT = "updated_at"
  const val RETENTION = "retention"
  const val RETENTION_REASON = "reason"
  const val RETENTION_DETAIL = "detail"
  const val RETENTION_PATH = "path"

  val TOP_LEVEL: Set<String> =
    setOf(
      CONTRACT_VERSION,
      TARGET,
      INVOCATION_ID,
      OWNER_TOKEN,
      GENERATION,
      PROCESS,
      CALLER_ROOT,
      PRIMARY_ROOT,
      COMMON_DIR,
      STATE,
      EXECUTION_PATH,
      EXECUTION_BRANCH,
      OWNED_REFS,
      PINNED_HEAD_SHA,
      PINNED_BASE_SHA,
      LAST_PUBLISHED_SHA,
      ACQUIRED_AT,
      UPDATED_AT,
      RETENTION,
    )
  val TARGET_FIELDS: Set<String> = setOf(TARGET_HOST, TARGET_REPOSITORY, TARGET_NUMBER, TARGET_URL)
  val PROCESS_FIELDS: Set<String> = setOf(HOST_IDENTITY, BOOT_IDENTITY, PID, PROCESS_BIRTH_TOKEN)
  val RETENTION_FIELDS: Set<String> = setOf(RETENTION_REASON, RETENTION_DETAIL, RETENTION_PATH)
}
