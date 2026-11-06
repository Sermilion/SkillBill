package skillbill.ports.idestatus

import skillbill.ports.idestatus.model.LiveChecksWriteMode
import skillbill.ports.idestatus.model.StandaloneNarrowWriteResult
import skillbill.ports.idestatus.model.ValidationCheckEventRecord

interface StandaloneValidationCheckRepository {
  /**
   * Inserts one validation check event with the caller-supplied sequence and attempt. The caller owns sequence and
   * attempt numbering; a duplicate `(invocation_id, sequence)` fails the insert.
   */
  fun appendValidationCheckEvent(record: ValidationCheckEventRecord)

  /** Returns every validation check event of [invocationId], ordered by sequence ascending. */
  fun readValidationCheckEvents(invocationId: String): List<ValidationCheckEventRecord>

  /**
   * Stores [activity] as `current_activity` for a live execution: `finished_at` is null and the lifecycle is active
   * or paused. Any other existing row returns FINISHED and stays unchanged. The status revision, `updated_at` and the
   * lease columns never change, so the compare-and-set status update path is unaffected.
   */
  fun writeCurrentActivity(
    invocationId: String,
    activity: String,
  ): StandaloneNarrowWriteResult

  /**
   * Sets `live_checks` for a live execution according to [mode]: UNAVAILABLE writes unavailable, KEEP_OR_UNAVAILABLE
   * keeps a stored value or writes unavailable, AVAILABLE writes available. The row rules and the untouched revision,
   * `updated_at` and lease columns match [writeCurrentActivity].
   */
  fun writeLiveChecks(
    invocationId: String,
    mode: LiveChecksWriteMode,
  ): StandaloneNarrowWriteResult
}
