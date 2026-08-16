package com.maxisch.client.gui

import com.maxisch.client.KeyHints
import com.maxisch.client.gui.widget.ActButtonWidget
import com.maxisch.client.gui.widget.ToolRailWidget
import com.maxisch.client.gui.tab.ApTab
import com.maxisch.client.gui.tab.AreaTab
import com.maxisch.client.gui.tab.BrushTab
import com.maxisch.client.gui.tab.DungeonTab
import com.maxisch.client.gui.tab.HistoryTab
import com.maxisch.client.gui.tab.PresetsTab
import com.maxisch.client.gui.tab.SettingsTab
import com.maxisch.paint.PaintSession
import com.maxisch.paint.PaintStorage
import com.maxisch.paint.PresetKind
import com.maxisch.paint.session.PaintArea
import com.maxisch.paint.session.PaintBrush
import com.maxisch.paint.session.PaintSelection
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.tabs.Tab
import net.minecraft.client.gui.components.tabs.TabManager
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
 * open. Settings used to also be reachable through a separate YACL screen behind ModMenu; that
 * screen is gone, and the Settings tab here is now the only way to reach every option. Block
 * picking stays a modal ([BlockPickerScreen]) - the picker needs the full screen height for its grid.
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
        /** How long a status line stays up before it fades out on its own. */
        private const val STATUS_MS = 6_000L

        private const val BUTTON_WIDTH = 64
        private const val BUTTON_HEIGHT = 20
        private const val BUTTON_GAP = 6

        /** Pixels per wheel notch when a tab is taller than its area. */
        private const val SCROLL_STEP = 16

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

    // The enter/exit consumers are called with null when there is no tab on that side of the
    // switch - the very first selectTab has nothing to exit - so both parameters must be nullable.
    private val tabManager = TabManager(
        { widget: AbstractWidget -> addRenderableWidget(widget) },
        { widget: AbstractWidget -> removeWidget(widget) },
        { tab: Tab? -> tab?.let { onTabEntered(it) } },
        { tab: Tab? -> (tab as? ApTab)?.onHidden() },
    )

    private var frame: PainterFrame? = null

    /** The bar and footer, drawn over the tabs so scrolled content slides under them. */
    private var chrome: PainterFrame? = null
    private var rail: ToolRailWidget? = null
    private var tabs: List<ApTab> = emptyList()
    private var footerButtons: List<ActButtonWidget> = emptyList()
    private var undoButton: ActButtonWidget? = null
    private var redoButton: ActButtonWidget? = null

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

        // Registration order is render order, and the frame is the ground everything else sits on.
        frame = addRenderableWidget(PainterFrame(part = PainterFrame.Part.GROUND))
        rail = addRenderableWidget(
            ToolRailWidget(
                titles = tabs.map { it.tabTitle },
                counts = railCounts(),
                selectedIndex = { tabs.indexOf(tabManager.currentTab) },
                onSelect = { index -> selectTab(index, true) },
            ),
        )

        val undo = ActButtonWidget(0, 0, BUTTON_WIDTH, BUTTON_HEIGHT, undoLabel(), ActButtonWidget.Variant.DEFAULT) {
            status(PaintStorage.undo())
            refreshTabs()
        }
        undo.setTooltip(KeyHints.undoTooltip())
        val redo = ActButtonWidget(0, 0, BUTTON_WIDTH, BUTTON_HEIGHT, redoLabel(), ActButtonWidget.Variant.DEFAULT) {
            status(PaintStorage.redo())
            refreshTabs()
        }
        redo.setTooltip(KeyHints.redoTooltip())
        val done = ActButtonWidget(
            0,
            0,
            BUTTON_WIDTH,
            BUTTON_HEIGHT,
            Component.translatable("gui.done"),
            ActButtonWidget.Variant.PRIMARY,
        ) { onClose() }

        undoButton = undo
        redoButton = redo
        // Right to left, so the primary action is the one against the screen edge.
        footerButtons = listOf(done, redo, undo)

        chrome = PainterFrame(part = PainterFrame.Part.CHROME, status = { visibleStatus() })
        raiseChrome()

        selectTab(lastTab.ordinal, false)
        refreshUndoButton()
        repositionElements()
    }

    /**
     * What each rail row reports under its name, in [TabId] order.
     *
     * Only what is already cheap to read every frame: a tool whose state has no one-number summary
     * shows nothing rather than something invented, which is why two of the six are null.
     */
    private fun railCounts(): List<() -> String?> = listOf(
        { PaintBrush.donor?.let { "r${PaintBrush.radius}" } },
        { if (PaintArea.complete) abbreviated(PaintArea.volume()) else null },
        { PaintSession.scope?.key?.takeIf { it.isNotBlank() } },
        { null },
        { PaintStorage.undoDepth.takeIf { it > 0 }?.toString() },
        { null },
    )

    /** 1_248 as "1.2k": the rail column is 116px and a raw block count does not fit. */
    private fun abbreviated(count: Long): String = when {
        count >= 1_000_000 -> "%.1fM".format(count / 1_000_000.0)
        count >= 1_000 -> "%.1fk".format(count / 1_000.0)
        else -> count.toString()
    }

    /** The settings tab is reachable from the rail, so the footer no longer carries a button for it. */
    private fun selectTab(index: Int, playSound: Boolean) {
        tabs.getOrNull(index)?.let { tabManager.setCurrentTab(it, playSound) }
    }

    /** The status line, or null once it has timed out. Clearing it here keeps the render pass pure. */
    private fun visibleStatus(): Component? {
        val message = statusText ?: return null
        if (System.currentTimeMillis() > statusUntil) {
            statusText = null
            return null
        }
        return message
    }

    /**
     * Moves the frame's chrome and the footer buttons to the end of the render order.
     *
     * A tab's widgets are registered when it is entered, which puts them after everything already
     * there. Without this the bar and the footer would sit under them, and a scrolled tab would run
     * its content straight over the top of the frame instead of under it.
     */
    private fun raiseChrome() {
        val bar = chrome ?: return
        removeWidget(bar)
        addRenderableWidget(bar)
        footerButtons.forEach {
            removeWidget(it)
            addRenderableWidget(it)
        }
    }

    private fun onTabEntered(tab: Tab) {
        raiseChrome()
        val index = tabs.indexOf(tab)
        if (index >= 0) lastTab = TabId.entries[index]
        (tab as? ApTab)?.refresh()
    }

    /** For the Settings tab's "Manage..." buttons: jump to Presets already on the right kind. */
    fun switchToPresets(kind: PresetKind) {
        (tabs.firstOrNull { it is PresetsTab } as? PresetsTab)?.selectKind(kind)
        selectTab(TabId.PRESETS.ordinal, true)
    }

    override fun repositionElements() {
        val frameWidget = frame ?: return
        // Before anything is placed: every tab's doLayout reads the frame's heights off this.
        PainterFrame.measure(height)
        listOfNotNull(frameWidget, chrome).forEach {
            it.setPosition(0, 0)
            it.width = width
            it.height = height
        }

        val top = PainterFrame.ARMED_HEIGHT
        val footerTop = frameWidget.footerTop(height)
        val contentHeight = (footerTop - top).coerceAtLeast(0)

        rail?.let {
            it.setPosition(0, top)
            it.height = contentHeight
        }

        // The status line lives on the footer strip now, so the content runs all the way down to it.
        tabManager.setTabArea(
            ScreenRectangle(
                ToolRailWidget.WIDTH,
                top,
                (width - ToolRailWidget.WIDTH).coerceAtLeast(0),
                contentHeight,
            ),
        )

        var buttonX = width - PainterFrame.PAD_X
        footerButtons.forEach { button ->
            buttonX -= button.width
            button.setPosition(buttonX, footerTop + (PainterFrame.FOOTER_HEIGHT - button.height) / 2)
            buttonX -= BUTTON_GAP
        }
    }

    override fun tick() {
        super.tick()
        (tabManager.currentTab as? ApTab)?.tick()
    }

    /**
     * Scrolls the visible tab when nothing under the cursor wanted the wheel.
     *
     * Lists claim it first - a wheel over a row list scrolls that list, which is what the hand
     * expects - so this only ever fires over the gaps between them.
     */
    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        if (super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)) return true
        val tab = tabManager.currentTab as? ApTab ?: return false
        return tab.scrollBy((-scrollY * SCROLL_STEP).toInt())
    }

    /** Closing the screen doesn't go through a tab switch, so the visible tab never otherwise gets
     *  a chance to batch its last edit into one rebuild - see [ApTab.onHidden]. */
    override fun onClose() {
        (tabManager.currentTab as? ApTab)?.onHidden()
        super.onClose()
    }

}
