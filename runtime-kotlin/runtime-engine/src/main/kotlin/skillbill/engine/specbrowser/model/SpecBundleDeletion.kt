package skillbill.engine.specbrowser.model

import java.nio.file.Path

public sealed interface SpecBundleDeletion {
  public data class Deleted(public val directory: Path) : SpecBundleDeletion

  public data object Absent : SpecBundleDeletion

  public data class Refused(public val reason: String) : SpecBundleDeletion

  public data class Failed(public val reason: String) : SpecBundleDeletion
}
