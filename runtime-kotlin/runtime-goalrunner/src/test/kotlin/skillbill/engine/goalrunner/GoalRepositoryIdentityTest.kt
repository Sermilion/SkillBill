package skillbill.engine.goalrunner

import skillbill.application.TestRepositoryEnclosingRoot
import skillbill.engine.goal.goalRepositoryIdentity
import java.nio.file.Path

fun goalRepositoryIdentity(repoRoot: Path): String = goalRepositoryIdentity(repoRoot, TestRepositoryEnclosingRoot)
