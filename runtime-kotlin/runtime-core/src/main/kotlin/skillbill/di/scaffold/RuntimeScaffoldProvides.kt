package skillbill.di.scaffold

import me.tatarka.inject.annotations.Provides
import skillbill.infrastructure.skills.install.scaffold.FileSystemScaffoldCatalogGateway
import skillbill.infrastructure.skills.install.scaffold.FileSystemScaffoldGateway
import skillbill.infrastructure.skills.scaffold.adapters.FileSystemScaffoldRepoValidation
import skillbill.infrastructure.skills.scaffold.platformpack.catalog.PlatformPackCatalogLoader
import skillbill.model.EnvironmentContext
import skillbill.ports.scaffold.ScaffoldCatalogGateway
import skillbill.ports.scaffold.ScaffoldGateway

internal interface RuntimeScaffoldProvides {
  @Provides
  fun scaffoldRepoValidation(
    environmentContext: EnvironmentContext,
    catalogLoader: PlatformPackCatalogLoader,
  ): FileSystemScaffoldRepoValidation = FileSystemScaffoldRepoValidation(environmentContext, catalogLoader)

  @Provides
  fun scaffoldGateway(gateway: FileSystemScaffoldGateway): ScaffoldGateway = gateway

  @Provides
  fun scaffoldCatalogGateway(gateway: FileSystemScaffoldCatalogGateway): ScaffoldCatalogGateway = gateway
}
