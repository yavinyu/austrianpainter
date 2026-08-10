package com.maxisch.paint

import com.maxisch.dungeon.RoomScope
import com.maxisch.dungeon.RoomTracker
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.world.level.block.Block

/**
 * The one entry point the rest of the mod paints through.
 *
 * Everything here is a one-liner onto [PaintSession] (what the rules apply to and when they are
 * written) or [PaintRules] (the rules themselves). Screens, keybinds and commands only ever talk to
 * this facade, so those two can be reshaped without a hundred call sites moving with them.
 */
object PaintStorage {

    /** Identifies the world/server the bindings belong to; null while not in a world. */
    val worldKey: String? get() = PaintSession.worldKey

    /** The dungeon room the player is standing in, if any. */
    val scope: RoomScope? get() = PaintSession.scope

    /** Corners of that room, for "select this room"; see [RoomTracker.currentRoomCorners]. */
    fun currentRoomCorners(): Pair<BlockPos, BlockPos>? = RoomTracker.currentRoomCorners()

    // ---------------------------------------------------------------- lifecycle

    fun onJoinWorld() = PaintSession.onJoinWorld()

    fun onLeaveWorld() = PaintSession.onLeaveWorld()

    fun onScopeChanged(next: RoomScope?) = PaintSession.onScopeChanged(next)

    fun onDeviceScopeChanged() = PaintSession.onDeviceScopeChanged()

    fun tick() = PaintSession.tick()

    fun flush() = PaintSession.flush()

    fun refreshIndex() = PaintIndexBuilder.refresh()

    // ---------------------------------------------------------------- preset switching

    fun activateBlockPreset(name: String) = PaintSession.activateBlockPreset(name)

    fun activateRoomPreset(name: String) = PaintSession.activateRoomPreset(name)

    fun activateBossPreset(name: String) = PaintSession.activateBossPreset(name)

    fun activateTypePreset(name: String) = PaintSession.activateTypePreset(name)

    fun activatePalette(name: String) = PaintSession.activatePalette(name)

    fun activateRuleset(name: String) = PaintSession.activateRuleset(name)

    // ---------------------------------------------------------------- rules

    fun typeRules(): Map<Block, Block> = PaintRules.typeRules()

    fun setTypeRule(source: Block, target: Block) = PaintRules.setTypeRule(source, target)

    fun removeTypeRule(source: Block) = PaintRules.removeTypeRule(source)

    fun positionsByDonor(): Map<Block, Int> = PaintRules.positionsByDonor()

    fun paintPositions(positions: Collection<BlockPos>, target: Block): Int =
        PaintRules.paintPositions(positions, target)

    fun paintPositions(positions: Collection<BlockPos>, donorFor: (BlockPos) -> Block): Int =
        PaintRules.paintPositions(positions, donorFor)

    /** Several donor groups as one undoable change; see [PaintRules.paintGroups]. */
    fun paintGroups(groups: List<PaintRules.Group>): Int = PaintRules.paintGroups(groups)

    fun unpaintPositions(positions: Collection<BlockPos>): Int = PaintRules.unpaintPositions(positions)

    fun removeDonor(target: Block): Int = PaintRules.removeDonor(target)

    fun clearCurrentScope() = PaintRules.clearCurrentScope()

    // ---------------------------------------------------------------- undo

    val canUndo: Boolean get() = PaintHistory.canUndo

    val undoDepth: Int get() = PaintHistory.depth

    /** The undo stack newest first; the history tab lists it. */
    fun undoStack(): List<PaintHistory.Step> = PaintHistory.stack()

    /** True when the last change was too large to record; the caller should say so. */
    val undoOverflowed: Boolean get() = PaintHistory.overflowed

    /** Reverses the newest change. Always returns a message worth showing the player. */
    fun undo(): Component = PaintHistory.undo()

    val canRedo: Boolean get() = PaintHistory.canRedo

    val redoDepth: Int get() = PaintHistory.redoDepth

    /** Replays the newest undone change. */
    fun redo(): Component = PaintHistory.redo()
}
