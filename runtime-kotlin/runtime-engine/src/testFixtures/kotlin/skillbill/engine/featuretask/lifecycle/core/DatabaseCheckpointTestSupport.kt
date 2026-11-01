package skillbill.engine.featuretask.lifecycle.core

import skillbill.ports.db.DatabaseCheckpoint
import skillbill.ports.db.model.DatabaseCheckpointResult

val absentDatabaseCheckpoint = DatabaseCheckpoint { DatabaseCheckpointResult.Absent }
