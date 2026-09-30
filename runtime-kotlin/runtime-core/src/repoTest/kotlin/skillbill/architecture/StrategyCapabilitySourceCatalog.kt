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
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtThisExpression
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.psi.KtUserType

internal data class CapabilitySource(
  val path: String,
  val source: String,
)

internal data class CapabilitySymbol(
  val name: String,
  val path: String,
  val sourcePaths: Set<String>,
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
          val imports = imports(file, names)
          file.declarations.flatMap { declarations(it, file.packageFqName.asString()) }.map { (name, declaration) ->
            name to symbol(name, source.path, declaration, imports, names)
          }
        }.groupBy { it.first }
        .mapValues { (name, declarations) ->
          val symbols = declarations.map { it.second }
          CapabilitySymbol(
            name = name,
            path = symbols.first().path,
            sourcePaths = symbols.flatMapTo(linkedSetOf()) { it.sourcePaths },
            edges = symbols.flatMapTo(linkedSetOf()) { it.edges },
            incomingTypes = symbols.flatMapTo(linkedSetOf()) { it.incomingTypes },
            unresolved = symbols.flatMapTo(linkedSetOf()) { it.unresolved },
            writerCalls = symbols.flatMapTo(linkedSetOf()) { it.writerCalls },
          )
        }
    } finally {
      Disposer.dispose(disposable)
    }
  }

  private fun imports(
    file: KtFile,
    names: Set<String>,
  ): Map<String, Set<String>> {
    val explicit =
      file.importDirectives.filterNot { it.isAllUnder }.mapNotNull { directive ->
        directive.importedFqName?.asString()?.let { fqn ->
          (directive.aliasName ?: fqn.substringAfterLast('.')) to fqn
        }
      }
    val explicitNames = explicit.mapTo(linkedSetOf()) { it.first }
    val wildcard =
      file.importDirectives.filter { it.isAllUnder }.flatMap { directive ->
        val owner = directive.importedFqName?.asString() ?: return@flatMap emptyList()
        names
          .filter { it.startsWith("$owner.") && '.' !in it.removePrefix("$owner.") }
          .map { it.substringAfterLast('.') to it }
          .filterNot { it.first in explicitNames }
      }
    return (explicit + wildcard).groupBy({ it.first }, { it.second }).mapValues { it.value.toSet() }
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
      val companionMembers =
        if (named is KtObjectDeclaration && named.isCompanion()) {
          members.flatMap { declarations(it, owner) }
        } else {
          emptyList()
        }
      listOf(name to declaration) + members.flatMap { declarations(it, name) } + companionMembers
    }
  }

  private fun symbol(
    name: String,
    path: String,
    declaration: KtDeclaration,
    imports: Map<String, Set<String>>,
    names: Set<String>,
  ): CapabilitySymbol {
    val edges = linkedSetOf<String>()
    val incoming = linkedSetOf<String>()
    val unresolved = linkedSetOf<String>()
    val packageName = declaration.containingKtFile.packageFqName.asString()
    val enclosingClass = enclosingClassName(declaration, packageName, names)
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
      reference.forEach { noteUnresolved(it, names, unresolved) }
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
      if (qualified != null &&
        qualifiedName(qualified) != null &&
        (qualified.parent as? KtDotQualifiedExpression)?.receiverExpression === qualified
      ) {
        return@forEach
      }
      if (parameter != null && !selector) {
        parameter.typeReference?.let { edges += references(it, packageName, imports, names, unresolved) }
        return@forEach
      }
      val locals = function?.let { PsiTreeUtil.findChildrenOfType(it, KtProperty::class.java) }.orEmpty()
      if (!selector && locals.any { it.name == identifier }) return@forEach
      val qualifiedNames =
        qualified
          ?.takeIf { selector }
          ?.receiverExpression
          ?.let(::qualifiedName)
          ?.let { receiver ->
            val first = receiver.substringBefore('.')
            val valueReceiver =
              function?.valueParameters?.any { it.name == first } == true ||
                locals.any { it.name == first } ||
                enclosingClass?.let { "$it.$first" in names } == true
            val receiverImports = imports[first].takeUnless { valueReceiver }
            val owners = receiverImports?.map { it + receiver.removePrefix(first) } ?: listOf(receiver)
            owners.mapNotNull { owner ->
              val candidate = "$owner.$identifier"
              when {
                candidate in names -> candidate
                owner in names -> owner
                receiver.startsWith("skillbill.") && GOVERNED_PACKAGES.any(candidate::startsWith) -> candidate
                else -> null
              }
            }
          }.orEmpty()
      if (qualifiedNames.isNotEmpty()) {
        edges += qualifiedNames
        qualifiedNames.forEach { noteUnresolved(it, names, unresolved) }
        return@forEach
      }
      val imported = imports[identifier]
      val member = enclosingClass?.let { "$it.$identifier".takeIf(names::contains) }
      val resolved = imported ?: setOfNotNull(member ?: "$packageName.$identifier".takeIf(names::contains))
      resolved.forEach { resolvedName ->
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
            }?.let { "$resolvedName.$it" }
            ?.takeIf(names::contains)
        val edge = member ?: resolvedName
        edges += edge
        noteUnresolved(edge, names, unresolved)
      }
    }
    return CapabilitySymbol(
      name,
      path,
      setOf(path),
      edges - name,
      incoming,
      unresolved,
      writerCalls(declaration, parameterTypes, packageName, imports, names),
    )
  }

  private fun writerCalls(
    declaration: KtDeclaration,
    parameters: Map<String, Set<String>>,
    packageName: String,
    imports: Map<String, Set<String>>,
    names: Set<String>,
  ): Set<String> {
    val receivers = parameters.toMutableMap()
    val properties = PsiTreeUtil.findChildrenOfType(declaration, KtProperty::class.java)
    properties.forEach { property ->
      val propertyName = property.name
      val propertyTypes =
        property.typeReference?.let { references(it, packageName, imports, names, linkedSetOf()) }.orEmpty()
      if (propertyName != null && propertyTypes.isNotEmpty()) receivers[propertyName] = propertyTypes
      val alias = property.initializer as? KtNameReferenceExpression
      alias?.getReferencedName()?.let { referenced ->
        receivers[referenced]?.let { types -> property.name?.let { receivers[it] = types } }
      }
    }
    val explicit =
      PsiTreeUtil
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
    val functions =
      (
        listOfNotNull(declaration as? KtNamedFunction) +
          PsiTreeUtil.findChildrenOfType(declaration, KtNamedFunction::class.java)
      ).distinct()
    val implicitExtensionWriters =
      functions
        .flatMap { function ->
          val receiverTypes =
            function.receiverTypeReference
              ?.let { receiver ->
                PsiTreeUtil
                  .findChildrenOfType(receiver, KtUserType::class.java)
                  .flatMap { type ->
                    val resolved = resolve(type, packageName, imports, names)
                    resolved.mapNotNull(StrategyCapabilityWriterInventory::qualifiedWriterOwner) +
                      listOfNotNull(
                        StrategyCapabilityWriterInventory.qualifiedWriterOwner(type.referencedName.orEmpty()),
                      )
                  }.toSet()
              }.orEmpty()
          if (receiverTypes.isEmpty()) return@flatMap emptyList()
          PsiTreeUtil.findChildrenOfType(function, KtCallExpression::class.java).mapNotNull { call ->
            val callee = call.calleeExpression as? KtNameReferenceExpression ?: return@mapNotNull null
            val qualified = call.parent as? KtDotQualifiedExpression
            if (qualified != null &&
              (
                qualified.selectorExpression !== call ||
                  qualified.receiverExpression !is KtThisExpression
              )
            ) {
              return@mapNotNull null
            }
            receiverTypes
              .map { owner -> "$owner.${callee.getReferencedName()}" }
              .firstOrNull(StrategyCapabilityWriterInventory.primitiveWriters::contains)
          }
        }.toSet()
    return explicit + implicitExtensionWriters
  }

  private fun references(
    type: KtTypeReference,
    packageName: String,
    imports: Map<String, Set<String>>,
    names: Set<String>,
    unresolved: MutableSet<String>,
  ): Set<String> =
    PsiTreeUtil
      .findChildrenOfType(type, KtUserType::class.java)
      .filterNot { (it.parent as? KtUserType)?.qualifier === it }
      .flatMapTo(linkedSetOf()) { userType ->
        resolve(userType, packageName, imports, names).onEach { noteUnresolved(it, names, unresolved) }
      }

  private fun qualifiedName(expression: KtExpression): String? =
    when (expression) {
      is KtNameReferenceExpression -> expression.getReferencedName()
      is KtDotQualifiedExpression -> {
        val receiver = qualifiedName(expression.receiverExpression)
        val selector = expression.selectorExpression?.let(::qualifiedName)
        if (receiver != null && selector != null) "$receiver.$selector" else null
      }
      else -> null
    }

  private fun enclosingClassName(
    declaration: KtDeclaration,
    packageName: String,
    names: Set<String>,
  ): String? {
    val classes = mutableListOf<String>()
    var parent = declaration.parent
    while (parent !is KtFile && parent != null) {
      if (parent is KtClassOrObject) parent.name?.let(classes::add)
      parent = parent.parent
    }
    if (classes.isEmpty()) return null
    val candidate = "$packageName.${classes.asReversed().joinToString(".")}"
    return candidate.takeIf(names::contains)
  }

  private fun resolve(
    type: KtUserType,
    packageName: String,
    imports: Map<String, Set<String>>,
    names: Set<String>,
  ): Set<String> {
    val simple = type.referencedName.orEmpty()
    val qualifier = type.qualifier?.text
    val qualified = if (qualifier == null) simple else "$qualifier.$simple"
    if (qualified.startsWith("skillbill.")) return setOf(qualified)
    imports[simple]?.let { return it }
    imports[qualified.substringBefore('.')]?.let { imported ->
      return imported.mapTo(linkedSetOf()) { it + qualified.removePrefix(qualified.substringBefore('.')) }
    }
    return setOf("$packageName.$qualified".takeIf(names::contains) ?: qualified)
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
