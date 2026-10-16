package skillbill.infrastructure.workflow.monitor

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

internal const val GUARD_REFUSAL_MARKER = "skill-bill pre-push guard refused"

internal const val GUARD_EXPECT_URL = "SKILL_BILL_PUSH_EXPECT_URL"
internal const val GUARD_EXPECT_REF = "SKILL_BILL_PUSH_EXPECT_REF"
internal const val GUARD_EXPECT_LOCAL_SHA = "SKILL_BILL_PUSH_EXPECT_LOCAL_SHA"
internal const val GUARD_EXPECT_REMOTE_SHA = "SKILL_BILL_PUSH_EXPECT_REMOTE_SHA"
internal const val GUARD_ORIGINAL_HOOK = "SKILL_BILL_PUSH_ORIGINAL_HOOK"

private val GUARD_SCRIPT =
  """
  |#!/bin/sh
  |refuse() { echo "$GUARD_REFUSAL_MARKER: ${'$'}1" >&2; exit 1; }
  |input="${'$'}(dirname "${'$'}0")/pushed-refs"
  |cat > "${'$'}input"
  |[ "${'$'}1" = "${'$'}$GUARD_EXPECT_URL" ] || refuse "remote ${'$'}1 is not the pinned head endpoint"
  |count=0
  |while read -r local_ref local_sha remote_ref remote_sha; do
  |  count=${'$'}((count + 1))
  |  [ "${'$'}remote_ref" = "${'$'}$GUARD_EXPECT_REF" ] || refuse "destination ${'$'}remote_ref is not ${'$'}$GUARD_EXPECT_REF"
  |  [ "${'$'}local_sha" = "${'$'}$GUARD_EXPECT_LOCAL_SHA" ] || refuse "pushed object ${'$'}local_sha is not the repair commit"
  |  [ "${'$'}remote_sha" = "${'$'}$GUARD_EXPECT_REMOTE_SHA" ] || refuse "remote head is ${'$'}remote_sha, not the last published head"
  |done < "${'$'}input"
  |[ "${'$'}count" -eq 1 ] || refuse "expected exactly one ref update, saw ${'$'}count"
  |if [ -n "${'$'}$GUARD_ORIGINAL_HOOK" ]; then
  |  "${'$'}$GUARD_ORIGINAL_HOOK" "${'$'}@" < "${'$'}input" || exit 1
  |fi
  |exit 0
  |
  """.trimMargin()

internal fun writeMonitorPrePushGuard(): Path {
  val ownerOnly = PosixFilePermissions.fromString("rwx------")
  val directory = Files.createTempDirectory("skill-bill-prepush-", PosixFilePermissions.asFileAttribute(ownerOnly))
  val hook = directory.resolve("pre-push")
  Files.writeString(hook, GUARD_SCRIPT)
  Files.setPosixFilePermissions(hook, ownerOnly)
  return directory
}

internal fun removeMonitorPrePushGuard(directory: Path) {
  runCatching {
    Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
  }
}
