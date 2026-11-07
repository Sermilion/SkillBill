package skillbill.infrastructure.workflow.validation

import org.w3c.dom.Element
import skillbill.contracts.time.JvmSystemClock
import skillbill.infrastructure.host.jvm.testGateJvmResolver
import skillbill.infrastructure.host.scratch.testAgentRunScratchRoot
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileSystemValidationGateJunitFindingsTest {
  @Test
  fun `stack body supplies kotlin location when testcase file and line attrs are absent`() {
    val body =
      """
      java.lang.IllegalArgumentException: Failed requirement.
      	at skillbill.engine.work.EmitShapeValidator.validate(IdeStatusServiceTestSupport.kt:231)
      	at skillbill.engine.work.IdeStatusService.emit(IdeStatusService.kt:174)
      	at java.base/java.lang.reflect.Method.invoke(Method.java:580)
      """.trimIndent()

    assertEquals("IdeStatusServiceTestSupport.kt:231", junitLocationFromStack(body))
  }

  @Test
  fun `stack preview prefers skillbill frames and omits jdk frames`() {
    val body =
      """
      boom
      	at java.base/java.lang.reflect.Method.invoke(Method.java:580)
      	at skillbill.engine.work.EmitShapeValidator.validate(IdeStatusServiceTestSupport.kt:231)
      	at skillbill.engine.work.IdeStatusService.emit(IdeStatusService.kt:174)
      """.trimIndent()

    val preview = junitStackPreview(body)
    assertContains(preview, "IdeStatusServiceTestSupport.kt:231")
    assertContains(preview, "IdeStatusService.kt:174")
    assertTrue("Method.java" !in preview)
  }

  @Test
  fun `parseJUnitXmlFile keeps thin message attribute plus stack location from body`() {
    val xml =
      """
      <?xml version="1.0"?>
      <testsuite>
        <testcase classname="skillbill.engine.work.IdeStatusServiceGoalProjectionTest"
                  name="goal with launched child projects child current_phase_execution()">
          <failure message="java.lang.IllegalArgumentException: Failed requirement."
                   type="java.lang.IllegalArgumentException">java.lang.IllegalArgumentException: Failed requirement.
      	at skillbill.engine.work.EmitShapeValidator.validate(IdeStatusServiceTestSupport.kt:231)
      	at skillbill.engine.work.IdeStatusService.emit(IdeStatusService.kt:174)
      </failure>
        </testcase>
      </testsuite>
      """.trimIndent()
    val path = Files.createTempFile("junit-opaque-", ".xml")
    try {
      Files.writeString(path, xml)
      val runner = FileSystemValidationGateRunner(JvmSystemClock, testGateJvmResolver(), testAgentRunScratchRoot())
      val finding = runner.parseJUnitXmlFile(path).single()
      assertEquals("IdeStatusServiceTestSupport.kt:231", finding.location)
      assertContains(finding.message, "Failed requirement.")
      assertContains(finding.message, "IdeStatusServiceTestSupport.kt:231")
    } finally {
      Files.deleteIfExists(path)
    }
  }

  @Test
  fun `explicit testcase file and line attrs win over stack derivation`() {
    val document = FileSystemValidationGateRunner.DOCUMENT_BUILDER.newDocument()
    val testcase = document.createElement("testcase")
    testcase.setAttribute("file", "Explicit.kt")
    testcase.setAttribute("line", "9")
    val body =
      """
      at skillbill.engine.work.EmitShapeValidator.validate(IdeStatusServiceTestSupport.kt:231)
      """.trimIndent()
    assertEquals("Explicit.kt:9", junitFailureLocation(testcase, body))
  }

  @Test
  fun `blank stack yields null location`() {
    assertNull(junitLocationFromStack("java.lang.IllegalArgumentException: Failed requirement."))
  }

  @Test
  fun `junitFailureMessage falls back to body when message attribute is blank`() {
    val document = FileSystemValidationGateRunner.DOCUMENT_BUILDER.newDocument()
    val failure = document.createElement("failure") as Element
    failure.textContent =
      """
      assertion failed
      	at skillbill.application.work.FooTest.bar(FooTest.kt:12)
      """.trimIndent()
    val message = junitFailureMessage(failure)
    assertContains(message, "assertion failed")
    assertContains(message, "FooTest.kt:12")
  }
}
