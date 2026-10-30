package skillbill.installer

import java.nio.file.Files
import java.nio.file.Path

internal const val FIXTURE_BUILD_IDENTITY = "commit=fixture\ndirty=false\nrelease_tag=\ninstall_run_id=\n"

internal object InstallerDesktopFixtures {
  val buildIdentityPrintf = "printf '${FIXTURE_BUILD_IDENTITY.replace("\n", "\\n")}'"

  fun seedSource(repoRoot: Path) {
    val desktopRoot = repoRoot.resolve("desktop")
    seedDistribution(desktopRoot.resolve("app/build/compose/binaries/main/app"))
    val gradlew = desktopRoot.resolve("gradlew")
    Files.writeString(gradlew, "#!/usr/bin/env bash\n[[ \"${'$'}*\" == '--no-daemon -q createDistributable' ]]\n")
    gradlew.toFile().setExecutable(true)
  }

  fun seedDistribution(
    root: Path,
    buildIdentity: String = FIXTURE_BUILD_IDENTITY,
  ): Path {
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
    writeBuildIdentity(app.resolve(resourcesPath()), buildIdentity)
    return app
  }

  fun resourcesPath(): String {
    val os = System.getProperty("os.name").lowercase()
    return when {
      os.contains("mac") -> "Contents/app/resources"
      os.contains("win") -> "app/resources"
      else -> "lib/app/resources"
    }
  }

  fun writeBuildIdentity(
    dir: Path,
    record: String = FIXTURE_BUILD_IDENTITY,
  ) {
    Files.createDirectories(dir)
    Files.writeString(dir.resolve("build-identity.properties"), record)
  }
}
