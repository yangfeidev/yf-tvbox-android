package me.bsdnme.tvbox.data

import me.bsdnme.tvbox.data.model.Category
import me.bsdnme.tvbox.data.model.MediaItem
import org.junit.Assert.*
import org.junit.Test

class FamilyContentTest {
    @Test fun `restricted categories include all descendants without affecting unrelated categories`() {
        val categories = listOf(Category("1", "伦理片"), Category("2", "其他", "1"), Category("3", "子类", "2"), Category("4", "电影"))
        assertEquals(setOf("1", "2", "3"), FamilyContent.restrictedIds(categories))
        assertFalse(FamilyContent.visible(MediaItem("1", "影片", typeId = "3"), categories))
        assertTrue(FamilyContent.visible(MediaItem("2", "影片", typeId = "4"), categories))
        assertFalse(FamilyContent.visible(MediaItem("3", "影片", categoryName = "倫理片"), categories))
    }

    @Test fun `unknown legacy library entry is hidden`() {
        assertFalse(FamilyContent.libraryVisible(MediaItem("1", "旧记录"), null))
        assertFalse(FamilyContent.libraryVisible(MediaItem("2", "无法查证", typeId = "9"), null))
    }
}
