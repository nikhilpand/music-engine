package com.aurora.engine.provider.ytmusic.config

import com.aurora.engine.provider.ytmusic.session.InnerTubeClientConfig
import kotlinx.serialization.json.Json

/**
 * Manages InnerTube client configurations with a layered fallback chain:
 *
 * ```
 * remote update → cached → bundled seed → hardcoded defaults
 * ```
 *
 * ## Design Rationale
 *
 * YouTube frequently changes client requirements (versions, user-agents, cipher requirements).
 * A static, hardcoded client list would require app updates for every change. The ConfigStore
 * allows:
 * 1. **Bundled seed**: Shipped with the APK, always available offline.
 * 2. **Remote update**: Optional JSON payload from a configuration endpoint, merged over seed.
 * 3. **Hardcoded fallback**: If both are missing/corrupt, two known-good clients are used.
 *
 * ## Thread Safety
 *
 * Reads are lock-free (volatile snapshot). Writes (applyRemoteUpdate) replace the snapshot
 * atomically. This is sufficient for the expected access pattern (rare writes, frequent reads).
 */
class ClientConfigStore(
    bundledConfigJson: String? = null
) {
    @Volatile
    private var activeConfigs: List<ClientConfigEntry> = emptyList()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    init {
        val bundled = if (bundledConfigJson != null) {
            loadFromJson(bundledConfigJson)
        } else {
            loadBundledFromResource()
        }

        activeConfigs = if (bundled.isNotEmpty()) {
            bundled.filter { it.enabled }.sortedByDescending { it.priority }
        } else {
            hardcodedDefaults()
        }
    }

    /**
     * All enabled clients, sorted by [ClientConfigEntry.priority] descending.
     */
    fun getActiveClients(): List<ClientConfigEntry> = activeConfigs

    /**
     * Clients capable of fetching metadata, sorted by [ClientConfigEntry.metadataPriority] descending.
     * Falls back to all active clients if none have [ClientConfigEntry.supportsMetadata].
     */
    fun getMetadataClients(): List<ClientConfigEntry> {
        val metadataCapable = activeConfigs
            .filter { it.supportsMetadata }
            .sortedByDescending { it.metadataPriority }

        return metadataCapable.ifEmpty { activeConfigs }
    }

    /**
     * Merge a remote configuration update over the current active set.
     *
     * Remote entries with the same [ClientConfigEntry.clientName] replace existing entries.
     * New entries are appended. Entries not in the remote payload are preserved (seed entries
     * are not removed by remote updates).
     */
    fun applyRemoteUpdate(remoteJson: String) {
        val remoteEntries = loadFromJson(remoteJson)
        if (remoteEntries.isEmpty()) return

        val merged = mutableMapOf<String, ClientConfigEntry>()
        // Seed first
        for (entry in activeConfigs) {
            merged[entry.clientName] = entry
        }
        // Remote overrides
        for (entry in remoteEntries) {
            merged[entry.clientName] = entry
        }

        activeConfigs = merged.values
            .filter { it.enabled }
            .sortedByDescending { it.priority }
    }

    /**
     * Look up a specific client by name.
     */
    fun getClient(clientName: String): ClientConfigEntry? =
        activeConfigs.find { it.clientName == clientName }

    /**
     * Bridge a [ClientConfigEntry] to the existing [InnerTubeClientConfig] type
     * used by [InnerTubeSession] and [YouTubePlaybackStrategy].
     */
    fun toInnerTubeClientConfig(entry: ClientConfigEntry): InnerTubeClientConfig {
        return InnerTubeClientConfig(
            clientName = entry.clientName,
            clientVersion = entry.clientVersion,
            clientScreen = entry.clientScreen,
            userAgent = entry.userAgent,
            osName = entry.osName,
            osVersion = entry.osVersion,
            platform = entry.platform,
            hl = entry.hl,
            gl = entry.gl,
            requiresCipher = entry.requiresCipher,
            requiresPoToken = entry.requiresPoToken,
            supportsSabr = entry.supportsSabr
        )
    }

    // ── Private ─────────────────────────────────────────────────────────────

    private fun loadFromJson(jsonString: String): List<ClientConfigEntry> {
        return try {
            json.decodeFromString<List<ClientConfigEntry>>(jsonString)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun loadBundledFromResource(): List<ClientConfigEntry> {
        return try {
            val stream = this::class.java.classLoader
                ?.getResourceAsStream("client_config_seed.json")
                ?: return emptyList()
            val content = stream.bufferedReader().use { it.readText() }
            loadFromJson(content)
        } catch (_: Exception) {
            emptyList()
        }
    }

    companion object {
        /**
         * Hardcoded fallback clients — used only when both bundled and remote configs are unavailable.
         * These two clients are known to work without cipher or PoToken.
         */
        fun hardcodedDefaults(): List<ClientConfigEntry> = listOf(
            ClientConfigEntry(
                clientName = "VISIONOS",
                clientVersion = "0.1",
                userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_0) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15",
                osName = "visionOS",
                osVersion = "1.0",
                platform = "DESKTOP",
                requiresCipher = false,
                requiresPoToken = false,
                supportsSabr = false,
                supportsMetadata = false,
                priority = 85,
                metadataPriority = 0
            ),
            ClientConfigEntry(
                clientName = "TVHTML5",
                clientVersion = "7.20241030.12.00",
                userAgent = "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/23.lts.4-devel (unlike Gecko) Starfish/2.2.0",
                osName = "Cobalt",
                osVersion = "23",
                platform = "TV",
                requiresCipher = false,
                requiresPoToken = false,
                supportsSabr = false,
                supportsMetadata = false,
                priority = 70,
                metadataPriority = 0
            )
        )
    }
}
