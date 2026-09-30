package skillbill.engine.featuretask.slotbaseline

import java.nio.file.Files

internal object SlotBaselineWriter {
  fun replaceTree(files: Map<String, String>) {
    SlotBaselinePaths.BUNDLE_DIRECTORIES
      .map(SlotBaselineTestResources::resolve)
      .forEach { directory -> directory.toFile().deleteRecursively() }
    files.forEach { (relativePath, text) ->
      val path = SlotBaselineTestResources.resolve(relativePath)
      Files.createDirectories(path.parent)
      Files.writeString(path, text)
    }
  }
}
