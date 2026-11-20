package me.bsdnme.tvbox.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PreferredSiteTest {
    private fun site(key: String, name: String, type: Int = 1) =
        Site(key, name, "https://example.com", type, true, true)

    @Test fun keepsSavedSiteInsteadOfNamedFactory() {
        val saved = site("saved", "已选")
        val factory = site("factory", "🌼影视｜工厂🌼")
        assertEquals(saved, SubscriptionDocument(sites = listOf(factory, saved)).preferredSite(saved.key))
    }

    @Test fun fallsBackToFirstUsableSiteWhenSavedSiteDisappears() {
        val first = site("first", "其他")
        val unavailable = site("unavailable", "影视工厂", type = 3)
        val document = SubscriptionDocument(sites = listOf(unavailable, first))
        assertEquals(first, document.preferredSite("missing"))
        assertNull(SubscriptionDocument(sites = listOf(unavailable)).preferredSite(""))
    }
}
