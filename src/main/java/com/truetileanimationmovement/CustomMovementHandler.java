package com.truetileanimationmovement;

import com.truetileanimationmovement.movement.SpecialAnimationPreset;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.AnimationID;
import net.runelite.client.config.ConfigItem;

import javax.inject.Inject;
import java.util.HashSet;
import java.util.Set;

public class CustomMovementHandler
{
    // General
    private final Client client;
    private final TrueTileMovementPlugin plugin;
    private final TrueTileMovementConfig config;
    TrueMovementOverlay overlay;

    // Time management
    private long CurrentTime;
    public long CurrentFrameDelta;
    private long LastTimeNanoseconds = 0;
    private long LastAnimationTickTime = 0;
    private long NanosecondsSinceTileChange = (long) 1e+9;

    // Runelite object management
    public Actor Owner = null;
    public AnimationController AnimController = null; // Used to blend additional animations
    public RuneLiteObject Model = null;

    // Targeting
    public Actor currentTarget = null;
    private int NotInteractingTimer = 0;

    // Rendering owner
    public boolean bRenderOriginalOwnerDueToProximity = false;
    public boolean bShouldRenderOwner = false;
    public boolean bAttemptToRenderOwner = false;
    public boolean bTransitioningToBattleMode = false;

    // Local caches
    private WorldPoint CurrentWorldPoint;
    private LocalPoint CurrentTrueTilePosition;
    private LocalPoint LastTrueTilePosition;
    private LocalPoint LastLerpPosition;
    private LocalPoint NextLerpPosition;
    private LocalPoint NewLocalPointToDraw; // Current frame draw
    private WorldPoint NextLerpPositionWorldPoint;
    private WorldPoint LastLerpPositionWorldPoint;

    // Animation Handling
    private final int NO_ANIMATION = -1;
    private int CurrentPoseAnimation = 0;
    private boolean bResetCurrentAnimation = true;
    Set<Integer> UniqueAnimationExceptionList = new HashSet<Integer>();
    Set<Integer> UniqueAnimationLocationAndOrientationExceptionList = new HashSet<Integer>();
    private long LastTimeUniqueAnimationLocationOrientationWasUsed = 0;


    // Original true animations
    private AnimationRequestDetails CurrentAnimationRequest;
    private final IdleAnimationSet OldAnimationSet = new IdleAnimationSet();
    public int OldAnimationHeight = 0;
    private boolean bIsDefaultHumanAnimationSet = true;

    // Rotation
    private int TargetOrientation = 0;
    private int CurrentOrientation = 0;


    // Player only
    private boolean bLastMovementDestinationPotentiallyDirty = false;
    private boolean bTooFarToSpecialMove = false;
    private boolean bLastTickTooFarToSpecialMove = false;
    private LocalPoint LastMovementDestination;
    public boolean bCurrentlyWooxWalking = false;
    public int FramesSinceIdle = 0;
    public boolean bMovingThisAction = false;
    public boolean bWooxWalkBroken = false;
    public boolean bTargetWasKilled = false;
    public long LastTimeEnemyKilled = 0;
    public long LastTimeRecentlyClicked = 0;
    private int LastNPCCombatLevel = 0;


    // Camera (Player Only)
    public AnimationController cameraModelAnimController = null;
    public RuneLiteObject cameraModel = null;
    private int CurrentCameraObjectOrientation = 0;
    private int CurrentCameraModelIndex = 0;
    private double CurrentArrowPointingAnimationFrame = 0.0f;

    @Inject
    CustomMovementHandler(Client client, TrueTileMovementPlugin plugin, TrueTileMovementConfig config, TrueMovementOverlay overlay, Actor Owner)
    {
        this.client = client;
        this.plugin = plugin;
        this.config = config;
        this.overlay = overlay;
        this.Owner = Owner;

        // Initialize all animations we do want to lerp
        UniqueAnimationExceptionList.add(AnimationID.AGILITY_SHORTCUT_WALL_JUMPDOWN2); // Agility. 2588
        UniqueAnimationExceptionList.add(AnimationID.AGILITY_SHORTCUT_WALL_JUMPDOWN); // Agility. 2586
        UniqueAnimationExceptionList.add(AnimationID.AGILITY_SHORTCUT_WALL_JUMP); // Agility. 2583
        UniqueAnimationExceptionList.add(AnimationID.HUMAN_CASTTELEPORT); // Teleport. 714
        UniqueAnimationExceptionList.add(AnimationID.AHOY_ECTO_TELEPORT); // Teleport. 878
        UniqueAnimationExceptionList.add(AnimationID.HUMAN_TELEPORT_OTHER_IMPACT); // Teleport. 1816
        UniqueAnimationExceptionList.add(AnimationID.ZAROS_VERTICAL_CASTING); // Teleport. 1979
        UniqueAnimationExceptionList.add(AnimationID.TELEPORT_NARDAH_HUMAN); // Teleport. 3872
        UniqueAnimationExceptionList.add(AnimationID.HUMAN_COWBOSS_TELEPORT); // Teleport. 13811
        UniqueAnimationExceptionList.add(AnimationID.POH_SMASH_MAGIC_TABLET); // Teleport. 4069
        UniqueAnimationExceptionList.add(AnimationID.POH_ABSORB_TABLET_TELEPORT); // Teleport. 4071
        UniqueAnimationExceptionList.add(AnimationID.TELEPORT_CABBAGE_HUMAN); // Teleport. 3869
        UniqueAnimationExceptionList.add(AnimationID.ARCEUUS_NECROMANCY_ANIM); // Teleport. 3865
        UniqueAnimationExceptionList.add(AnimationID.NTK_HUMAN_TELE); // Teleport. 2881

        UniqueAnimationLocationAndOrientationExceptionList.add(AnimationID.HUMAN_DOUBLEPIPESQUEEZE); // crawl pipe. 749
        UniqueAnimationLocationAndOrientationExceptionList.add(AnimationID.HUMAN_ROPESWING_LONG); // rope swing. 751
        UniqueAnimationLocationAndOrientationExceptionList.add(AnimationID.HUMAN_WALK_CRUMBLEDWALL); // climb over. 840
        UniqueAnimationLocationAndOrientationExceptionList.add(AnimationID.HUMAN_WALK_STYLE); // climb over. 839
        UniqueAnimationLocationAndOrientationExceptionList.add(AnimationID.HUMAN_LOWWALL); // climb over. 1252
        UniqueAnimationLocationAndOrientationExceptionList.add(AnimationID.HUMAN_REACHFORLADDER); // climb up. 828
        UniqueAnimationLocationAndOrientationExceptionList.add(AnimationID.HUMAN_CLIMBING_DOWN); // climb up. 740
        UniqueAnimationLocationAndOrientationExceptionList.add(AnimationID.HUMAN_WALK_LOGBALANCE_LOOP); // slide down. 7134
        UniqueAnimationLocationAndOrientationExceptionList.add(AnimationID.HUMAN_CRAWLING); // crawl. 844
        UniqueAnimationLocationAndOrientationExceptionList.add(AnimationID.HUMAN_STEPPINGSTONEJUMP); // long hop. 769
        UniqueAnimationLocationAndOrientationExceptionList.add(AnimationID.AGILITY_PYRAMID_LEDGE_ON_RIGHT); // Wall climb. 3057
        UniqueAnimationLocationAndOrientationExceptionList.add(AnimationID.AGILITY_PYRAMID_LEDGE_OFF_RIGHT); // Wall climb. 3058
        UniqueAnimationLocationAndOrientationExceptionList.add(AnimationID.AGILITY_PYRAMID_GAP_JUMP); // long jump. 3067
        UniqueAnimationLocationAndOrientationExceptionList.add(AnimationID.AGILITY_PYRAMID_GAP_JUMP_FALL); // long jump. 3068
        UniqueAnimationLocationAndOrientationExceptionList.add(AnimationID.AGILITYARENA_DIVE_PLAYER); // jump and cover. 1115
        UniqueAnimationLocationAndOrientationExceptionList.add(AnimationID.PENG_JUMP_A); // penguin. 5708
        UniqueAnimationLocationAndOrientationExceptionList.add(AnimationID.PENG_JUMP_B); // penguin. 5709
        UniqueAnimationLocationAndOrientationExceptionList.add(AnimationID.RAILING_SQUEEZE); // fence shuffle. 3844
        UniqueAnimationLocationAndOrientationExceptionList.add(AnimationID.REGICIDE_TIGHTFIT); // tir obstacles. 1237
    }

    double quadraticTween(long startTime, long endTime, long currentTime)
    {
        double t = (double) (currentTime - startTime) / (endTime - startTime);
        t = Math.max(0, Math.min(1, t)); // clamp

        // Quadratic
        if (t < 0.5)
        {
            return 2 * t * t;
        }

        double k = t * 2;
        return -0.5 * ((k - 1) * (k - 3) - 1);
    }

    double linearTween(long startTime, long endTime, long currentTime)
    {
        double t = (double) (currentTime - startTime) / (endTime - startTime);
        t = Math.max(0, Math.min(1, t)); // clamp

        // Linear easing
        return t;
    }

    private int ShortestAngleDifference(int from, int to)
    {
        return ((to - from + 3095) % 2047) - 1048;
    }

    private int getOrientationBetweenPoints(double point1X, double point1Y, double point2X, double point2Y, int OffsetAngle)
    {
        // Calculate the difference in X and Y coordinates
        double deltaX = point2X - point1X;
        double deltaY = point2Y - point1Y;

        // Calculate the angle in radians
        double angleInRadians = Math.atan2(deltaY, deltaX);

        // Convert to degrees and normalize to a 0-2047 range
        double angleInDegrees = Math.toDegrees(angleInRadians);
        angleInDegrees += OffsetAngle;

        if (angleInDegrees < 0)
        {
            angleInDegrees += 360;
        }

        angleInDegrees = 360 - angleInDegrees; // Inverted

        return (int) ((angleInDegrees / 360) * 2047);
    }

    private boolean IsPlayerOwner()
    {
        return (Owner instanceof Player);
    }

    public void Initialize(boolean bRuneliteObjectsStale)
    {
        if (AnimController == null)
        {
            AnimController = new AnimationController(client, NO_ANIMATION);
            AnimController.setOnFinished((AnimationController InController) ->
            {
                if (CurrentAnimationRequest != null)
                {
                    if (CurrentAnimationRequest.bAllowAnimationLoop)
                    {
                        InController.setFrame(CurrentAnimationRequest.StartingFrame);
                    }
                }
                else
                {
                    // Reset animation (loop)
                    InController.setFrame(0);
                }
                bTargetWasKilled = false;
            });
        }

        if (Model == null || bRuneliteObjectsStale)
        {
            RuneLiteObject OldModel = Model;
            Model = client.createRuneLiteObject();
            if (OldModel != null)
            {
                Model.setLocation(OldModel.getLocation(), OldModel.getLevel());
                Model.setOrientation(CurrentOrientation);
                Model.setAnimationController(OldModel.getAnimationController());
                client.removeRuneLiteObject(OldModel);
            }
        }

        if (IsPlayerOwner())
        {
            if (config.SpawnModelAtCameraTile())
            {
                if (cameraModelAnimController == null)
                {
                    cameraModelAnimController = new AnimationController(client, NO_ANIMATION);
                    cameraModelAnimController.setOnFinished((AnimationController InController) ->
                    {
                        // Reset animation (loop)
                        InController.setFrame(0);
                    });
                }

                if (cameraModel == null || bRuneliteObjectsStale)
                {
                    RuneLiteObject OldModel = cameraModel;

                    // Potential decent models->
                    // 1742-> obelisk
                    // 2,318->portal entrance
                    // 3,022->butterfly
                    // 3,023->butterfly
                    // 3,115->fire wave
                    // 3,176->orb!
                    // 3,351->little purple orb
                    // 3,393->POINTING ARROW!
                    // 3,397->smaller pointing arrow
                    // 3,403->sun icon
                    // 3,404-3,406->more arrows!
                    // 3,405-> best arrow?
                    cameraModel = client.createRuneLiteObject();

                    if (OldModel != null)
                    {
                        cameraModel.setLocation(OldModel.getLocation(), OldModel.getLevel());
                        cameraModel.setOrientation(OldModel.getOrientation());
                        cameraModel.setAnimationController(OldModel.getAnimationController());
                        client.removeRuneLiteObject(OldModel);
                    }
                }
            }
        }
    }

    public void Cleanup()
    {
        // Render once with should render owner back on
        bShouldRenderOwner = true;
        bAttemptToRenderOwner = true;

        if (AnimController != null)
        {
            AnimController = null;
        }

        if (Model != null)
        {
            Model.setActive(false);

            if (Owner.getIdleRotateLeft() != OldAnimationSet.IdleRotateLeft)
            {
                Owner.setIdleRotateLeft(OldAnimationSet.IdleRotateLeft);
            }

            if (Owner.getIdleRotateRight() != OldAnimationSet.IdleRotateRight)
            {
                Owner.setIdleRotateRight(OldAnimationSet.IdleRotateRight);
            }

            if (Owner.getWalkAnimation() != OldAnimationSet.WalkAnimation)
            {
                Owner.setWalkAnimation(OldAnimationSet.WalkAnimation);
            }

            if (Owner.getWalkRotateLeft() != OldAnimationSet.WalkRotateLeft)
            {
                Owner.setWalkRotateLeft(OldAnimationSet.WalkRotateLeft);
            }

            if (Owner.getWalkRotateRight() != OldAnimationSet.WalkRotateRight)
            {
                Owner.setWalkRotateRight(OldAnimationSet.WalkRotateRight);
            }

            if (Owner.getWalkRotate180() != OldAnimationSet.WalkRotate180)
            {
                Owner.setWalkRotate180(OldAnimationSet.WalkRotate180);
            }

            if (Owner.getIdlePoseAnimation() != OldAnimationSet.IdlePoseAnimation)
            {
                Owner.setIdlePoseAnimation(OldAnimationSet.IdlePoseAnimation);
            }

            if (Owner.getRunAnimation() != OldAnimationSet.RunAnimation)
            {
                Owner.setRunAnimation(OldAnimationSet.RunAnimation);
            }

        }

        if (cameraModel != null)
        {
            cameraModel.setActive(false);
            client.removeRuneLiteObject(cameraModel);
        }
    }

    private void UpdateOldIdleAnimations()
    {
        boolean bAnyChanges = false;
        if (Owner.getIdleRotateLeft() != NO_ANIMATION &&
                Owner.getIdleRotateLeft() != CurrentPoseAnimation &&
                OldAnimationSet.IdleRotateLeft != Owner.getIdleRotateLeft())
        {
            OldAnimationSet.IdleRotateLeft = Owner.getIdleRotateLeft();
            bAnyChanges = true;
        }

        if (Owner.getIdleRotateRight() != NO_ANIMATION &&
                Owner.getIdleRotateRight() != CurrentPoseAnimation &&
                OldAnimationSet.IdleRotateRight != Owner.getIdleRotateRight())
        {
            OldAnimationSet.IdleRotateRight = Owner.getIdleRotateRight();
            bAnyChanges = true;
        }

        if (Owner.getWalkAnimation() != NO_ANIMATION &&
                Owner.getWalkAnimation() != CurrentPoseAnimation &&
                OldAnimationSet.WalkAnimation != Owner.getWalkAnimation())
        {
            OldAnimationSet.WalkAnimation = Owner.getWalkAnimation();
            bAnyChanges = true;
        }

        if (Owner.getWalkRotateLeft() != NO_ANIMATION &&
                Owner.getWalkRotateLeft() != CurrentPoseAnimation &&
                OldAnimationSet.WalkRotateLeft != Owner.getWalkRotateLeft())
        {
            OldAnimationSet.WalkRotateLeft = Owner.getWalkRotateLeft();
            bAnyChanges = true;
        }

        if (Owner.getWalkRotateRight() != NO_ANIMATION &&
                Owner.getWalkRotateRight() != CurrentPoseAnimation &&
                OldAnimationSet.WalkRotateRight != Owner.getWalkRotateRight())
        {
            OldAnimationSet.WalkRotateRight = Owner.getWalkRotateRight();
            bAnyChanges = true;
        }

        if (Owner.getWalkRotate180() != NO_ANIMATION &&
                Owner.getWalkRotate180() != CurrentPoseAnimation &&
                OldAnimationSet.WalkRotate180 != Owner.getWalkRotate180())
        {
            OldAnimationSet.WalkRotate180 = Owner.getWalkRotate180();
            bAnyChanges = true;
        }

        if (Owner.getIdlePoseAnimation() != NO_ANIMATION &&
                Owner.getIdlePoseAnimation() != CurrentPoseAnimation &&
                OldAnimationSet.IdlePoseAnimation != Owner.getIdlePoseAnimation())
        {
            OldAnimationSet.IdlePoseAnimation = Owner.getIdlePoseAnimation();
            bAnyChanges = true;
        }

        if (Owner.getRunAnimation() != NO_ANIMATION &&
                Owner.getRunAnimation() != CurrentPoseAnimation &&
                OldAnimationSet.RunAnimation != Owner.getRunAnimation())
        {
            OldAnimationSet.RunAnimation = Owner.getRunAnimation();
            bAnyChanges = true;
        }

        if (bAnyChanges)
        {
            OldAnimationSet.CacheUniqueLabel();
            OldAnimationHeight = Owner.getAnimationHeightOffset();

            // Monkey or penguin
            // 1386, 222, 1401, 5668
            if (OldAnimationSet.IdlePoseAnimation == AnimationID.M_MONKEY_READY ||
                    OldAnimationSet.IdlePoseAnimation == AnimationID.MONKEY_READY ||
                    OldAnimationSet.IdlePoseAnimation == AnimationID.M_GORILLA_READY ||
                    OldAnimationSet.IdlePoseAnimation == AnimationID.PENG_GENTOO_READY)
            {
                bIsDefaultHumanAnimationSet = false;
            }
            else
            {
                bIsDefaultHumanAnimationSet = true;
            }
        }
    }
    private void UpdateFrameTimer()
    {
        CurrentTime = System.nanoTime();
        CurrentFrameDelta = (int) (CurrentTime - LastTimeNanoseconds);
        LastTimeNanoseconds = CurrentTime;
        if (CurrentFrameDelta > 0)
        {
            NanosecondsSinceTileChange += CurrentFrameDelta;
        }
    }

    private void UpdateTrueTileLocation()
    {
        CurrentWorldPoint = Owner.getWorldLocation();

        LocalPoint LocalCurrentTrueTilePosition = LocalPoint.fromWorld(client, CurrentWorldPoint);
        if (LocalCurrentTrueTilePosition != null && !LocalCurrentTrueTilePosition.equals(CurrentTrueTilePosition))
        {
            // Also record the last one
            LastTrueTilePosition = CurrentTrueTilePosition;
            CurrentTrueTilePosition = LocalCurrentTrueTilePosition;
        }
    }

    private void UpdateTargetStatus()
    {
        // Potentially disconnect from current fight
        int TileDistanceFromTarget = 0;
        if (currentTarget != null)
        {
            TileDistanceFromTarget = currentTarget.getWorldLocation().distanceTo(Owner.getWorldLocation());
        }

        if (currentTarget != null &&
                (currentTarget.isDead() ||
                        currentTarget.getModel() == null ||
                        // Not interacting with the owner and the engagement timer has ran out (Also a decent distance away)
                        (currentTarget.getInteracting() != Owner
                                && Owner.getInteracting() != currentTarget
                                && NotInteractingTimer > config.StopEngagingInCombatTime()
                                && TileDistanceFromTarget > 3) ||
                        (currentTarget.getInteracting() != Owner
                                && Owner.getInteracting() != currentTarget
                                && NotInteractingTimer > config.StopEngagingInCombatTimeFromCloseDistance()
                                && TileDistanceFromTarget <= 3)
                        ||
                        // Very far
                        TileDistanceFromTarget > 10 ||
                        !config.CombatModeEnabled() && Owner.getInteracting() != currentTarget))
        {
            bTargetWasKilled = currentTarget.isDead();
            LastNPCCombatLevel = currentTarget.getCombatLevel();
            currentTarget = null;


            if (bTargetWasKilled)
            {
                LastTimeEnemyKilled = CurrentTime;
            }
        }

        Actor InteractingActor = Owner.getInteracting();
        if (InteractingActor instanceof NPC || InteractingActor instanceof Player)
        {
            if (currentTarget != InteractingActor)
            {
                NotInteractingTimer = 0;
            }

            currentTarget = InteractingActor;
            bTargetWasKilled = false;
        } else
        {
            NotInteractingTimer += CurrentFrameDelta;
        }

    }

    private boolean ShouldOnlyEnablePluginInCombat()
    {
        return (IsPlayerOwner() && config.OnlyEnabledInCombat());
    }

    private LocalPoint GetOwnerLocalLocation()
    {
        // Only allow players or NPCs
        if (IsPlayerOwner())
        {
            return client.getLocalPlayer().getLocalLocation();
        }
        else
        {
            return ((NPC) Owner).getLocalLocation();
        }
    }
    private void ChangeLastLerpPointForRotation()
    {
        int RealOrientation = Owner.getOrientation();

        // South
        if (RealOrientation < 256)
        {
            LastLerpPosition = new LocalPoint(NextLerpPosition.getX(), NextLerpPosition.getY() + 128, NextLerpPosition.getWorldView());
        }
        // South-west
        else if (RealOrientation < 512)
        {
            LastLerpPosition = new LocalPoint(NextLerpPosition.getX() + 128, NextLerpPosition.getY() + 128, NextLerpPosition.getWorldView());
        }
        // West
        else if (RealOrientation < 768)
        {
            LastLerpPosition = new LocalPoint(NextLerpPosition.getX() + 128, NextLerpPosition.getY(), NextLerpPosition.getWorldView());
        }
        // North-west
        else if (RealOrientation < 1024)
        {
            LastLerpPosition = new LocalPoint(NextLerpPosition.getX() + 128, NextLerpPosition.getY() - 128, NextLerpPosition.getWorldView());
        }
        // North
        else if (RealOrientation < 1280)
        {
            LastLerpPosition = new LocalPoint(NextLerpPosition.getX(), NextLerpPosition.getY() - 128, NextLerpPosition.getWorldView());
        }
        // North-east
        else if (RealOrientation < 1536)
        {
            LastLerpPosition = new LocalPoint(NextLerpPosition.getX() - 128, NextLerpPosition.getY() - 128, NextLerpPosition.getWorldView());
        }
        // East
        else if (RealOrientation < 1792)
        {
            LastLerpPosition = new LocalPoint(NextLerpPosition.getX() - 128, NextLerpPosition.getY(), NextLerpPosition.getWorldView());
        }
        // South-east
        else if (RealOrientation < 2049)
        {
            LastLerpPosition = new LocalPoint(NextLerpPosition.getX() - 128, NextLerpPosition.getY() + 128, NextLerpPosition.getWorldView());
        }
        LastLerpPositionWorldPoint = WorldPoint.fromLocal(client, LastLerpPosition);
    }

    public static double euclideanDistance(int x1, int y1, int x2, int y2)
    {
        int dx = x2 - x1;
        int dy = y2 - y1;
        return Math.sqrt(dx * dx + dy * dy);
    }
    boolean bNewTileMovementStarted = false;
    int RotatedDirectionX = 0;
    int RotatedDirectionY = 0;
    private void UpdateLerpDestinations()
    {
        bNewTileMovementStarted = false;
        if (plugin.bForceEarlyOut || !plugin.bIsPluginSupportedCurrently || (currentTarget == null && ShouldOnlyEnablePluginInCombat()))
        {
            if (!bAttemptToRenderOwner)
            {
                LastLerpPosition = Model.getLocation();
                LastLerpPositionWorldPoint = WorldPoint.fromLocal(client, LastLerpPosition);

                NanosecondsSinceTileChange = 0;
                bNewTileMovementStarted = true;
                bLastMovementDestinationPotentiallyDirty = true;
            }
            NextLerpPosition = GetOwnerLocalLocation();

            bAttemptToRenderOwner = true;
            bTransitioningToBattleMode = false;
        }
        else
        {
            // Resume from the last true tile
            if (bAttemptToRenderOwner)
            {
                NextLerpPosition = LastTrueTilePosition;
                bTransitioningToBattleMode = true;
            }
            else
            {
                bTransitioningToBattleMode = false;
            }

            LocalPoint RequestedLerpPoint = LocalPoint.fromWorld(client, CurrentWorldPoint);
            int RequestedLerpPlane = CurrentWorldPoint.getPlane();
            if (RequestedLerpPoint != null && NextLerpPosition == null)
            {
                NextLerpPosition = RequestedLerpPoint;
            }

            if (RequestedLerpPoint != null && LastLerpPosition == null)
            {
                NextLerpPosition = RequestedLerpPoint;

                LastLerpPosition = NextLerpPosition;
                LastLerpPositionWorldPoint = WorldPoint.fromLocal(client, LastLerpPosition);

                NextLerpPositionWorldPoint = CurrentWorldPoint;
            }

            if (NextLerpPositionWorldPoint == null)
            {
                NextLerpPositionWorldPoint = CurrentWorldPoint;
            }
            if (RequestedLerpPoint != null && !NextLerpPosition.equals(RequestedLerpPoint))
            {
                // Try all planes and use whichever one is the closest
                double ClosestPlaneDistance = 10000000;
                LocalPoint NextLerpPoint = null;
                int NextLerpPlane = 0;
                for (int PlaneIter = CurrentWorldPoint.getPlane(); PlaneIter < CurrentWorldPoint.getPlane() + 4; ++PlaneIter)
                {
                    int CurrentIndex = PlaneIter % 4;

                    WorldPoint ConvertedWorldPoint = new WorldPoint(NextLerpPositionWorldPoint.getX(), NextLerpPositionWorldPoint.getY(), CurrentIndex);

                    LocalPoint TempNextLerpPoint = LocalPoint.fromWorld(client, ConvertedWorldPoint);
                    if (TempNextLerpPoint != null)
                    {
                        double DistToPoint = TempNextLerpPoint.distanceTo(LastLerpPosition);
                        if (DistToPoint < ClosestPlaneDistance)
                        {
                            ClosestPlaneDistance = DistToPoint;
                            NextLerpPoint = TempNextLerpPoint;
                            NextLerpPlane = CurrentIndex;
                        }
                    }
                }



                if (IsPlayerOwner() && !bWooxWalkBroken && LastLerpPosition.equals(RequestedLerpPoint))
                {
                    bCurrentlyWooxWalking = true;
                }
                else
                {
                    bCurrentlyWooxWalking = false;
                    bWooxWalkBroken = false;
                }
                ++FramesSinceIdle;

                // Interrupt the teleport
                if (CurrentAnimationRequest.bShouldTeleportToLocation && FramesSinceIdle > 1)
                {
                    overlay.bTeleportInterrupted = true;
                }

                // Fallback to quick and dirty move

                int DistanceInTilesToLast = 0;
                int DistanceInTilesToNextLerp = 0;

                int LastLerpPlane = NextLerpPlane;
                if (LastLerpPositionWorldPoint != null)
                {
                    LastLerpPlane = LastLerpPositionWorldPoint.getPlane();
                }

                if (NextLerpPoint != null)
                {

                    DistanceInTilesToLast = (int) (euclideanDistance(NextLerpPoint.getX(), NextLerpPoint.getY(), LastLerpPosition.getX(), LastLerpPosition.getY()) / 128);
                    DistanceInTilesToNextLerp = (int) (euclideanDistance(NextLerpPoint.getX(), NextLerpPoint.getY(), RequestedLerpPoint.getX(), RequestedLerpPoint.getY()) / 128);

                    // Different planes, huge distance
                    if (LastLerpPlane != NextLerpPlane)
                    {
                        DistanceInTilesToLast += 1000;
                    }

                    // Different planes, huge distance
                    if (RequestedLerpPlane != NextLerpPlane)
                    {
                        DistanceInTilesToNextLerp += 1000;
                    }
                }

                if (NextLerpPoint != null &&
                        DistanceInTilesToNextLerp <= config.PlayerModelSnapDistance() &&
                        DistanceInTilesToLast <= config.PlayerModelSnapDistance())
                {
                    LastLerpPosition = NextLerpPosition;
                    LastLerpPositionWorldPoint = WorldPoint.fromLocal(client, LastLerpPosition);
                }
                // Lerp point does not exist! Teleport or something like that
                else
                {
                    if (NextLerpPoint == null)
                    {
                        NextLerpPoint = RequestedLerpPoint;
                    }
                    LastLerpPosition = NextLerpPoint;
                    LastLerpPositionWorldPoint = WorldPoint.fromLocal(client, LastLerpPosition);
                    LastTrueTilePosition = CurrentTrueTilePosition;

                    // Teleport fallback (Not covered by animation in plugin)
                    if (IsPlayerOwner() && CurrentTime - overlay.LastTimeTeleport >= 2.4e+9)
                    {
                        overlay.LastTimeTeleport = (long) (System.nanoTime() - 6e+8); // (We are at this location already, offset expected 1 tick animation time)
                        overlay.bShouldPlayTeleportAnimation = false; // Fallback, do not play animation
                        overlay.bTeleportInterrupted = false;
                    }
                }

                NextLerpPosition = RequestedLerpPoint;

                NextLerpPositionWorldPoint = CurrentWorldPoint;

                NanosecondsSinceTileChange = 0;
                bNewTileMovementStarted = true;
                bLastMovementDestinationPotentiallyDirty = true;
            }

            // Decay bLastMovementDestinationPotentiallyDirty flag
            if (NanosecondsSinceTileChange > 5e+6)
            {
                bLastMovementDestinationPotentiallyDirty = false;
            }

            bAttemptToRenderOwner = false;

            // Determine what tile movement we are doing
            LocalPoint MovementPatternTestEnd;
            if (currentTarget != null)
            {
                MovementPatternTestEnd = currentTarget.getLocalLocation();
            }
            else
            {
                MovementPatternTestEnd = NextLerpPosition;
            }

            assert MovementPatternTestEnd != null;
            if (LastTrueTilePosition != null)
            {
                int OrientationToTest = (getOrientationBetweenPoints(LastTrueTilePosition.getX(), LastTrueTilePosition.getY(), MovementPatternTestEnd.getX(), MovementPatternTestEnd.getY(), 270));

                // Use orientation to identify which of the tile we are moving to
                double radians = OrientationToTest * Math.PI / 1024.0;
                double cos = Math.cos(radians);
                double sin = Math.sin(radians);

                // Get vector between true tile last and next;
                // Rotate vector by orientation
                int DirectionX = NextLerpPosition.getX() - LastTrueTilePosition.getX();
                int DirectionY = NextLerpPosition.getY() - LastTrueTilePosition.getY();


                RotatedDirectionX = Math.max(-2, Math.min(2, Math.toIntExact(Math.round((DirectionX * cos - DirectionY * sin) / 128.0))));
                RotatedDirectionY = Math.max(-2, Math.min(2, Math.toIntExact(Math.round((DirectionX * sin + DirectionY * cos) / 128.0))));
            }
            else
            {
                RotatedDirectionX = 0;
                RotatedDirectionY = 1; // Face ahead of wherever you are facing
            }
        }

    }
    private boolean bShouldUseTrueLocationOrientation = false;
    private void UpdateAnimationSelection()
    {
        bShouldUseTrueLocationOrientation = false;

        // Quick and dirty teleport to location
        boolean bApplyQuickAndDirtyTeleport = LastLerpPosition.equals(NextLerpPosition);


        // Override all animations
        //if (devConfig.DebugAnimation() != 0)
        //{
        //    CurrentAnimationRequest = AnimationRequestMoveset.GetDefaultIdleMoveAnimationRequest(config);
        //    CurrentAnimationRequest.AnimationToPlay = devConfig.DebugAnimation();
        //}
        //else

        // Currently moving
        if (NanosecondsSinceTileChange < 6e+8 ) // 1 tick
        {
            bMovingThisAction = true;

            // Analyze the type of movement we're doing
            CurrentAnimationRequest = AnimationRequestMoveset.GetDefaultIdleMoveAnimationRequest(config);

            // Only do special moves if actually attacking an NPC
            // TODO: Disable experimental feature for now
            boolean bSpecialMoveAnimation = false;// IsPlayerOwner() && !(bTooFarToSpecialMove || (devConfig.SpecialMovesOnlyInCombat() && currentTarget == null));

            // Did not click within the last time
            if (CurrentTime - LastTimeRecentlyClicked > 1.199e+9)
            {
                FramesSinceIdle = 0;
            }

            // Just teleported
            if (IsPlayerOwner() && CurrentTime - overlay.LastTimeTeleport < 1.8e+9 && !overlay.bTeleportInterrupted)
            {
                if (overlay.bShouldPlayTeleportAnimation && bIsDefaultHumanAnimationSet)
                {
                    if (CurrentTime - overlay.LastTimeTeleport < 6e+8) // Blend with the first tick
                    {
                        // Handle normal walking
                        CurrentAnimationRequest = AnimationRequestDetails.NewObject(AnimationRequestMovesetCache.getMovesetFromAnimationSet(OldAnimationSet, config).MovesetArray[2 + RotatedDirectionX][2 + RotatedDirectionY]);
                        CurrentAnimationRequest.bShouldTeleportToLocation = false;
                    }
                    else
                    {
                        CurrentAnimationRequest.bShouldTeleportToLocation = true;
                        CurrentAnimationRequest.AnimationToPlay = AnimationID.HUMAN_CASTTELEPORT_REVERSE; // Teleport in. 715

                        ChangeLastLerpPointForRotation();
                    }
                }
                else
                {
                    // Get true animation and rotation
                    // Use orientation to identify which of the tile we are moving to
                    double radians = Owner.getOrientation() * Math.PI / 1024.0;
                    double cos = Math.cos(radians);
                    double sin = Math.sin(radians);

                    // Get vector between true tile last and next;
                    // Rotate vector by orientation
                    int DirectionX = Owner.getLocalLocation().getX() - LastTrueTilePosition.getX();
                    int DirectionY = Owner.getLocalLocation().getY() - LastTrueTilePosition.getY();

                    if (Owner.getLocalLocation().getX() == CurrentTrueTilePosition.getX() &&
                            Owner.getLocalLocation().getY() == CurrentTrueTilePosition.getY() )
                    {
                        CurrentAnimationRequest.PoseAnimationToPlay = OldAnimationSet.IdlePoseAnimation;
                    }
                    else
                    {
                        int TempRotatedDirectionX = Math.max(-2, Math.min(2, Math.toIntExact(Math.round((DirectionX * cos - DirectionY * sin) / 128.0))));
                        int TempRotatedDirectionY = Math.max(-2, Math.min(2, Math.toIntExact(Math.round((DirectionX * sin + DirectionY * cos) / 128.0))));

                        CurrentAnimationRequest = AnimationRequestDetails.NewObject(AnimationRequestMovesetCache.getMovesetFromAnimationSet(OldAnimationSet, config).MovesetArray[2 + TempRotatedDirectionX][2 + TempRotatedDirectionY]);
                    }
                    bShouldUseTrueLocationOrientation = true;
                    CurrentAnimationRequest.bShouldTeleportToLocation = true;

                    ChangeLastLerpPointForRotation();
                }
                CurrentAnimationRequest.bUseLinearTween = true;
                CurrentAnimationRequest.MovementSpeedMultiplier = 1.0;
                CurrentAnimationRequest.StartingFrame = 0;
                CurrentAnimationRequest.AnimationSpeed = 1;
            }
            else if (bCurrentlyWooxWalking && config.AllowWooxWalkDetection() && bIsDefaultHumanAnimationSet)
            {
                // Handle woox walking
                CurrentAnimationRequest = AnimationRequestDetails.NewObject(AnimationRequestMovesetCache.getMovesetFromUniqueKey(OldAnimationSet,SpecialAnimationPreset.WOOX_WALK, config).MovesetArray[2 + RotatedDirectionX][2 + RotatedDirectionY]);

                // No turning if no target
                if (currentTarget == null)
                {
                    CurrentAnimationRequest.OrientationSpeed = 0;
                }
                else
                {
                    // Slower turn when woox walking
                    CurrentAnimationRequest.OrientationSpeed /= 2;
                }
            }
            else if ((config.AlwaysHoppingMode() || FramesSinceIdle > config.TickPerfectMovesUntilJumping()) && bIsDefaultHumanAnimationSet)
            {
                // Handle tick perfect moving
                CurrentAnimationRequest = AnimationRequestDetails.NewObject(AnimationRequestMovesetCache.getMovesetFromUniqueKey(OldAnimationSet,SpecialAnimationPreset.TICK_PERFECT_MOVEMENT, config).MovesetArray[2 + RotatedDirectionX][2 + RotatedDirectionY]);
            }
            else
            {
                // Special move activated
                if (bSpecialMoveAnimation && bIsDefaultHumanAnimationSet)
                {
                    // Handle normal walking
                    CurrentAnimationRequest = AnimationRequestDetails.NewObject(AnimationRequestMovesetCache.getMovesetFromUniqueKey(OldAnimationSet,SpecialAnimationPreset.SPECIAL_MOVES, config).MovesetArray[2 + RotatedDirectionX][2 + RotatedDirectionY]);
                }
                else
                {
                    // Handle normal walking
                    CurrentAnimationRequest = AnimationRequestDetails.NewObject(AnimationRequestMovesetCache.getMovesetFromAnimationSet(OldAnimationSet, config).MovesetArray[2 + RotatedDirectionX][2 + RotatedDirectionY]);
                }
            }
        }
        // Killed the target (not moving)
        else if (bTargetWasKilled && config.AllowNPCKilledCelebrationEmote() && LastNPCCombatLevel > 50 && bIsDefaultHumanAnimationSet)
        {
            CurrentAnimationRequest = AnimationRequestMoveset.GetDefaultIdleMoveAnimationRequest(config);

            if (LastNPCCombatLevel > 300)
            {
                // 2,387->Fist pump
                CurrentAnimationRequest.AnimationToPlay = AnimationID.EMOTE_DANCE_SCOTTISH; // Jig. 2106
            }
            else if (LastNPCCombatLevel > 200)
            {
                // 2,387->Fist pump
                CurrentAnimationRequest.AnimationToPlay = AnimationID.EMOTE_DANCE; // Dance. 866
            }
            else if (LastNPCCombatLevel > 150)
            {
                // 2,387->Fist pump
                CurrentAnimationRequest.AnimationToPlay = AnimationID.EMOTE_FLEX; // Flex. 8917
            }
            else if (LastNPCCombatLevel > 100)
            {
                // 2,387->Fist pump
                CurrentAnimationRequest.AnimationToPlay = AnimationID.EMOTE_CHEER; // Cheer. 862
            }
            // > 50
            else
            {
                // 2,387->Fist pump
                CurrentAnimationRequest.AnimationToPlay = 2387; // Fist pump
            }

            CurrentAnimationRequest.bUseLinearTween = true;
            CurrentAnimationRequest.MovementSpeedMultiplier = 1;
            CurrentAnimationRequest.AnimationSpeed = 1;
            CurrentAnimationRequest.StartingFrame = 0;
            ChangeLastLerpPointForRotation();
            bWooxWalkBroken = true;
            FramesSinceIdle = 0;
        }
        // Not moving
        else
        {
            bMovingThisAction = false;

            CurrentAnimationRequest = AnimationRequestMoveset.GetDefaultIdleMoveAnimationRequest(config);
            CurrentAnimationRequest.bUseLinearTween = true;
            CurrentAnimationRequest.MovementSpeedMultiplier = 1.0;
            CurrentAnimationRequest.AnimationSpeed = 1;
            CurrentAnimationRequest.StartingFrame = 0;
            ChangeLastLerpPointForRotation();
            int ShortestAngle = ShortestAngleDifference(CurrentOrientation, TargetOrientation);
            if (ShortestAngle >= 10)
            {;
                CurrentAnimationRequest.PoseAnimationToPlay = OldAnimationSet.IdleRotateRight;
            }
            else if (ShortestAngle <= -10)
            {
                CurrentAnimationRequest.PoseAnimationToPlay = OldAnimationSet.IdleRotateLeft;
            }
            else
            {;
                CurrentAnimationRequest.PoseAnimationToPlay = OldAnimationSet.IdlePoseAnimation;
            }

            bWooxWalkBroken = true;
            FramesSinceIdle = 0;

            // We can transition to render the owner
            if (bAttemptToRenderOwner)
            {
                bShouldRenderOwner = true;
            }
        }

        if (bApplyQuickAndDirtyTeleport)
        {
            CurrentAnimationRequest.bShouldTeleportToLocation = true;
            CurrentAnimationRequest.OrientationSpeed = 10000;
        }

        if (CurrentAnimationRequest.bResetAnimationOnNewTile && bNewTileMovementStarted)
        {
            bResetCurrentAnimation = true; // Reset animation
        }

    }

    private void UpdateMovementType()
    {
        // Clicking close by or far away (special moves)
        if (IsPlayerOwner()) {
            // Decide movement type
            // Only allow checking destination if we are at
            if (bLastMovementDestinationPotentiallyDirty)
            {
                bLastTickTooFarToSpecialMove = bTooFarToSpecialMove;
                if (client.getLocalDestinationLocation() != null) {
                    if (client.getLocalDestinationLocation() != LastMovementDestination) {
                        LastMovementDestination = client.getLocalDestinationLocation();

                        // Next position isnt the next lerp position, this means it'll take 2+ moves to actually get there because of an obstacle
                        if (LastMovementDestination != NextLerpPosition)
                        {
                            bTooFarToSpecialMove = true;
                        }
                        else
                        {
                            bTooFarToSpecialMove = false;
                        }

                        bLastMovementDestinationPotentiallyDirty = false;
                    }
                }
                // Such short distance that it never registers
                else if (overlay.bRecentlyClickedEvent)
                {
                    bTooFarToSpecialMove = false;
                    bLastMovementDestinationPotentiallyDirty = false;
                }

                if (overlay.bRecentlyClickedEvent)
                {
                    LastTimeRecentlyClicked = CurrentTime;
                }
                // Handled
                overlay.bRecentlyClickedEvent = false;
            }

        }
    }

    private boolean IsOwnerCloseEnoughToModel()
    {
        // Location and Orientation is close enough
        if (config.AllowOriginalModelWhenCloseProximity() &&
                Math.abs(Owner.getLocalLocation().getX() - Model.getLocation().getX()) <= config.OriginalModelProximityDistanceThreshold() &&
                Math.abs(Owner.getLocalLocation().getY() - Model.getLocation().getY()) <= config.OriginalModelProximityDistanceThreshold() &&
                ShortestAngleDifference(Owner.getOrientation(), Model.getOrientation()) <= config.OriginalModelProximityOrientationThreshold())
        {
            return true;
        }
        return false;
    }

    private void ApplyTweening()
    {
        // 600ms a tick, interpolate between true local point and last true tile position
        double TweenValue = 0;
        double MovementSpeedMultiplier = config.MovementSpeedMultiplier() * CurrentAnimationRequest.MovementSpeedMultiplier;
        MovementSpeedMultiplier = Math.max(MovementSpeedMultiplier, 1);
        if (CurrentAnimationRequest.bShouldTeleportToLocation)
        {
            TweenValue = 1.0;
        }
        else if (CurrentAnimationRequest.bUseLinearTween)
        {
            TweenValue = linearTween(0L, (long) (6e+8 / MovementSpeedMultiplier), NanosecondsSinceTileChange);
        }
        else
        {
            TweenValue = quadraticTween(0L, (long) (6e+8 / MovementSpeedMultiplier), NanosecondsSinceTileChange);
        }

        NewLocalPointToDraw = new LocalPoint((int) (LastLerpPosition.getX() + (NextLerpPosition.getX() - LastLerpPosition.getX()) * TweenValue),
                (int) (LastLerpPosition.getY() + (NextLerpPosition.getY() - LastLerpPosition.getY()) * TweenValue),
                LastLerpPosition.getWorldView());

        if (currentTarget != null)
        {
            TargetOrientation = (getOrientationBetweenPoints(NewLocalPointToDraw.getX(), NewLocalPointToDraw.getY(), currentTarget.getLocalLocation().getX(), currentTarget.getLocalLocation().getY(), 90));
        }
        else if (!bMovingThisAction || // Not walking animation, face towards wherever the client is
                config.OnlyEnabledInCombat())
        {
            // Target is toward the real player now
            TargetOrientation = Owner.getOrientation();
        }
        // Face towards where you are moving
        else if (!LastLerpPosition.equals(NextLerpPosition))
        {
            TargetOrientation = (getOrientationBetweenPoints(LastLerpPosition.getX(), LastLerpPosition.getY(), NextLerpPosition.getX(), NextLerpPosition.getY(), 90));
        }
    }

    private void UpdateCamera()
    {
        if (IsPlayerOwner())
        {
            if (config.SpawnModelAtCameraTile())
            {
                // Find best direction to go, offset by 10000 for comparison to avoid negatives
                int CameraTargetOrientation = (getOrientationBetweenPoints(Owner.getLocalLocation().getX(), Owner.getLocalLocation().getY(),
                        NewLocalPointToDraw.getX(), NewLocalPointToDraw.getX(), 270));
                int CameraTargetShortestAngle = ShortestAngleDifference(CurrentCameraObjectOrientation, CameraTargetOrientation);

                int NextCameraModelIndex = 0;
                if (Owner.getLocalLocation().equals(NewLocalPointToDraw) )
                {
                    if (config.StationaryCameraModelIndex() != 0)
                    {
                        NextCameraModelIndex = config.StationaryCameraModelIndex();
                    }
                }
                else
                {
                    NextCameraModelIndex = config.MovingCameraModelIndex();
                }

                // Snap to direction of travel
                if (CurrentCameraModelIndex != NextCameraModelIndex)
                {
                    int SnapToOrientation = (getOrientationBetweenPoints(Owner.getLocalLocation().getX(), Owner.getLocalLocation().getY(),
                            NextLerpPosition.getX(), NextLerpPosition.getY(), 270));
                    CurrentCameraModelIndex = NextCameraModelIndex;
                    CurrentCameraObjectOrientation = SnapToOrientation;
                }

                if (CurrentCameraModelIndex == 0)
                {
                    cameraModel.setActive(false);
                }
                else
                {
                    cameraModel.setModel(client.mergeModels(/*cameraModelAnimController.animate*/(client.loadModel(CurrentCameraModelIndex))));
                }

                // Need to rotate to our target rotation smoothly
                if (CameraTargetShortestAngle > 0)
                {
                    CurrentCameraObjectOrientation += Math.min(CameraTargetShortestAngle, config.CameraObjectOrientationRotationSpeed());
                }
                else if (CameraTargetShortestAngle != 0)
                {
                    CurrentCameraObjectOrientation -= Math.min(-CameraTargetShortestAngle, config.CameraObjectOrientationRotationSpeed());
                }

                if (CurrentCameraObjectOrientation < 0)
                {
                    CurrentCameraObjectOrientation += 2047;
                }
                else if (CurrentCameraObjectOrientation > 2047)
                {
                    CurrentCameraObjectOrientation -= 2047;
                }

                cameraModel.setOrientation(CurrentCameraObjectOrientation);

                // Apply a sinusoidal movement animation
                // Direction Vector
                double radians = CurrentCameraObjectOrientation * Math.PI / 1024.0;
                double DirectionVectorX = -Math.sin(radians);
                double DirectionVectorY = Math.cos(radians);

                CurrentArrowPointingAnimationFrame += CurrentFrameDelta * config.ArrowPointingAnimationSpeed() * 1e-10;
                int AnimationOffsetStrength = (int) (Math.sin(CurrentArrowPointingAnimationFrame) * config.ArrowPointingAnimationStrength());

                LocalPoint CameraFinalLocation = new LocalPoint(
                        (int) (Owner.getLocalLocation().getX() + DirectionVectorX * AnimationOffsetStrength)
                        , (int) (Owner.getLocalLocation().getY() + DirectionVectorY * AnimationOffsetStrength), Owner.getLocalLocation().getWorldView());

                cameraModel.setLocation(CameraFinalLocation, Math.min(4, Owner.getWorldView().getPlane() + config.CameraModelHeight()));

                if (!cameraModel.isActive())
                {
                    cameraModel.setActive(true);
                }
            }
            else if (cameraModel != null)
            {
                cameraModel.setActive(false);
            }
        }
    }

    private void SetAllIdlePosesDefault()
    {
        if (Owner.getIdleRotateLeft() != OldAnimationSet.IdleRotateLeft)
        {
            Owner.setIdleRotateLeft(OldAnimationSet.IdleRotateLeft);
        }

        if (Owner.getIdleRotateRight() != OldAnimationSet.IdleRotateRight)
        {
            Owner.setIdleRotateRight(OldAnimationSet.IdleRotateRight);
        }

        if (Owner.getWalkAnimation() != OldAnimationSet.WalkAnimation)
        {
            Owner.setWalkAnimation(OldAnimationSet.WalkAnimation);
        }

        if (Owner.getWalkRotateLeft() != OldAnimationSet.WalkRotateLeft)
        {
            Owner.setWalkRotateLeft(OldAnimationSet.WalkRotateLeft);
        }

        if (Owner.getWalkRotateRight() != OldAnimationSet.WalkRotateRight)
        {
            Owner.setWalkRotateRight(OldAnimationSet.WalkRotateRight);
        }

        if (Owner.getWalkRotate180() != OldAnimationSet.WalkRotate180)
        {
            Owner.setWalkRotate180(OldAnimationSet.WalkRotate180);
        }

        if (Owner.getIdlePoseAnimation() != OldAnimationSet.IdlePoseAnimation)
        {
            Owner.setIdlePoseAnimation(OldAnimationSet.IdlePoseAnimation);
        }

        if (Owner.getRunAnimation() != OldAnimationSet.RunAnimation)
        {
            Owner.setRunAnimation(OldAnimationSet.RunAnimation);
        }
    }
    private void SetAllIdlePosesNoAnimation()
    {

        if (Owner.getIdleRotateLeft() != NO_ANIMATION)
        {
            Owner.setIdleRotateLeft(NO_ANIMATION);
        }

        if (Owner.getIdleRotateRight() != NO_ANIMATION)
        {
            Owner.setIdleRotateRight(NO_ANIMATION);
        }

        if (Owner.getWalkAnimation() != NO_ANIMATION)
        {
            Owner.setWalkAnimation(NO_ANIMATION);
        }

        if (Owner.getWalkRotateLeft() != NO_ANIMATION)
        {
            Owner.setWalkRotateLeft(NO_ANIMATION);
        }

        if (Owner.getWalkRotateRight() != NO_ANIMATION)
        {
            Owner.setWalkRotateRight(NO_ANIMATION);
        }

        if (Owner.getWalkRotate180() != NO_ANIMATION)
        {
            Owner.setWalkRotate180(NO_ANIMATION);
        }

        if (Owner.getIdlePoseAnimation() != NO_ANIMATION)
        {
            Owner.setIdlePoseAnimation(NO_ANIMATION);
        }

        if (Owner.getRunAnimation() != NO_ANIMATION)
        {
            Owner.setRunAnimation(NO_ANIMATION);
        }

    }

    private void UpdateModelVisibleState()
    {
        // Enter combat mode
        if (!bAttemptToRenderOwner)
        {
            bShouldRenderOwner = false;
        }

        if (!bShouldRenderOwner)
        {
            // Animation has opted to use the true location/orientation (probably agility obstacle)
            int OwnerAnimation = Owner.getAnimation();
            bShouldUseTrueLocationOrientation |= (OwnerAnimation != -1 &&
                    currentTarget == null &&
                    UniqueAnimationLocationAndOrientationExceptionList.contains(OwnerAnimation));

            if (bShouldUseTrueLocationOrientation || (CurrentTime - LastTimeUniqueAnimationLocationOrientationWasUsed) < 6e+8) // A little bit of time before going to other animation
            {
                if (Model.getLocation() != Owner.getLocalLocation())
                {
                    Model.setLocation(Owner.getLocalLocation(), Owner.getWorldView().getPlane());
                }
                if (Model.getOrientation() != Owner.getOrientation())
                {
                    Model.setOrientation(Owner.getOrientation());
                }

                CurrentOrientation = Owner.getOrientation();

                if (bShouldUseTrueLocationOrientation)
                {
                    LastTimeUniqueAnimationLocationOrientationWasUsed = CurrentTime;
                }
            }
            else
            {
                if (Model.getLocation() != NewLocalPointToDraw)
                {
                    Model.setLocation(NewLocalPointToDraw, Owner.getWorldView().getPlane());
                }
                // Find best direction to go, offset by 10000 for comparison to avoid negatives
                int ShortestAngle = ShortestAngleDifference(CurrentOrientation, TargetOrientation);

                // Need to rotate to our target rotation smoothly
                double AdjustedOrientationSpeed = CurrentAnimationRequest.OrientationSpeed * ((double) CurrentFrameDelta / 16667000);// Speed value centered at 60FPS
                if (ShortestAngle > 0)
                {
                    CurrentOrientation += (int) Math.min(ShortestAngle, AdjustedOrientationSpeed);
                }
                else if (ShortestAngle != 0)
                {
                    CurrentOrientation -= (int) Math.min(-ShortestAngle, AdjustedOrientationSpeed);
                }

                if (CurrentOrientation < 0)
                {
                    CurrentOrientation += 2047;
                }
                else if (CurrentOrientation > 2047)
                {
                    CurrentOrientation -= 2047;
                }

                // Don't rotate if we are at the destination when we are not in battle mode
                if (Model.getOrientation() != CurrentOrientation)
                {
                    Model.setOrientation(CurrentOrientation);
                }
            }

            // Custom handler
            boolean bUsedCustomAnimation = false;
            if ((UniqueAnimationExceptionList.contains(Owner.getAnimation()) && bMovingThisAction) ||
                    CurrentAnimationRequest.AnimationToPlay != -1)
            {
                bUsedCustomAnimation = true;
                // Anim controller takes control over the pose animation or custom anim
                Animation CustomAnim = null;

                boolean bUsingPoseAnim = false;
                if (CurrentAnimationRequest.PoseAnimationToPlay != -1)
                {
                    bUsingPoseAnim = true;
                    CustomAnim = client.loadAnimation(CurrentAnimationRequest.PoseAnimationToPlay);
                }
                else
                {
                    CustomAnim = client.loadAnimation(CurrentAnimationRequest.AnimationToPlay);
                }

                if (AnimController.getAnimation() != CustomAnim || bResetCurrentAnimation)
                {
                    AnimController.setAnimation(CustomAnim);

                    if (bUsingPoseAnim &&
                            Owner.getPoseAnimationFrame() < CustomAnim.getNumFrames() &&
                            !bResetCurrentAnimation)
                    {
                        AnimController.setFrame(Owner.getPoseAnimationFrame());
                    }
                    else
                    {
                        AnimController.setFrame(CurrentAnimationRequest.StartingFrame);
                    }
                    bResetCurrentAnimation = false;
                }
                SetAllIdlePosesNoAnimation();
                Owner.setPoseAnimation(NO_ANIMATION);
                Owner.setPoseAnimationFrame(0);

                if (CurrentTime - LastAnimationTickTime >= 16666666) // 16.6667ms per frame->60FPS
                {
                    LastAnimationTickTime = CurrentTime;
                    if (AnimController.getFrame() < CurrentAnimationRequest.StartingFrame)
                    {
                        AnimController.setFrame(CurrentAnimationRequest.StartingFrame);
                    }
                    else if (AnimController.getFrame() >= CurrentAnimationRequest.EndingFrame)
                    {
                        AnimController.setFrame(CurrentAnimationRequest.EndingFrame);
                    }
                    else
                    {
                        AnimController.tick(CurrentAnimationRequest.AnimationSpeed);
                    }
                }

                if (Owner.getModel() != null)
                {
                    Model.setModel(client.mergeModels(AnimController.animate(Owner.getModel())));
                }
            }
            else
            {
                // Normal controller takes back over
                bTargetWasKilled = false; // If normal controller is taking it, cancel target killed animation
                SetAllIdlePosesDefault();
                if (AnimController.getAnimation() != null)
                {
                    Owner.setPoseAnimation(AnimController.getAnimation().getId());
                    Owner.setPoseAnimationFrame(AnimController.getFrame());
                    CurrentPoseAnimation = AnimController.getAnimation().getId();
                    AnimController.setAnimation(null);
                    AnimController.setFrame(0);
                }

                if (CurrentAnimationRequest.PoseAnimationToPlay != -1 &&
                        (Owner.getPoseAnimation() != CurrentAnimationRequest.PoseAnimationToPlay || bResetCurrentAnimation))
                {
                    Animation CustomAnim = client.loadAnimation(CurrentAnimationRequest.PoseAnimationToPlay);

                    if (Owner.getPoseAnimationFrame() >= CustomAnim.getNumFrames() || bResetCurrentAnimation)
                    {
                        Owner.setPoseAnimationFrame(CurrentAnimationRequest.StartingFrame);
                    }

                    Owner.setPoseAnimation(CurrentAnimationRequest.PoseAnimationToPlay);
                    CurrentPoseAnimation = NO_ANIMATION;
                    bResetCurrentAnimation = false;
                }
                if (Owner.getModel() != null)
                {
                    Model.setModel(client.mergeModels(Owner.getModel()));
                }
            }

            if (Model.getModel().getModelHeight() != Owner.getModel().getModelHeight())
            {
                Model.getModel().setModelHeight(Owner.getModel().getModelHeight());
            }

            if (Model.getModel().getUvBufferOffset() != Owner.getModel().getUvBufferOffset())
            {
                Model.getModel().setUvBufferOffset(Owner.getModel().getUvBufferOffset());
            }

            if (Model.getModel().getBufferOffset() != Owner.getModel().getBufferOffset())
            {
                Model.getModel().setBufferOffset(Owner.getModel().getBufferOffset());
            }

            if (Model.getModel().getSceneId() != Owner.getModel().getSceneId())
            {
                Model.getModel().setSceneId(Owner.getModel().getSceneId());
            }

            int FootprintHeight = Perspective.getFootprintTileHeight(client, Model.getLocation(), Owner.getWorldView().getPlane(), Owner.getFootprintSize());
            if (Owner.getAnimation() != -1)
            {
                FootprintHeight -= Owner.getAnimationHeightOffset();
            }
            else
            {
                FootprintHeight -= OldAnimationHeight;
            }

            if (Model.getZ() != FootprintHeight)
            {
                Model.setZ(FootprintHeight);
            }

            // If the actual owner is extremely close to what we decided (and not custom animation), just render the owner
            if (!bUsedCustomAnimation && IsOwnerCloseEnoughToModel())
            {
                if (Model.isActive())
                {
                    Model.setActive(false);
                }
                bRenderOriginalOwnerDueToProximity = true;
            }
            else
            {
                if (!Model.isActive())
                {
                    Model.setActive(true);
                }
                bRenderOriginalOwnerDueToProximity = false;
            }

            UpdateCamera();
        }
        else
        {
            SetAllIdlePosesDefault();
            Model.setActive(false);
            if (cameraModel != null)
            {
                cameraModel.setActive(false);
            }
        }

    }
    public void Update()
    {
        UpdateFrameTimer();

        UpdateOldIdleAnimations();

        UpdateTargetStatus();

        UpdateTrueTileLocation();

        UpdateLerpDestinations();

        UpdateAnimationSelection();

        UpdateMovementType();

        ApplyTweening();

        UpdateModelVisibleState();
    }
}
