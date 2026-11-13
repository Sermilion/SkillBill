package skillbill.infrastructure.contracts.projectchecks

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import skillbill.infrastructure.contracts.locator.ProjectCheckCommandSnapshotSchemaPaths

private const val SNAPSHOT_DEF = "projectCheckCommandSnapshot"
private const val DEFS = "\$defs"
private const val REF = "\$ref"

internal fun JsonNode.inlineProjectCheckCommandSnapshotSchemaRefs(): JsonNode {
  val root = this as? ObjectNode ?: return this
  val body = snapshotSchemaBody()
  check(body is ObjectNode) { "project-check-command snapshot schema must be an object" }
  rewriteLocalDefsRefs(body, "#/$DEFS/$SNAPSHOT_DEF/$DEFS")
  val defs = root.get(DEFS) as? ObjectNode ?: root.putObject(DEFS)
  defs.set<JsonNode>(SNAPSHOT_DEF, body)
  replaceSnapshotRefs(root)
  return root
}

private fun snapshotSchemaBody(): JsonNode {
  val stream =
    checkNotNull(
      Thread.currentThread().contextClassLoader.getResourceAsStream(
        ProjectCheckCommandSnapshotSchemaPaths.CLASSPATH_RESOURCE,
      )
        ?: ProjectCheckCommandSnapshotSchemaClasspath::class.java.classLoader.getResourceAsStream(
          ProjectCheckCommandSnapshotSchemaPaths.CLASSPATH_RESOURCE,
        ),
    ) {
      "project-check-command snapshot schema is missing from the classpath at " +
        ProjectCheckCommandSnapshotSchemaPaths.CLASSPATH_RESOURCE
    }
  val raw = stream.use { YAMLMapper().readTree(it) }
  check(raw is ObjectNode) { "project-check-command snapshot schema must be an object" }
  raw.remove("\$schema")
  raw.remove("\$id")
  raw.remove("title")
  return raw
}

private fun replaceSnapshotRefs(node: JsonNode) {
  if (node is ObjectNode) {
    val ref = node.get(REF)?.asText()
    if (ref == ProjectCheckCommandSnapshotSchemaPaths.EXPECTED_SCHEMA_ID) {
      node.removeAll()
      node.put(REF, "#/$DEFS/$SNAPSHOT_DEF")
      return
    }
    node.fields().forEachRemaining { field -> replaceSnapshotRefs(field.value) }
    return
  }
  if (node.isArray) {
    node.forEach(::replaceSnapshotRefs)
  }
}

private fun rewriteLocalDefsRefs(
  node: JsonNode,
  targetPrefix: String,
) {
  if (node is ObjectNode) {
    val ref = node.get(REF)?.asText()
    if (ref != null && ref.startsWith("#/$DEFS/")) {
      node.put(REF, targetPrefix + ref.removePrefix("#/$DEFS"))
    }
    node.fields().forEachRemaining { field -> rewriteLocalDefsRefs(field.value, targetPrefix) }
    return
  }
  if (node.isArray) {
    node.forEach { child -> rewriteLocalDefsRefs(child, targetPrefix) }
  }
}

private object ProjectCheckCommandSnapshotSchemaClasspath
