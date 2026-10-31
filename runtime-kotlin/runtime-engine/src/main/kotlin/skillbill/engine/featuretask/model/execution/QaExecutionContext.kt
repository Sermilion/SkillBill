package skillbill.engine.featuretask.model.execution

import skillbill.model.FileLocation
import skillbill.ports.qa.model.QaWorkspace
import skillbill.scaffold.model.QaTargetKind
import java.nio.file.Path

public data class QaExecutionContext(
  public val workspace: QaWorkspace,
  public val expected: QaExpectedIdentity,
  public val target: QaExecutionTarget,
  public val policy: QaPolicy = QaPolicy.STANDALONE,
  internal val preparationWarnings: List<String> = emptyList(),
  internal val callerBaseline: QaCallerBaseline? = null,
)

public sealed interface QaExecutionTarget {
  public data class Supported(
    public val targets: List<QaDeclaredTarget>,
    public val checkout: Path,
  ) : QaExecutionTarget {
    init {
      require(targets.isNotEmpty()) { "A supported QA target needs at least one declared target." }
    }
  }

  public data class Skipped(val reason: QaSkipReason, val detail: String) : QaExecutionTarget
}

public data class QaDeclaredTarget(
  public val packSlug: String,
  public val kinds: Set<QaTargetKind>,
  public val guidancePath: FileLocation,
)

public data class QaExpectedIdentity(
  public val headRevision: String,
  public val treeIdentity: String,
  public val evidencePrefix: String,
)

public data class QaCallerBaseline(
  public val head: String,
  public val treeIdentity: String,
  public val status: String,
)
