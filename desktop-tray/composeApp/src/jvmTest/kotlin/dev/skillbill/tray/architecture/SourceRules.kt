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
    val project = checkNotNull(source.project) { "source $path has no Gradle source-set attribution" }
    val sourceSet = checkNotNull(source.sourceSet) { "source $path has no Gradle source-set attribution" }
    val forbidden = buildSet {
      if (sourceSet == "commonMain" || sourceSet == "commonTest") addAll(commonForbidden)
      if (project == ModuleGraphRules.DOMAIN) addAll(domainForbidden)
      if (sourceSet in productionSourceSets && project != ModuleGraphRules.TESTING) add(TESTING_PACKAGE)
    }
    found.referencesAny(forbidden).map { "$path references $it" }
  }
}
