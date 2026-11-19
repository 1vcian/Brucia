package app.brucia.features

/** A screen supplied by the app composition root, addressed by a stable catalog ID. */
data class FeatureScreenBinding<S : Any>(
    val featureId: String,
    /** Some legacy screens (NRF24) present their own missing-module UI. */
    val handlesCapability: Boolean = false,
    val screen: S,
)

data class FeatureDestination<S : Any>(
    val feature: FeatureDescriptor,
    val screen: S,
    val handlesCapability: Boolean,
)

/**
 * Complete, validated catalog-to-screen association. Pure Kotlin: no Compose, Activity,
 * transport or ViewModel dependency. Screen values can be composables or test fixtures.
 * Unknown navigation returns absence; it must never launch an unrelated device operation.
 */
class FeatureScreenRegistry<S : Any> private constructor(
    val destinations: List<FeatureDestination<S>>,
) {
    private val byId = destinations.associateBy { it.feature.id }
    private val byTabId = destinations.associateBy { it.feature.tabId }

    fun byId(id: String): FeatureDestination<S>? = byId[id]
    fun byTabId(tabId: Int): FeatureDestination<S>? = byTabId[tabId]

    companion object {
        fun <S : Any> of(
            catalog: FeatureRegistry,
            bindings: List<FeatureScreenBinding<S>>,
        ): FeatureScreenRegistry<S> {
            val indexed = LinkedHashMap<String, FeatureScreenBinding<S>>()
            for (binding in bindings) {
                require(catalog.byId(binding.featureId) != null) {
                    "Screen has no catalog feature: '${binding.featureId}'"
                }
                require(indexed.put(binding.featureId, binding) == null) {
                    "Duplicate screen for feature: '${binding.featureId}'"
                }
            }
            val missing = catalog.features.filter { it.id !in indexed }
            require(missing.isEmpty()) { "Missing screens for features: ${missing.map { it.id }}" }
            return FeatureScreenRegistry(catalog.features.map { feature ->
                val binding = indexed.getValue(feature.id)
                FeatureDestination(feature, binding.screen, binding.handlesCapability)
            })
        }
    }
}
