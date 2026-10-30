package app.brucia.features

/**
 * Pure-JVM feature catalog. This component has NO Android, Compose, transport or
 * ViewModel dependencies. It only describes and indexes the existing tools so they
 * can be enumerated and looked up; [app.brucia.MainActivity] renders from it.
 *
 * [tabId] is the legacy integer tab id used by [app.brucia.MainActivity]. It is kept
 * as a stable adapter so existing navigation keeps working. [id] is an internal,
 * deterministic stable string id (reverse-DNS style, e.g. "org.brucia.wifi.wardrive")
 * frozen in [BuiltinFeatures]; it is NOT derived from localized labels at runtime and
 * NOT a versioned public plugin API.
 *
 * [cap] is the nullable hardware capability a feature needs (null = always available),
 * carried over unchanged from the original ToolEntry.cap.
 */
data class FeatureDescriptor(
    val id: String,
    val tabId: Int,
    val title: String,
    val description: String,
    val cap: String? = null,
)

/** An ordered group of features, mirroring the original ToolCategory. */
data class FeatureCategory(
    val name: String,
    val shortName: String,
    val features: List<FeatureDescriptor>,
)

/** Thrown when a registry is constructed from an invalid catalog. */
class FeatureRegistryException(message: String) : IllegalArgumentException(message)

/**
 * Immutable, validated registry over the feature catalog. Construct via [of], which
 * validates the catalog and defensively copies all caller-owned lists before exposing
 * anything usable. Unknown lookups return null (never a fallback feature).
 */
class FeatureRegistry private constructor(
    /** Categories in navigation order; the lists are defensive copies. */
    val categories: List<FeatureCategory>,
) {
    /** All features across all categories, in category then tool order. */
    val features: List<FeatureDescriptor> = categories.flatMap { it.features }

    private val byId: Map<String, FeatureDescriptor> = features.associateBy { it.id }
    private val byTabId: Map<Int, FeatureDescriptor> = features.associateBy { it.tabId }

    /** Lookup by stable string id; null if absent. */
    fun byId(id: String): FeatureDescriptor? = byId[id]

    /** Lookup by legacy integer tab id; null if absent. */
    fun byTabId(tabId: Int): FeatureDescriptor? = byTabId[tabId]

    companion object {
        /** Stable ids: lowercase alphanumeric segments separated by dots, >= 2 segments. */
        private val ID_PATTERN = Regex("^[a-z0-9]+(\\.[a-z0-9]+)+$")

        /**
         * Validate [categories] and build a registry. Rejects duplicate stable ids,
         * duplicate tab ids (both across categories), and malformed/blank ids.
         * All input lists are copied defensively, so later mutation of a caller-owned
         * list does not affect the returned registry.
         */
        fun of(categories: List<FeatureCategory>): FeatureRegistry {
            val copied = categories.map { cat ->
                FeatureCategory(cat.name, cat.shortName, cat.features.toList())
            }.toList()

            val seenIds = HashSet<String>()
            val seenTabs = HashSet<Int>()
            for (cat in copied) {
                for (f in cat.features) {
                    if (!ID_PATTERN.matches(f.id)) {
                        throw FeatureRegistryException(
                            "Malformed or blank stable id: '${f.id}' (tab=${f.tabId})"
                        )
                    }
                    if (!seenIds.add(f.id)) {
                        throw FeatureRegistryException("Duplicate stable id: '${f.id}'")
                    }
                    if (!seenTabs.add(f.tabId)) {
                        throw FeatureRegistryException("Duplicate tab id: ${f.tabId}")
                    }
                }
            }
            return FeatureRegistry(copied)
        }
    }
}
