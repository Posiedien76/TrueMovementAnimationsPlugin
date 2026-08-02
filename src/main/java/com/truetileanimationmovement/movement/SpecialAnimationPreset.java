package com.truetileanimationmovement.movement;

import lombok.ToString;

/**
 * Defines the set of special animation presets that can override the default
 * walk/run animations during movement.
 * <p>
 * Each preset represents a distinct movement behaviour with its own tailored
 * animation moveset. Presets are intended to be looked up and cached by their
 * {@link #getUniqueLabel() unique label}.
 */
@ToString
public enum SpecialAnimationPreset
{
    /**
     * A preset for visually expressive combat-oriented movement animations.
     * <p>
     * Each direction in the moveset grid is assigned a unique flavour animation
     * (e.g. spin moves, side-steps, knockbacks, jumps) tuned to the relative
     * direction and distance of travel.
     */
    SPECIAL_MOVES("SpecialMoves"),

    /**
     * A preset representing the <a
     * href="https://oldschool.runescape.wiki/w/Vorkath/Strategies#The_Woox_Walk">
     * Woox Walk</a> animation sequence.
     * <p>
     * Activates when the player is detected as Woox Walking: alternating
     * between attacking and moving on every game tick to avoid boss
     * mechanics. The moveset uses small hops for 1-tile moves and larger jumps
     * for 2-tile moves to reflect the rapid, snappy nature of this technique.
     */
    WOOX_WALK("WooxWalk"),

    /**
     * A preset for tick-perfect continuous movement, where the player moves
     * every game tick without pausing.
     * <p>
     * Activates when the player has been moving for more consecutive ticks than
     * the configured {@code TickPerfectMovesUntilJumping} threshold, or when
     * {@code AlwaysHoppingMode} is enabled. Uses the same jump-based moveset as
     * {@link #WOOX_WALK}: small hops for 1-tile moves and larger jumps for
     * 2-tile moves, to convey the fast, precise cadence of tick-perfect
     * pathing.
     */
    TICK_PERFECT_MOVEMENT("TickPerfectMovement");

    private final String uniqueLabel;

    SpecialAnimationPreset(final String uniqueLabel)
    {
        this.uniqueLabel = uniqueLabel;
    }

    /**
     * Returns the unique string label used to identify and cache the moveset
     * for this preset in {@code String} based key caches.
     *
     * @return the unique label for this preset
     */
    public String getUniqueLabel()
    {
        return uniqueLabel;
    }
}
