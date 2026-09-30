package skillbill.engine.operation.verify

internal sealed interface VerifyIntake {
  val storageKey: String
  val value: String
  val label: String

  data class SpecFile(
    override val value: String,
  ) : VerifyIntake {
    override val storageKey: String get() = VerifyWorkflow.SPEC_PATH
    override val label: String get() = value
  }

  data class Text(
    override val value: String,
  ) : VerifyIntake {
    override val storageKey: String get() = VerifyWorkflow.INTAKE
    override val label: String
      get() {
        val firstLine = value.lineSequence().map(String::trim).firstOrNull(String::isNotEmpty).orEmpty()
        return if (firstLine.length > LABEL_LIMIT) firstLine.take(LABEL_LIMIT).trimEnd() + ELLIPSIS else firstLine
      }
  }

  companion object {
    private const val LABEL_LIMIT = 120
    private const val ELLIPSIS = "…"

    fun stored(inputContext: Any?): VerifyIntake? =
      VerifyWorkflow.string(inputContext, VerifyWorkflow.SPEC_PATH)?.let(::SpecFile)
        ?: VerifyWorkflow.string(inputContext, VerifyWorkflow.INTAKE)?.let(::Text)
  }
}
