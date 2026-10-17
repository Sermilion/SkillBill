package skillbill.ports.repository

import skillbill.model.FileLocation
import java.nio.file.Path

interface RepositoryEnclosingRootPort {
  fun enclosingRepositoryRoot(start: Path): Path

  fun canonicalPath(path: Path): Path

  fun optionalRealPath(path: Path): Path?

  fun repositoryIdentity(repoRoot: Path): String

  fun primaryRepositoryRoot(repoRoot: Path): Path
}

fun Path.toFileLocation(): FileLocation = FileLocation(toString())
