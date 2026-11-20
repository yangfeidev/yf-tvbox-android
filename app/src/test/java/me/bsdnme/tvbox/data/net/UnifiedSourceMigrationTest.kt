package me.bsdnme.tvbox.data.net

import org.junit.Assert.assertEquals
import org.junit.Test

class UnifiedSourceMigrationTest {
    private val legacy = """{"sites":[{"key":"old-key","name":"影视","type":1,"api":"https://source.example/api"}]}"""
    private val unified = """{"sites":[{"key":"new-key","name":"影视","type":1,"api":"https://source.example/api"}]}"""
    private val other = """{"sites":[{"key":"other","name":"备用","type":1,"api":"https://other.example/api"}]}"""

    @Test fun keepsIdentityAcrossMigrationAndTemporaryRemoval() {
        val aliases = mutableMapOf<String, String>()
        val migrated = SubscriptionApi.preserveSourceKeys(unified, SubscriptionApi.parse(legacy), aliases)
        assertEquals("old-key", SubscriptionApi.parse(migrated).sites.single().key)

        val withoutSource = SubscriptionApi.preserveSourceKeys(other, SubscriptionApi.parse(migrated), aliases)
        val restored = SubscriptionApi.preserveSourceKeys(unified, SubscriptionApi.parse(withoutSource), aliases)
        assertEquals("old-key", SubscriptionApi.parse(restored).sites.single().key)
    }

    @Test fun avoidsReusingADuplicateLegacyKey() {
        val duplicate = """{"sites":[{"key":"same","name":"甲","type":1,"api":"https://a.example/api"},{"key":"same","name":"乙","type":1,"api":"https://b.example/api"}]}"""
        val incoming = """{"sites":[{"key":"a","name":"甲","type":1,"api":"https://a.example/api"},{"key":"b","name":"乙","type":1,"api":"https://b.example/api"}]}"""
        val output = SubscriptionApi.preserveSourceKeys(incoming, SubscriptionApi.parse(duplicate), mutableMapOf())
        assertEquals(listOf("a", "b"), SubscriptionApi.parse(output).sites.map { it.key })
    }
}
