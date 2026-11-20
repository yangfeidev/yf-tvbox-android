package me.bsdnme.tvbox.vm

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import me.bsdnme.tvbox.core.DefaultSources
import me.bsdnme.tvbox.data.LocalStore
import me.bsdnme.tvbox.data.SourceCheckCache
import me.bsdnme.tvbox.data.SourceCheckSnapshot
import me.bsdnme.tvbox.data.SourceCheckEntry
import me.bsdnme.tvbox.data.SourceNetwork
import me.bsdnme.tvbox.data.sourceNetwork
import me.bsdnme.tvbox.data.model.AppSettings
import me.bsdnme.tvbox.data.model.ChannelGroup
import me.bsdnme.tvbox.data.model.LibraryEntry
import me.bsdnme.tvbox.data.model.MediaItem
import me.bsdnme.tvbox.data.model.Site
import me.bsdnme.tvbox.data.model.Subscription
import me.bsdnme.tvbox.data.model.SubscriptionDocument
import me.bsdnme.tvbox.data.model.WarehouseEntry
import me.bsdnme.tvbox.data.model.preferredSite
import me.bsdnme.tvbox.data.net.SourceException
import me.bsdnme.tvbox.data.net.SubscriptionApi
import me.bsdnme.tvbox.data.net.VodApi
import me.bsdnme.tvbox.data.net.SpiderRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
    val localSourceChecks: Boolean = false,
    val checkingSources: Boolean = false,
    val checkedSourceKeys: Set<String> = emptySet(),
    val availableSourceKeys: Set<String> = emptySet(),
    val sourceCheckHasSnapshot: Boolean = false,
    val sourceCheckStale: Boolean = false,
    val sourceCheckCompleted: Int = 0,
    val sourceCheckTotal: Int = 0,
    val sourceCheckUpdateReady: Boolean = false,
    val sourceCheckMessage: String? = null,
    val showAllSources: Boolean = false,
    val error: String? = null,
    val favorites: List<LibraryEntry> = emptyList(),
    val history: List<LibraryEntry> = emptyList(),
) {
    val usableSites: List<Site> get() = document?.usableSites.orEmpty()
    val selectableSites: List<Site>
        get() = if (localSourceChecks && !showAllSources) usableSites.filter { it.type == 3 || it.key in availableSourceKeys || it.key == currentSite?.key } else usableSites
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
    private var sourceCheckJob: Job? = null
    private var sourceCheckPrepareJob: Job? = null
    private var networkChangeJob: Job? = null
    private var runningCheckId: String? = null
    private var displayedSnapshot: SourceCheckSnapshot? = null
    private var pendingSnapshot: SourceCheckSnapshot? = null
    private var snapshotSubscriptionId = ""
    private var settingsVisible = false
    private var appForeground = true
    private val connectivity = application.getSystemService(ConnectivityManager::class.java)
    private var currentNetwork = sourceNetwork(application)
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = scheduleNetworkUpdate()
        override fun onLost(network: Network) = scheduleNetworkUpdate()
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = scheduleNetworkUpdate()
    }

    private companion object {
        /** 直播列表本地缓存有效期：2 小时 */
        const val LIVE_CACHE_TTL_MS = 2 * 60 * 60 * 1000L
    }

    init {
        SpiderRuntime.initialize(application)
        runCatching { connectivity?.registerDefaultNetworkCallback(networkCallback) }
        // 单写者消费者直接挂在 viewModelScope 上（onCleared 自动取消，无需自管 scope），
        // 每条写入统一切到 IO 执行，保证收藏 / 历史的落盘按提交顺序串行完成
        viewModelScope.launch {
            for (write in libraryWrites) withContext(Dispatchers.IO) { write() }
        }
        viewModelScope.launch { loadLocal() }
    }

    override fun onCleared() {
        runCatching { connectivity?.unregisterNetworkCallback(networkCallback) }
        networkChangeJob?.cancel()
        sourceCheckJob?.cancel()
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
                val cached = store.readSubscriptionCache(active.id)?.let { runCatching { SubscriptionApi.parse(it, active.url) }.getOrNull() }
                if (_state.value.settings.activeSubscriptionId == active.id && cached != null) applyDocument(cached)
                _state.update { it.copy(initializing = false) }
                if ((settings.autoUpdateOnLaunch || active.url == DefaultSources.LEGACY_BOX) && _state.value.settings.activeSubscriptionId == active.id) refreshActive(silent = true)
            }
        } else if (settings.autoAddDefault) {
            // 没有任何订阅时静默导入默认影视源；失败不打扰用户，设置页会显示错误
            viewModelScope.launch { addSubscription(DefaultSources.BOX, "默认影视源"); _state.update { it.copy(initializing = false) } }
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
                val document = SubscriptionApi.parse(raw, url)
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
                val site = document.preferredSite(_state.value.settings.currentSiteKey)
                store.currentSiteKey = site?.key.orEmpty()
                _state.update {
                    it.copy(
                        subscriptions = next,
                        document = document,
                        currentSite = site,
                        settings = it.settings.copy(activeSubscriptionId = id, currentSiteKey = site?.key.orEmpty()),
                        loading = false,
                    )
                }
                startLocalSourceChecks(document, id, url)
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
                    val cached = store.readSubscriptionCache(nextId)?.let { raw -> runCatching { SubscriptionApi.parse(raw, next.firstOrNull()?.url.orEmpty()) }.getOrNull() }
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
            val cached = store.readSubscriptionCache(id)?.let { raw -> runCatching { SubscriptionApi.parse(raw, _state.value.subscriptions.firstOrNull { it.id == id }?.url.orEmpty()) }.getOrNull() }
            if (_state.value.settings.activeSubscriptionId != id) return@launch
            if (cached != null) applyDocument(cached)
            val isLegacyDefault = _state.value.subscriptions.firstOrNull { it.id == id }?.url == DefaultSources.LEGACY_BOX
            if (cached == null || isLegacyDefault) refreshSubscription(id)
        }
    }

    suspend fun refreshSubscription(id: String?, silent: Boolean = false) = withContext(Dispatchers.IO) {
        val current = _state.value
        val targetId = id ?: current.settings.activeSubscriptionId
        val active = current.subscriptions.firstOrNull { it.id == targetId } ?: return@withContext

        if (!silent) _state.update { it.copy(loading = true, refreshingSubscriptionId = active.id, error = null) }
        try {
            val targetUrl = if (active.url == DefaultSources.LEGACY_BOX) DefaultSources.BOX else active.url
            var raw = SubscriptionApi.fetchRaw(targetUrl)
            var document = SubscriptionApi.parse(raw, targetUrl)
            if (document.isWarehouse) throw SourceException("该地址已变成多仓订阅，请重新添加子仓库")
            if (targetUrl == DefaultSources.BOX) {
                val previous = store.readSubscriptionCache(active.id)
                    ?.let { runCatching { SubscriptionApi.parse(it, active.url) }.getOrNull() }
                val sourceKeys = store.readSourceKeys(active.id)
                raw = SubscriptionApi.preserveSourceKeys(raw, previous, sourceKeys)
                document = SubscriptionApi.parse(raw, targetUrl)
                store.writeSourceKeys(active.id, sourceKeys)
            }
            store.writeSubscriptionCache(active.id, raw)
            val next = _state.value.subscriptions.map {
                if (it.id == active.id) {
                    it.copy(
                        url = targetUrl,
                        name = if (it.url == DefaultSources.LEGACY_BOX && it.name == "默认单仓") "默认影视源" else it.name,
                        updatedAt = System.currentTimeMillis(),
                        siteCount = document.sites.size,
                    )
                } else it
            }
            store.saveSubscriptions(next)
            _state.update { if (silent) it.copy(subscriptions = next) else it.copy(subscriptions = next, loading = false, refreshingSubscriptionId = null, error = null) }
            if (_state.value.settings.activeSubscriptionId == active.id) applyDocument(document, preferDefault = true)
        } catch (e: kotlinx.coroutines.CancellationException) {
            _state.update { if (it.refreshingSubscriptionId == active.id) it.copy(loading = false, refreshingSubscriptionId = null) else it }
            throw e
        } catch (e: Exception) {
            if (!silent) _state.update { it.copy(loading = false, refreshingSubscriptionId = null, error = e.message ?: "订阅刷新失败") }
        }
    }

    private fun applyDocument(document: SubscriptionDocument, preferDefault: Boolean = false) {
        val usable = document.usableSites
        val savedKey = _state.value.settings.currentSiteKey
        val site = if (preferDefault) document.preferredSite(savedKey)
            else usable.firstOrNull { it.key == savedKey } ?: usable.firstOrNull { it.type != 3 }
        if (site != null) store.currentSiteKey = site.key
        _state.update {
            it.copy(
                document = document,
                currentSite = site,
                settings = it.settings.copy(currentSiteKey = site?.key.orEmpty()),
            )
        }
        val active = _state.value.subscriptions.firstOrNull { it.id == _state.value.settings.activeSubscriptionId }
        startLocalSourceChecks(document, active?.id.orEmpty(), active?.url.orEmpty())
    }

    private fun startLocalSourceChecks(document: SubscriptionDocument, subscriptionId: String, url: String, force: Boolean = false) {
        sourceCheckPrepareJob?.cancel()
        sourceCheckPrepareJob = viewModelScope.launch {
            val managed = url.isNotBlank() && document.sites.isNotEmpty()
            if (!managed) {
                sourceCheckJob?.cancel(); sourceCheckJob = null; runningCheckId = null
                displayedSnapshot = null; pendingSnapshot = null; snapshotSubscriptionId = subscriptionId
                _state.update { it.copy(localSourceChecks = false, checkingSources = false, checkedSourceKeys = emptySet(), availableSourceKeys = emptySet(), sourceCheckHasSnapshot = false, sourceCheckUpdateReady = false, sourceCheckStale = false, sourceCheckMessage = null, showAllSources = false) }
                return@launch
            }
            val network = currentNetwork
            val sites = document.usableSites.filter { it.type != 3 }.sortedBy { if (it.key == _state.value.currentSite?.key) 0 else 1 }
            val ids = sites.map(SourceCheckCache::identity).toSet()
            val runId = subscriptionId + "|" + network.key + "|" + ids.sorted().joinToString(",")
            if (!force && runningCheckId == runId && sourceCheckJob?.isActive == true) return@launch
            sourceCheckJob?.cancel(); sourceCheckJob = null; runningCheckId = null
            val changedSubscription = snapshotSubscriptionId != subscriptionId
            if (changedSubscription) {
                displayedSnapshot = null; pendingSnapshot = null; snapshotSubscriptionId = subscriptionId
                _state.update { it.copy(localSourceChecks = true, checkedSourceKeys = emptySet(), availableSourceKeys = emptySet(), sourceCheckHasSnapshot = false, sourceCheckUpdateReady = false, sourceCheckStale = false, sourceCheckMessage = null, showAllSources = false) }
            } else _state.update { it.copy(localSourceChecks = true) }
            val snapshots = withContext(Dispatchers.IO) { store.readSourceChecks(subscriptionId) }
            if (_state.value.settings.activeSubscriptionId != subscriptionId || currentNetwork != network) return@launch
            val exact = snapshots.firstOrNull { it.network == network.key && network.key.isNotBlank() }
            val candidate = exact ?: snapshots.maxByOrNull { it.checkedAt }
            if (displayedSnapshot == null && candidate != null) applySourceSnapshot(candidate, document)
            else if (exact != null && displayedSnapshot?.network != exact.network) {
                if (settingsVisible) { pendingSnapshot = exact; _state.update { it.copy(sourceCheckUpdateReady = true) } }
                else applySourceSnapshot(exact, document)
            }
            val displayed = displayedSnapshot
            _state.update { it.copy(sourceCheckStale = displayed != null && (displayed.network != network.key || !displayed.isFresh(System.currentTimeMillis()) || !displayed.entries.keys.containsAll(ids)), sourceCheckMessage = if (!network.online) if (displayed != null) "当前网络不可用，保留上次检测结果" else "当前网络不可用，暂时无法检测" else null) }
            val fresh = exact?.isFresh(System.currentTimeMillis()) == true && exact.entries.keys.containsAll(ids)
            if (!appForeground || !network.online || (!force && fresh) || sites.isEmpty()) {
                _state.update { it.copy(checkingSources = false) }
                return@launch
            }
            runningCheckId = runId
            _state.update { it.copy(checkingSources = true, sourceCheckCompleted = 0, sourceCheckTotal = sites.size, sourceCheckMessage = null) }
            sourceCheckJob = viewModelScope.launch {
                val semaphore = Semaphore(6)
                val results = coroutineScope {
                    sites.map { site -> async {
                        val ok = semaphore.withPermit {
                            withContext(Dispatchers.IO) {
                                try { VodApi.list(site, timeoutSeconds = 8).items.isNotEmpty() }
                                catch (e: CancellationException) { throw e }
                                catch (_: Exception) { false }
                            }
                        }
                        _state.update { it.copy(sourceCheckCompleted = it.sourceCheckCompleted + 1) }
                        SourceCheckCache.identity(site) to ok
                    } }.awaitAll().toMap()
                }
                if (_state.value.settings.activeSubscriptionId != subscriptionId || currentNetwork != network || runningCheckId != runId) return@launch
                runningCheckId = null
                val noSuccess = results.values.none { it }
                val previousAvailable = (exact ?: displayedSnapshot)?.entries?.values?.any { it.available } == true
                if (!currentNetwork.online || (noSuccess && previousAvailable)) {
                    _state.update { it.copy(checkingSources = false, sourceCheckStale = true, sourceCheckMessage = "本轮未检测到可用源，保留上次结果") }
                    return@launch
                }
                val snapshot = SourceCheckCache.merge(exact, network.key, System.currentTimeMillis(), results)
                withContext(Dispatchers.IO) { store.writeSourceChecks(subscriptionId, snapshot) }
                if (_state.value.settings.activeSubscriptionId != subscriptionId || currentNetwork != network) return@launch
                if (settingsVisible) {
                    pendingSnapshot = snapshot
                    _state.update { it.copy(checkingSources = false, sourceCheckUpdateReady = true, sourceCheckMessage = if (noSuccess) "本轮未检测到可用源，请检查网络或稍后重试" else null) }
                } else {
                    applySourceSnapshot(snapshot, _state.value.document ?: document)
                    _state.update { it.copy(checkingSources = false, sourceCheckMessage = if (noSuccess) "本轮未检测到可用源，请检查网络或稍后重试" else null) }
                }
            }
        }
    }

    private fun applySourceSnapshot(snapshot: SourceCheckSnapshot, document: SubscriptionDocument) {
        displayedSnapshot = snapshot; pendingSnapshot = null
        val spiderKeys = document.sites.filter { it.type == 3 }.map { it.key }.toSet()
        val checked = document.usableSites.filter { site -> snapshot.entries[SourceCheckCache.identity(site)]?.let { it.available || it.failures >= 2 } == true }.map { it.key }.toSet()
        val available = document.usableSites.filter { site -> snapshot.entries[SourceCheckCache.identity(site)]?.available == true }.map { it.key }.toSet()
        val currentIds = document.usableSites.filter { it.type != 3 }.map(SourceCheckCache::identity)
        _state.update { it.copy(checkedSourceKeys = checked + it.checkedSourceKeys.intersect(spiderKeys), availableSourceKeys = available + it.availableSourceKeys.intersect(spiderKeys), sourceCheckHasSnapshot = true, sourceCheckUpdateReady = false, sourceCheckStale = snapshot.network != currentNetwork.key || !snapshot.isFresh(System.currentTimeMillis()) || !snapshot.entries.keys.containsAll(currentIds), sourceCheckMessage = null) }
    }

    fun applySourceCheckUpdate() {
        val snapshot = pendingSnapshot ?: return
        if (snapshotSubscriptionId != _state.value.settings.activeSubscriptionId || snapshot.network != currentNetwork.key) {
            pendingSnapshot = null
            _state.update { it.copy(sourceCheckUpdateReady = false) }
            return
        }
        _state.value.document?.let { applySourceSnapshot(snapshot, it) }
    }

    fun setSourceSettingsVisible(visible: Boolean) {
        settingsVisible = visible
        if (!visible) applySourceCheckUpdate()
    }

    fun showAllSources(show: Boolean) { _state.update { it.copy(showAllSources = show) } }

    fun refreshSourceChecks() {
        val state = _state.value
        val active = state.subscriptions.firstOrNull { it.id == state.settings.activeSubscriptionId } ?: return
        state.document?.let { startLocalSourceChecks(it, active.id, active.url, force = true) }
    }

    private fun scheduleNetworkUpdate() {
        networkChangeJob?.cancel()
        networkChangeJob = viewModelScope.launch {
            delay(750)
            val next = sourceNetwork(getApplication())
            if (next == currentNetwork) return@launch
            currentNetwork = next
            sourceCheckJob?.cancel(); sourceCheckJob = null; runningCheckId = null
            pendingSnapshot = null
            _state.update { it.copy(checkingSources = false, sourceCheckUpdateReady = false, sourceCheckStale = it.sourceCheckHasSnapshot, sourceCheckMessage = if (next.online) null else if (it.sourceCheckHasSnapshot) "当前网络不可用，保留上次检测结果" else "当前网络不可用，暂时无法检测") }
            if (appForeground) restartSourceChecks()
        }
    }

    private fun restartSourceChecks() {
        val state = _state.value
        val active = state.subscriptions.firstOrNull { it.id == state.settings.activeSubscriptionId } ?: return
        state.document?.let { startLocalSourceChecks(it, active.id, active.url) }
    }

    fun setAppForeground(foreground: Boolean) {
        if (appForeground == foreground) return
        appForeground = foreground
        if (foreground) { currentNetwork = sourceNetwork(getApplication()); restartSourceChecks() }
        else { sourceCheckPrepareJob?.cancel(); sourceCheckJob?.cancel(); sourceCheckJob = null; runningCheckId = null; _state.update { it.copy(checkingSources = false) } }
    }

    fun selectSite(site: Site) {
        store.currentSiteKey = site.key
        _state.update { it.copy(currentSite = site, settings = it.settings.copy(currentSiteKey = site.key)) }
    }

    /** Run the same initialization and homepage path used by the UI, after explicit approval. */
    suspend fun selectSpider(site: Site): String? = withContext(Dispatchers.IO) {
        val subscriptionId = _state.value.settings.activeSubscriptionId
        val document = _state.value.document
        val network = sourceNetwork(getApplication())
        fun saveResult(available: Boolean) {
            if (network.online && network.key.isNotBlank()) {
                val checkedAt = if (document?.usableSites?.none { it.type != 3 } == true) System.currentTimeMillis() else 0
                store.writeSourceChecks(subscriptionId, SourceCheckSnapshot(network.key, checkedAt, mapOf(SourceCheckCache.identity(site) to SourceCheckEntry(available, if (available) 0 else 2))))
            }
        }
        try {
            VodApi.list(site)
            if (_state.value.settings.activeSubscriptionId != subscriptionId || _state.value.document !== document || sourceNetwork(getApplication()) != network) return@withContext "订阅或网络已改变，请重新选择源站"
            saveResult(true)
            _state.update { it.copy(checkedSourceKeys = it.checkedSourceKeys + site.key, availableSourceKeys = it.availableSourceKeys + site.key) }
            selectSite(site)
            null
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (sourceNetwork(getApplication()) == network) saveResult(false)
            _state.update { it.copy(checkedSourceKeys = it.checkedSourceKeys + site.key, availableSourceKeys = it.availableSourceKeys - site.key) }
            error.message ?: "源站检测失败"
        }
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
                        typeId = item.typeId,
                        categoryName = item.categoryName,
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
                    typeId = item.typeId,
                    categoryName = item.categoryName,
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
