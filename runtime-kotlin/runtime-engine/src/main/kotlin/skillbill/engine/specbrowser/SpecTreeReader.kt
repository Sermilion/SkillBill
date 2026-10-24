package skillbill.engine.specbrowser

import skillbill.application.decomposition.DECOMPOSITION_MANIFEST_FILENAME
import skillbill.engine.specbrowser.model.SpecBrowserFileKind
import skillbill.ports.workflow.decomposition.DecompositionManifestStore
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionSubtask
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.time.Instant

internal sealed interface RawSpecNode {
  val name: String
  val path: String
  val absolutePath: Path
}

internal data class RawSpecBundle(
  val issueKey: String?,
  val title: String,
  val manifest: DecompositionManifest?,
  val manifestModified: Instant? = null,
)

internal data class RawSpecDirectory(
  override val name: String,
  override val path: String,
  override val absolutePath: Path,
  val bundle: RawSpecBundle?,
  val children: List<RawSpecNode>,
) : RawSpecNode

internal data class RawSpecFile(
  override val name: String,
  override val path: String,
  override val absolutePath: Path,
  val kind: SpecBrowserFileKind,
  val subtask: DecompositionSubtask?,
) : RawSpecNode

internal data class SpecTreeRead(
  val roots: List<RawSpecNode>,
  val failure: String?,
)

internal class SpecTreeReader(
  private val store: DecompositionManifestStore,
  private val manifests: SpecManifestCache,
) {
  fun read(specsRoot: Path): SpecTreeRead = attempt(specsRoot) ?: attempt(specsRoot) ?: unreadable(specsRoot)

  private fun attempt(specsRoot: Path): SpecTreeRead? =
    try {
      SpecTreeRead(scan(specsRoot), null)
    } catch (failure: NoSuchFileException) {
      if (failure.file == specsRoot.toString()) SpecTreeRead(emptyList(), null) else null
    } catch (failure: UncheckedIOException) {
      if (failure.cause is NoSuchFileException) null else unreadable(specsRoot)
    } catch (_: IOException) {
      unreadable(specsRoot)
    }

  private fun unreadable(specsRoot: Path): SpecTreeRead =
    SpecTreeRead(
      emptyList(),
      "Can't read the spec folder $specsRoot. Fix the folder's permissions, then retry.",
    )

  private fun scan(specsRoot: Path): List<RawSpecNode> {
    val entries = store.listTree(specsRoot).filter { it != specsRoot && !hidden(specsRoot, it) }
    val tree = entries.toHashSet()
    val directories = HashMap<Path, List<Path>>()
    collectDirectories(specsRoot, tree, directories)
    val directorySet = directories.keys
    val files = entries.filter { it !in directorySet }.groupBy { it.parent }
    val roots = children(specsRoot, specsRoot, directories, files, null)
    val manifestPaths = entries.filterTo(HashSet()) { it.fileName.toString() == DECOMPOSITION_MANIFEST_FILENAME }
    manifests.retainUnder(specsRoot, manifestPaths)
    return roots
  }

  private fun collectDirectories(
    directory: Path,
    tree: Set<Path>,
    into: MutableMap<Path, List<Path>>,
  ) {
    val children = store.listDirectChildDirectories(directory).filter { it in tree }
    into[directory] = children
    children.forEach { collectDirectories(it, tree, into) }
  }

  private fun hidden(
    specsRoot: Path,
    entry: Path,
  ): Boolean = specsRoot.relativize(entry).any { it.toString().startsWith(".") }

  private fun children(
    specsRoot: Path,
    directory: Path,
    directories: Map<Path, List<Path>>,
    files: Map<Path?, List<Path>>,
    bundle: RawSpecBundle?,
  ): List<RawSpecNode> {
    val subdirectories =
      directories.getValue(directory).map { directoryNode(specsRoot, it, directories, files) }
    val fileNodes = files[directory].orEmpty().map { fileNode(specsRoot, it, bundle) }
    return subdirectories.filter { it.bundle != null }.sortedWith(byName()) +
      subdirectories.filter { it.bundle == null }.sortedWith(byName()) +
      orderedFiles(fileNodes, bundle != null)
  }

  private fun directoryNode(
    specsRoot: Path,
    directory: Path,
    directories: Map<Path, List<Path>>,
    files: Map<Path?, List<Path>>,
  ): RawSpecDirectory {
    val directFiles = files[directory].orEmpty().map { it.fileName.toString() }
    val isBundle = DECOMPOSITION_MANIFEST_FILENAME in directFiles || SPEC_FILENAME in directFiles
    val name = directory.fileName.toString()
    val bundle = if (isBundle) bundleOf(directory, name, DECOMPOSITION_MANIFEST_FILENAME in directFiles) else null
    return RawSpecDirectory(
      name = name,
      path = relativePath(specsRoot, directory),
      absolutePath = directory,
      bundle = bundle,
      children = children(specsRoot, directory, directories, files, bundle),
    )
  }

  private fun bundleOf(
    directory: Path,
    name: String,
    hasManifest: Boolean,
  ): RawSpecBundle {
    if (!hasManifest) return rawSpecBundle(name, null)
    val manifestPath = directory.resolve(DECOMPOSITION_MANIFEST_FILENAME)
    return rawSpecBundle(name, manifests.read(manifestPath), manifests.modifiedAt(manifestPath))
  }

  private fun fileNode(
    specsRoot: Path,
    file: Path,
    bundle: RawSpecBundle?,
  ): RawSpecFile {
    val name = file.fileName.toString()
    val manifest = bundle?.manifest
    val subtask = manifest?.subtasks?.firstOrNull { it.specPath.substringAfterLast('/') == name }
    val kind =
      when {
        bundle == null -> SpecBrowserFileKind.OTHER
        manifest == null && name == SPEC_FILENAME -> SpecBrowserFileKind.PARENT_SPEC
        manifest != null && manifest.parentSpecPath.substringAfterLast('/') == name -> SpecBrowserFileKind.PARENT_SPEC
        subtask != null -> SpecBrowserFileKind.SUBTASK_SPEC
        else -> SpecBrowserFileKind.OTHER
      }
    return RawSpecFile(
      name = name,
      path = relativePath(specsRoot, file),
      absolutePath = file,
      kind = kind,
      subtask = subtask.takeIf { kind == SpecBrowserFileKind.SUBTASK_SPEC },
    )
  }

  private fun orderedFiles(
    files: List<RawSpecFile>,
    insideBundle: Boolean,
  ): List<RawSpecFile> =
    if (insideBundle) {
      files.filter { it.kind == SpecBrowserFileKind.PARENT_SPEC }.sortedWith(byName()) +
        files.filter { it.kind == SpecBrowserFileKind.SUBTASK_SPEC }.sortedBy { it.subtask?.id } +
        files.filter { it.kind == SpecBrowserFileKind.OTHER }.sortedWith(byName())
    } else {
      files.sortedWith(byName())
    }

  private fun byName(): Comparator<RawSpecNode> = compareBy(specNaturalOrder) { it.name }

  private fun relativePath(
    specsRoot: Path,
    entry: Path,
  ): String = specsRoot.relativize(entry).joinToString("/") { it.toString() }

  private companion object {
    const val SPEC_FILENAME = "spec.md"
  }
}

private val ISSUE_KEY_PREFIX = Regex("^[A-Z][A-Z0-9]*-[0-9]+")

internal fun rawSpecBundle(
  folderName: String,
  manifest: DecompositionManifest?,
  manifestModified: Instant? = null,
): RawSpecBundle =
  RawSpecBundle(
    issueKey = manifest?.issueKey?.takeIf(String::isNotBlank) ?: ISSUE_KEY_PREFIX.find(folderName)?.value,
    title = manifest?.featureName?.takeIf(String::isNotBlank) ?: humanisedTitle(folderName),
    manifest = manifest,
    manifestModified = manifestModified,
  )

private fun humanisedTitle(folderName: String): String {
  val key = ISSUE_KEY_PREFIX.find(folderName)?.value
  val afterKey = key?.let { folderName.substring(it.length) } ?: folderName
  val slug = if (key != null && (afterKey.startsWith('-') || afterKey.startsWith('_'))) afterKey.drop(1) else afterKey
  val words = slug.replace('-', ' ').replace('_', ' ').trim()
  return words.replaceFirstChar(Char::uppercaseChar).ifEmpty { folderName }
}
