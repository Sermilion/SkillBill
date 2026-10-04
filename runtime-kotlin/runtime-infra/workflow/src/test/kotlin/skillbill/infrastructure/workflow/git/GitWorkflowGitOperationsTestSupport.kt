package skillbill.infrastructure.workflow.git

import java.nio.file.Path

internal fun git(
  repoRoot: Path,
  vararg args: String,
): String {
  val output = runGit(repoRoot, *args)
  if (args.firstOrNull() == "init") {
    runGit(repoRoot, "config", "commit.gpgsign", "false")
    runGit(repoRoot, "config", "tag.gpgsign", "false")
  }
  return output
}

internal fun runGit(
  repoRoot: Path,
  vararg args: String,
): String {
  val process =
    ProcessBuilder(listOf("git", "-C", repoRoot.toString()) + args)
      .redirectErrorStream(true)
      .start()
  val output = process.inputStream.bufferedReader().readText().trim()
  val exitCode = process.waitFor()
  check(exitCode == 0) { "git ${args.joinToString(" ")} failed with $exitCode: $output" }
  return output
}
