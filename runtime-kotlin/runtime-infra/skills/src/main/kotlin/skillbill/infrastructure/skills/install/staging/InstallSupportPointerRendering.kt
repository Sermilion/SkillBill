package skillbill.infrastructure.skills.install.staging

import skillbill.infrastructure.skills.scaffold.authoring.normalizeMarkdownLineEndings
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

internal fun writeRenderedSupportPointerFiles(
  repoRoot: Path,
  sourceSkillDir: Path,
  tempDir: Path,
  pointers: List<GeneratedSupportPointer>,
): List<Path> =
  pointers.map { pointer ->
    val resolvedRepoRoot = repoRoot.toAbsolutePath().normalize()
    val resolvedSource = sourceSkillDir.toAbsolutePath().normalize()
    val targetFile = pointer.target.toAbsolutePath().normalize()
    val pointerFile = tempDir.resolve(pointer.name).normalize()
    if (!pointerFile.startsWith(tempDir)) {
      invalidInstallStaging(
        resolvedSource.toString(),
        "Supporting pointer '${pointer.name}' staging path '$pointerFile' escapes staging dir '$tempDir'.",
      )
    }
    if (!targetFile.startsWith(resolvedRepoRoot)) {
      invalidInstallStaging(
        resolvedSource.toString(),
        "Supporting pointer '${pointer.name}' target '$targetFile' escapes repoRoot '$resolvedRepoRoot'.",
      )
    }
    if (!Files.isRegularFile(targetFile, LinkOption.NOFOLLOW_LINKS)) {
      invalidInstallStaging(
        resolvedSource.toString(),
        "Supporting pointer '${pointer.name}' targets '$targetFile' which does not exist.",
      )
    }
    if (resolvedSource.resolve(pointer.name).normalize() == targetFile) {
      invalidInstallStaging(
        resolvedSource.toString(),
        "Supporting pointer '${pointer.name}' resolves to itself at '$targetFile'.",
      )
    }

    val rendered = normalizeMarkdownLineEndings(Files.readString(targetFile)).trimEnd() + "\n"
    Files.write(pointerFile, rendered.toByteArray(StandardCharsets.UTF_8))
    pointerFile
  }
