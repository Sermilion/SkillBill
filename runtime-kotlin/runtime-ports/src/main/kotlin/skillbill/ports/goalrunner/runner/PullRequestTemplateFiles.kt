package skillbill.ports.goalrunner.runner

import java.nio.file.Path

/** Reads the repository files a pull request template search looks at, without writing any. */
interface PullRequestTemplateFiles {
  /** The real path of [path] when it names an existing regular file, or null. */
  fun regularFile(path: Path): Path?

  /** The real paths of the regular `.md` files directly inside [directory], by name; empty when it is no directory. */
  fun markdownFiles(directory: Path): List<Path>

  /** The UTF-8 text of the regular file at [file]. */
  fun readText(file: Path): String
}
