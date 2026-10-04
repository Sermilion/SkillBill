package skillbill.infrastructure.contracts.system

import skillbill.error.core.SkillBillRuntimeException
import skillbill.infrastructure.contracts.locator.GoalPlanningPreparationSchemaPaths
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ClasspathPackagedContractInspectorTest {
  @Test
  fun `candidate rejects missing resources and mixed nested producer pins`() {
    val inspector = ClasspathPackagedContractInspector()
    inspector.inspect()
    val resources =
      listOf(
        GoalPlanningPreparationSchemaPaths.CLASSPATH_RESOURCE,
        GoalPlanningPreparationSchemaPaths.HISTORICAL_0_2_PHASE_OUTPUT_0_6_CLASSPATH_RESOURCE,
      )
    resources.forEach { resource ->
      val path = resource.removePrefix("/")
      val loader = javaClass.classLoader
      val source = requireNotNull(loader.getResourceAsStream(path)).bufferedReader().use { it.readText() }
      val variants =
        listOf(
          source.replace(
            "phase_output_contract_version: { type: string, const:",
            "phase_output_contract_version: { type: string, enum:",
          ),
          source.replace(
            "planning_contract_version: { type: string, const: \"0.2\" }",
            "planning_contract_version: { type: string, const: \"0.1\" }",
          ),
          source.replace(
            "contract_version: { type: string, const: \"0.2\" }",
            "contract_version: { type: string, const: \"0.1\" }",
          ),
          null,
        )
      variants.forEach { replacement ->
        val candidate =
          object : ClassLoader(loader) {
            override fun getResourceAsStream(name: String): InputStream? =
              if (name == path) {
                replacement?.let { ByteArrayInputStream(it.toByteArray()) }
              } else {
                super.getResourceAsStream(name)
              }
          }
        val error = assertFailsWith<SkillBillRuntimeException> { inspector.inspect(candidate) }
        assertEquals(PackagedContractFailureCode.INCOMPATIBLE_PACKAGE, error.code)
      }
    }
  }
}
