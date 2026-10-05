
package skillbill.infrastructure.skills.scaffold.runtime.service

import skillbill.error.shellcontent.scaffoldRollbackError
import skillbill.infrastructure.host.jvm.rollbackDeleteEmptyDirectory
import skillbill.infrastructure.host.jvm.rollbackDeleteIfExists
import skillbill.infrastructure.host.jvm.rollbackDeleteRegularFileOrSymlink
import skillbill.infrastructure.host.jvm.rollbackRestoreBytes
import skillbill.infrastructure.skills.scaffold.runtime.service.externalpack.rollbackRegisteredExternalPlatformPack
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.Files
import java.nio.file.Path

internal fun rollback(
  txn: ScaffoldTransaction,
  adapters: ScaffoldAdapterSeams,
) {
  val errors = mutableListOf<String>()
  rollbackRegisteredExternalPlatformPack(txn, errors)
  adapters.rollbackInstallTargets(txn, errors)
  rollbackSymlinks(txn, errors)
  rollbackManifests(txn, errors)
  rollbackFiles(txn, errors)
  rollbackDirs(txn, errors)
  if (errors.isNotEmpty()) {
    throw scaffoldRollbackError(
      "Rollback encountered errors while reverting scaffold: ${errors.joinToString("; ")}",
    )
  }
}

internal fun rollbackSymlinks(
  txn: ScaffoldTransaction,
  errors: MutableList<String>,
) {
  for (link in txn.createdSymlinks.asReversed()) {
    recordRollbackFailure(errors, "symlink $link") {
      if (Files.isSymbolicLink(link) || Files.exists(link)) {
        rollbackDeleteIfExists(link)
      }
    }
  }
}

internal fun rollbackManifests(
  txn: ScaffoldTransaction,
  errors: MutableList<String>,
) {
  for (snapshot in txn.manifestSnapshots.asReversed()) {
    recordRollbackFailure(errors, "manifest ${snapshot.manifestPath}") {
      rollbackRestoreBytes(snapshot.manifestPath, snapshot.originalBytes)
    }
  }
}

internal fun rollbackFiles(
  txn: ScaffoldTransaction,
  errors: MutableList<String>,
) {
  for (path in txn.createdPaths.asReversed()) {
    recordRollbackFailure(errors, "file $path") {
      rollbackDeleteRegularFileOrSymlink(path)
    }
  }
}

internal fun rollbackDirs(
  txn: ScaffoldTransaction,
  errors: MutableList<String>,
) {
  for (directory in txn.createdDirs.asReversed()) {
    recordRollbackFailure(errors, "dir $directory") {
      rollbackDeleteEmptyDirectory(directory)
    }
  }
}

private fun recordRollbackFailure(
  errors: MutableList<String>,
  label: String,
  action: () -> Unit,
) {
  try {
    action()
  } catch (error: IOException) {
    errors += "$label: ${error.message}"
  } catch (error: UncheckedIOException) {
    errors += "$label: ${error.message}"
  }
}

internal const val ADD_ON_INSTALL_NOTE: String =
  "Add-on shipped as a supporting asset of its owning platform package; auto-install does not apply."

internal const val PLATFORM_PACK_INSTALL_NOTE: String =
  "Auto-installed the generated platform-pack skills into detected local agents."

internal fun platformPackManifestPath(
  repoRoot: Path,
  platform: String,
): Path = repoRoot.resolve("platform-packs").resolve(platform).resolve("platform.yaml")
