package skillbill.infrastructure.skills.nativeagent

import skillbill.infrastructure.skills.nativeagent.composition.NativeAgentSource
import skillbill.infrastructure.skills.nativeagent.rendering.NativeAgentProvider
import kotlin.test.Test
import kotlin.test.assertEquals

class NativeAgentRenderSnapshotTest {
  private val source =
    NativeAgentSource(
      name = "bill-snapshot-demo",
      description = "Snapshot demo agent.",
      body = "# Snapshot Demo\n\nFirst line.\nSecond: line with colon.",
    )

  private val quotedSource =
    NativeAgentSource(
      name = "bill-snapshot-quoted",
      description = "Edge: case\nwith back\\slash",
      body = "# Snapshot Quoted\n\nBody line.",
    )

  @Test
  fun `claude render is byte-exact`() {
    val expected =
      """
      ---
      name: bill-snapshot-demo
      description: Snapshot demo agent.
      ---

      # Snapshot Demo

      First line.
      Second: line with colon.

      """.trimIndent()

    assertEquals(expected, NativeAgentProvider.Claude.render(source))
  }

  @Test
  fun `codex render is byte-exact`() {
    val expected =
      """
      name = "bill-snapshot-demo"
      description = "Snapshot demo agent."

      developer_instructions = ${'"'}${'"'}${'"'}
      # Snapshot Demo

      First line.
      Second: line with colon.
      ${'"'}${'"'}${'"'}

      """.trimIndent()

    assertEquals(expected, NativeAgentProvider.Codex.render(source))
  }

  @Test
  fun `claude render is byte-exact when description forces yaml quoting`() {
    val expected =
      """
      ---
      name: bill-snapshot-quoted
      description: "Edge: case\nwith back\\slash"
      ---

      # Snapshot Quoted

      Body line.

      """.trimIndent()

    assertEquals(expected, NativeAgentProvider.Claude.render(quotedSource))
  }

  @Test
  fun `codex render is byte-exact when description has special chars`() {
    val expected =
      """
      name = "bill-snapshot-quoted"
      description = "Edge: case\nwith back\\slash"

      developer_instructions = ${'"'}${'"'}${'"'}
      # Snapshot Quoted

      Body line.
      ${'"'}${'"'}${'"'}

      """.trimIndent()

    assertEquals(expected, NativeAgentProvider.Codex.render(quotedSource))
  }

  @Test
  fun `cursor render is byte-exact`() {
    val expected =
      """
      ---
      name: bill-snapshot-demo
      description: Snapshot demo agent.
      ---

      # Snapshot Demo

      First line.
      Second: line with colon.

      """.trimIndent()

    assertEquals(expected, NativeAgentProvider.Cursor.render(source))
    assertEquals("bill-snapshot-demo.md", NativeAgentProvider.Cursor.fileName("bill-snapshot-demo"))
  }

  @Test
  fun `cursor render is byte-exact when description forces yaml quoting`() {
    val expected =
      """
      ---
      name: bill-snapshot-quoted
      description: "Edge: case\nwith back\\slash"
      ---

      # Snapshot Quoted

      Body line.

      """.trimIndent()

    assertEquals(expected, NativeAgentProvider.Cursor.render(quotedSource))
  }
}
