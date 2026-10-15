package com.unciv.ui.screens.savescreens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoadGamePreviewSelectionTest {
    private val first = LoadGameSaveKey("SaveFiles/first", 100, 200)

    @Test fun latePreviewCannotOverwriteAnotherSelectionOrARefreshedList() {
        assertTrue(acceptsLoadGamePreview(first, first, 3, 3))
        assertFalse(acceptsLoadGamePreview(first, first.copy(path = "SaveFiles/other"), 3, 3))
        assertFalse(acceptsLoadGamePreview(first, first, 2, 3))
        assertFalse(acceptsLoadGamePreview(first, null, 3, 3))
    }

    @Test fun sameNamedCloudReplacementInvalidatesPendingMetadata() {
        assertFalse(acceptsLoadGamePreview(first, first.copy(modified = 101), 3, 3))
        assertFalse(acceptsLoadGamePreview(first, first.copy(length = 201), 3, 3))
    }
}
