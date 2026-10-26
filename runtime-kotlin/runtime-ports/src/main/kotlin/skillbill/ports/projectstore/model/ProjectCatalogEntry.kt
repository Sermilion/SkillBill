package skillbill.ports.projectstore.model

import java.nio.file.Path
import java.time.Instant

data class ProjectCatalogEntry(
  val projectId: String,
  val storeRoot: Path,
  val specsRoot: Path,
  val checkoutRoots: List<Path>,
  val lastSeen: Instant?,
  val lastCheckoutRoot: Path,
  val hasSpecs: Boolean,
)
