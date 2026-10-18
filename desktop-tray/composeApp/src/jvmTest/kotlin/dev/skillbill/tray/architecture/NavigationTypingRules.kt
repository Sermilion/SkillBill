package dev.skillbill.tray.architecture

import org.jetbrains.kotlin.CoreEnvironmentDeprecation
import org.jetbrains.kotlin.K1Deprecation
import org.jetbrains.kotlin.cli.extensionsStorage
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtNullableType
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtUserType

/**
 * Route arguments and entry wiring are typed: no string-keyed `Any` map carries them and no unchecked cast reads them back.
 * The scan covers the navigation, window-lifetime and host-intent packages, the entry component and each feature's route file,
 * and it follows import aliases, so `import kotlin.collections.Map as Dict` is still a map.
 */
internal object NavigationTypingRules {
  private val mapTypes = setOf("Map", "MutableMap", "HashMap", "LinkedHashMap")
  private val scopedPackages = listOf("/navigation/", "/lifetime/", "/host/")
  private val scopedFiles = listOf("/composition/WindowComponent.kt", "Route.kt", "Routes.kt")

  fun inScope(path: String): Boolean = scopedPackages.any { it in path } || scopedFiles.any { path.endsWith(it) }

  fun scopedSources(sources: List<Source>): List<Source> = sources.filter { inScope(it.path) }

  @OptIn(K1Deprecation::class, CoreEnvironmentDeprecation::class, CompilerConfiguration.Internals::class, ExperimentalCompilerApi::class)
  fun violations(sources: List<Source>): List<String> {
    val disposable = Disposer.newDisposable()
    return try {
      val environment = KotlinCoreEnvironment.createForProduction(
        disposable,
        CompilerConfiguration().apply { extensionsStorage = CompilerPluginRegistrar.ExtensionStorage() },
        EnvironmentConfigFiles.JVM_CONFIG_FILES,
      )
      val factory = KtPsiFactory(environment.project, false)
      sources.flatMap { source ->
        val file = factory.createFile(source.path.substringAfterLast('/'), source.text)
        val aliases = file.importDirectives.mapNotNull { directive ->
          val alias = directive.aliasName ?: return@mapNotNull null
          alias to directive.importedFqName?.shortName()?.asString().orEmpty()
        }.toMap()
        fun canonical(name: String?): String? = name?.let { aliases[it] ?: it }

        val untypedMaps = PsiTreeUtil.findChildrenOfType(file, KtUserType::class.java).filter { type ->
          val arguments = type.typeArguments.map { argument ->
            val element = argument.typeReference?.typeElement
            val argumentType = (if (element is KtNullableType) element.innerType else element) as? KtUserType
            canonical(argumentType?.referencedName)
          }
          canonical(type.referencedName) in mapTypes && arguments.size == 2 && arguments[0] == "String" && arguments[1] == "Any"
        }.map { "${source.path} carries route data in a string-keyed Any map: ${it.text}" }
        val uncheckedCasts = PsiTreeUtil.findChildrenOfType(file, KtAnnotationEntry::class.java).filter { entry ->
          canonical(entry.shortName?.asString()) == "Suppress" && "UNCHECKED_CAST" in entry.text
        }.map { "${source.path} suppresses UNCHECKED_CAST" }
        untypedMaps + uncheckedCasts
      }
    } finally {
      Disposer.dispose(disposable)
    }
  }
}
