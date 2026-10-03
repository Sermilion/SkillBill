package skillbill.infrastructure.skills.scaffold.platformpack.catalog

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import skillbill.error.core.ExternalPlatformPackConfigError
import skillbill.error.shellcontent.missingContentFile
import skillbill.model.toPath
import skillbill.scaffold.model.PlatformManifest

internal fun assertExternalPackContentPresent(manifest: PlatformManifest) {
  val declared = listOfNotNull(manifest.declaredFiles.baseline) + manifest.declaredFiles.areas.values
  declared.forEach { location ->
    val path = location.toPath().toAbsolutePath().normalize()
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
      throw missingContentFile(
        "External platform pack '${manifest.slug}' is missing required content at '$path'.",
      )
    }
  }
}

internal fun assertExternalPlatformPackDeclaredReads(
  manifest: PlatformManifest,
  sharedSupportRoot: Path,
) {
  val packRoot = manifest.packRoot.toPath().toAbsolutePath().normalize()
  val files =
    buildList {
      add(packRoot.resolve("platform.yaml"))
      manifest.declaredFiles.baseline?.let { location -> add(location.toPath()) }
      manifest.declaredFiles.areas.values.forEach { location -> add(location.toPath()) }
    }
  files.forEach { file -> assertAllowedExternalPackRead(file, packRoot, sharedSupportRoot) }
}

internal fun assertExternalPlatformPackTreeReads(
  packRoot: Path,
  sharedSupportRoot: Path,
) {
  if (!Files.exists(packRoot)) {
    return
  }
  val realPack = realExternalPackPath(packRoot)
  if (!Files.isDirectory(realPack)) {
    return
  }
  Files.walk(realPack).use { stream ->
    stream.forEach { path ->
      if (Files.isSymbolicLink(path)) {
        assertAllowedExternalPackRead(path, realPack, sharedSupportRoot)
      }
    }
  }
}

internal fun assertAllowedExternalPackRead(
  file: Path,
  packRoot: Path,
  sharedSupportRoot: Path,
) {
  val normalized = file.toAbsolutePath().normalize()
  val normalizedPack = packRoot.toAbsolutePath().normalize()
  val normalizedShared = sharedSupportRoot.toAbsolutePath().normalize()
  val real = realExternalPackPath(normalized)
  val realPack = realExternalPackPath(normalizedPack)
  val realShared = normalizedShared.takeIf { Files.exists(it) }?.let(::realExternalPackPath)
  val allowed = real.startsWith(realPack) || (realShared != null && real.startsWith(realShared))
  if (!allowed) {
    throw ExternalPlatformPackConfigError(
      "External platform pack read escapes the registered pack root and checkout .bill-shared directory.",
    )
  }
}

private fun realExternalPackPath(path: Path): Path =
  try {
    path.toRealPath()
  } catch (error: IOException) {
    throw ExternalPlatformPackConfigError(
      "External platform pack '${path.fileName}' references missing content.",
      error,
    )
  }
