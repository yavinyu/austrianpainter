package com.maxisch.client.gui.tab

import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.tabs.Tab
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.network.chat.Component

/**
 * A tab of the paint screen.
 *
 * Vanilla's [Tab] has no tick and no refresh hook, so those are added here and driven by
 * [com.maxisch.client.gui.PainterScreen]: the area tab needs a tick for its rescan debounce, and
 * every tab needs to be told when the rules changed underneath it.
 *
 * Widgets are built once, in the implementing class's constructor. Building them in [doLayout]
 * would leak a fresh set into the screen's children on every resize and every tab switch.
 */
abstract class ApTab(private val titleKey: String) : Tab {

    private val widgets = mutableListOf<AbstractWidget>()

    override fun getTabTitle(): Component = Component.translatable(titleKey)

    override fun getTabExtraNarration(): Component = Component.empty()

    override fun visitChildren(consumer: java.util.function.Consumer<AbstractWidget>) {
        widgets.forEach(consumer)
    }

    /** Registers a widget with this tab. Call from the constructor, never from [doLayout]. */
    protected fun <T : AbstractWidget> add(widget: T): T {
        widgets.add(widget)
        return widget
    }

    /** Positions the already-built widgets inside the area the tab was given. */
    abstract override fun doLayout(area: ScreenRectangle)

    /** Called once a tick while this tab is the visible one. */
    open fun tick() = Unit

    /** Called when the tab becomes visible, and whenever it changes something itself. */
    open fun refresh() = Unit
}
