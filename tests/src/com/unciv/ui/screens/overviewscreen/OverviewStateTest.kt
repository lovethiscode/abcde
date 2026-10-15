package com.unciv.ui.screens.overviewscreen

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.utils.Json
import com.unciv.models.metadata.OverviewPersistableData
import com.unciv.models.translations.TranslationFileReader
import com.unciv.models.translations.squareBraceRegex
import com.unciv.testing.BaseTestRunner
import com.unciv.ui.components.widgets.SortableGrid
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class OverviewStateTest {
    @Test fun legacyUnitSortStillLoadsAndTransientSelectionIsNotSaved() {
        val json = Json()
        val overview = json.fromJson(OverviewPersistableData::class.java,
            """{"last":"Units","Units":{"sortedBy":"Health","direction":"Descending"}}""")
        val units = overview[EmpireOverviewCategories.Units] as UnitOverviewTab.UnitTabPersistableData
        assertEquals(EmpireOverviewCategories.Units, overview.last)
        assertEquals(UnitOverviewTabColumn.Health, units.sortedBy)
        assertEquals(SortableGrid.SortDirection.Descending, units.direction)
        units.selectedUnitId = 17
        units.scrollY = 120f
        val saved = json.toJson(overview)
        assertFalse(saved.contains("selectedUnitId"))
        assertFalse(saved.contains("scrollY"))
        val restored = json.fromJson(OverviewPersistableData::class.java, saved)
        assertEquals(UnitOverviewTabColumn.Health,
            (restored[EmpireOverviewCategories.Units] as UnitOverviewTab.UnitTabPersistableData).sortedBy)
    }

    @Test fun newOverviewCopyIsTranslatedWithoutChangingPlaceholders() {
        val keys = listOf("Select a unit to view details", "Locate on map", "Search cities", "City details",
            "Open city", "No matching cities", "No units", "Income and expenses", "Resource supply and demand",
            "Use the location icon to select a unit on the map", "City names open cities; info icons show details here",
            "City production total", "Our offers", "Their offers", "Available", "Rename unit", "[count] cities · [population] population")
        for (language in listOf("English", "Simplified_Chinese", "Traditional_Chinese", "Spanish", "French",
            "German", "Brazilian_Portuguese", "Russian", "Japanese", "Korean")) {
            val file = Gdx.files.internal("jsons/translations/$language.properties")
            val entries = TranslationFileReader.read(file)
            for (key in keys) {
                val translated = entries[key]
                assertFalse("$language: $key", translated.isNullOrBlank())
                assertEquals("$language: $key", squareBraceRegex.findAll(key).map { it.value }.toList(),
                    squareBraceRegex.findAll(translated!!).map { it.value }.toList())
            }
        }
    }
}
