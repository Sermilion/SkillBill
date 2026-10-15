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
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtConstantExpression
import org.jetbrains.kotlin.psi.KtConstructor
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtTypeAlias
import org.jetbrains.kotlin.psi.KtUserType

internal data class ImportReference(val fqName: String, val allUnder: Boolean)

/**
 * What a source file names. [calls] holds every called simple name, so a bare constructor call is visible too;
 * [injectDefaults] lists `Owner.parameter` for each defaulted parameter of an `@Inject` constructor;
 * [collaboratorDefaults] lists the same for a defaulted private constructor property of any non-value class;
 * [supertypes] maps each named class, object or interface to the simple names of its supertypes, [importAliases] maps an import
 * alias to the simple name it stands for and applies only inside its own file, and [typeAliases] does the same for a typealias,
 * which applies project-wide, so a supertype cannot hide behind a rename; [names] holds every simple
 * name the code references, imports and types included, but nothing written in a comment or a string literal.
 */
internal data class SourceReferences(
  val imports: List<ImportReference>,
  val qualifiedNames: Set<String>,
  val calls: Set<String> = emptySet(),
  val injectDefaults: List<String> = emptyList(),
  val collaboratorDefaults: List<String> = emptyList(),
  val supertypes: Map<String, Set<String>> = emptyMap(),
  val importAliases: Map<String, String> = emptyMap(),
  val typeAliases: Map<String, String> = emptyMap(),
  val names: Set<String> = emptySet(),
) {
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
      val calls = PsiTreeUtil.findChildrenOfType(file, KtCallExpression::class.java)
        .mapNotNull { (it.calleeExpression as? KtNameReferenceExpression)?.getReferencedName() }.toSet()
      val names = PsiTreeUtil.findChildrenOfType(file, KtNameReferenceExpression::class.java).map { it.getReferencedName() }.toSet()
      SourceReferences(
        imports, (expressions + types).toSet(), calls, injectDefaults(file), collaboratorDefaults(file), supertypes(file), importAliases(file),
        typeAliases(file), names,
      )
    }
  } finally {
    Disposer.dispose(disposable)
  }
}

/**
 * `Owner.parameter` for each defaulted private constructor property of a class that holds collaborators. Value types
 * (data, enum and annotation classes) keep public defaulted properties and are skipped, as are plain literal defaults.
 */
private fun collaboratorDefaults(file: KtFile): List<String> =
  PsiTreeUtil.findChildrenOfType(file, KtClass::class.java)
    .filterNot { it.isData() || it.isEnum() || it.isAnnotation() }
    .flatMap { type ->
      val constructors: List<KtConstructor<*>> = listOfNotNull(type.primaryConstructor) + type.secondaryConstructors
      constructors.flatMap { constructor ->
        constructor.valueParameters
          .filter { it.hasValOrVar() && it.hasModifier(KtTokens.PRIVATE_KEYWORD) && it.hasDefaultValue() }
          .filterNot { it.defaultValue is KtConstantExpression || it.defaultValue is KtStringTemplateExpression }
          .map { "${type.name}.${it.name}" }
      }
    }

private fun supertypes(file: KtFile): Map<String, Set<String>> =
  PsiTreeUtil.findChildrenOfType(file, KtClassOrObject::class.java).mapNotNull { type ->
    val name = type.name ?: return@mapNotNull null
    name to type.superTypeListEntries.mapNotNull { (it.typeReference?.typeElement as? KtUserType)?.referencedName }.toSet()
  }.groupBy({ it.first }, { it.second }).mapValues { (_, sets) -> sets.flatten().toSet() }

private fun importAliases(file: KtFile): Map<String, String> =
  file.importDirectives.mapNotNull { directive ->
    val alias = directive.aliasName ?: return@mapNotNull null
    directive.importedFqName?.shortName()?.asString()?.let { alias to it }
  }.toMap()

private fun typeAliases(file: KtFile): Map<String, String> =
  PsiTreeUtil.findChildrenOfType(file, KtTypeAlias::class.java).mapNotNull { alias ->
    val name = alias.name ?: return@mapNotNull null
    (alias.getTypeReference()?.typeElement as? KtUserType)?.referencedName?.let { name to it }
  }.toMap()

private fun injectDefaults(file: KtFile): List<String> =
  PsiTreeUtil.findChildrenOfType(file, KtClass::class.java).flatMap { type ->
    val classInjected = type.annotationEntries.any { it.shortName?.asString() == "Inject" }
    val constructors: List<KtConstructor<*>> = listOfNotNull(type.primaryConstructor) + type.secondaryConstructors
    constructors.flatMap { constructor ->
      val injected = classInjected || constructor.annotationEntries.any { it.shortName?.asString() == "Inject" }
      if (!injected) emptyList() else constructor.valueParameters.filter { it.hasDefaultValue() }.map { "${type.name}.${it.name}" }
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
