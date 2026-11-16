package me.bsdnme.tvbox.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PreferredSiteTest {
    private fun site(key: String, name: String, type: Int = 1) =
        Site(key, name, "https://example.com", type, true, true)

    @Test fun prefersNamedSiteOverSavedAndFirst() {
        val first = site("first", "其他")
        val preferred = site("preferred", "🌼影视 | 工厂🌼")
        assertEquals(preferred, SubscriptionDocument(sites = listOf(first, preferred)).preferredSite(first.key))
    }

    @Test fun prefersMovieParadiseThenOtherFlowerSites() {
        val flower = site("flower", "🌼量子｜影视🌼")
        val movie = site("movie", "🌼电影丨天堂🌼")
        val factory = site("factory", "🌼影视｜工厂🌼")
        assertEquals(factory, SubscriptionDocument(sites = listOf(flower, movie, factory)).preferredSite(""))
        assertEquals(movie, SubscriptionDocument(sites = listOf(flower, movie)).preferredSite(""))
        assertEquals(flower, SubscriptionDocument(sites = listOf(flower)).preferredSite(""))
    }

    @Test fun fallsBackToSavedThenFirstUsableSite() {
        val first = site("first", "其他")
        val saved = site("saved", "已选")
        val unavailable = site("preferred", "影视丨工厂", type = 3)
        val document = SubscriptionDocument(sites = listOf(unavailable, first, saved))
        assertEquals(saved, document.preferredSite(saved.key))
        assertEquals(first, document.preferredSite("missing"))
        assertNull(SubscriptionDocument(sites = listOf(unavailable)).preferredSite(""))
    }
}
