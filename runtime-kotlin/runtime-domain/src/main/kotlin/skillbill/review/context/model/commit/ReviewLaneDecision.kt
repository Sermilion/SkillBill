package skillbill.review.context.model.commit

import skillbill.review.context.model.hunk.canonicalFieldList
import skillbill.review.context.model.hunk.requireRepositoryRelativePath

data class ReviewLaneDecision(
  val lane: String,
  val included: Boolean,
  val reason: String,
  val signals: List<String> = emptyList(),
  val ownedPaths: List<String> = emptyList(),
  val orderIndex: Int = 0,
  val required: Boolean = false,
  val originLayerChains: List<List<String>> = emptyList(),
  val owningPack: String? = null,
  val specialistSkillName: String? = null,
  val addOns: List<String> = emptyList(),
) {
  init {
    require(lane.isNotBlank()) { "Lane decision lane must not be blank." }
    require(reason.isNotBlank()) { "Lane decision '$lane' must carry a non-blank reason." }
    require(signals.distinct().size == signals.size) { "Lane decision signals must be unique." }
    ownedPaths.forEach(::requireRepositoryRelativePath)
    require(normalizedOwnedPaths.distinct().size == ownedPaths.size) {
      "Lane decision '$lane' owned paths must be unique."
    }
    require(!included || ownedPaths.isNotEmpty()) {
      "Included lane '$lane' must declare the paths it owns so assignments partition the packet."
    }
    require(included || ownedPaths.isEmpty()) { "Excluded lane '$lane' cannot own paths." }
    require(orderIndex >= 0) { "Lane decision order index cannot be negative." }
    require(originLayerChains.all { it.isNotEmpty() && it.all(String::isNotBlank) }) {
      "Lane decision origin chains must contain non-blank pack slugs."
    }
    require(originLayerChains.distinct().size == originLayerChains.size) {
      "Lane decision origin chains must be unique."
    }
    require(addOns.distinct().size == addOns.size) { "Lane decision add-ons must be unique." }
    if (included) {
      require(originLayerChains.isNotEmpty()) { "Included lane '$lane' must declare an origin chain." }
      require(!owningPack.isNullOrBlank()) { "Included lane '$lane' must declare its owning pack." }
      require(!specialistSkillName.isNullOrBlank()) { "Included lane '$lane' must declare its specialist skill." }
    }
  }

  val normalizedOwnedPaths: List<String> get() = ownedPaths

  val canonical: String
    get() =
      listOf(
        lane,
        included.toString(),
        reason,
        canonicalFieldList(signals.sorted()),
        canonicalFieldList(normalizedOwnedPaths.sorted()),
        orderIndex.toString(),
        required.toString(),
        canonicalFieldList(originLayerChains.map { canonicalFieldList(it) }),
        owningPack.orEmpty(),
        specialistSkillName.orEmpty(),
        canonicalFieldList(addOns),
      )
        .let { canonicalFieldList(it) }
}
