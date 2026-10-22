package skillbill.di.telemetry

import me.tatarka.inject.annotations.Provides
import skillbill.application.telemetry.lifecycle.GoalLifecycleTelemetryEmitter
import skillbill.application.telemetry.lifecycle.LifecycleTelemetryService
import skillbill.application.telemetry.service.TelemetryLevelMutationService
import skillbill.application.telemetry.settings.DefaultTelemetrySettingsProvider
import skillbill.infrastructure.host.FileTelemetryConfigStore
import skillbill.infrastructure.host.FileBlockIncidentSpool
import skillbill.ports.blockincident.BlockIncidentSpool
import skillbill.infrastructure.http.HttpTelemetryClient
import skillbill.ports.telemetry.transport.TelemetryClient
import skillbill.ports.telemetry.transport.TelemetryConfigStore
import skillbill.ports.telemetry.transport.TelemetryLevelMutator
import skillbill.ports.telemetry.transport.TelemetrySettingsProvider

internal interface RuntimeTelemetryProvides {
  @Provides
  fun blockIncidentSpool(spool: FileBlockIncidentSpool): BlockIncidentSpool = spool

  @Provides
  fun telemetryConfigStore(store: FileTelemetryConfigStore): TelemetryConfigStore = store

  @Provides
  fun telemetrySettingsProvider(provider: DefaultTelemetrySettingsProvider): TelemetrySettingsProvider = provider

  @Provides
  fun telemetryClient(client: HttpTelemetryClient): TelemetryClient = client

  @Provides
  fun telemetryLevelMutator(service: TelemetryLevelMutationService): TelemetryLevelMutator = service

  @Provides
  fun goalLifecycleTelemetryEmitter(service: LifecycleTelemetryService): GoalLifecycleTelemetryEmitter = service
}
