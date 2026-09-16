package com.aurora.engine.provider.ytmusic.config

import org.junit.jupiter.api.Test
import com.google.common.truth.Truth.assertThat

class ClientConfigStoreTest {

    private val testSeedJson = """
    [
        {
            "clientName": "CLIENT_A",
            "clientVersion": "1.0",
            "userAgent": "AgentA/1.0",
            "requiresCipher": false,
            "supportsSabr": true,
            "supportsMetadata": true,
            "metadataPriority": 90,
            "enabled": true,
            "priority": 100
        },
        {
            "clientName": "CLIENT_B",
            "clientVersion": "2.0",
            "userAgent": "AgentB/2.0",
            "requiresCipher": true,
            "supportsSabr": false,
            "supportsMetadata": true,
            "metadataPriority": 100,
            "enabled": true,
            "priority": 80
        },
        {
            "clientName": "CLIENT_C",
            "clientVersion": "3.0",
            "userAgent": "AgentC/3.0",
            "enabled": false,
            "priority": 50
        }
    ]
    """.trimIndent()

    @Test
    fun `loads seed and sorts by priority descending`() {
        val store = ClientConfigStore(testSeedJson)
        val clients = store.getActiveClients()

        assertThat(clients).hasSize(2) // CLIENT_C disabled
        assertThat(clients[0].clientName).isEqualTo("CLIENT_A")
        assertThat(clients[1].clientName).isEqualTo("CLIENT_B")
    }

    @Test
    fun `disabled clients are excluded`() {
        val store = ClientConfigStore(testSeedJson)
        assertThat(store.getClient("CLIENT_C")).isNull()
    }

    @Test
    fun `metadata clients sorted by metadataPriority`() {
        val store = ClientConfigStore(testSeedJson)
        val metaClients = store.getMetadataClients()

        assertThat(metaClients).hasSize(2)
        assertThat(metaClients[0].clientName).isEqualTo("CLIENT_B") // metadataPriority 100
        assertThat(metaClients[1].clientName).isEqualTo("CLIENT_A") // metadataPriority 90
    }

    @Test
    fun `remote update merges over seed`() {
        val store = ClientConfigStore(testSeedJson)

        val remoteJson = """
        [
            {
                "clientName": "CLIENT_A",
                "clientVersion": "1.1",
                "userAgent": "AgentA/1.1",
                "enabled": true,
                "priority": 50
            },
            {
                "clientName": "CLIENT_D",
                "clientVersion": "4.0",
                "userAgent": "AgentD/4.0",
                "enabled": true,
                "priority": 95
            }
        ]
        """.trimIndent()

        store.applyRemoteUpdate(remoteJson)
        val clients = store.getActiveClients()

        // CLIENT_A updated (priority dropped), CLIENT_D added, CLIENT_B preserved
        assertThat(clients.map { it.clientName }).containsExactly("CLIENT_D", "CLIENT_B", "CLIENT_A")
        assertThat(store.getClient("CLIENT_A")?.clientVersion).isEqualTo("1.1")
    }

    @Test
    fun `corrupt remote update is ignored`() {
        val store = ClientConfigStore(testSeedJson)
        val before = store.getActiveClients().size

        store.applyRemoteUpdate("not-valid-json!!!}")
        assertThat(store.getActiveClients()).hasSize(before)
    }

    @Test
    fun `hardcoded defaults are used when no seed provided`() {
        val store = ClientConfigStore(null)
        // When no classpath resource, it should fall through to hardcoded defaults
        // (unless test env has a resource — the important thing is it doesn't crash)
        assertThat(store.getActiveClients()).isNotEmpty()
    }

    @Test
    fun `toInnerTubeClientConfig bridges correctly`() {
        val store = ClientConfigStore(testSeedJson)
        val entry = store.getActiveClients().first()
        val config = store.toInnerTubeClientConfig(entry)

        assertThat(config.clientName).isEqualTo(entry.clientName)
        assertThat(config.clientVersion).isEqualTo(entry.clientVersion)
        assertThat(config.userAgent).isEqualTo(entry.userAgent)
        assertThat(config.requiresCipher).isEqualTo(entry.requiresCipher)
        assertThat(config.supportsSabr).isEqualTo(entry.supportsSabr)
    }
}
