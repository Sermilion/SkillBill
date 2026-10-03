package skillbill.infrastructure.skills.scaffold.pointer

import java.io.File
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.InstallFailureCode
import skillbill.infrastructure.host.jvm.atomicWriteBytes
import skillbill.infrastructure.skills.scaffold.platformpack.loader.discoverPlatformPackManifests
import skillbill.infrastructure.skills.scaffold.runtime.service.contract.SHELL_CONTRACT_VERSION
import skillbill.model.toPath
import skillbill.scaffold.model.PlatformManifest
import skillbill.scaffold.model.PointerSpec

internal data class PointerRegenerationResult(
  val regeneratedFiles: List<Path>,
)

private class PointerRegenerationContext(
  val repoRoot: Path,
  val originalBytes: MutableMap<Path, ByteArray>?,
  val createdPaths: MutableList<Path>?,
  val written: MutableList<Path>,
)

object PointerOperations {
  internal fun regenerate(
    repoRoot: Path,
    originalBytes: MutableMap<Path, ByteArray>? = null,
    createdPaths: MutableList<Path>? = null,
  ): PointerRegenerationResult {
    val resolvedRepoRoot = repoRoot.toAbsolutePath().normalize()
    val packsRoot = resolvedRepoRoot.resolve("platform-packs")
    if (!Files.isDirectory(packsRoot)) {
      return PointerRegenerationResult(emptyList())
    }
    val context =
      PointerRegenerationContext(
        repoRoot = resolvedRepoRoot,
        originalBytes = originalBytes,
        createdPaths = createdPaths,
        written = mutableListOf(),
      )
    discoverPlatformPackManifests(packsRoot).forEach { pack ->

      requireMatchingContractVersion(pack)
      regeneratePackPointers(context, pack)
    }
    return PointerRegenerationResult(context.written.sortedBy { it.toString() })
  }
}

private fun regeneratePackPointers(
  context: PointerRegenerationContext,
  pack: PlatformManifest,
) {
  val sortedPointers =
    pack.pointers.sortedWith(
      compareBy({ it.skillRelativeDir }, { it.name }),
    )
  sortedPointers.forEach { spec ->
    writePointerIfChanged(context, pack.packRoot.toPath(), spec)
  }
}

private fun writePointerIfChanged(
  context: PointerRegenerationContext,
  packRoot: Path,
  spec: PointerSpec,
) {
  val resolvedPackRoot = packRoot.toAbsolutePath().normalize()
  val pointerFile = resolvedPackRoot.resolve(spec.skillRelativeDir).resolve(spec.name).normalize()
  require(pointerFile.startsWith(resolvedPackRoot)) {
    "Pointer '${spec.name}' under '${spec.skillRelativeDir}' resolves outside packRoot '$resolvedPackRoot'."
  }
  val rendered = renderPointer(context.repoRoot, packRoot, spec)

  val existed = Files.exists(pointerFile, LinkOption.NOFOLLOW_LINKS)
  val isSymlink = Files.isSymbolicLink(pointerFile)
  val currentContent: String? =
    when {
      !existed -> null
      isSymlink -> Files.readSymbolicLink(pointerFile).toString().replace(File.separatorChar, '/')
      else -> Files.readString(pointerFile).trimEnd('\n', '\r')
    }
  if (currentContent == rendered) {
    return
  }
  Files.createDirectories(pointerFile.parent)
  if (existed && context.originalBytes != null && pointerFile !in context.originalBytes) {
    val originalBytesForRollback =
      if (isSymlink) {
        currentContent.orEmpty().toByteArray(Charsets.UTF_8)
      } else {
        Files.readAllBytes(pointerFile)
      }
    context.originalBytes[pointerFile] = originalBytesForRollback
  }
  writePointerArtifact(pointerFile, rendered, existed, isSymlink)
  if (!existed) {
    context.createdPaths?.add(pointerFile)
  }
  context.written.add(pointerFile)
}

private fun writePointerArtifact(
  pointerFile: Path,
  rendered: String,
  existed: Boolean,
  wasSymlink: Boolean,
) {
  if (existed) {
    if (wasSymlink) {
      Files.delete(pointerFile)
    } else {
      Files.deleteIfExists(pointerFile)
    }
  }
  try {
    Files.createSymbolicLink(pointerFile, Path.of(rendered))
  } catch (_: FileSystemException) {
    atomicWriteBytes(pointerFile, rendered.toByteArray(Charsets.UTF_8))
  } catch (_: UnsupportedOperationException) {
    atomicWriteBytes(pointerFile, rendered.toByteArray(Charsets.UTF_8))
  }
}

private fun requireMatchingContractVersion(pack: PlatformManifest) {
  if (pack.contractVersion != SHELL_CONTRACT_VERSION) {
    throw SkillBillRuntimeException(InstallFailureCode.CONTRACT_VERSION_MISMATCH,
      "Platform pack '${pack.slug}': declares contract_version '${pack.contractVersion}' " +
        "but the shell expects '$SHELL_CONTRACT_VERSION'.",
    )
  }
}
