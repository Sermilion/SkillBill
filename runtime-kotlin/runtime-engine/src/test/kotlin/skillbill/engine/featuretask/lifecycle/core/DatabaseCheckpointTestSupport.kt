package skillbill.engine.featuretask.lifecycle.core

import skillbill.ports.db.DatabaseCheckpoint
import skillbill.ports.db.model.DatabaseCheckpointResult

internal val absentDatabaseCheckpoint = DatabaseCheckpoint { DatabaseCheckpointResult.Absent }
