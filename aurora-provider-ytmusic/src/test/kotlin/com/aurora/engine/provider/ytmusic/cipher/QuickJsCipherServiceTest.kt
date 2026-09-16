package com.aurora.engine.provider.ytmusic.cipher

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class QuickJsCipherServiceTest {

    private lateinit var server: MockWebServer
    private lateinit var httpClient: OkHttpClient
    private lateinit var cipherService: QuickJsCipherService

    private val testPlayerJs = """
        var aB={
            rR:function(a,b){var c=a[0];a[0]=a[b%a.length];a[b%a.length]=c},
            wQ:function(a){a.reverse()},
            Hy:function(a,b){a.splice(0,b)}
        };
        var Xk=function(a){a=a.split("");aB.wQ(a,44);aB.rR(a,6);aB.Hy(a,2);return a.join("")};
        
        var nTransform=function(a){var b=a.split("");b.reverse();return b.join("")};
        
        &&(b=a.get("n"))&&(b=nTransform(b),a.set("n",b));
    """.trimIndent()

    @BeforeEach
    fun setup() {
        server = MockWebServer()
        server.start()
        httpClient = OkHttpClient()
        cipherService = QuickJsCipherService(httpClient)
    }

    @AfterEach
    fun teardown() {
        cipherService.close()
        server.shutdown()
    }

    @Test
    fun `decipherSignature executes real QuickJS and transforms signature correctly`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(testPlayerJs))
        val playerUrl = server.url("/player.js").toString()

        val input = "abcdefgh"
        val result = cipherService.decipherSignature(input, playerUrl)

        assertThat(result).isEqualTo("fedcha")
    }

    @Test
    fun `transformN executes real QuickJS and reverses string`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(testPlayerJs))
        val playerUrl = server.url("/player.js").toString()

        val input = "throttleValue123"
        val result = cipherService.transformN(input, playerUrl)

        assertThat(result).isEqualTo("321eulaVelttorht")
    }

    @Test
    fun `caching prevents redundant network requests for same player URL`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(testPlayerJs))
        val playerUrl = server.url("/player.js").toString()

        val r1 = cipherService.decipherSignature("abcdefgh", playerUrl)
        val r2 = cipherService.transformN("hello", playerUrl)

        assertThat(r1).isEqualTo("fedcha")
        assertThat(r2).isEqualTo("olleh")
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `invalidate clears cache and triggers refetch`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(testPlayerJs))
        server.enqueue(MockResponse().setResponseCode(200).setBody(testPlayerJs))
        val playerUrl = server.url("/player.js").toString()

        cipherService.decipherSignature("abcdefgh", playerUrl)
        assertThat(server.requestCount).isEqualTo(1)

        cipherService.invalidate(playerUrl)
        cipherService.decipherSignature("abcdefgh", playerUrl)
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `evaluation error throws CipherException with EVALUATION phase`() = runBlocking {
        val brokenJs = """
            var aB={
                rR:function(a,b){throw new Error("intentional JS failure");},
                wQ:function(a){a.reverse();},
                Hy:function(a,b){a.splice(0,b);}
            };
            var Xk=function(a){a=a.split("");aB.rR(a,6);return a.join("");};
            var nTransform=function(a){var b=a.split("");return b.join("");};
            &&(b=a.get("n"))&&(b=nTransform(b),a.set("n",b));
        """.trimIndent()
        server.enqueue(MockResponse().setResponseCode(200).setBody(brokenJs))
        val playerUrl = server.url("/player.js").toString()

        val ex = assertThrows<CipherException> {
            cipherService.decipherSignature("input", playerUrl)
        }
        assertThat(ex.phase).isEqualTo(CipherPhase.EVALUATION)
        assertThat(ex.message).contains("intentional JS failure")
    }
}
