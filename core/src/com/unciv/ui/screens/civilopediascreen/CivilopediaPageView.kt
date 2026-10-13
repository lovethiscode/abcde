package com.unciv.ui.screens.civilopediascreen

import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.models.skins.ExpeditionBlueTheme
import com.unciv.ui.components.extensions.addSeparator
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.input.KeyCharAndCode
import com.unciv.ui.components.input.keyShortcuts
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.widgets.AutoScrollPane
import com.unciv.ui.components.widgets.UiNavigation
import com.unciv.ui.images.IconTextButton
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.BaseScreen

/** The iOS reading layout. Rules, entry selection and search remain owned by CivilopediaScreen. */
internal class CivilopediaPageView(private val screen: CivilopediaScreen) : Table() {
    private val content = Table()
    val scroll = AutoScrollPane(content).apply {
        setScrollingDisabled(true, false)
        setOverscroll(false, false)
    }
    private val contentWidth get() = screen.stage.width - 48f
    private val touchSize get() = UiNavigation.minimumTouchSize()

    init {
        name = "civilopedia-pages"
        setFillParent(true)
        content.top().left().pad(8f, 24f, 24f, 24f)
        val left = Table()
        left.add(UiNavigation.backButton().apply {
            onActivation { screen.backPage() }
            keyShortcuts.add(KeyCharAndCode.BACK)
        }).minHeight(touchSize).padRight(8f)
        left.add(button("Category:", "OtherIcons/MenuIcon", "civilopedia-categories") { screen.openCategories() }).minSize(touchSize)
        val right = Table()
        right.add(button("", "OtherIcons/Search", "civilopedia-search") { screen.openSearch() }).minSize(touchSize).padRight(8f)
        right.add(UiNavigation.dismissButton().apply { onActivation { screen.game.popScreen() } })
            .minSize(touchSize)
        val title = "Civilopedia".toLabel(ExpeditionBlueTheme.text, 25)
        val header = Table().apply { pad(10f, 20f, 10f, 20f) }
        if (screen.stage.width < screen.stage.height) {
            header.add(title).colspan(2).padBottom(8f).row()
            header.add(left).left().expandX()
            header.add(right).right()
        } else {
            header.add(left).left().expandX()
            header.add(title).pad(0f, 12f, 0f, 12f)
            header.add(right).right().expandX()
        }
        add(header).growX().row()
        addSeparator(ExpeditionBlueTheme.border)
        add(scroll).grow()
    }

    private fun button(text: String, icon: String, actorName: String, wrap: Boolean = false, action: () -> Unit) =
        IconTextButton(text, icon.takeIf { it.isNotEmpty() }?.let { ImageGetter.getImage(it) }, 20).apply {
            name = actorName
            label.wrap = wrap
            if (wrap) labelCell.minWidth(0f).growX()
            onActivation { action() }
        }

    private fun begin() {
        content.clear()
        content.defaults().left()
        scroll.scrollY = 0f
    }

    private fun finish() {
        content.invalidateHierarchy()
        validate()
        scroll.layout()
        scroll.updateVisualScroll()
        screen.stage.scrollFocus = scroll
    }

    fun categories() {
        begin()
        content.add("Category:".toLabel(fontSize = 30)).padBottom(20f).row()
        val grid = Table()
        val columns = (contentWidth / 220f).toInt().coerceIn(2, 5)
        val cellWidth = (contentWidth - 12f * (columns - 1)) / columns
        screen.visibleCategories.forEachIndexed { index, category ->
            val actor = button(category.label, category.headerIcon, "civilopedia-category-${category.name}", wrap = true) {
                screen.openCategory(category)
            }
            grid.add(actor).width(cellWidth).minHeight(maxOf(touchSize, 86f)).fillY()
                .padRight(if (index % columns == columns - 1) 0f else 12f).padBottom(12f)
            if (index % columns == columns - 1) grid.row()
        }
        content.add(grid).width(contentWidth).top().row()
        finish()
    }

    fun browse() {
        begin()
        content.add(screen.selectedCategory.label.toLabel(fontSize = 30)).padBottom(16f).row()
        val columns = (contentWidth / 280f).toInt().coerceIn(1, 3)
        val cellWidth = (contentWidth - 12f * (columns - 1)) / columns
        var subCategory: String? = null
        var grid = Table()
        var index = 0
        fun finishGroup() {
            if (index == 0) return
            content.add(grid).width(contentWidth).top().padBottom(12f).row()
            grid = Table()
            index = 0
        }
        for (entry in screen.selectedEntries) {
            if (subCategory != entry.subCategory) {
                finishGroup()
                subCategory = entry.subCategory
                if (subCategory != null)
                    content.add(SubCategoryTable(subCategory)).width(contentWidth).padBottom(12f).row()
            }
            val actor = IconTextButton(entry.name, entry.image, 22).apply {
                name = "civilopedia-entry-${entry.name}"
                label.wrap = true
                labelCell.minWidth(0f).growX()
                iconCell.size(38f).padRight(12f)
                onActivation { screen.openEntry(entry.name) }
            }
            grid.add(actor).width(cellWidth).minHeight(maxOf(touchSize, 86f)).fillY()
                .padRight(if (index % columns == columns - 1) 0f else 12f).padBottom(12f)
            if (index % columns == columns - 1) grid.row()
            index++
        }
        finishGroup()
        finish()
    }

    private fun render(lines: List<FormattedLine>, width: Float): Table =
        MarkupRenderer.render(lines, width, padding = 6f, minimumLinkHeight = touchSize,
            fitToWidth = true, linkAction = screen::openLink)

    fun detail(entry: CivilopediaScreen.CivilopediaEntry) {
        begin()
        val flavour = entry.flavour ?: return finish()
        val article = CivilopediaArticle(flavour.assembleCivilopediaText(screen.ruleset).civilopediaText)
        val width = contentWidth
        val art = article.sections["art"].orEmpty()
        if (art.any { it.extraImage.isNotEmpty() }) {
            val heading = Table()
            val artWidth = 90f
            heading.add(render(article.introduction, width - artWidth - 20f)).top().left()
            heading.add(render(art, artWidth)).top().padLeft(20f)
            content.add(heading).width(width).row()
        } else {
            content.add(render(article.introduction, width)).width(width).row()
            if (art.isNotEmpty()) content.add(render(art, width)).width(width).row()
        }

        val stats = article.sections["stats"].orEmpty().flatMap { it.civilopediaStats }
        if (stats.isNotEmpty()) {
            val columns = minOf(stats.size, if (width >= 800f) 4 else 2)
            val grid = Table().apply {
                background = BaseScreen.skinStrings.getUiBackground("CivilopediaScreen/Stats",
                    tintColor = ExpeditionBlueTheme.panel)
                pad(12f)
            }
            val cellWidth = (width - 24f) / columns
            stats.forEachIndexed { index, stat ->
                val cell = Table().apply {
                    pad(8f, 12f, 8f, 12f)
                    val caption = Table()
                    caption.add(stat.icon.toString().toLabel(fontSize = 20)).padRight(8f)
                    caption.add(stat.label.toLabel(ExpeditionBlueTheme.muted, 17, hideIcons = true).apply { wrap = true })
                        .minWidth(0f).growX()
                    add(caption).width(cellWidth - 24f).left().row()
                    add(stat.value.toString().toLabel(fontSize = 34)).left().padTop(4f)
                }
                grid.add(cell).width(cellWidth).top()
                if (index % columns == columns - 1) grid.row()
            }
            content.add(grid).width(width).padTop(12f).padBottom(20f).row()
        }

        val unstructuredStats = article.sections["stats"].orEmpty().filter { it.civilopediaStats.isEmpty() }
        if (unstructuredStats.isNotEmpty()) content.add(render(unstructuredStats, width)).width(width).row()

        val groups = listOf(listOf("conditions", "abilities"), listOf("upgrades"), listOf("related"))
            .filter { group -> group.any { key -> article.sections[key]?.any { !it.isEmpty() } == true } }
        val columns = minOf(groups.size.coerceAtLeast(1), (width / 320f).toInt().coerceIn(1, 3))
        val sectionWidth = (width - 24f * (columns - 1)) / columns
        val sections = Table()
        groups.forEachIndexed { index, group ->
            val column = Table().apply { top().left() }
            for (key in group) {
                val lines = article.sections[key].orEmpty()
                if (lines.none { !it.isEmpty() }) continue
                column.addSeparator(ExpeditionBlueTheme.border)
                val title = when (key) {
                    "conditions" -> "Unit type"
                    "abilities" -> "Unit ability"
                    "upgrades" -> "Upgrade"
                    else -> "" // The generated see-also header is already part of the original content.
                }
                if (title.isNotEmpty()) column.add(title.toLabel(ExpeditionBlueTheme.gold, 20))
                    .left().padTop(8f).padBottom(4f).row()
                column.add(render(lines, sectionWidth)).width(sectionWidth).padBottom(12f).row()
            }
            sections.add(column).width(sectionWidth).top()
                .padRight(if (index % columns == columns - 1) 0f else 24f)
            if (index % columns == columns - 1) sections.row()
        }
        content.add(sections).width(width).row()
        // Unknown generated sections and unstructured custom content always keep their original renderer.
        for ((key, lines) in article.sections) {
            if (key !in setOf("art", "stats", "conditions", "abilities", "upgrades", "related"))
                content.add(render(lines, width)).width(width).row()
        }
        content.add(render(article.conclusion, width)).width(width).row()
        content.addSeparator(ExpeditionBlueTheme.border)
        val footer = Table()
        footer.add(button(screen.selectedCategory.label, "OtherIcons/BackArrow", "civilopedia-browse") {
            screen.openCategory(screen.selectedCategory)
        }).minHeight(touchSize).left().expandX()
        footer.add(button("Category:", "OtherIcons/MenuIcon", "civilopedia-all-categories") {
            screen.openCategories()
        }).minHeight(touchSize).right()
        content.add(footer).width(width).padTop(12f).row()
        finish()
    }
}
