package skillbill.application.decomposition.model

import skillbill.ports.projectstore.model.ProjectStoreLocation
import java.nio.file.Path

data class SpecBundleScope(
  val checkoutRoot: Path,
  val store: ProjectStoreLocation,
)
