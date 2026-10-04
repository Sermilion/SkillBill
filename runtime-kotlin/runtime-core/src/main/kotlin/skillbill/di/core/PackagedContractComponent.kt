package skillbill.di.core

import me.tatarka.inject.annotations.Component
import skillbill.ports.system.PackagedContractInspector

@Component
abstract class PackagedContractComponent : PackagedContractProvides {
  abstract val inspector: PackagedContractInspector
}
