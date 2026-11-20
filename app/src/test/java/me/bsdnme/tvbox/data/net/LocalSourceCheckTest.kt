package me.bsdnme.tvbox.data.net

import me.bsdnme.tvbox.data.model.Site
import me.bsdnme.tvbox.data.model.SubscriptionDocument
import me.bsdnme.tvbox.vm.AppUiState
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSourceCheckTest {
    private fun site(key: String, api: String = "https://example.com") = Site(key, key, api, 1, true, true)

    @Test fun managedListShowsOnlySourcesConfirmedOnTheDevice() {
        val document = SubscriptionDocument(sites = listOf(site("bad"), site("good")))
        val state = AppUiState(document = document, localSourceChecks = true, availableSourceKeys = setOf("good"))
        assertEquals(listOf("good"), state.selectableSites.map { it.key })
        assertEquals(2, state.usableSites.size)
        assertEquals(2, state.copy(localSourceChecks = false).selectableSites.size)
    }

    @Test fun shortProbeTimeoutDoesNotWaitForTheNormalRequestTimeout() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setHeadersDelay(3, TimeUnit.SECONDS).setBody("{}"))
            val started = System.nanoTime()
            try {
                VodApi.list(site("slow", server.url("/vod").toString()), timeoutSeconds = 1)
                throw AssertionError("A stalled source should time out")
            } catch (e: SourceException) {
                val elapsedMs = (System.nanoTime() - started) / 1_000_000
                assertTrue("Timed out after $elapsedMs ms", elapsedMs < 5000)
                assertTrue("Unexpected error: ${e.message}", e.message.orEmpty().contains("超时"))
            }
        } finally {
            server.shutdown()
        }
    }

    @Test fun progressUpdatesDoNotChangeTheDisplayedSnapshot() {
        val document = SubscriptionDocument(sites = listOf(site("a"), site("b"), site("c")))
        val state = AppUiState(document = document, localSourceChecks = true, checkingSources = true,
            availableSourceKeys = setOf("a", "c"), checkedSourceKeys = setOf("a", "b", "c"), sourceCheckTotal = 3)
        assertEquals(listOf("a", "c"), state.selectableSites.map { it.key })
        assertEquals(state.selectableSites, state.copy(sourceCheckCompleted = 2).selectableSites)
        assertEquals(listOf("a", "b", "c"), state.copy(showAllSources = true).selectableSites.map { it.key })
    }
}
