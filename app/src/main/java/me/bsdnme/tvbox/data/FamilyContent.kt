package me.bsdnme.tvbox.data

import me.bsdnme.tvbox.data.model.Category
import me.bsdnme.tvbox.data.model.MediaItem
import me.bsdnme.tvbox.data.model.PagedMedia
import me.bsdnme.tvbox.data.model.Site
import me.bsdnme.tvbox.data.net.SourceException
import me.bsdnme.tvbox.data.net.VodApi
import java.util.concurrent.ConcurrentHashMap

/** Classification is per source: IDs are not shared between sources. */
object FamilyContent {
    private val categoryCache = ConcurrentHashMap<Site, List<Category>>()
    private val terms = listOf("伦理", "倫理", "情色", "色情", "成人", "限制级", "限制級", "三级片", "三級片", "福利片", "18+")
    fun restrictedName(name: String) = terms.any { name.contains(it, ignoreCase = true) }
    fun restrictedIds(categories: List<Category>): Set<String> {
        val ids = categories.filter { restrictedName(it.name) }.map { it.typeId }.toMutableSet()
        var changed: Boolean
        do {
            changed = false
            categories.forEach { if (it.parentId in ids && ids.add(it.typeId)) changed = true }
        } while (changed)
        return ids
    }
    private fun rawCategories(site: Site): List<Category> = categoryCache[site] ?: VodApi.categories(site).also { categoryCache[site] = it }
    fun categories(site: Site, family: Boolean): List<Category> {
        val all = VodApi.categories(site)
        categoryCache[site] = all
        val blocked = if (family) restrictedIds(all) else emptySet()
        return all.filterNot { it.typeId in blocked }
    }
    fun visible(item: MediaItem, categories: List<Category>): Boolean =
        !restrictedName(item.name) && !restrictedName(item.remarks) &&
            !restrictedName(item.categoryName) && item.typeId !in restrictedIds(categories)
    private fun filter(site: Site, page: PagedMedia, family: Boolean): PagedMedia {
        if (!family) return page
        val categories = runCatching { rawCategories(site) }.getOrDefault(page.categories)
        val blocked = restrictedIds(categories)
        return page.copy(items = page.items.filter { !restrictedName(it.name) && !restrictedName(it.remarks) && !restrictedName(it.categoryName) && it.typeId !in blocked },
            categories = page.categories.filterNot { it.typeId in blocked || restrictedName(it.name) })
    }
    fun list(site: Site, typeId: String = "", page: Int = 1, family: Boolean): PagedMedia {
        val result = filter(site, VodApi.list(site, typeId, page), family)
        // Some remote spiders return no homepage and VodApi falls back to their first category.
        // If that category was hidden, try a visible one instead.
        if (family && site.type in listOf(3, 4) && typeId.isBlank() && result.items.isEmpty()) {
            for (category in result.categories.take(4)) {
                val candidate = filter(site, VodApi.list(site, category.typeId, 1), true)
                if (candidate.items.isNotEmpty()) return candidate.copy(categories = result.categories)
            }
        }
        return result
    }
    suspend fun listChildren(site: Site, parentId: String, categories: List<Category>, page: Int, family: Boolean): PagedMedia =
        filter(site, VodApi.listChildren(site, parentId, categories, page), family)
    fun search(site: Site, keyword: String, page: Int = 1, family: Boolean): PagedMedia =
        filter(site, VodApi.search(site, keyword, page), family)
    fun detail(site: Site, id: String, family: Boolean) = VodApi.detail(site, id).also {
        if (family && !visible(it.item, runCatching { rawCategories(site) }.getOrDefault(emptyList())))
            throw SourceException("家庭模式下无法查看此内容")
    }
    fun libraryVisible(item: MediaItem, site: Site?): Boolean {
        if (item.typeId.isBlank() && item.categoryName.isBlank()) return false
        val categories = site?.let { categoryCache[it] }.orEmpty()
        // A category ID without its source's category table cannot be classified safely.
        if (item.typeId.isNotBlank() && categories.none { it.typeId == item.typeId }) return false
        return visible(item, categories)
    }
}
