package com.myreading.core

import org.junit.Assert.*
import org.junit.Test

class ImageSupportTest {
    @Test fun samplingStartsAtOneAndLimitsLargePhotos() {
        assertEquals(1, imageSampleSize(800, 600))
        assertEquals(4, imageSampleSize(8000, 6000))
        assertEquals(1, imageSampleSize(2048, 2048))
    }

    @Test fun photosFitPagesAndKeepAspectRatioInBothDirections() {
        val chapter = Chapter("images", "Text/ch.xhtml", "", 0, true,
            """<html xmlns="http://www.w3.org/1999/xhtml"><body><p>前</p><img src="../Images/photo.jpg"/><p>后</p></body></html>""".toByteArray(), emptySet())
        val engine = VerticalLayoutEngine(imageDimensions = { _, _ -> 1200f to 800f })
        ReadingOrientation.entries.forEach { orientation ->
            val settings = LayoutSettings(orientation = orientation, pageWidth = 320f, pageHeight = 480f)
            val pages = engine.paginate(chapter, Stylesheet(emptyList(), emptySet()), settings).pages
            val fragments = pages.flatMap { it.columns }.flatMap { it.fragments }
            val picture = fragments.filterIsInstance<LayoutFragment.Image>().single()
            assertEquals(1.5f, picture.width / picture.height, 0.01f)
            assertTrue(picture.x >= settings.marginLeft - 0.01f)
            assertTrue(picture.x + picture.width <= settings.pageWidth - settings.marginRight + 0.01f)
            assertTrue(picture.y + picture.height <= settings.pageHeight - settings.marginBottom + 0.01f)
            assertEquals("前后", fragments.filterIsInstance<LayoutFragment.Text>().joinToString("") { it.sourceText })
        }
    }
}
