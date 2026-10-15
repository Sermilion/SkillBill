package dev.skillbill.tray.architecture

import org.jetbrains.kotlin.CoreEnvironmentDeprecation
import org.jetbrains.kotlin.K1Deprecation
import org.jetbrains.kotlin.cli.extensionsStorage
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.com.intellij.openapi.Disposable
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCallableReferenceExpression
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.psi.KtValueArgumentList
import kotlin.test.assertTrue

internal const val DESKTOP_PACKAGE_ROOT = "composeApp/src/jvmMain/kotlin/dev/skillbill/tray"

private val sortCalls = setOf(
  "sortedWith", "sortedBy", "sortedByDescending", "sortWith", "compareBy", "compareByDescending", "thenBy", "thenByDescending",
)

private val membershipCalls = setOf(
  "any", "all", "none", "count", "filter", "filterNot", "partition", "first", "firstOrNull", "groupBy", "sumOf",
)

private val policyEnums = setOf("Lifecycle", "Freshness")

internal const val SORTS_WORK = "sorts work itself"
internal const val JUDGES_MEMBERSHIP = "judges membership or aggregate status itself"
internal const val JUDGES_TIME_WINDOW = "judges a time window itself"
internal const val WEIGHS_FRESHNESS = "weighs freshness into eligibility itself"

private const val DURATION_UNITS = "seconds|minutes|hours|days"
private const val TIME_FACTS = "finishedAt|updatedAt|observedAt|freshnessDeadline"

/** A window of time measured against a raw timestamp: the app judging membership the runtime already decides. */
private val timeWindowJudgement = Regex(
  """\b(?:$TIME_FACTS)\b[^\n"]*\b\d+\.(?:$DURATION_UNITS)\b|\b\d+\.(?:$DURATION_UNITS)\b[^\n"]*\b(?:$TIME_FACTS)\b""",
)

/** Freshness weighed into an eligibility verdict; the runtime's eligibility already includes it. */
private val freshnessEligibility = Regex("""Freshness\.FRESH[^\n]*[eE]ligible|[eE]ligible[^\n]*Freshness\.FRESH""")

internal data class FunctionBody(
  val name: String,
  val path: String,
  val calls: Set<String>,
  val localOwner: String?,
  val contextOwner: String?,
  val functionDeclaration: Boolean,
  val calledFunctions: Set<String>,
  val packageName: String,
  val imports: Map<String, String>,
  val wildcardPackages: Set<String>,
  val memberOwners: Set<String>,
  val visibleNames: Set<String>,
  val minArity: Int,
  val maxArity: Int,
  val extensionReceiver: String?,
  val callSites: Map<String, List<CallSite>>,
  /** Policy the declaration decides itself: sorting, lifecycle or freshness predicates, time windows, eligibility. */
  val policySites: List<String> = emptyList(),
  /** The function or property whose body declares this one, for a local; locals are parsed as declarations of their own. */
  val enclosingDeclaration: String? = null,
)

/** One call of a name: how many arguments it passes and every type its receiver is, with that type's ancestors. */
internal data class CallSite(val arity: Int, val receiverTypes: Set<String>?)

/** A call only reaches a function that takes its arguments and, for a qualified call, is a member or extension of its receiver. */
internal fun FunctionBody.accepts(site: CallSite): Boolean {
  if (!functionDeclaration) return true
  val receivers = site.receiverTypes
  return site.arity in minArity..maxArity &&
    (receivers == null || memberOwners.any { it in receivers } || extensionReceiver in receivers)
}

/**
 * Follows every call, reference and type edge from [roots] through the parsed declarations. An empty root set and an edge
 * into project code that resolves to no declaration each fail the walk. An
 * explicit import of a [generated] declaration (fully qualified) resolves there and ends: generated code is derived
 * from the governed sources, which the walk already covers.
 */
internal fun walkGovernedEdges(
  functions: List<FunctionBody>,
  roots: List<FunctionBody>,
  generated: Set<String> = emptySet(),
): Set<FunctionBody> {
  assertTrue(roots.isNotEmpty(), "No composition, application, data, or control entry points were parsed.")
  val byName = functions.groupBy(FunctionBody::name)
  val sourcePackages = functions.map(FunctionBody::packageName).toSet()
  val visited = mutableSetOf<FunctionBody>()
  val pending = ArrayDeque(roots.map { it to listOf(it.path + ":" + it.name) })
  while (pending.isNotEmpty()) {
    val (function, trace) = pending.removeFirst()
    if (!visited.add(function)) continue
    function.calls.forEach { name ->
      val declarations = byName[name]
      val imported = function.imports[name]
      if (imported != null && imported in generated) return@forEach
      val importedPackage = imported?.substringBeforeLast('.')
      val projectOwned = (name in function.calledFunctions || declarations.orEmpty().any {
        !it.functionDeclaration && it.packageName == importedPackage && it.memberOwners.isEmpty() && it.localOwner == null
      } || name.firstOrNull()?.isUpperCase() == true) &&
        (importedPackage in sourcePackages || declarations.orEmpty().any {
        it.packageName in function.wildcardPackages && it.localOwner == null && it.memberOwners.isEmpty()
      } ||
        imported?.startsWith("skillbill.") == true || imported?.startsWith("dev.skillbill.") == true)
      val importedTarget = function.imports[name]
      val visiblePackages = buildSet {
        add(function.packageName)
        importedTarget?.substringBeforeLast('.')?.let(::add)
        addAll(function.wildcardPackages)
      }
      val sites = function.callSites[name].orEmpty()
      val candidates = declarations?.filter { target ->
        val explicitlyImported = imported == "${target.packageName}.${target.name}" ||
          target.memberOwners.any { imported == "${target.packageName}.$it.${target.name}" }
        (!target.functionDeclaration || name in function.calledFunctions) &&
          (sites.isEmpty() || sites.any { target.accepts(it) }) &&
          (explicitlyImported || target.packageName in visiblePackages || target.memberOwners.any { it in function.visibleNames }) &&
          (explicitlyImported || target.memberOwners.isEmpty() || target.path == function.path ||
            target.memberOwners.any { it in function.visibleNames }) &&
          (target.localOwner == null || target.path == function.path && target.localOwner == function.contextOwner)
      } ?: if (projectOwned) {
        error("Unresolved governed edge at ${function.path}:${function.name}: $name, import=$imported")
      } else emptyList()
      if (candidates.isEmpty() && projectOwned) error("Unresolved governed edge at ${function.path}:${function.name}: $name, import=$imported")
      candidates.forEach { target -> pending.addLast(target to trace + (target.path + ":" + target.name)) }
    }
  }
  return visited
}

@OptIn(K1Deprecation::class, CoreEnvironmentDeprecation::class, CompilerConfiguration.Internals::class, ExperimentalCompilerApi::class)
private fun parseFiles(disposable: Disposable, sources: List<Source>): Map<Source, KtFile> {
  val environment = KotlinCoreEnvironment.createForProduction(
    disposable,
    CompilerConfiguration().apply { extensionsStorage = CompilerPluginRegistrar.ExtensionStorage() },
    EnvironmentConfigFiles.JVM_CONFIG_FILES,
  )
  val factory = KtPsiFactory(environment.project, false)
  return sources.associateWith { source -> factory.createFile(source.path.substringAfterLast('/'), source.text) }
}

/** The fully qualified top-level declarations of [sources], the names an import can reach. */
internal fun generatedDeclarations(sources: List<Source>): Set<String> {
  val disposable = Disposer.newDisposable()
  return try {
    parseFiles(disposable, sources).values.flatMapTo(mutableSetOf()) { file ->
      file.declarations.filterIsInstance<KtNamedDeclaration>().mapNotNull { it.name }.map { "${file.packageFqName.asString()}.$it" }
    }
  } finally {
    Disposer.dispose(disposable)
  }
}

internal fun parsedSymbols(sources: List<Source>): List<FunctionBody> {
  val disposable = Disposer.newDisposable()
  return try {
    val files = parseFiles(disposable, sources)
    val governedTypes = files.values.flatMap { PsiTreeUtil.findChildrenOfType(it, KtClassOrObject::class.java) }
      .mapNotNull { it.name }.toSet()
    val propertyTypes = files.values.flatMap { PsiTreeUtil.findChildrenOfType(it, KtProperty::class.java) }
      .mapNotNull { property -> property.typeReference?.text?.let {
        property.name to it.substringBefore('<').removeSuffix("?").substringAfterLast('.')
      } }.groupBy({ it.first }, { it.second }).mapValues { it.value.toSet() }
    val returnTypes = files.values.flatMap { PsiTreeUtil.findChildrenOfType(it, KtNamedFunction::class.java) }
      .mapNotNull { function -> function.typeReference?.text?.let {
        function.name to it.substringBefore('<').removeSuffix("?").substringAfterLast('.')
      } }.groupBy({ it.first }, { it.second }).mapNotNull { (name, types) ->
        types.distinct().singleOrNull()?.let { name to it }
      }.toMap()
    val directSupertypes = files.values.flatMap { PsiTreeUtil.findChildrenOfType(it, KtClassOrObject::class.java) }
      .filter { it.name != null }.groupBy({ it.name.orEmpty() }, { type ->
        type.superTypeListEntries.flatMap { entry ->
          Regex("[A-Za-z_][A-Za-z0-9_]*").findAll(entry.text).map(MatchResult::value).toList()
        }.filter { it in governedTypes }
      }).mapValues { (_, lists) -> lists.flatten().toSet() }
    fun withAncestors(type: String): Set<String> {
      val found = mutableSetOf(type)
      val pending = ArrayDeque(listOf(type))
      while (pending.isNotEmpty()) directSupertypes[pending.removeFirst()].orEmpty().filter(found::add).forEach(pending::addLast)
      return found
    }
    val namesByPackage = files.values.groupBy { it.packageFqName.asString() }.mapValues { (_, packageFiles) ->
      packageFiles.flatMap { PsiTreeUtil.findChildrenOfType(it, KtNamedDeclaration::class.java) }
        .filter { it is KtNamedFunction || it is KtClassOrObject || it is KtProperty }
        .mapNotNull { it.name }.toSet()
    }
    files.flatMap { (source, file) ->
      val aliases = file.importDirectives.mapNotNull { directive ->
        val fqName = directive.importedFqName?.asString() ?: return@mapNotNull null
        (directive.aliasName ?: fqName.substringAfterLast('.')) to fqName.substringAfterLast('.')
      }.toMap()
      val visibleNames = namesByPackage[file.packageFqName.asString()].orEmpty() + aliases.keys +
        file.importDirectives.filter { it.isAllUnder }.flatMap {
          namesByPackage[it.importedFqName?.asString()].orEmpty()
        }
      (PsiTreeUtil.findChildrenOfType(file, KtNamedFunction::class.java) +
        PsiTreeUtil.findChildrenOfType(file, KtProperty::class.java) +
        PsiTreeUtil.findChildrenOfType(file, KtClassOrObject::class.java)).map { declaration ->
        val owner = declaration as? KtClassOrObject
          ?: PsiTreeUtil.getParentOfType(declaration, KtClassOrObject::class.java, true)
        // The enclosing function's locals (a local val is a declaration of its own), typed by their declaration or by what
        // initializes them: a constructor or a call with a declared return type.
        val enclosing = PsiTreeUtil.getParentOfType(declaration, KtNamedFunction::class.java, false)
        val localTypes = enclosing?.let { ownedChildren(it, KtProperty::class.java) }.orEmpty().mapNotNull { property ->
          val callee = (property.initializer as? KtCallExpression)?.calleeExpression?.text
          val type = property.typeReference?.text
            ?: callee?.let { returnTypes[it] ?: it.takeIf { name -> name.firstOrNull()?.isUpperCase() == true } }
          type?.let { property.name to it.substringBefore('<').removeSuffix("?").substringAfterLast('.') }
        }.toMap()
        val typedReceivers = owner?.declarations.orEmpty().filterIsInstance<KtProperty>().mapNotNull { property ->
          val type = property.typeReference?.text ?: (property.initializer as? KtCallExpression)?.calleeExpression?.text
          type?.let { property.name to it.substringBefore('<').removeSuffix("?").substringAfterLast('.') }
        }.toMap() + ((declaration as? KtNamedFunction)?.valueParameters.orEmpty() +
          owner?.primaryConstructorParameters.orEmpty()).mapNotNull { parameter ->
            parameter.typeReference?.text?.let { parameter.name to it.substringBefore('<').removeSuffix("?").substringAfterLast('.') }
          }.toMap() + localTypes
        fun receiverTypeOf(call: KtCallExpression): String? {
          // Only a call that is the selector has a receiver; in `f(x).g()` the call `f(x)` is itself the receiver of `g`.
          val receiver = (call.parent as? KtDotQualifiedExpression)?.takeIf { it.selectorExpression === call }?.receiverExpression
          return typedReceivers[receiver?.text]
            ?: (receiver as? KtCallExpression)?.calleeExpression?.text?.let {
              returnTypes[it] ?: it.takeIf { name -> name.firstOrNull()?.isUpperCase() == true }
            }
        }
        val actualCalls = ownedChildren(declaration, KtCallExpression::class.java).filter { call ->
          val qualified = call.parent as? KtDotQualifiedExpression
          val receiverType = receiverTypeOf(call)
          (receiverType == null || receiverType in governedTypes) &&
            (call.calleeExpression?.text in visibleNames || qualified?.selectorExpression === call)
        }
        val parameters = (declaration as? KtNamedFunction)?.valueParameters
        val callSites = actualCalls.mapNotNull { call ->
          val called = call.calleeExpression?.text?.substringAfterLast('.') ?: return@mapNotNull null
          (aliases[called] ?: called) to CallSite(call.valueArguments.size, receiverTypeOf(call)?.let(::withAncestors))
        }.groupBy({ it.first }, { it.second })
        FunctionBody(
          name = declaration.name.orEmpty(),
          path = source.path,
          packageName = file.packageFqName.asString(),
          imports = file.importDirectives.filterNot { it.isAllUnder }.mapNotNull { directive ->
            directive.importedFqName?.asString()?.let { it.substringAfterLast('.') to it }
          }.toMap(),
          wildcardPackages = file.importDirectives.filter { it.isAllUnder }
            .mapNotNull { it.importedFqName?.asString() }.toSet(),
          localOwner = PsiTreeUtil.getParentOfType(declaration, KtNamedFunction::class.java, true)?.name,
          contextOwner = (declaration as? KtNamedFunction)?.name
            ?: PsiTreeUtil.getParentOfType(declaration, KtNamedFunction::class.java, true)?.name,
          functionDeclaration = declaration is KtNamedFunction,
          memberOwners = buildSet {
            owner?.name?.let(::add)
            owner?.superTypeListEntries.orEmpty().flatMap {
              Regex("[A-Za-z_][A-Za-z0-9_]*").findAll(it.text).map(MatchResult::value).toList()
            }.forEach(::add)
          },
          // A typed receiver brings its members into view even when its type is never named, as with a call's return value.
          visibleNames = visibleNames + localTypes.values + callSites.values.flatten().flatMap { it.receiverTypes.orEmpty() } +
            ownedChildren(declaration, KtNameReferenceExpression::class.java).flatMap { propertyTypes[it.getReferencedName()].orEmpty() },
          minArity = parameters?.count { !it.hasDefaultValue() && !it.isVarArg } ?: 0,
          maxArity = if (parameters == null || parameters.any { it.isVarArg }) Int.MAX_VALUE else parameters.size,
          extensionReceiver = (declaration as? KtNamedFunction)?.receiverTypeReference?.text
            ?.substringBefore('<')?.removeSuffix("?")?.substringAfterLast('.'),
          callSites = callSites,
          calledFunctions = actualCalls.mapNotNull { it.calleeExpression?.text?.substringAfterLast('.') }
            .map { aliases[it] ?: it }.toSet() +
            ownedChildren(declaration, KtCallableReferenceExpression::class.java)
              .map { it.callableReference.getReferencedName() }.map { aliases[it] ?: it },
          calls = buildSet {
            actualCalls.mapNotNull { it.calleeExpression?.text?.substringAfterLast('.') }
              .mapTo(this) { aliases[it] ?: it }
            ownedChildren(declaration, KtNameReferenceExpression::class.java)
              .filter { reference ->
                reference.getReferencedName() in visibleNames ||
                  (reference.parent as? KtDotQualifiedExpression)?.selectorExpression === reference ||
                  (reference.parent.parent as? KtDotQualifiedExpression)?.selectorExpression === reference.parent
              }
              .mapTo(this) { aliases[it.getReferencedName()] ?: it.getReferencedName() }
            ownedChildren(declaration, KtTypeReference::class.java)
              .flatMap { Regex("[A-Za-z_][A-Za-z0-9_]*").findAll(it.text).map(MatchResult::value).toList() }
              .filter { it in visibleNames }
              .mapTo(this) { aliases[it] ?: it }
            ownedChildren(declaration, KtCallableReferenceExpression::class.java)
              .map { it.callableReference.getReferencedName() }.mapTo(this) { aliases[it] ?: it }
          },
          policySites = policySitesOf(declaration),
          enclosingDeclaration = PsiTreeUtil.getParentOfType(declaration, true, KtNamedFunction::class.java, KtProperty::class.java)?.name,
        )
      }
    }
  } finally {
    Disposer.dispose(disposable)
  }
}

/** The policy a declaration decides itself, found in its own PSI rather than in the text of its whole file. */
private fun policySitesOf(declaration: KtDeclaration): List<String> = buildList {
  if (ownedChildren(declaration, KtCallExpression::class.java).any { it.calleeExpression?.text in sortCalls }) add(SORTS_WORK)
  if (ownedChildren(declaration, KtDotQualifiedExpression::class.java).any { reference ->
      reference.receiverExpression.text in policyEnums && reference.insideMembershipLambda(declaration)
    }) {
    add(JUDGES_MEMBERSHIP)
  }
  // A class's own text holds its members, which are declarations of their own; only functions and properties carry a window.
  if (declaration !is KtClassOrObject) {
    if (timeWindowJudgement.containsMatchIn(declaration.text)) add(JUDGES_TIME_WINDOW)
    if (freshnessEligibility.containsMatchIn(declaration.text)) add(WEIGHS_FRESHNESS)
  }
}

/** Whether this reference sits in a lambda passed to a membership or aggregate call of [declaration]. */
private fun KtDotQualifiedExpression.insideMembershipLambda(declaration: KtDeclaration): Boolean =
  generateSequence<PsiElement>(parent) { it.parent }.takeWhile { it !== declaration }
    .filterIsInstance<KtLambdaExpression>().any { lambda ->
      val argument = lambda.parent
      val container = argument?.parent
      val call = (if (container is KtValueArgumentList) container.parent else container) as? KtCallExpression
      call?.calleeExpression?.text in membershipCalls
    }

internal fun <T : PsiElement> ownedChildren(declaration: KtDeclaration, type: Class<T>): List<T> =
  PsiTreeUtil.findChildrenOfType(declaration, type).filter { child ->
    generateSequence(child.parent) { it.parent }.takeWhile { it !== declaration }
      .none { it is KtNamedFunction || it is KtClassOrObject || it is KtProperty }
  }
