package skillbill.installer

import java.nio.file.Files
import java.nio.file.Path

internal object InstallerDesktopFixtures {
  fun seedSource(repoRoot: Path) {
    val desktopRoot = repoRoot.resolve("desktop-tray")
    seedDistribution(desktopRoot.resolve("build/compose/binaries/main/app"))
    val gradlew = desktopRoot.resolve("gradlew")
    Files.writeString(gradlew, "#!/usr/bin/env bash\n[[ \"${'$'}*\" == '--no-daemon -q createDistributable' ]]\n")
    gradlew.toFile().setExecutable(true)
  }

  fun seedDistribution(root: Path): Path {
    val os = System.getProperty("os.name").lowercase()
    val app = root.resolve(if (os.contains("mac")) "Skill Bill.app" else "Skill Bill")
    val executable =
      app.resolve(
        when {
          os.contains("mac") -> "Contents/MacOS/Skill Bill"
          os.contains("win") -> "bin/Skill Bill.bat"
          else -> "bin/Skill Bill"
        },
      )
    Files.createDirectories(executable.parent)
    Files.writeString(executable, "#!/usr/bin/env bash\nexit 0\n")
    executable.toFile().setExecutable(true)
    return app
  }
}
