package skillbill.ports.install.selection.model

import skillbill.install.model.SharedInstallSelection
import java.nio.file.Path

data class ReadLatestSuccessfulInstallSelectionRequest(
  val installHome: Path,
)

data class ReadLatestSuccessfulInstallSelectionResult(
  val selection: SharedInstallSelection?,
)

data class WriteLatestSuccessfulInstallSelectionRequest(
  val installHome: Path,
  val selection: SharedInstallSelection,
)

data class WriteLatestSuccessfulInstallSelectionResult(
  val path: Path,
)
