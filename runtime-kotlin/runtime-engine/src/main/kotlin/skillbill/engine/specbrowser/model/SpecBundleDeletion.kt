package skillbill.engine.specbrowser.model

import java.nio.file.Path

sealed interface SpecBundleDeletion {
  data class Deleted(val directory: Path) : SpecBundleDeletion

  data object Absent : SpecBundleDeletion

  data class Refused(val reason: String) : SpecBundleDeletion

  data class Failed(val reason: String) : SpecBundleDeletion
}
