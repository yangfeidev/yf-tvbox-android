package me.bsdnme.tvbox

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.bsdnme.tvbox.data.model.Site
import me.bsdnme.tvbox.data.net.SpiderRuntime
import me.bsdnme.tvbox.data.net.VodApi
import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** On-device smoke test against the current TVAPP configuration; upstream may be unavailable. */
@RunWith(AndroidJUnit4::class)
class SpiderDeviceTest {
    @Test fun tvappJarSmoke() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        SpiderRuntime.initialize(context)
        val jar = "https://mpimg.cn/down.php/ea7a1ae7d5b3a88329515f918385276a.jar"
        val arguments = InstrumentationRegistry.getArguments()
        val onlySite = arguments.getString("site").orEmpty()
        val quick = arguments.getString("quick") == "true"
        val sites = listOf(
            Site("jike", "即刻", "csp_Jike", 3, true, false, jar = jar),
            Site("wogg", "玩偶", "csp_Wogg", 3, true, false, """{"site":["https://www.wogg.net"]}""", jar),
            Site("xbpq", "枫叶", "csp_XBPQ", 3, true, false, """{"分类url":"https://www.guan-feng.com/"}""", "https://wp.kayyw.com/f/bobgtv/%E5%B0%B1%E5%87%A0%E4%B8%AA.jar"),
        ).filter { onlySite.isBlank() || it.key == onlySite }
        var homeWorked = false
        var detailWorked = false
        var playbackTried = false
        for (site in sites) {
            var hash: String? = null
            var previouslyApproved = false
            try {
                val prepared = SpiderRuntime.prepare(site, refresh = true)
                hash = prepared.permissionKey
                previouslyApproved = SpiderRuntime.approved(prepared)
                if (!previouslyApproved) {
                    val rejected = try { VodApi.categories(site); false } catch (_: Exception) { true }
                    assertTrue("Unapproved JAR must not execute", rejected)
                }
                SpiderRuntime.approve(prepared)
                fun <T> step(name: String, block: () -> T): T? = try {
                    block().also { value -> println("SPIDER_TEST ${site.key} $name OK " + when (value) {
                        is me.bsdnme.tvbox.data.model.PagedMedia -> "items=${value.items.size} categories=${value.categories.size}"
                        is me.bsdnme.tvbox.data.model.MediaDetail -> "sources=${value.usableSources.size}"
                        is List<*> -> "count=${value.size}"
                        else -> value.toString().take(200)
                    }) }
                } catch (e: Throwable) { println("SPIDER_TEST ${site.key} $name FAILED ${e.message}"); null }
                val categories = step("categories") { VodApi.categories(site) }.orEmpty()
                val home = step("home") { VodApi.list(site) }
                if (home?.items?.isNotEmpty() == true) homeWorked = true
                if (!quick) {
                    categories.firstOrNull()?.let { category -> step("category") { VodApi.list(site, category.typeId) } }
                    step("search") { VodApi.search(site, "电影") }
                }
                home?.items?.firstOrNull()?.let { item ->
                    val detail = step("detail") { VodApi.detail(site, item.id) }
                    if (detail != null) detailWorked = true
                    detail?.usableSources?.take(4)?.forEachIndexed { index, source ->
                        source.episodes.firstOrNull()?.let { episode ->
                            val playback = step("play$index") { VodApi.resolvePlayback(site, source.name, episode.url) }
                            if (playback != null && !playbackTried) {
                                playbackTried = true
                                val done = CountDownLatch(1)
                                var outcome = "timeout"
                                var player: ExoPlayer? = null
                                val handler = Handler(Looper.getMainLooper())
                                handler.post {
                                    player = ExoPlayer.Builder(context).build().also { p ->
                                        p.addListener(object : Player.Listener {
                                            override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_READY) { outcome = "READY"; done.countDown() } }
                                            override fun onPlayerError(error: PlaybackException) { outcome = "ERROR ${error.errorCodeName}"; done.countDown() }
                                        })
                                        p.setMediaItem(MediaItem.fromUri(playback.url))
                                        p.prepare()
                                    }
                                }
                                done.await(20, TimeUnit.SECONDS)
                                handler.post { player?.release() }
                                println("SPIDER_TEST ${site.key} player $outcome")
                            }
                        }
                    }
                    if (detail == null) step("detailRaw") { SpiderRuntime.call(site, "detail", item.id).take(200) }
                }
            } catch (e: Exception) { println("SPIDER_TEST ${site.key} prepare FAILED ${e.message}") }
            finally {
                if (hash != null && !previouslyApproved) context.getSharedPreferences("spider_permissions", 0).edit().remove(hash).commit()
            }
        }
        assertTrue("No sampled TVAPP JAR returned homepage items", homeWorked)
        assertTrue("No sampled TVAPP JAR returned details", detailWorked)
    }
}
