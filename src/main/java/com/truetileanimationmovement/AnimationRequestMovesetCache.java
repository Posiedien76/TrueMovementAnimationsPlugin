package com.truetileanimationmovement;

import java.util.HashMap;
import java.util.Map;

public class AnimationRequestMovesetCache
{
    static public Map<String, AnimationRequestMoveset> NameToMovesetRequest = new HashMap<>();
    static public AnimationRequestMoveset GetAnimationRequestMovesetFromUniqueKey(IdleAnimationSet AnimSet, String UniqueLabel, TrueTileMovementConfig config)
    {
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
        String UniqueLabel = AnimSet.GetUniqueLabel();
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
