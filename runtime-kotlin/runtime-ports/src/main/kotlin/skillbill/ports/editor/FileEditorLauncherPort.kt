package skillbill.ports.editor

import java.nio.file.Path

/**
 * Opens a file for the user to edit.
 */
interface FileEditorLauncherPort {
  /**
   * Opens [path] in `VISUAL`, then `EDITOR` from [environment], then the operating system default application.
   * Returns false when no editor could be launched; never throws for a launch failure.
   */
  fun open(
    path: Path,
    environment: Map<String, String>,
  ): Boolean
}
