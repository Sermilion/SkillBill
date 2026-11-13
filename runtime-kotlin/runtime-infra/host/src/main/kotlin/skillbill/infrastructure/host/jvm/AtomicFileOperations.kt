package skillbill.infrastructure.host.jvm

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

fun atomicMoveReplacing(
  source: Path,
  target: Path,
  strictAtomic: Boolean = false,
) {
  try {
    Files.move(source, target, ATOMIC_MOVE, REPLACE_EXISTING)
  } catch (error: AtomicMoveNotSupportedException) {
    if (strictAtomic) throw error
    Files.move(source, target, REPLACE_EXISTING)
  }
}
