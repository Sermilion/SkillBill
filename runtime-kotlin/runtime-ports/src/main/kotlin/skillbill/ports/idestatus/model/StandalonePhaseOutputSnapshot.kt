package skillbill.ports.idestatus.model

import java.time.Instant

data class StandalonePhaseOutputSnapshot(
  val tail: List<String> = emptyList(),
  val latestLineAt: Instant? = null,
  val latestAttributedLines: Map<Int, String> = emptyMap(),
  val latestAttributedLineAt: Map<Int, Instant> = emptyMap(),
)
