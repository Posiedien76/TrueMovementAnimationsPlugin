package com.truetileanimationmovement;

import java.lang.reflect.Array;

public class AnimationRequestMoveset
{
// Juicy animations
// 868->Jog
// 870->Jumping jack (maybe crappy side step?)
// 846->knocked back (probably not useful?)
// 845->crawl, just kind of fun
// 839->turn style climb, maybe a jump?
// 822-> SIDE STEP RIGHT
// 821-> SIDE STEP LEFT
// 820-> WALK BACKWARDS
// 807-> JUMP FORWARD
// 759->SWING LEFT (swing off wall obstacle) (good for movement back diagonal 1 maybe?)
// 758->SWING RIGHT (swing off wall obstacle) (good for movement back diagonal 1 maybe?)
// 741-> Small hop
// 726->COVER HEAD, looks realllly nice for a charge forward 2 tiles
// 439->Spin move
// 424->block, maybe a move back one?
// 409->another cool spin move
// 246->charge forward punch
// 1206-> Walk backwards
// 1207-> Walk left
// 1208-> Walk right
// 1378-> dramatic jump
// 1441-> knock back
// 1,707->cool run forward (arms up)
// 1,706->side step left (arms up)
// 1,705->side step right (arms up)
// 1,704->walk forward (arms up)
// 1,745->stomping both feet
// 1,764->sick jump land
// 1,770-> lean WAY back
// 1,775->T pose flip, funny
// 1,834-> block, pretty good step back
// 1,852-> huge jump and land
//2,107 -> spin emote
//2,106 -> jig
// 2,109-> jump for joy
// 2,242->fell from the sky (funny)
// 2,387->Fist pump
// 2,390->Big jump back
// 2,588-> Very nice jump down animation (maybe end or start combat?)
// 2,750-> Super far jump forward
// 3,013-> back away slowly
// 3,039-> Drunk walk
// 3,067-> Big jump forward
// 3,178-> standard run
// 3,177->standard walk
// 4,003->land on your butt
// 4,772->tight rope walk
// REMEMBER YOU CAN ALSO PLAY THESE BACKWARDS

    // 2D grid array
    // (NOTE: THESE ARE ALWAYS BASED ON PLAYER'S ORIENTATION!)
    //    +-------+-------+-------+-------+-------+
    //    | 2NW   | 2NNW  | 2N    | 2NNE  | 2NE   |
    //    +-------+-------+-------+-------+-------+
    //    | 2WWN  | NW    | N     | NE    | 2EEN  |
    //    +-------+-------+-------+-------+-------+
    //    | 2W    | W     | X     | E     | 2E    |
    //    +-------+-------+-------+-------+-------+
    //    | 2WWS  | SW    | S     | SE    | 2EES  |
    //    +-------+-------+-------+-------+-------+
    //    | 2SW   | 2SSW  | 2S    | 2SSE  | 2SE   |
    //    +-------+-------+-------+-------+-------+
    //
    //    +-------+-------+-------+-------+-------+
    //    | 0,4   | 1,4   | 2,4   | 3,4   | 4,4   |
    //    +-------+-------+-------+-------+-------+
    //    | 0,3   | 1,3   | 2,3   | 3,3   | 4,3   |
    //    +-------+-------+-------+-------+-------+
    //    | 0,2   | 1,2   | 2,2 X | 3,2   | 4,2   |
    //    +-------+-------+-------+-------+-------+
    //    | 0,1   | 1,1   | 2,1   | 3,1   | 4,1   |
    //    +-------+-------+-------+-------+-------+
    //    | 0,0   | 1,0   | 2,0   | 3,0   | 4,0   |
    //    +-------+-------+-------+-------+-------+
    //

    public AnimationRequestDetails[][] MovesetArray = new AnimationRequestDetails[5][5]; // 5 by 5 grid around player

    public AnimationRequestDetails EAST_2;
    public AnimationRequestDetails EAST_1;
    public AnimationRequestDetails CENTER;
    public AnimationRequestDetails WEST_1;
    public AnimationRequestDetails WEST_2;

    public AnimationRequestDetails NORTHEAST_1;
    public AnimationRequestDetails NORTHWEST_1;
    public AnimationRequestDetails NORTHEAST_2;
    public AnimationRequestDetails NORTHWEST_2;

    public AnimationRequestDetails SOUTHEAST_1;
    public AnimationRequestDetails SOUTHWEST_1;
    public AnimationRequestDetails SOUTHEAST_2;
    public AnimationRequestDetails SOUTHWEST_2;

    public AnimationRequestDetails FORWARD_1;
    public AnimationRequestDetails FORWARD_2;
    public AnimationRequestDetails BACK_1;
    public AnimationRequestDetails BACK_2;

    public AnimationRequestDetails NORTHEASTEAST;
    public AnimationRequestDetails NORTHWESTWEST;
    public AnimationRequestDetails NORTHNORTHEAST;
    public AnimationRequestDetails NORTHNORTHWEST;

    public AnimationRequestDetails SOUTHEASTEAST;
    public AnimationRequestDetails SOUTHWESTWEST;
    public AnimationRequestDetails SOUTHSOUTHEAST;
    public AnimationRequestDetails SOUTHSOUTHWEST;

    public void Initialize()
    {
        EAST_2 = MovesetArray[4][2];
        EAST_1 = MovesetArray[3][2];
        CENTER = MovesetArray[2][2];
        WEST_1 = MovesetArray[1][2];
        WEST_2 = MovesetArray[0][2];

        NORTHEAST_1 = MovesetArray[3][3];
        NORTHWEST_1 = MovesetArray[1][3];
        NORTHEAST_2 = MovesetArray[0][4];
        NORTHWEST_2 = MovesetArray[4][4];

        SOUTHEAST_1 = MovesetArray[3][1];
        SOUTHWEST_1 = MovesetArray[1][1];
        SOUTHEAST_2 = MovesetArray[0][0];
        SOUTHWEST_2 = MovesetArray[4][0];

        FORWARD_1 = MovesetArray[2][3];
        FORWARD_2 = MovesetArray[2][4];
        BACK_1 = MovesetArray[2][1];
        BACK_2 = MovesetArray[2][0];

        NORTHEASTEAST = MovesetArray[4][3];
        NORTHWESTWEST = MovesetArray[0][3];
        NORTHNORTHEAST = MovesetArray[1][4];
        NORTHNORTHWEST = MovesetArray[3][4];

        SOUTHEASTEAST = MovesetArray[4][1];
        SOUTHWESTWEST = MovesetArray[0][1];
        SOUTHSOUTHEAST = MovesetArray[1][0];
        SOUTHSOUTHWEST = MovesetArray[3][0];

    }
    static public AnimationRequestDetails GetDefaultSpecialMoveAnimationRequest()
    {
        AnimationRequestDetails NewRequest = new AnimationRequestDetails();

        NewRequest.bResetAnimationOnNewTile = true;
        NewRequest.AnimationToPlay = -1;
        NewRequest.PoseAnimationToPlay = -1;
        NewRequest.StartingFrame = 0;
        NewRequest.EndingFrame = 100000;
        NewRequest.AnimationSpeed = 1;
        NewRequest.MovementSpeedMultiplier = 0;
        NewRequest.bUseLinearTween = false;
        NewRequest.bShouldTeleportToLocation = false;
        NewRequest.bAtDestinationLocation = false;
        NewRequest.OrientationSpeed = 60;
        return NewRequest;
    }

    static public AnimationRequestDetails GetDefaultIdleMoveAnimationRequest( TrueTileMovementConfig config)
    {
        AnimationRequestDetails NewRequest = new AnimationRequestDetails();

        NewRequest.bResetAnimationOnNewTile = false;
        NewRequest.AnimationToPlay = -1;
        NewRequest.PoseAnimationToPlay = -1;
        NewRequest.StartingFrame = 0;
        NewRequest.EndingFrame = 5000;
        NewRequest.AnimationSpeed = 1;
        NewRequest.MovementSpeedMultiplier = 1.0;
        NewRequest.bUseLinearTween = true;
        NewRequest.bShouldTeleportToLocation = false;
        NewRequest.bAtDestinationLocation = false;
        NewRequest.OrientationSpeed = config.OrientationRotationSpeed();
        return NewRequest;
    }

    public void ConstructFromSpecialAnimationSet(IdleAnimationSet AnimSet, String SpecialAnimationKey, TrueTileMovementConfig config) {
        if (SpecialAnimationKey.equals("SpecialMoves")) {
            for (int i = 0; i < 5; ++i) {
                for (int j = 0; j < 5; ++j) {
                    MovesetArray[i][j] = GetDefaultSpecialMoveAnimationRequest();
                }
            }
            Initialize();

            // SOUTHEAST_2;
            SOUTHEAST_2.AnimationToPlay = 1770; // lean WAY back
            SOUTHEAST_2.bUseLinearTween = false;
            SOUTHEAST_2.MovementSpeedMultiplier = 1.5;
            SOUTHEAST_2.AnimationSpeed = 1;
            SOUTHEAST_2.StartingFrame = 0;


            // SOUTHSOUTHWEST;
            SOUTHWESTWEST.AnimationToPlay = 1764; // sick jump land
            SOUTHWESTWEST.bUseLinearTween = false;
            SOUTHWESTWEST.MovementSpeedMultiplier = 1.5;
            SOUTHWESTWEST.AnimationSpeed = 1;
            SOUTHWESTWEST.StartingFrame = 0;


            // WEST_2;
            WEST_2.AnimationToPlay = 2107; // Side step 2, spin emote
            WEST_2.MovementSpeedMultiplier = 2;
            WEST_2.AnimationSpeed = 1;
            WEST_2.StartingFrame = 4;

            // NORTHWESTWEST;
            NORTHWESTWEST.AnimationToPlay = 409; // another cool spin move
            NORTHWESTWEST.MovementSpeedMultiplier = 2;
            NORTHWESTWEST.AnimationSpeed = 1;
            NORTHWESTWEST.StartingFrame = 0;


            // NORTHEAST_2;
            NORTHEAST_2.AnimationToPlay = 1852; // huge jump land
            NORTHEAST_2.bUseLinearTween = false;
            NORTHEAST_2.MovementSpeedMultiplier = 1.5;
            NORTHEAST_2.AnimationSpeed = 1;
            NORTHEAST_2.StartingFrame = 0;


            // SOUTHSOUTHEAST;
            SOUTHSOUTHEAST.AnimationToPlay = 1764; // sick jump land
            SOUTHSOUTHEAST.bUseLinearTween = false;
            SOUTHSOUTHEAST.MovementSpeedMultiplier = 1.5;
            SOUTHSOUTHEAST.AnimationSpeed = 1;
            SOUTHSOUTHEAST.StartingFrame = 0;


            // SOUTHWEST_1;
            SOUTHWEST_1.AnimationToPlay = 1702; // side step small
            SOUTHWEST_1.bUseLinearTween = true;
            SOUTHWEST_1.MovementSpeedMultiplier = 1.0;
            SOUTHWEST_1.AnimationSpeed = 1;
            SOUTHWEST_1.StartingFrame = 0;

            // WEST_1;
            WEST_1.AnimationToPlay = 821; // SIDE STEP LEFT
            WEST_1.MovementSpeedMultiplier = 1.5;
            WEST_1.AnimationSpeed = 2;
            WEST_1.StartingFrame = 0;

            // NORTHWEST_1;
            NORTHWEST_1.AnimationToPlay = 807; // Small hop
            NORTHWEST_1.MovementSpeedMultiplier = 3.0;
            NORTHWEST_1.StartingFrame = 7;
            NORTHWEST_1.AnimationSpeed = 1;


            // NORTHNORTHEAST;
            NORTHNORTHEAST.AnimationToPlay = 2750; // Super far jump forward
            NORTHNORTHEAST.bUseLinearTween = false;
            NORTHNORTHEAST.MovementSpeedMultiplier = 1.6;
            NORTHNORTHEAST.AnimationSpeed = 2;
            NORTHNORTHEAST.StartingFrame = 2;

            // BACK_2;
            BACK_2.AnimationToPlay = 2390; // big knockback
            BACK_2.bUseLinearTween = false;
            BACK_2.MovementSpeedMultiplier = 2;
            BACK_2.AnimationSpeed = 1;
            BACK_2.StartingFrame = 0;

            // BACK_1;
            BACK_1.AnimationToPlay = 1441; // knockback
            BACK_1.bUseLinearTween = true;
            BACK_1.MovementSpeedMultiplier = 1;
            BACK_1.AnimationSpeed = 1;
            BACK_1.StartingFrame = 0;

            CENTER.AnimationToPlay = AnimSet.IdleRotateRight; // Center

            FORWARD_1.AnimationToPlay = 7515; // Jab forward
            FORWARD_1.MovementSpeedMultiplier = 2.0;
            FORWARD_1.StartingFrame = 0;
            FORWARD_1.AnimationSpeed = 1;

            // FORWARD_2;
            FORWARD_2.AnimationToPlay = 3067; // Big jump forward
            FORWARD_2.MovementSpeedMultiplier = 2;
            FORWARD_2.AnimationSpeed = 2;
            FORWARD_2.StartingFrame = 2;

            // SOUTHWESTWEST;
            SOUTHSOUTHWEST.AnimationToPlay = 870; // Jumping Jack
            SOUTHSOUTHWEST.bUseLinearTween = false;
            SOUTHSOUTHWEST.MovementSpeedMultiplier = 2;
            SOUTHSOUTHWEST.AnimationSpeed = 1;
            SOUTHSOUTHWEST.StartingFrame = 0;


            // SOUTHEAST_1;
            SOUTHEAST_1.AnimationToPlay = 1702; // side step small
            SOUTHEAST_1.bUseLinearTween = true;
            SOUTHEAST_1.MovementSpeedMultiplier = 1.0;
            SOUTHEAST_1.AnimationSpeed = 1;
            SOUTHEAST_1.StartingFrame = 0;


            // EAST_2;
            EAST_1.AnimationToPlay = 822; // SIDE STEP RIGHT
            EAST_1.MovementSpeedMultiplier = 1.5;
            EAST_1.AnimationSpeed = 2;

            // NORTHEAST_1;
            NORTHEAST_1.AnimationToPlay = 807; // North-east
            NORTHEAST_1.MovementSpeedMultiplier = 3.0;
            NORTHEAST_1.StartingFrame = 7;
            NORTHEAST_1.AnimationSpeed = 1;

            // NORTHNORTHWEST;
            NORTHNORTHWEST.AnimationToPlay = 2750; // Super far jump forward
            NORTHNORTHWEST.bUseLinearTween = false;
            NORTHNORTHWEST.MovementSpeedMultiplier = 1.6;
            NORTHNORTHWEST.AnimationSpeed = 2;
            NORTHNORTHWEST.StartingFrame = 2;

            // SOUTHWEST_2;
            SOUTHWEST_2.AnimationToPlay = 1770; // lean WAY back
            SOUTHWEST_2.bUseLinearTween = false;
            SOUTHWEST_2.MovementSpeedMultiplier = 1.5;
            SOUTHWEST_2.AnimationSpeed = 1;
            SOUTHWEST_2.StartingFrame = 0;

            // SOUTHEASTEAST;
            SOUTHEASTEAST.AnimationToPlay = 870; // Jumping Jack
            SOUTHEASTEAST.bUseLinearTween = false;
            SOUTHEASTEAST.MovementSpeedMultiplier = 2;
            SOUTHEASTEAST.AnimationSpeed = 1;
            SOUTHEASTEAST.StartingFrame = 0;

            // EAST_2
            EAST_2.AnimationToPlay = 2107; // SIDE_STEP 2 - spin emote
            EAST_2.MovementSpeedMultiplier = 2;
            EAST_2.AnimationSpeed = 1;
            EAST_2.StartingFrame = 4;

            // NORTHEASTEAST;
            NORTHEASTEAST.AnimationToPlay = 409; // another cool spin move
            NORTHEASTEAST.MovementSpeedMultiplier = 2;
            NORTHEASTEAST.AnimationSpeed = 1;
            NORTHEASTEAST.StartingFrame = 0;


            // NORTHWEST_2;
            NORTHWEST_2.AnimationToPlay = 1852; // huge jump land
            NORTHWEST_2.bUseLinearTween = false;
            NORTHWEST_2.MovementSpeedMultiplier = 1.5;
            NORTHWEST_2.AnimationSpeed = 1;
            NORTHWEST_2.StartingFrame = 0;
        }
        else if (SpecialAnimationKey.equals("WooxWalk"))
        {
            for (int i = 0; i < 5; ++i)
            {
                for (int j = 0; j < 5; ++j)
                {
                    MovesetArray[i][j] = GetDefaultSpecialMoveAnimationRequest();

                    // 2 Tiles
                    if (i == 0 || j == 0 || i == 4 || j == 4)
                    {
                        MovesetArray[i][j].bResetAnimationOnNewTile = true;
                        MovesetArray[i][j].AnimationToPlay = 1604;
                        MovesetArray[i][j].bUseLinearTween = false;
                        MovesetArray[i][j].MovementSpeedMultiplier = 1.5;
                        MovesetArray[i][j].AnimationSpeed = 1;
                        MovesetArray[i][j].StartingFrame = 2;
                    }
                    // 1 Tile
                    else if (i == 1 || j == 1 || i == 3 || j == 3)
                    {
                        MovesetArray[i][j].AnimationToPlay = 741; // Little jump
                        MovesetArray[i][j].MovementSpeedMultiplier = 2.0;
                        MovesetArray[i][j].bUseLinearTween = false;
                        MovesetArray[i][j].StartingFrame = 2;
                        MovesetArray[i][j].AnimationSpeed = 1;
                    }
                }
            }
            Initialize();
        }
        else if (SpecialAnimationKey.equals("TickPerfectMovement"))
        {
            for (int i = 0; i < 5; ++i)
            {
                for (int j = 0; j < 5; ++j)
                {
                    MovesetArray[i][j] = GetDefaultSpecialMoveAnimationRequest();

                    // 2 Tiles
                    if (i == 0 || j == 0 || i == 4 || j == 4)
                    {
                        MovesetArray[i][j].bResetAnimationOnNewTile = true;
                        MovesetArray[i][j].AnimationToPlay = 1604;
                        MovesetArray[i][j].bUseLinearTween = false;
                        MovesetArray[i][j].MovementSpeedMultiplier = 1.5;
                        MovesetArray[i][j].AnimationSpeed = 1;
                        MovesetArray[i][j].StartingFrame = 2;
                    }
                    // 1 Tile
                    else if (i == 1 || j == 1 || i == 3 || j == 3)
                    {
                        MovesetArray[i][j].AnimationToPlay = 741; // Little jump
                        MovesetArray[i][j].MovementSpeedMultiplier = 2.0;
                        MovesetArray[i][j].bUseLinearTween = false;
                        MovesetArray[i][j].StartingFrame = 2;
                        MovesetArray[i][j].AnimationSpeed = 1;
                    }
                }
            }
            Initialize();

        }
    }

    // Idle animation set mapping
    public void ConstructFromIdleAnimationSet(IdleAnimationSet AnimSet, TrueTileMovementConfig config)
    {
        for (int i = 0; i < 5; ++i)
        {
            for (int j = 0; j < 5; ++j)
            {
                MovesetArray[i][j] = GetDefaultIdleMoveAnimationRequest(config);
            }
        }
        Initialize();

        SOUTHEAST_2.PoseAnimationToPlay = AnimSet.WalkRotate180; SOUTHEAST_2.AnimationSpeed = 2; // Backwards 2, side step 2
        SOUTHWESTWEST.PoseAnimationToPlay = AnimSet.WalkRotateRight; // South, side step 2
        WEST_2.PoseAnimationToPlay = AnimSet.WalkRotateLeft; // Side step 2
        NORTHWESTWEST.PoseAnimationToPlay = AnimSet.WalkRotateLeft; // North, Side step 2
        NORTHEAST_2.PoseAnimationToPlay = AnimSet.RunAnimation; // North-west 2

        SOUTHSOUTHEAST.PoseAnimationToPlay = AnimSet.WalkRotate180; SOUTHSOUTHEAST.AnimationSpeed = 2; // Backwards 2, side step 1
        SOUTHWEST_1.PoseAnimationToPlay = AnimSet.WalkRotate180; // South-west
        WEST_1.PoseAnimationToPlay = AnimSet.WalkRotateLeft; // Side step 1
        NORTHWEST_1.PoseAnimationToPlay = AnimSet.WalkAnimation; // North-west
        NORTHNORTHEAST.PoseAnimationToPlay = AnimSet.RunAnimation; // West, forward 2

        BACK_2.PoseAnimationToPlay = AnimSet.WalkRotate180; BACK_2.AnimationSpeed = 2; // Backwards 2
        BACK_1.PoseAnimationToPlay = AnimSet.WalkRotate180; // Backwards
        CENTER.PoseAnimationToPlay = AnimSet.IdleRotateRight; // Center
        FORWARD_1.PoseAnimationToPlay = AnimSet.WalkAnimation; // Forward
        FORWARD_2.PoseAnimationToPlay = AnimSet.RunAnimation; // 2 Forward

        SOUTHSOUTHWEST.PoseAnimationToPlay = AnimSet.WalkRotate180; SOUTHSOUTHWEST.AnimationSpeed = 2; // Backwards 2, side step 1
        SOUTHEAST_1.PoseAnimationToPlay = AnimSet.WalkRotate180; // South-east
        EAST_1.PoseAnimationToPlay = AnimSet.WalkRotateRight; // Side step 1
        NORTHEAST_1.PoseAnimationToPlay = AnimSet.WalkAnimation; // North-east
        NORTHNORTHWEST.PoseAnimationToPlay = AnimSet.RunAnimation; // East, forward 2

        SOUTHWEST_2.PoseAnimationToPlay = AnimSet.WalkRotate180; SOUTHWEST_2.AnimationSpeed = 2; // Backwards 2, side step 2
        SOUTHEASTEAST.PoseAnimationToPlay = AnimSet.WalkRotateRight; // South, side step 2
        EAST_2.PoseAnimationToPlay = AnimSet.WalkRotateRight; // Side step 2
        NORTHEASTEAST.PoseAnimationToPlay = AnimSet.WalkRotateRight; // North, Side step 2
        NORTHWEST_2.PoseAnimationToPlay = AnimSet.RunAnimation; // North-east 2
    }
}
