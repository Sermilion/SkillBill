package skillbill.di.featurespec

import me.tatarka.inject.annotations.Provides
import skillbill.infrastructure.workflow.filesystem.FileSystemChildFailureOutputStore
import skillbill.infrastructure.workflow.filesystem.FileSystemFeatureSpecPathResolver
import skillbill.infrastructure.workflow.filesystem.FileSystemGoalRuntimeStateFileStore
import skillbill.infrastructure.workflow.filesystem.FileSystemSpecScratchStore
import skillbill.ports.diagnostics.ChildFailureOutputStore
import skillbill.ports.featurespec.FeatureSpecPathResolverPort
import skillbill.ports.workflow.goalstate.GoalRuntimeStateFileStore
import skillbill.ports.workflow.specscratch.SpecScratchStore

internal interface RuntimeFeatureSpecProvides {
  @Provides
  fun childFailureOutputStore(store: FileSystemChildFailureOutputStore): ChildFailureOutputStore = store

  @Provides
  fun specScratchStore(store: FileSystemSpecScratchStore): SpecScratchStore = store

  @Provides
  fun goalRuntimeStateFileStore(store: FileSystemGoalRuntimeStateFileStore): GoalRuntimeStateFileStore = store

  @Provides
  fun featureSpecPathResolverPort(adapter: FileSystemFeatureSpecPathResolver): FeatureSpecPathResolverPort = adapter
}
