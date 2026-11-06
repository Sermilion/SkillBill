package skillbill.ports.idestatus

import skillbill.ports.idestatus.model.StandalonePhaseOutputSnapshot

interface StandaloneOutputRepository {
  fun writeOutputSnapshot(
    executionId: String,
    snapshot: StandalonePhaseOutputSnapshot,
  )

  fun readOutput(executionId: String): StandalonePhaseOutputSnapshot?
}
