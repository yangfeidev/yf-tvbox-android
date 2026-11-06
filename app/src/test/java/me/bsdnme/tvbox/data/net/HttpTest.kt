package me.bsdnme.tvbox.data.net

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.TimeUnit

class HttpTest {
    @Test
    fun `longer read timeout allows a delayed subscription response`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("subscription").setHeadersDelay(1500, TimeUnit.MILLISECONDS))
        server.start()
        try {
            assertEquals("subscription", Http.get(server.url("/box").toString(), timeoutSeconds = 3, readTimeoutSeconds = 2))
        } finally {
            server.shutdown()
        }
    }

    @Test(expected = SourceException::class)
    fun `total timeout still limits a slow response`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("subscription").setHeadersDelay(1500, TimeUnit.MILLISECONDS))
        server.start()
        try {
            Http.get(server.url("/box").toString(), timeoutSeconds = 1, readTimeoutSeconds = 3)
        } finally {
            server.shutdown()
        }
    }
}
