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

    private var scrollOffset = 0
    private var maxScroll = 0
    private var lastArea: ScreenRectangle? = null

    /**
     * Applies the tab's own scroll before handing the area to [layout].
     *
     * Settings needs more height than the screen has at GUI scale 3, and it is not the only tab that
     * can: any of them overflow on a short enough window. Rather than every tab growing its own
     * scroll handling, the area they lay out into is simply shifted up and made taller, and the
     * frame's chrome is drawn over the top and bottom so the overflow slides under it.
     */
    final override fun doLayout(area: ScreenRectangle) {
        lastArea = area
        arrange(area)
    }

    private fun arrange(area: ScreenRectangle) {
        layout(shifted(area))

        // Widgets are already shifted, so the unscrolled bottom is their lowest edge plus whatever
        // was scrolled away above them.
        val bottom = widgets.filter { it.visible }.maxOfOrNull { it.y + it.height } ?: area.bottom()
        maxScroll = (bottom + scrollOffset - area.bottom()).coerceAtLeast(0)

        // A tab can shrink - a list empties, a section hides - and leave the offset past the end.
        if (scrollOffset > maxScroll) {
            scrollOffset = maxScroll
            layout(shifted(area))
        }
    }

    private fun shifted(area: ScreenRectangle): ScreenRectangle = ScreenRectangle(
        area.left(),
        area.top() - scrollOffset,
        area.width(),
        area.height() + scrollOffset,
    )

    /**
     * Scrolls by [delta] pixels, returning whether anything moved.
     *
     * False means the tab fits, which is the screen's cue to leave the event alone.
     */
    fun scrollBy(delta: Int): Boolean {
        val next = (scrollOffset + delta).coerceIn(0, maxScroll)
        if (next == scrollOffset) return false
        scrollOffset = next
        lastArea?.let { arrange(it) }
        return true
    }

    /** True when this tab has more content than its area, so the screen knows a wheel means scroll. */
    fun canScroll(): Boolean = maxScroll > 0

    /** Positions the already-built widgets inside the area the tab was given. */
    abstract fun layout(area: ScreenRectangle)

    /** Called once a tick while this tab is the visible one. */
    open fun tick() = Unit

    /** Called when the tab becomes visible, and whenever it changes something itself. */
    open fun refresh() = Unit

    /**
     * Called when this tab stops being the visible one - by switching to another tab, or by the
     * screen closing. Lets a tab batch several edits into one expensive rebuild instead of paying
     * it once per click.
     */
    open fun onHidden() = Unit
}
