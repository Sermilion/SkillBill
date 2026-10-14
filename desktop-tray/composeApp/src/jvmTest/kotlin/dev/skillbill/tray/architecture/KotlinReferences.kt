package dev.skillbill.tray.architecture

import org.jetbrains.kotlin.CoreEnvironmentDeprecation
import org.jetbrains.kotlin.K1Deprecation
import org.jetbrains.kotlin.cli.extensionsStorage
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtUserType

internal data class ImportReference(val fqName: String, val allUnder: Boolean)

internal data class SourceReferences(val imports: List<ImportReference>, val qualifiedNames: Set<String>) {
  fun referencesAny(prefixes: Collection<String>): List<String> = buildSet {
    prefixes.forEach { prefix ->
      imports.forEach { import ->
        if (import.fqName.matches(prefix)) add(import.fqName)
        if (import.allUnder && prefix.startsWith("${import.fqName}.")) {
          val rest = prefix.removePrefix("${import.fqName}.")
          qualifiedNames.filter { it.matches(rest) }.forEach { add("${import.fqName}.*:$it") }
        }
      }
      qualifiedNames.filter { it.matches(prefix) }.forEach(::add)
    }
  }.sorted()

  private fun String.matches(prefix: String): Boolean = this == prefix || startsWith("$prefix.")
}

@OptIn(K1Deprecation::class, CoreEnvironmentDeprecation::class, CompilerConfiguration.Internals::class, ExperimentalCompilerApi::class)
internal fun scanReferences(sources: List<Source>): Map<Source, SourceReferences> {
  val disposable = Disposer.newDisposable()
  return try {
    val environment = KotlinCoreEnvironment.createForProduction(
      disposable,
      CompilerConfiguration().apply { extensionsStorage = CompilerPluginRegistrar.ExtensionStorage() },
      EnvironmentConfigFiles.JVM_CONFIG_FILES,
    )
    val factory = KtPsiFactory(environment.project, false)
    sources.associateWith { source ->
      val file = factory.createFile(source.path.substringAfterLast('/'), source.text)
      val imports = file.importDirectives.mapNotNull { directive ->
        directive.importedFqName?.asString()?.let { ImportReference(it, directive.isAllUnder) }
      }
      val expressions = PsiTreeUtil.findChildrenOfType(file, KtDotQualifiedExpression::class.java).mapNotNull { dottedName(it) }
      val types = PsiTreeUtil.findChildrenOfType(file, KtUserType::class.java).mapNotNull { dottedName(it) }
      SourceReferences(imports, (expressions + types).toSet())
    }
  } finally {
    Disposer.dispose(disposable)
  }
}

private fun dottedName(element: PsiElement?): String? = when (element) {
  is KtNameReferenceExpression -> element.getReferencedName()
  is KtCallExpression -> (element.calleeExpression as? KtNameReferenceExpression)?.getReferencedName()
  is KtDotQualifiedExpression -> {
    val receiver = dottedName(element.receiverExpression)
    val selector = dottedName(element.selectorExpression)
    if (receiver != null && selector != null) "$receiver.$selector" else receiver
  }
  is KtUserType -> {
    val name = element.referencedName
    val qualifier = element.qualifier?.let(::dottedName)
    if (name == null) null else if (qualifier == null) name else "$qualifier.$name"
  }
  else -> null
}
