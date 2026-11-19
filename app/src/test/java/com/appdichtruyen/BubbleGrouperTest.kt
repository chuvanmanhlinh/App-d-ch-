package com.appdichtruyen

import com.appdichtruyen.engine.BubbleGrouper
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BubbleGrouperTest {

    @Test
    fun meaningfulText_filtersNoiseAndKeepsComicDialogue() {
        assertFalse(BubbleGrouper.isMeaningfulText(""))
        assertFalse(BubbleGrouper.isMeaningfulText("...?!"))
        assertFalse(BubbleGrouper.isMeaningfulText("123"))
        assertFalse(BubbleGrouper.isMeaningfulText("a"))
        assertFalse(BubbleGrouper.isMeaningfulText("12/24"))
        assertFalse(BubbleGrouper.isMeaningfulText("ch. 45"))
        assertFalse(BubbleGrouper.isMeaningfulText("12:30"))
        assertFalse(BubbleGrouper.isMeaningfulText("www.example.com"))
        assertTrue(BubbleGrouper.isMeaningfulText("Watch out!"))
        assertTrue(BubbleGrouper.isMeaningfulText("何"))
        assertTrue(BubbleGrouper.isMeaningfulText("안녕"))
    }
}
