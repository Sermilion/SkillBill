package skillbill.scaffold.policy.platformpack

import skillbill.error.core.ambiguousExternalPlatformPack
import skillbill.scaffold.policy.platformpack.model.EffectivePlatformPackCatalog
import skillbill.scaffold.policy.platformpack.model.EffectivePlatformPackEntry
import skillbill.scaffold.policy.platformpack.model.LoadedPlatformPack

fun buildEffectivePlatformPackCatalog(
  bundled: List<LoadedPlatformPack>,
  external: List<LoadedPlatformPack>,
  bundledSlugs: Set<String> = bundled.map { pack -> pack.manifest.slug }.toSet(),
): EffectivePlatformPackCatalog {
  val bundledBySlug = bundled.associateBy { pack -> pack.manifest.slug }
  val externalBySlug = linkedMapOf<String, LoadedPlatformPack>()
  external.forEach { pack ->
    val slug = pack.manifest.slug
    val existing = externalBySlug[slug]
    if (existing != null && existing.canonicalRoot != pack.canonicalRoot) {
      throw ambiguousExternalPlatformPack(
        "External platform pack slug '$slug' is declared by multiple roots: " +
          "'${existing.canonicalRoot}' and '${pack.canonicalRoot}'.",
      )
    }
    externalBySlug[slug] = pack
  }
  val slugs = (bundledSlugs + bundledBySlug.keys + externalBySlug.keys).toSortedSet()
  val entries =
    slugs.map { slug ->
      val externalPack = externalBySlug[slug]
      if (externalPack != null) {
        EffectivePlatformPackEntry(
          loaded = externalPack,
          shadowedBundledSlug = slug.takeIf { it in bundledSlugs },
        )
      } else {
        val bundledPack = bundledBySlug.getValue(slug)
        EffectivePlatformPackEntry(loaded = bundledPack, shadowedBundledSlug = null)
      }
    }
  return EffectivePlatformPackCatalog(entries)
}
