package com.truetileanimationmovement;

import java.util.HashMap;
import java.util.Map;

public class AnimationRequestMovesetCache
{
    static public Map<String, AnimationRequestMoveset> NameToMovesetRequest = new HashMap<>();
    static public AnimationRequestMoveset GetAnimationRequestMovesetFromUniqueKey(IdleAnimationSet AnimSet, String UniqueLabel, TrueTileMovementConfig config)
    {
        // TODO: BUG->Config not effecting Label, so changes to the config does not update this
        if (NameToMovesetRequest.containsKey(UniqueLabel))
        {
            return NameToMovesetRequest.get(UniqueLabel);
        }

        AnimationRequestMoveset NewMoveset = new AnimationRequestMoveset();
        NewMoveset.Initialize();

        NewMoveset.ConstructFromSpecialAnimationSet(AnimSet, UniqueLabel, config);

        NameToMovesetRequest.put(UniqueLabel, NewMoveset);

        return NewMoveset;
    }
    static public AnimationRequestMoveset GetAnimationRequestMovesetFromAnimationSet(IdleAnimationSet AnimSet, TrueTileMovementConfig config)
    {
        // Have the label encode a unique String for all config options that can mess with it
        String UniqueLabel = AnimSet.GetUniqueLabel() + config.OrientationRotationSpeed();
        if (NameToMovesetRequest.containsKey(UniqueLabel))
        {
            return NameToMovesetRequest.get(UniqueLabel);
        }

        AnimationRequestMoveset NewMoveset = new AnimationRequestMoveset();
        NewMoveset.Initialize();

        NewMoveset.ConstructFromIdleAnimationSet(AnimSet, config);

        NameToMovesetRequest.put(UniqueLabel, NewMoveset);

        return NewMoveset;
    }
}
