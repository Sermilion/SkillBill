package skillbill.engine.work.control

import skillbill.ports.db.model.RuntimeDatabaseReadinessResult

internal fun RuntimeDatabaseReadinessResult.admitsWorkControl(): Boolean =
  when (this) {
    is RuntimeDatabaseReadinessResult.Ready -> existing
    is RuntimeDatabaseReadinessResult.Refused -> false
  }
