package skillbill.ports.filesystem.model

import java.nio.file.Path

data class DatabaseFileObservation(val path: Path, val fileKey: String)
