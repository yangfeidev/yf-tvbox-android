package me.bsdnme.tvbox

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.bsdnme.tvbox.data.LocalStore
import me.bsdnme.tvbox.data.SourceCheckEntry
import me.bsdnme.tvbox.data.SourceCheckSnapshot
import me.bsdnme.tvbox.data.sourceNetwork
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SourceCheckDeviceTest {
    @Test fun snapshotsSurviveStoreRecreationAndStaySeparatedByNetwork() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = "test_" + UUID.randomUUID()
        try {
            val store = LocalStore(context)
            store.writeSourceChecks(id, SourceCheckSnapshot("wifi", 10, mapOf("a" to SourceCheckEntry(true))))
            store.writeSourceChecks(id, SourceCheckSnapshot("mobile", 20, mapOf("a" to SourceCheckEntry(false, 2))))
            store.writeSourceChecks(id, SourceCheckSnapshot("wifi", 0, mapOf("jar" to SourceCheckEntry(true))))
            val snapshots = LocalStore(context).readSourceChecks(id).associateBy { it.network }
            assertEquals(10L, snapshots["wifi"]?.checkedAt)
            assertEquals(SourceCheckEntry(true), snapshots["wifi"]?.entries?.get("a"))
            assertEquals(SourceCheckEntry(true), snapshots["wifi"]?.entries?.get("jar"))
            assertEquals(SourceCheckEntry(false, 2), snapshots["mobile"]?.entries?.get("a"))
            val network = sourceNetwork(context)
            assertTrue(network.key.isEmpty() || network.key.matches(Regex("[0-9a-f]{64}")))
        } finally {
            context.getSharedPreferences("feigemedia", 0).edit().remove("source_checks_$id").commit()
        }
    }
}
