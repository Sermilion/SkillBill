package skillbill.architecture

import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RuntimeComponentInboundApiArchitectureTest {
  private val diRoot = "runtime-kotlin/runtime-core/src/main/kotlin/skillbill/di"

  @Test
  fun `RuntimeComponent abstract service property set matches the pinned inventory`() {
    val source =
      ArchitectureScanSupport.runtimeRoot
        .resolve(PrincipleEnforcementInventory.RUNTIME_COMPONENT_SOURCE)
        .readText()
    assertPinnedServiceProperties(source)
  }

  @Test
  fun `service property guard rejects added and removed properties`() {
    val expected = PrincipleEnforcementInventory.runtimeComponentInboundApi.toSet()
    val added = expected + "unexpectedService"
    val removed = expected - expected.first()

    assertFailsWith<AssertionError> {
      assertPinnedServiceProperties(componentSource(added))
    }
    assertFailsWith<AssertionError> {
      assertPinnedServiceProperties(componentSource(removed))
    }
  }

  private fun assertPinnedServiceProperties(source: String) {
    assertEquals(
      PrincipleEnforcementInventory.runtimeComponentInboundApi.toSet(),
      ArchitectureScanSupport.abstractPropertyNames(source),
      "RuntimeComponent abstract service properties must match " +
        "PrincipleEnforcementInventory.runtimeComponentInboundApi; " +
        "that pin is the logical service surface, not the full composition API.",
    )
  }

  private fun componentSource(properties: Set<String>): String =
    buildString {
      appendLine("abstract class RuntimeComponent {")
      properties.forEach { property ->
        appendLine("  abstract val $property: Service")
      }
      appendLine("}")
    }

  @Test
  fun `composition surface exposes only classified public callables`() {
    val violations = ArchitectureScanSupport.runtimeComponentPublicCallableViolations(diRoot)
    assertEquals(emptyList(), violations, violations.joinToString("\n"))
  }

  @Test
  fun `abstract property scanner reports added abstract properties across modifier forms`() {
    val source =
      """

      abstract class RuntimeComponent {
        abstract val goalRunner: GoalRunner
        abstract val extraSurface: ExtraSurface
        internal abstract val internalSurface: InternalSurface
        protected abstract val protectedSurface: ProtectedSurface
        @Deprecated("legacy") abstract val annotatedSurface: AnnotatedSurface
        abstract var mutableSurface: MutableSurface
        fun helper(): Int = 1
      }
      """.trimIndent()
    assertEquals(
      setOf(
        "goalRunner",
        "extraSurface",
        "internalSurface",
        "protectedSurface",
        "annotatedSurface",
        "mutableSurface",
      ),
      ArchitectureScanSupport.abstractPropertyNames(source),
    )
  }

  @Test
  fun `public callable scanner fails when RuntimeComponent adds a helper with unchanged abstract properties`() {
    val source =
      """

      abstract class RuntimeComponent {
        abstract val goalRunner: GoalRunner
        fun extraHelper(): Int = 1
      }
      """.trimIndent()
    val violations =
      ArchitectureScanSupport.runtimeComponentPublicCallableViolationsInSource(
        PrincipleEnforcementInventory.RUNTIME_COMPONENT_SOURCE,
        source,
      )
    assertEquals(
      listOf(
        "${PrincipleEnforcementInventory.RUNTIME_COMPONENT_SOURCE} exposes public function " +
          "'extraHelper' outside the @Provides generated-wiring surface.",
      ),
      violations,
    )
  }

  @Test
  fun `public callable scanner fails when a provider mixin adds a helper`() {
    val source =
      """

      internal interface RuntimeExampleProvides {
        @Provides
        fun examplePort(adapter: ExampleAdapter): ExamplePort = adapter

        fun mixinHelper(): Int = 1
      }
      """.trimIndent()
    val violations =
      ArchitectureScanSupport.runtimeComponentPublicCallableViolationsInSource(
        "runtime-kotlin/runtime-core/src/main/kotlin/skillbill/di/RuntimeExampleProvides.kt",
        source,
      )
    assertEquals(
      listOf(
        "runtime-kotlin/runtime-core/src/main/kotlin/skillbill/di/RuntimeExampleProvides.kt exposes public " +
          "function 'mixinHelper' outside the @Provides generated-wiring surface.",
      ),
      violations,
    )
  }

  @Test
  fun `public callable scanner allows only Provides methods including runtimeContext and databaseSessionFactory`() {
    val source =
      """

      abstract class RuntimeComponent {
        @Provides
        fun runtimeContext(): RuntimeContext = resolvedRuntimeContext

        @Provides @RuntimeSingleton
        fun databaseSessionFactory(context: EnvironmentContext): DatabaseSessionFactory =
          error("fixture")
      }
      """.trimIndent()
    assertTrue(ArchitectureScanSupport.runtimeComponentPublicCallableViolationsInSource("example.kt", source).isEmpty())
  }
}
