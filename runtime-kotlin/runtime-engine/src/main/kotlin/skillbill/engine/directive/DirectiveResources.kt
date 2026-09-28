package skillbill.engine.directive

internal fun directiveResource(path: String): String =
  requireNotNull(DirectiveResourceAnchor::class.java.getResourceAsStream(path)) {
    "Missing directive resource $path."
  }.use { stream -> stream.readBytes().decodeToString() }

private object DirectiveResourceAnchor
