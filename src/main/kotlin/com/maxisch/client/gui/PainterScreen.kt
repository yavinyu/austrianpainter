package com.maxisch.client.gui

import com.maxisch.client.KeyHints
import com.maxisch.client.gui.tab.ApTab
import com.maxisch.client.gui.tab.AreaTab
import com.maxisch.client.gui.tab.BrushTab
import com.maxisch.client.gui.tab.DungeonTab
import com.maxisch.client.gui.tab.HistoryTab
import com.maxisch.client.gui.tab.PresetsTab
import com.maxisch.client.gui.tab.SettingsTab
import com.maxisch.paint.PaintStorage
import com.maxisch.paint.PresetKind
import com.maxisch.paint.session.PaintSelection
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.tabs.Tab
import net.minecraft.client.gui.components.tabs.TabManager
import net.minecraft.client.gui.components.tabs.TabNavigationBar
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout
import net.minecraft.client.gui.layouts.LinearLayout
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.client.gui.screens.Screen
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.world.level.block.Block

/**
 * The whole paint UI, in one screen.
 *
 * Everything the mod can do is one tab click away rather than several screens deep, and results are
 * reported on a status line here instead of in chat, which the player cannot see while a screen is
 * open. The YACL screen ([ApSettingsScreen]) still exists as ModMenu's config-button target, but the
 * Settings tab here is the primary way to reach every option. Block picking stays a modal
 * ([BlockPickerScreen]) - the picker needs the full screen height for its grid.
 */
class PainterScreen private constructor(parent: Screen?) :
    ApScreen(Component.translatable("austrianpainter.screen.title"), parent) {

    /** Declaration order must match the tab list built in [init] - [onTabEntered] maps by index. */
    enum class TabId(val titleKey: String) {
        BRUSH("austrianpainter.tab.brush"),
        AREA("austrianpainter.tab.area"),
        DUNGEON("austrianpainter.tab.dungeon"),
        PRESETS("austrianpainter.tab.presets"),
        HISTORY("austrianpainter.tab.history"),
        SETTINGS("austrianpainter.tab.settings"),
    }

    companion object {
        private const val MARGIN = 8
        private const val STATUS_HEIGHT = 14
        private const val STATUS_COLOR = 0xFFFFFF55.toInt()

        /** How long a status line stays up before it fades out on its own. */
        private const val STATUS_MS = 6_000L

        /**
         * Survives a round trip through the block picker, so choosing a donor from the area tab
         * comes back to the area tab.
         */
        private var lastTab = TabId.BRUSH

        fun open(parent: Screen? = null): PainterScreen = PainterScreen(parent)

        fun openAt(tab: TabId, parent: Screen? = null): PainterScreen {
            lastTab = tab
            return PainterScreen(parent)
        }

        /** Convenience for the settings screen's three "Manage ..." buttons. */
        fun openPresets(parent: Screen?) {
            Minecraft.getInstance().setScreenAndShow(openAt(TabId.PRESETS, parent))
        }
    }

    private val layout = HeaderAndFooterLayout(this)
    // The enter/exit consumers are called with null when there is no tab on that side of the
    // switch - the very first selectTab has nothing to exit - so both parameters must be nullable.
    private val tabManager = TabManager(
        { widget: AbstractWidget -> addRenderableWidget(widget) },
        { widget: AbstractWidget -> removeWidget(widget) },
        { tab: Tab? -> tab?.let { onTabEntered(it) } },
        { tab: Tab? -> (tab as? ApTab)?.onHidden() },
    )

    private var tabBar: TabNavigationBar? = null
    private var tabs: List<ApTab> = emptyList()
    private var undoButton: Button? = null
    private var redoButton: Button? = null

    /**
     * Captured once when the screen opens: the crosshair freezes the moment any screen is up, so
     * re-reading it per tab switch would only ever come back null.
     */
    var lookedAtPos: BlockPos? = null
        private set

    var lookedAtBlock: Block? = null
        private set

    private var statusText: Component? = null
    private var statusUntil = 0L

    /**
     * Reports the result of an action where the player is actually looking.
     *
     * An action too large to record for undo says so instead: the paint result is visible in the
     * world anyway, whereas silently losing the history is the surprising part.
     */
    fun status(message: Component) {
        statusText = if (PaintStorage.undoOverflowed) {
            Component.translatable("austrianpainter.undo.too_big")
        } else {
            message
        }
        statusUntil = System.currentTimeMillis() + STATUS_MS
        refreshUndoButton()
    }

    /** Re-reads the rules into the visible tab; used after an undo changes them underneath it. */
    private fun refreshTabs() {
        (tabManager.currentTab as? ApTab)?.refresh()
    }

    private fun refreshUndoButton() {
        undoButton?.let {
            it.message = undoLabel()
            it.active = PaintStorage.canUndo
        }
        redoButton?.let {
            it.message = redoLabel()
            it.active = PaintStorage.canRedo
        }
    }

    private fun undoLabel(): Component =
        Component.translatable("austrianpainter.undo.button", PaintStorage.undoDepth)

    private fun redoLabel(): Component =
        Component.translatable("austrianpainter.redo.button", PaintStorage.redoDepth)

    override fun init() {
        lookedAtPos = PaintSelection.lookedAtPos()
        lookedAtBlock = PaintSelection.lookedAtBlock()

        tabs = listOf(
            BrushTab(this),
            AreaTab(this),
            DungeonTab(this),
            PresetsTab(this),
            HistoryTab(this),
            SettingsTab(this),
        )

        val bar = TabNavigationBar.builder(tabManager, width)
            .addTabs(*tabs.toTypedArray<Tab>())
            .build()
        tabBar = addRenderableWidget(bar)

        val footer = layout.addToFooter(LinearLayout.horizontal().spacing(8))
        undoButton = footer.addChild(
            Button.builder(undoLabel()) {
                status(PaintStorage.undo())
                refreshTabs()
            }
                .width(100)
                .tooltip(KeyHints.undoTooltip())
                .build(),
        )
        redoButton = footer.addChild(
            Button.builder(redoLabel()) {
                status(PaintStorage.redo())
                refreshTabs()
            }
                .width(100)
                .tooltip(KeyHints.redoTooltip())
                .build(),
        )
        footer.addChild(
            Button.builder(Component.translatable("austrianpainter.settings")) {
                tabBar?.selectTab(TabId.SETTINGS.ordinal, true)
            }.width(100).build(),
        )
        footer.addChild(
            Button.builder(Component.translatable("gui.done")) { onClose() }.width(100).build(),
        )

        layout.visitWidgets { widget: AbstractWidget -> addRenderableWidget(widget) }
        bar.selectTab(lastTab.ordinal, false)
        refreshUndoButton()
        repositionElements()
    }

    private fun onTabEntered(tab: Tab) {
        val index = tabs.indexOf(tab)
        if (index >= 0) lastTab = TabId.entries[index]
        (tab as? ApTab)?.refresh()
    }

    /** For the Settings tab's "Manage..." buttons: jump to Presets already on the right kind. */
    fun switchToPresets(kind: PresetKind) {
        (tabs.firstOrNull { it is PresetsTab } as? PresetsTab)?.selectKind(kind)
        tabBar?.selectTab(TabId.PRESETS.ordinal, true)
    }

    override fun repositionElements() {
        val bar = tabBar ?: return
        bar.updateWidth(width)
        val top = bar.rectangle.bottom()
        // The status line sits between the content and the footer, so the content stops short of it.
        val bottom = height - layout.footerHeight - STATUS_HEIGHT
        tabManager.setTabArea(ScreenRectangle(0, top, width, (bottom - top).coerceAtLeast(0)))
        layout.setHeaderHeight(top)
        layout.arrangeElements()
    }

    override fun tick() {
        super.tick()
        (tabManager.currentTab as? ApTab)?.tick()
    }

    /** Closing the screen doesn't go through a tab switch, so the visible tab never otherwise gets
     *  a chance to batch its last edit into one rebuild - see [ApTab.onHidden]. */
    override fun onClose() {
        (tabManager.currentTab as? ApTab)?.onHidden()
        super.onClose()
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)

        val message = statusText ?: return
        if (System.currentTimeMillis() > statusUntil) {
            statusText = null
            return
        }
        graphics.text(font, message, MARGIN, height - layout.footerHeight - STATUS_HEIGHT + 2, STATUS_COLOR)
    }
}
