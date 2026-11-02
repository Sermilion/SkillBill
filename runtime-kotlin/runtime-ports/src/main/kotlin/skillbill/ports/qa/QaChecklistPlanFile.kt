package skillbill.ports.qa

import java.nio.file.Path

interface QaChecklistPlanFile {
  /** Reads UTF-8 plan text, or returns null when it exceeds the checklist size limit. */
  fun read(path: Path): String?
}
