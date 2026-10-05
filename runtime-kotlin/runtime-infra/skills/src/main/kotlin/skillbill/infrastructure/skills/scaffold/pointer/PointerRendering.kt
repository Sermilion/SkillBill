package skillbill.infrastructure.skills.scaffold.pointer

import skillbill.error.shellcontent.invalidScaffoldInputError
import skillbill.scaffold.model.PointerSpec
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

fun renderPointer(
  repoRoot: Path,
  packRoot: Path,
  spec: PointerSpec,
): String {
  val resolvedRepoRoot = repoRoot.toAbsolutePath().normalize()
  val resolvedPackRoot = packRoot.toAbsolutePath().normalize()
  val pointerDir = resolvedPackRoot.resolve(spec.skillRelativeDir).normalize()
  val pointerFile = pointerDir.resolve(spec.name).normalize()
  val targetFile = resolvedRepoRoot.resolve(spec.target).normalize()
  val violation =
    when {
      !targetFile.startsWith(resolvedRepoRoot) ->
        "Pointer '${spec.name}' target '${spec.target}' escapes repoRoot '$resolvedRepoRoot'."

      !pointerFile.startsWith(resolvedPackRoot) ->
        "Pointer '${spec.name}' under '${spec.skillRelativeDir}' escapes pack root '$resolvedPackRoot'."

      !Files.isRegularFile(targetFile, LinkOption.NOFOLLOW_LINKS) ->
        "Pointer '${spec.name}' under '${spec.skillRelativeDir}' targets '${spec.target}' " +
          "which does not exist at '$targetFile'."

      pointerFile == targetFile ->
        "Pointer '${spec.name}' under '${spec.skillRelativeDir}' resolves to itself at '$pointerFile'."
      else -> null
    }
  if (violation != null) throw invalidScaffoldInputError(violation)
  val relative = pointerDir.relativize(targetFile).toString()
  return normalizePointerPath(relative)
}

internal fun normalizePointerPath(raw: String): String {
  val forwardSlashed = raw.replace('\\', '/')
  val withoutLeadingDot = if (forwardSlashed.startsWith("./")) forwardSlashed.removePrefix("./") else forwardSlashed
  return collapseDuplicateSlashes(withoutLeadingDot)
}

private fun collapseDuplicateSlashes(value: String): String {
  if ("//" !in value) {
    return value
  }
  val builder = StringBuilder(value.length)
  var previousSlash = false
  for (ch in value) {
    if (ch == '/') {
      if (!previousSlash) {
        builder.append(ch)
      }
      previousSlash = true
    } else {
      builder.append(ch)
      previousSlash = false
    }
  }
  return builder.toString()
}
