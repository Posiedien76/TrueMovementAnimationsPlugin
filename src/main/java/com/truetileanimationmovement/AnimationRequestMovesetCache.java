package com.truetileanimationmovement;

import java.util.HashMap;
import java.util.Map;

public class AnimationRequestMovesetCache
{
    private static final Map<String, AnimationRequestMoveset>
            NAME_TO_MOVESET_REQUEST = new HashMap<>();

    private AnimationRequestMovesetCache()
    {
    }

    public static AnimationRequestMoveset getMovesetFromUniqueKey(
            final IdleAnimationSet animSet,
            final String uniqueLabel,
            final TrueTileMovementConfig config)
    {
        // TODO: BUG->Config not effecting Label, so changes to the config does not update this
        return NAME_TO_MOVESET_REQUEST.computeIfAbsent(uniqueLabel, key ->
        {
            final AnimationRequestMoveset newMoveset =
                    new AnimationRequestMoveset();
            newMoveset.Initialize();
            newMoveset.ConstructFromSpecialAnimationSet(
                    animSet, uniqueLabel, config);
            return newMoveset;
        });
    }

    public static AnimationRequestMoveset getMovesetFromAnimationSet(
            final IdleAnimationSet animSet,
            final TrueTileMovementConfig config)
    {
        // Have the label encode a unique String for all config options that can mess with it
        final String uniqueLabel =
                animSet.GetUniqueLabel() + config.OrientationRotationSpeed();
        return NAME_TO_MOVESET_REQUEST.computeIfAbsent(uniqueLabel, key ->
        {
            final AnimationRequestMoveset newMoveset =
                    new AnimationRequestMoveset();
            newMoveset.Initialize();
            newMoveset.ConstructFromIdleAnimationSet(animSet, config);
            return newMoveset;
        });
    }
}
