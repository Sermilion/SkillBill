package skillbill.engine.featuretask.validation.projectchecks

import skillbill.contracts.config.projectchecks.ProjectCheckCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QualityGateCommandRenderingTest {
  @Test
  fun `rendered argv preserves boundaries and shell-sensitive content`() {
    assertEquals(
      "'sh' '-c' './gradlew compileKotlin' '' 'two words' 'it'\"'\"'s' '\$HOME; `literal`' 'line 1\nline 2'",
      QualityGateCommandRendering.renderArgv(
        listOf(
          "sh",
          "-c",
          "./gradlew compileKotlin",
          "",
          "two words",
          "it's",
          "\$HOME; `literal`",
          "line 1\nline 2",
        ),
      ),
    )
  }

  @Test
  fun `command records retain order and working directories`() {
    assertEquals(
      "command 1\nworking directory: 'modules/a b'\nargv: 'sh' '-c' './gradlew compileKotlin'\n\n" +
        "command 2\nworking directory: '.'\nargv: './gradlew' 'check ; still one argument'",
      QualityGateCommandRendering.renderCommands(
        listOf(
          ProjectCheckCommand(listOf("sh", "-c", "./gradlew compileKotlin"), "modules/a b"),
          ProjectCheckCommand(listOf("./gradlew", "check ; still one argument"), "."),
        ),
      ),
    )
  }

  @Test
  fun `markdown block leaves literal backticks and multiline values intact`() {
    val value = "argv: 'echo' '```\nnext line'"
    val rendered = QualityGateCommandRendering.markdownCodeBlock(value)

    assertTrue(rendered.startsWith("````\n"))
    assertTrue(value in rendered)
    assertTrue(rendered.endsWith("\n````"))
  }
}
