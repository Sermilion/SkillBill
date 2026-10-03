package skillbill.di.core

import me.tatarka.inject.annotations.Provides
import skillbill.infrastructure.contracts.system.ClasspathPackagedContractInspector
import skillbill.ports.system.PackagedContractInspector

internal interface PackagedContractProvides {
  @Provides
  fun inspector(implementation: ClasspathPackagedContractInspector): PackagedContractInspector = implementation
}
