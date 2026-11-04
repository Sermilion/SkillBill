package skillbill.infrastructure.launcher.process.launch

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolutePathString

class AgentRunProcessTreeTest {
  @Test
  @Timeout(60)
  fun `refresh finds children started by a process whose parent already exited`(
    @TempDir directory: Path,
  ) {
    val gates = listOf("hold", "detach", "spawn").associateWith(directory::resolve)
    gates.values.forEach { gate ->
      assertTrue(ProcessBuilder("mkfifo", gate.absolutePathString()).start().waitFor() == 0)
    }
    val process = ProcessBuilder("sh", "-c", DETACHING_TREE_SCRIPT).directory(directory.toFile()).start()
    val tree = AgentRunProcessTree(process)
    try {
      val stdout = process.inputStream.bufferedReader()
      val detached = ProcessHandle.of(stdout.readLine().toLong()).get()
      val launcher = detached.parent().get()
      tree.refresh()

      Files.writeString(gates.getValue("detach"), "\n")
      launcher.onExit().get()
      tree.refresh()
      Files.writeString(gates.getValue("spawn"), "\n")
      val childPid = stdout.readLine().toLong()

      assertTrue(tree.refresh().any { handle -> handle.pid() == childPid })
    } finally {
      tree.signalForcibly()
    }
  }

  private companion object {
    const val DETACHING_TREE_SCRIPT =
      """
      sh -c '
        sh -c "
          echo \${'$'}\${'$'}
          read _ < spawn
          sleep 300 &
          echo \${'$'}!
          wait
        " &
        read _ < detach
      ' &
      wait
      read _ < hold
      """
  }
}
