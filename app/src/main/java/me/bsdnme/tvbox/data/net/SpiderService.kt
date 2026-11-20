package me.bsdnme.tvbox.data.net

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import me.bsdnme.tvbox.data.model.Site
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Separate process contains crashes; it still uses this application's Android permissions. */
class SpiderService : Service() {
    private val executor = Executors.newCachedThreadPool()
    override fun onCreate() { super.onCreate(); SpiderRuntime.initialize(this) }
    override fun onDestroy() { executor.shutdownNow(); super.onDestroy(); android.os.Process.killProcess(android.os.Process.myPid()) }
    override fun onBind(intent: Intent?): IBinder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != IBinder.FIRST_CALL_TRANSACTION || reply == null) return super.onTransact(code, data, reply, flags)
            val args = List(10) { data.readString().orEmpty() }
            try {
                val site = Site(args[0], args[1], args[2], 3, true, false, args[3], args[4], args[5])
                val task = executor.submit<String> { SpiderRuntime.executeLocal(site, args[6], args[7], args[8], args[9]) }
                val result = try { task.get(45, TimeUnit.SECONDS) } catch (_: TimeoutException) {
                    task.cancel(true)
                    // A spider can ignore interruption; terminate only its separate process.
                    android.os.Process.killProcess(android.os.Process.myPid())
                    throw SourceException("Spider 执行超时")
                } catch (e: java.util.concurrent.ExecutionException) { throw e.cause ?: e }
                reply.writeString("")
                reply.writeString(result)
            } catch (e: Throwable) { reply.writeString(e.message ?: e.javaClass.simpleName) }
            return true
        }
    }
}
