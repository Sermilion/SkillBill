package skillbill.engine.operation.unittestvalue

internal object UnitTestPathClassifier {
  private val TEST_DIRECTORIES = setOf("test", "tests", "__tests__")
  private val TEST_STEM_SUFFIXES = listOf("Test", "Tests", "Spec")
  private val TEST_NAME_PATTERNS =
    listOf(
      Regex("""test_.+\.py"""),
      Regex(""".+_test\.(?:py|go)"""),
      Regex(""".+\.(?:test|spec)\.[^.]+"""),
    )
  private const val RESOURCES_DIRECTORY = "resources"
  private const val SOURCE_DIRECTORY = "src"
  private const val SOURCE_SET_TEST_SUFFIX = "Test"

  fun isUnitTest(path: String): Boolean {
    val segments = path.replace('\\', '/').split('/').filter(String::isNotEmpty)
    val name = segments.lastOrNull() ?: return false
    val directories = segments.dropLast(1)
    if (RESOURCES_DIRECTORY in directories) return false
    return inTestSourceSet(directories) || directories.any(TEST_DIRECTORIES::contains) || isTestFileName(name)
  }

  private fun inTestSourceSet(directories: List<String>): Boolean =
    directories.zipWithNext().any { (parent, sourceSet) ->
      parent == SOURCE_DIRECTORY && sourceSet.endsWith(SOURCE_SET_TEST_SUFFIX)
    }

  private fun isTestFileName(name: String): Boolean {
    val stem = name.substringBeforeLast('.')
    return (stem != name && TEST_STEM_SUFFIXES.any(stem::endsWith)) || TEST_NAME_PATTERNS.any { it.matches(name) }
  }
}
