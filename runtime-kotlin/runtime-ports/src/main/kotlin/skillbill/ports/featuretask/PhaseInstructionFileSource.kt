package skillbill.ports.featuretask

import java.nio.file.Path

/**
 * Reads the committed per-phase instruction files under a repository's `.skill-bill/phases`, without writing any.
 * Read failures surface as `IOException`, `UncheckedIOException`, or `SecurityException`; callers own the policy.
 */
interface PhaseInstructionFileSource {
  /** The names of the `.md` entries directly inside [directory], or null when [directory] is not a directory. */
  fun markdownFileNames(directory: Path): List<String>?

  /** The byte size of [file] when it is a regular file, or null otherwise. */
  fun regularFileSize(file: Path): Long?

  /** The exact bytes of the regular file at [file]. */
  fun readBytes(file: Path): ByteArray
}
