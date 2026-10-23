package skillbill.ports.featurespec

import skillbill.ports.featurespec.model.FeatureSpecPathResolveInput
import skillbill.ports.featurespec.model.FeatureSpecPathResolveResult

/**
 * Resolves a feature spec by issue key, searching the project store first and the legacy `.feature-specs/`
 * directory second. Matches carry the origin they came from.
 */
fun interface FeatureSpecPathResolverPort {
  fun resolve(input: FeatureSpecPathResolveInput): FeatureSpecPathResolveResult
}
