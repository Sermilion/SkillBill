package skillbill.architecture

import org.jetbrains.kotlin.K1Deprecation
import org.jetbrains.kotlin.cli.extensionsStorage
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCallableReferenceExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.psi.KtUserType

internal data class CapabilitySource(
  val path: String,
  val source: String,
)

internal data class CapabilitySymbol(
  val name: String,
  val path: String,
  val edges: Set<String>,
  val incomingTypes: Set<String>,
  val unresolved: Set<String>,
  val writerCalls: Set<String>,
)

internal object StrategyCapabilitySourceCatalog {
  @OptIn(K1Deprecation::class, CompilerConfiguration.Internals::class, ExperimentalCompilerApi::class)
  fun parse(sources: List<CapabilitySource>): Map<String, CapabilitySymbol> {
    val disposable = Disposer.newDisposable()
    return try {
      val environment =
        KotlinCoreEnvironment.createForProduction(
          disposable,
          CompilerConfiguration().apply { extensionsStorage = CompilerPluginRegistrar.ExtensionStorage() },
          EnvironmentConfigFiles.JVM_CONFIG_FILES,
        )
      val factory = KtPsiFactory(environment.project, false)
      val files = sources.associateWith { factory.createFile(it.path.substringAfterLast('/'), it.source) }
      val names =
        files.values
          .flatMap { file ->
            file.declarations.flatMap { declarations(it, file.packageFqName.asString()) }.map { it.first }
          }.toSet()
      files
        .flatMap { (source, file) ->
          val imports =
            file.importDirectives
              .mapNotNull { directive ->
                directive.importedFqName?.asString()?.let { fqn ->
                  (directive.aliasName ?: fqn.substringAfterLast('.')) to fqn
                }
              }.toMap()
          file.declarations.flatMap { declarations(it, file.packageFqName.asString()) }.map { (name, declaration) ->
            name to symbol(name, source.path, declaration, imports, names)
          }
        }.toMap()
    } finally {
      Disposer.dispose(disposable)
    }
  }

  private fun declarations(
    declaration: KtDeclaration,
    owner: String,
  ): List<Pair<String, KtDeclaration>> {
    val named = declaration as? KtNamedDeclaration ?: return emptyList()
    val members = (named as? KtClassOrObject)?.declarations.orEmpty()
    val name = named.name?.let { "$owner.$it" }
    return if (name == null) {
      members.flatMap { declarations(it, owner) }
    } else {
      listOf(name to declaration) + members.flatMap { declarations(it, name) }
    }
  }

  private fun symbol(
    name: String,
    path: String,
    declaration: KtDeclaration,
    imports: Map<String, String>,
    names: Set<String>,
  ): CapabilitySymbol {
    val edges = linkedSetOf<String>()
    val incoming = linkedSetOf<String>()
    val unresolved = linkedSetOf<String>()
    val packageName = declaration.containingKtFile.packageFqName.asString()
    val parameters = PsiTreeUtil.findChildrenOfType(declaration, KtParameter::class.java)
    val parameterTypes =
      parameters
        .mapNotNull { parameter ->
          val parameterName = parameter.name ?: return@mapNotNull null
          parameter.typeReference?.let { parameterName to references(it, packageName, imports, names, unresolved) }
        }.toMap()
    PsiTreeUtil.findChildrenOfType(declaration, KtUserType::class.java).forEach { type ->
      if ((type.parent as? KtUserType)?.qualifier === type) return@forEach
      val reference = resolve(type, packageName, imports, names)
      val parameter = PsiTreeUtil.getParentOfType(type, KtParameter::class.java)
      val function = parameter?.let { PsiTreeUtil.getParentOfType(it, KtNamedFunction::class.java) }
      if (function != null) incoming += reference else edges += reference
      noteUnresolved(reference, names, unresolved)
    }
    PsiTreeUtil.findChildrenOfType(declaration, KtNameReferenceExpression::class.java).forEach { reference ->
      if (PsiTreeUtil.getParentOfType(reference, KtTypeReference::class.java) != null) return@forEach
      val identifier = reference.getReferencedName()
      val function = PsiTreeUtil.getParentOfType(reference, KtNamedFunction::class.java)
      val functionParameter = function?.valueParameters?.firstOrNull { it.name == identifier }
      val classParameter =
        PsiTreeUtil
          .getParentOfType(reference, KtClassOrObject::class.java)
          ?.let { it as? KtClass }
          ?.primaryConstructorParameters
          ?.firstOrNull { it.name == identifier }
      val parameter = functionParameter ?: classParameter
      val call = reference.parent as? KtCallExpression
      val qualified = (call?.parent ?: reference.parent) as? KtDotQualifiedExpression
      val selector = qualified?.selectorExpression === (call ?: reference)
      if (parameter != null && !selector) {
        parameter.typeReference?.let { edges += references(it, packageName, imports, names, unresolved) }
        return@forEach
      }
      val locals = function?.let { PsiTreeUtil.findChildrenOfType(it, KtProperty::class.java) }.orEmpty()
      if (!selector && locals.any { it.name == identifier }) return@forEach
      val imported = imports[identifier]
      val resolved = imported ?: "$packageName.$identifier".takeIf(names::contains)
      if (resolved != null) {
        val member =
          qualified
            ?.takeIf { it.receiverExpression === reference }
            ?.selectorExpression
            ?.let { expression ->
              when (expression) {
                is KtCallExpression -> expression.calleeExpression?.text
                is KtNameReferenceExpression -> expression.getReferencedName()
                else -> null
              }
            }?.let { "$resolved.$it" }
            ?.takeIf(names::contains)
        val edge = member ?: resolved
        edges += edge
        noteUnresolved(edge, names, unresolved)
      }
    }
    return CapabilitySymbol(name, path, edges - name, incoming, unresolved, writerCalls(declaration, parameterTypes))
  }

  private fun writerCalls(
    declaration: KtDeclaration,
    parameters: Map<String, Set<String>>,
  ): Set<String> {
    val receivers = parameters.toMutableMap()
    val properties = PsiTreeUtil.findChildrenOfType(declaration, KtProperty::class.java)
    properties.forEach { property ->
      val alias = property.initializer as? KtNameReferenceExpression
      alias?.getReferencedName()?.let { referenced ->
        receivers[referenced]?.let { types -> property.name?.let { receivers[it] = types } }
      }
    }
    return PsiTreeUtil
      .findChildrenOfType(declaration, KtNameReferenceExpression::class.java)
      .flatMap { reference ->
        val parent = reference.parent
        val receiver =
          when (parent) {
            is KtCallExpression -> (parent.parent as? KtDotQualifiedExpression)?.receiverExpression
            is KtCallableReferenceExpression -> parent.receiverExpression
            else -> null
          } as? KtNameReferenceExpression
        receivers[receiver?.getReferencedName()].orEmpty().mapNotNull { type ->
          val operation = "$type.${reference.getReferencedName()}"
          operation.takeIf(StrategyCapabilityWriterInventory.primitiveWriters::contains)
        }
      }.toSet()
  }

  private fun references(
    type: KtTypeReference,
    packageName: String,
    imports: Map<String, String>,
    names: Set<String>,
    unresolved: MutableSet<String>,
  ): Set<String> =
    PsiTreeUtil
      .findChildrenOfType(type, KtUserType::class.java)
      .filterNot { (it.parent as? KtUserType)?.qualifier === it }
      .mapTo(linkedSetOf()) { userType ->
        resolve(userType, packageName, imports, names).also { noteUnresolved(it, names, unresolved) }
      }

  private fun resolve(
    type: KtUserType,
    packageName: String,
    imports: Map<String, String>,
    names: Set<String>,
  ): String {
    val simple = type.referencedName.orEmpty()
    val qualifier = type.qualifier?.text
    val qualified = if (qualifier == null) simple else "$qualifier.$simple"
    if (qualified.startsWith("skillbill.")) return qualified
    imports[simple]?.let { return it }
    imports[qualified.substringBefore('.')]?.let { imported ->
      return imported + qualified.removePrefix(qualified.substringBefore('.'))
    }
    return "$packageName.$qualified".takeIf(names::contains) ?: qualified
  }

  private fun noteUnresolved(
    reference: String,
    names: Set<String>,
    unresolved: MutableSet<String>,
  ) {
    if (reference !in names && GOVERNED_PACKAGES.any(reference::startsWith)) unresolved += reference
  }

  private val GOVERNED_PACKAGES =
    listOf(
      "skillbill.engine.featuretask.slot.",
      "skillbill.engine.featuretask.runloop.",
      "skillbill.engine.goalrunner.planning.",
    )
}
