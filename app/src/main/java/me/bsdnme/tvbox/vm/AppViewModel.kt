package me.bsdnme.tvbox.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import me.bsdnme.tvbox.core.DefaultSources
import me.bsdnme.tvbox.data.LocalStore
import me.bsdnme.tvbox.data.model.AppSettings
import me.bsdnme.tvbox.data.model.ChannelGroup
import me.bsdnme.tvbox.data.model.LibraryEntry
import me.bsdnme.tvbox.data.model.MediaItem
import me.bsdnme.tvbox.data.model.Site
import me.bsdnme.tvbox.data.model.Subscription
import me.bsdnme.tvbox.data.model.SubscriptionDocument
import me.bsdnme.tvbox.data.model.WarehouseEntry
import me.bsdnme.tvbox.data.net.SourceException
import me.bsdnme.tvbox.data.net.SubscriptionApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 添加订阅的结果，用于订阅页区分「成功 / 需要选子仓库 / 失败」 */
sealed interface AddResult {
    data object Success : AddResult
    data class NeedWarehouse(val entries: List<WarehouseEntry>) : AddResult
    data class Failure(val message: String) : AddResult
}

data class AppUiState(
    val settings: AppSettings = AppSettings(),
    val subscriptions: List<Subscription> = emptyList(),
    val document: SubscriptionDocument? = null,
    val currentSite: Site? = null,
    val loading: Boolean = false,
    val initializing: Boolean = true,
    val localLoaded: Boolean = false,
    val refreshingSubscriptionId: String? = null,
    val addingSubscriptionUrl: String? = null,
    val pinnedSites: Set<String> = emptySet(),
    val error: String? = null,
    val favorites: List<LibraryEntry> = emptyList(),
    val history: List<LibraryEntry> = emptyList(),
) {
    val usableSites: List<Site> get() = document?.usableSites.orEmpty()
    val hasSubscription: Boolean get() = subscriptions.isNotEmpty()

    /** 因缺少 JVM 而不可用的源数量，设置页要显示出来 */
    val skippedSiteCount: Int
        get() = (document?.sites?.size ?: 0) - usableSites.size
}

/**
 * 全局状态。
 *
 * 只用一个 ViewModel，避免 P0 阶段引入过度分层：
 * 订阅 → 源站 → 内容，收藏 / 历史 / 设置，全部在这里聚合。
 *
 * 约定：所有挂起方法内部捕获异常并写入 [AppUiState.error]，
 * UI 只读状态，不写 try/catch。
 */
class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val store = LocalStore(application)
    private val _state = MutableStateFlow(AppUiState())
    val state: StateFlow<AppUiState> = _state.asStateFlow()
    private val pendingAdds = mutableMapOf<String, Deferred<AddResult>>()
    private val libraryWrites = Channel<() -> Unit>(Channel.UNLIMITED)

    private companion object {
        /** 直播列表本地缓存有效期：2 小时 */
        const val LIVE_CACHE_TTL_MS = 2 * 60 * 60 * 1000L
    }

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            for (write in libraryWrites) write()
        }
        viewModelScope.launch { loadLocal() }
    }

    override fun onCleared() {
        libraryWrites.close()
        super.onCleared()
    }

    private suspend fun loadLocal() {
        val local = withContext(Dispatchers.IO) {
            val settings = store.loadSettings()
            val subscriptions = store.loadSubscriptions()
            AppUiState(
                settings = settings,
                subscriptions = subscriptions,
                favorites = store.loadFavorites(),
                history = store.loadHistory(),
                pinnedSites = store.loadPinnedSites(),
            )
        }
        val settings = local.settings
        val subscriptions = local.subscriptions
        _state.update {
            it.copy(
                settings = settings,
                subscriptions = subscriptions,
                favorites = local.favorites,
                history = local.history,
                pinnedSites = local.pinnedSites,
                localLoaded = true,
            )
        }

        // 首次启动：把内置地址写进去，用户装上即可直接看到内容
        if (settings.liveUrl.isBlank()) {
            store.setLiveUrl(DefaultSources.LIVE)
            _state.update { it.copy(settings = it.settings.copy(liveUrl = DefaultSources.LIVE)) }
        }

        val active = subscriptions.firstOrNull { it.id == settings.activeSubscriptionId }
            ?: subscriptions.firstOrNull()
        if (active != null) {
            if (settings.activeSubscriptionId != active.id) {
                store.activeSubscriptionId = active.id
                _state.update { it.copy(settings = it.settings.copy(activeSubscriptionId = active.id)) }
            }
            viewModelScope.launch(Dispatchers.IO) {
                val cached = store.readSubscriptionCache(active.id)?.let { runCatching { SubscriptionApi.parse(it) }.getOrNull() }
                if (_state.value.settings.activeSubscriptionId == active.id && cached != null) applyDocument(cached)
                _state.update { it.copy(initializing = false) }
                if (settings.autoUpdateOnLaunch && _state.value.settings.activeSubscriptionId == active.id) refreshActive(silent = true)
            }
        } else if (settings.autoAddDefault) {
            // 没有任何订阅时静默导入内置单仓；失败不打扰用户，设置页会显示错误
            viewModelScope.launch { addSubscription(DefaultSources.BOX, "默认单仓"); _state.update { it.copy(initializing = false) } }
        } else {
            _state.update { it.copy(initializing = false) }
        }
    }

    // ------------------------------------------------------------------ 订阅

    suspend fun addSubscription(rawUrl: String, name: String = ""): AddResult {
        val url = SubscriptionApi.normalizeUrl(rawUrl)
        if (url.isBlank()) return AddResult.Failure("请输入订阅地址")
        val pending = synchronized(pendingAdds) {
            pendingAdds[url] ?: viewModelScope.async(start = CoroutineStart.LAZY) {
                performAddSubscription(url, name)
            }.also { task ->
                pendingAdds[url] = task
                task.invokeOnCompletion {
                    synchronized(pendingAdds) {
                        if (pendingAdds[url] === task) pendingAdds.remove(url)
                    }
                }
            }
        }
        return pending.await()
    }

    private suspend fun performAddSubscription(rawUrl: String, name: String): AddResult =
        withContext(Dispatchers.IO) {
            val url = SubscriptionApi.normalizeUrl(rawUrl)
            if (url.isBlank()) return@withContext AddResult.Failure("请输入订阅地址")
            _state.update { it.copy(loading = true, addingSubscriptionUrl = url, error = null) }
            try {
                val raw = SubscriptionApi.fetchRaw(url)
                val document = SubscriptionApi.parse(raw)
                if (document.isWarehouse) {
                    _state.update { it.copy(loading = false) }
                    return@withContext AddResult.NeedWarehouse(document.warehouse)
                }
                val id = url.hashCode().toUInt().toString(16)
                val subscription = Subscription(
                    id = id,
                    url = url,
                    name = name.ifBlank { url.substringAfter("://").substringBefore("/") },
                    addedAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                    siteCount = document.sites.size,
                )
                val next = _state.value.subscriptions.filter { it.id != id } + subscription
                store.saveSubscriptions(next)
                store.writeSubscriptionCache(id, raw)
                store.activeSubscriptionId = id
                _state.update {
                    it.copy(
                        subscriptions = next,
                        settings = it.settings.copy(activeSubscriptionId = id),
                        loading = false,
                    )
                }
                applyDocument(document)
                AddResult.Success
            } catch (e: CancellationException) {
                _state.update { it.copy(loading = false) }
                throw e
            } catch (e: SourceException) {
                _state.update { it.copy(loading = false, error = e.message) }
                AddResult.Failure(e.message ?: "添加失败")
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = "添加失败：${e.message}") }
                AddResult.Failure(e.message ?: "添加失败")
            } finally {
                _state.update { if (it.addingSubscriptionUrl == url) it.copy(addingSubscriptionUrl = null) else it }
            }
        }

    fun removeSubscription(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val next = _state.value.subscriptions.filter { it.id != id }
            store.saveSubscriptions(next)
            _state.update { it.copy(subscriptions = next) }
            if (_state.value.settings.activeSubscriptionId == id || next.isEmpty()) {
                val nextId = next.firstOrNull()?.id.orEmpty()
                store.activeSubscriptionId = nextId
                _state.update { it.copy(document = null, currentSite = null, settings = it.settings.copy(activeSubscriptionId = nextId)) }
                if (nextId.isEmpty()) {
                    _state.update {
                        it.copy(
                            document = null,
                            currentSite = null,
                            settings = it.settings.copy(activeSubscriptionId = ""),
                        )
                    }
                } else {
                    val cached = store.readSubscriptionCache(nextId)?.let { runCatching { SubscriptionApi.parse(it) }.getOrNull() }
                    if (cached != null && _state.value.settings.activeSubscriptionId == nextId) applyDocument(cached)
                    refreshSubscription(nextId)
                }
            }
        }
    }

    suspend fun refreshActive(silent: Boolean = false) = refreshSubscription(null, silent)

    fun activateSubscription(id: String) {
        if (_state.value.settings.activeSubscriptionId == id) return
        store.activeSubscriptionId = id
        _state.update { it.copy(document = null, currentSite = null, settings = it.settings.copy(activeSubscriptionId = id)) }
        viewModelScope.launch(Dispatchers.IO) {
            val cached = store.readSubscriptionCache(id)?.let { runCatching { SubscriptionApi.parse(it) }.getOrNull() }
            if (_state.value.settings.activeSubscriptionId != id) return@launch
            if (cached != null) applyDocument(cached) else refreshSubscription(id)
        }
    }

    suspend fun refreshSubscription(id: String?, silent: Boolean = false) = withContext(Dispatchers.IO) {
        val current = _state.value
        val targetId = id ?: current.settings.activeSubscriptionId
        val active = current.subscriptions.firstOrNull { it.id == targetId } ?: return@withContext

        if (!silent) _state.update { it.copy(loading = true, refreshingSubscriptionId = active.id, error = null) }
        try {
            val raw = SubscriptionApi.fetchRaw(active.url)
            val document = SubscriptionApi.parse(raw)
            if (document.isWarehouse) throw SourceException("该地址已变成多仓订阅，请重新添加子仓库")
            store.writeSubscriptionCache(active.id, raw)
            val next = _state.value.subscriptions.map {
                if (it.id == active.id) {
                    it.copy(updatedAt = System.currentTimeMillis(), siteCount = document.sites.size)
                } else it
            }
            store.saveSubscriptions(next)
            _state.update { if (silent) it.copy(subscriptions = next) else it.copy(subscriptions = next, loading = false, refreshingSubscriptionId = null, error = null) }
            if (_state.value.settings.activeSubscriptionId == active.id) applyDocument(document)
        } catch (e: kotlinx.coroutines.CancellationException) {
            _state.update { if (it.refreshingSubscriptionId == active.id) it.copy(loading = false, refreshingSubscriptionId = null) else it }
            throw e
        } catch (e: Exception) {
            if (!silent) _state.update { it.copy(loading = false, refreshingSubscriptionId = null, error = e.message ?: "订阅刷新失败") }
        }
    }

    private fun applyDocument(document: SubscriptionDocument) {
        val usable = document.usableSites
        val savedKey = _state.value.settings.currentSiteKey
        val site = usable.firstOrNull { it.key == savedKey } ?: usable.firstOrNull()
        if (site != null) store.currentSiteKey = site.key
        _state.update {
            it.copy(
                document = document,
                currentSite = site,
                settings = it.settings.copy(currentSiteKey = site?.key.orEmpty()),
            )
        }
    }

    fun selectSite(site: Site) {
        store.currentSiteKey = site.key
        _state.update { it.copy(currentSite = site, settings = it.settings.copy(currentSiteKey = site.key)) }
    }

    fun togglePinnedSite(site: Site) {
        val key = "${_state.value.settings.activeSubscriptionId}::${site.key}"
        val pins = _state.value.pinnedSites
        val next = if (key in pins) pins - key else pins + key
        store.savePinnedSites(next)
        _state.update { it.copy(pinnedSites = next) }
    }

    // ------------------------------------------------------------------ 直播

    /**
     * 直播列表加载，三级缓存：内存 → 本地文件 → 网络。
     *
     * 网络层要 30~40 秒（聚合地址每次现场探测上游），
     * 所以除了内存缓存，还必须落盘，否则每次冷启动都要空等。
     * 本地缓存 2 小时过期。
     */
    suspend fun loadLive(url: String, forceRefresh: Boolean = false): List<ChannelGroup> =
        withContext(Dispatchers.IO) {
            if (!forceRefresh) {
                me.bsdnme.tvbox.data.net.LiveCache.get(url)?.let { return@withContext it }
                val cachedRaw = store.readLiveCache(url, LIVE_CACHE_TTL_MS)
                cachedRaw?.let { raw ->
                    val groups = me.bsdnme.tvbox.data.net.LiveParser.parse(raw)
                    if (groups.isNotEmpty()) {
                        me.bsdnme.tvbox.data.net.LiveCache.put(url, groups)
                        return@withContext groups
                    }
                }
            }
            val raw = try {
                me.bsdnme.tvbox.data.net.Http.get(url, timeoutSeconds = 45)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                // 网络失败时退回过期缓存：有旧数据总比一片空白强
                val stale = store.readLiveCache(url, Long.MAX_VALUE)
                if (stale != null) {
                    return@withContext me.bsdnme.tvbox.data.net.LiveParser.parse(stale)
                }
                throw e
            }
            val groups = me.bsdnme.tvbox.data.net.LiveParser.parse(raw)
            if (groups.isNotEmpty()) {
                me.bsdnme.tvbox.data.net.LiveCache.put(url, groups)
                store.writeLiveCache(url, raw)
            }
            groups
        }

    // ------------------------------------------------------------------ 收藏历史

    fun isFavorite(siteKey: String, mediaId: String): Boolean =
        _state.value.favorites.any { it.uniqueKey == "$siteKey::$mediaId" }

    fun toggleFavorite(item: MediaItem, site: Site) {
            val key = "${site.key}::${item.id}"
            val current = _state.value.favorites
            val next = if (current.any { it.uniqueKey == key }) {
                current.filter { it.uniqueKey != key }
            } else {
                listOf(
                    LibraryEntry(
                        siteKey = site.key,
                        mediaId = item.id,
                        name = item.name,
                        poster = item.poster,
                        remarks = item.remarks,
                        updatedAt = System.currentTimeMillis(),
                    ),
                ) + current
            }
            _state.update { it.copy(favorites = next) }
            libraryWrites.trySend { store.saveFavorites(next) }
    }

    fun recordHistory(item: MediaItem, site: Site, sourceName: String, episodeName: String, progress: Float) {
        val key = "${site.key}::${item.id}"
        val next = (listOf(
                LibraryEntry(
                    siteKey = site.key,
                    mediaId = item.id,
                    name = item.name,
                    poster = item.poster,
                    remarks = item.remarks,
                    episodeName = episodeName,
                    sourceName = sourceName,
                    progress = progress.coerceIn(0f, 1f),
                    updatedAt = System.currentTimeMillis(),
                ),
            ) + _state.value.history.filter { it.uniqueKey != key }).take(200)
        _state.update { it.copy(history = next) }
        libraryWrites.trySend { store.saveHistory(next) }
    }

    fun removeHistory(entry: LibraryEntry) {
        val next = _state.value.history.filterNot { it.uniqueKey == entry.uniqueKey }
        _state.update { it.copy(history = next) }
        libraryWrites.trySend { store.saveHistory(next) }
    }

    fun clearHistory() {
        _state.update { it.copy(history = emptyList()) }
        libraryWrites.trySend { store.saveHistory(emptyList()) }
    }

    // ------------------------------------------------------------------ 设置

    fun setForceTelevision(value: Boolean) {
        store.setForceTelevision(value)
        _state.update { it.copy(settings = it.settings.copy(forceTelevision = value)) }
    }

    fun setAutoUpdate(value: Boolean) {
        store.setAutoUpdate(value)
        _state.update { it.copy(settings = it.settings.copy(autoUpdateOnLaunch = value)) }
    }

    fun setLiveUrl(value: String) {
        store.setLiveUrl(value)
        _state.update { it.copy(settings = it.settings.copy(liveUrl = value)) }
    }

    fun clearError() {
        if (_state.value.error == null) return
        _state.update { it.copy(error = null) }
    }
}
