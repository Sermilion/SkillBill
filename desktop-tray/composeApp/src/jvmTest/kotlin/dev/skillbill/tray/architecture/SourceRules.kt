package dev.skillbill.tray.architecture

internal object SourceRules {
  const val TESTING_PACKAGE = "dev.skillbill.tray.core.testing"

  val commonForbidden = listOf("java", "javax", "jdk", "sun", "com.sun", "org.sqlite", "org.xerial", "skillbill")
  val domainForbidden = listOf(
    "androidx.compose", "org.jetbrains.compose", "androidx.lifecycle", "org.jetbrains.androidx", "androidx.room",
    "androidx.room3", "androidx.datastore", "androidx.navigation", "androidx.sqlite", "androidx.paging",
    "dev.nucleusframework", "skillbill", "dev.skillbill.tray.application", "dev.skillbill.tray.infrastructure",
    "dev.skillbill.tray.ui", "dev.skillbill.tray.presentation", "dev.skillbill.tray.composition",
    "dev.skillbill.tray.core.designsystem",
  )

  fun violations(references: Map<Source, SourceReferences>): List<String> = references.flatMap { (source, found) ->
    val path = source.path
    val forbidden = buildSet {
      if ("/src/commonMain/" in path || "/src/commonTest/" in path) addAll(commonForbidden)
      if (path.startsWith("core/domain/src/")) addAll(domainForbidden)
      val production = "/src/commonMain/" in path || "/src/jvmMain/" in path
      if (production && !path.startsWith("core/testing/")) add(TESTING_PACKAGE)
    }
    found.referencesAny(forbidden).map { "$path references $it" }
  }
}
