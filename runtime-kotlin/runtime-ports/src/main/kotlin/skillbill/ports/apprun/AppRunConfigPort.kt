package skillbill.ports.apprun

import skillbill.ports.apprun.model.AppRunConfig
import java.nio.file.Path

interface AppRunConfigPort {
  /** Reads the checkout override, returning the documented debug default when the file is missing. */
  fun read(checkoutRoot: Path): AppRunConfig
}
