package com.truetileanimationmovement;

import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;

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
    private int CurrentFrameDelta;
    private long LastTimeMilliseconds = 0;
    private long LastAnimationTickTime = 0;
    private int MillisecondsSinceTileChange = 0;

    // Runelite object management
    private Actor Owner = null;
    public AnimationController AnimController = null;
    public RuneLiteObject Model = null;

    // Targeting
    public Actor currentTarget = null;
    private int NotInteractingTimer = 0;

    // Rendering owner
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
    private int NO_ANIMATION = -1;
    private int CurrentAnimationIDPlaying = 3;
    Set<Integer> UniqueAnimationExceptionList = new HashSet<Integer>();
    Set<Integer> UniqueAnimationLocationAndOrientationExceptionList = new HashSet<Integer>();
    private long LastTimeUniqueAnimationLocationOrientationWasUsed = 0;


    // Original true animations
    private AnimationRequestDetails CurrentAnimationRequest;
    private IdleAnimationSet OldAnimationSet = new IdleAnimationSet();
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
        UniqueAnimationExceptionList.add(829); // Eat food
        UniqueAnimationExceptionList.add(2588); // Agility
        UniqueAnimationExceptionList.add(2586); // Agility
        UniqueAnimationExceptionList.add(2583); // Agility
        UniqueAnimationExceptionList.add(714); // Teleport
        UniqueAnimationExceptionList.add(878); // Teleport
        UniqueAnimationExceptionList.add(1816); // Teleport
        UniqueAnimationExceptionList.add(1979); // Teleport
        UniqueAnimationExceptionList.add(3872); // Teleport
        UniqueAnimationExceptionList.add(13811); // Teleport
        UniqueAnimationExceptionList.add(4069); // Teleport
        UniqueAnimationExceptionList.add(4071); // Teleport
        UniqueAnimationExceptionList.add(3869); // Teleport
        UniqueAnimationExceptionList.add(3865); // Teleport

        UniqueAnimationLocationAndOrientationExceptionList.add(749); // crawl pipe
        UniqueAnimationLocationAndOrientationExceptionList.add(751); // rope swing
        UniqueAnimationLocationAndOrientationExceptionList.add(840); // climb over
        UniqueAnimationLocationAndOrientationExceptionList.add(839); // climb over
        UniqueAnimationLocationAndOrientationExceptionList.add(1252); // climb over
        UniqueAnimationLocationAndOrientationExceptionList.add(828); // climb up
        UniqueAnimationLocationAndOrientationExceptionList.add(740); // climb up
        UniqueAnimationLocationAndOrientationExceptionList.add(7134); // slide down
        UniqueAnimationLocationAndOrientationExceptionList.add(844); // crawl
        UniqueAnimationLocationAndOrientationExceptionList.add(769); // long hop
        UniqueAnimationLocationAndOrientationExceptionList.add(3057); // Wall climb
        UniqueAnimationLocationAndOrientationExceptionList.add(3058); // Wall climb
        UniqueAnimationLocationAndOrientationExceptionList.add(3067); // long jump
        UniqueAnimationLocationAndOrientationExceptionList.add(3068); // long jump
        UniqueAnimationLocationAndOrientationExceptionList.add(1115); // jump and cover
        UniqueAnimationLocationAndOrientationExceptionList.add(5708); // penguin
        UniqueAnimationLocationAndOrientationExceptionList.add(5709); // penguin
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
                // Reset animation (loop)
                InController.setFrame(0);

                // In the case an enemy animation is played, we reset this now
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
            Model.setModel(null);
            Model = null;
            client.removeRuneLiteObject(Model);

            if (Owner.getIdleRotateLeft() == NO_ANIMATION)
            {
                Owner.setIdleRotateLeft(OldAnimationSet.IdleRotateLeft);
            }

            if (Owner.getIdleRotateRight() == NO_ANIMATION)
            {
                Owner.setIdleRotateRight(OldAnimationSet.IdleRotateRight);
            }

            if (Owner.getWalkAnimation() == NO_ANIMATION)
            {
                Owner.setWalkAnimation(OldAnimationSet.WalkAnimation);
            }

            if (Owner.getWalkRotateLeft() == NO_ANIMATION)
            {
                Owner.setWalkRotateLeft(OldAnimationSet.WalkRotateLeft);
            }

            if (Owner.getWalkRotateRight() == NO_ANIMATION)
            {
                Owner.setWalkRotateRight(OldAnimationSet.WalkRotateRight);
            }

            if (Owner.getWalkRotate180() == NO_ANIMATION)
            {
                Owner.setWalkRotate180(OldAnimationSet.WalkRotate180);
            }

            if (Owner.getIdlePoseAnimation() == NO_ANIMATION)
            {
                Owner.setIdlePoseAnimation(OldAnimationSet.IdlePoseAnimation);
            }

            if (Owner.getPoseAnimation() == NO_ANIMATION)
            {
                Owner.setPoseAnimation(OldAnimationSet.PoseAnimation);
            }

            if (Owner.getRunAnimation() == NO_ANIMATION)
            {
                Owner.setRunAnimation(OldAnimationSet.RunAnimation);
            }

        }

        if (cameraModel != null)
        {
            cameraModel.setModel(null);
            cameraModel = null;
            client.removeRuneLiteObject(cameraModel);
        }
    }

    private void UpdateOldIdleAnimations()
    {
        boolean bAnyChanges = false;
        if (Owner.getIdleRotateLeft() != NO_ANIMATION &&
                OldAnimationSet.IdleRotateLeft != Owner.getIdleRotateLeft())
        {
            OldAnimationSet.IdleRotateLeft = Owner.getIdleRotateLeft();
            bAnyChanges = true;
        }

        if (Owner.getIdleRotateRight() != NO_ANIMATION &&
                OldAnimationSet.IdleRotateRight != Owner.getIdleRotateRight())
        {
            OldAnimationSet.IdleRotateRight = Owner.getIdleRotateRight();
            bAnyChanges = true;
        }

        if (Owner.getWalkAnimation() != NO_ANIMATION &&
                OldAnimationSet.WalkAnimation != Owner.getWalkAnimation())
        {
            OldAnimationSet.WalkAnimation = Owner.getWalkAnimation();
            bAnyChanges = true;
        }

        if (Owner.getWalkRotateLeft() != NO_ANIMATION &&
                OldAnimationSet.WalkRotateLeft != Owner.getWalkRotateLeft())
        {
            OldAnimationSet.WalkRotateLeft = Owner.getWalkRotateLeft();
            bAnyChanges = true;
        }

        if (Owner.getWalkRotateRight() != NO_ANIMATION &&
                OldAnimationSet.WalkRotateRight != Owner.getWalkRotateRight())
        {
            OldAnimationSet.WalkRotateRight = Owner.getWalkRotateRight();
            bAnyChanges = true;
        }

        if (Owner.getWalkRotate180() != NO_ANIMATION &&
                OldAnimationSet.WalkRotate180 != Owner.getWalkRotate180())
        {
            OldAnimationSet.WalkRotate180 = Owner.getWalkRotate180();
            bAnyChanges = true;
        }

        if (Owner.getIdlePoseAnimation() != NO_ANIMATION &&
                OldAnimationSet.IdlePoseAnimation != Owner.getIdlePoseAnimation())
        {
            OldAnimationSet.IdlePoseAnimation = Owner.getIdlePoseAnimation();
            bAnyChanges = true;
        }

        if (Owner.getPoseAnimation() != NO_ANIMATION &&
                OldAnimationSet.PoseAnimation != Owner.getPoseAnimation())
        {
            OldAnimationSet.PoseAnimation = Owner.getPoseAnimation();
            bAnyChanges = true;
        }

        if (Owner.getRunAnimation() != NO_ANIMATION &&
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
            if (OldAnimationSet.IdlePoseAnimation == 1386 ||
                    OldAnimationSet.IdlePoseAnimation == 222 ||
                    OldAnimationSet.IdlePoseAnimation == 1401 ||
                    OldAnimationSet.IdlePoseAnimation == 5668)
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
        CurrentTime = System.currentTimeMillis();
        CurrentFrameDelta = (int) (CurrentTime - LastTimeMilliseconds);
        LastTimeMilliseconds = CurrentTime;
        MillisecondsSinceTileChange += CurrentFrameDelta;
    }

    private void UpdateTrueTileLocation()
    {
        CurrentWorldPoint = Owner.getWorldLocation();

        LocalPoint LocalCurrentTrueTilePosition = LocalPoint.fromWorld(client, CurrentWorldPoint);
        if (!LocalCurrentTrueTilePosition.equals(CurrentTrueTilePosition))
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

                MillisecondsSinceTileChange = 0;
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
            if (LastLerpPosition == null)
            {
                NextLerpPosition = RequestedLerpPoint;

                LastLerpPosition = NextLerpPosition;
                LastLerpPositionWorldPoint = WorldPoint.fromLocal(client, LastLerpPosition);

                NextLerpPositionWorldPoint = CurrentWorldPoint;
            }
            if (NextLerpPosition == null)
            {
                NextLerpPosition = RequestedLerpPoint;
            }

            if (NextLerpPositionWorldPoint == null)
            {
                NextLerpPositionWorldPoint = CurrentWorldPoint;
            }
            if (!NextLerpPosition.equals(RequestedLerpPoint))
            {
                // Try all planes and use whichever one is the closest
                double ClosestPlaneDistance = 10000000;
                LocalPoint NextLerpPoint = null;
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

                // Fallback to quick and dirty move
                if (NextLerpPoint != null &&
                        !((Math.abs(NextLerpPoint.getX() - LastLerpPosition.getX()) <= 1024) &&
                                (Math.abs(NextLerpPoint.getY() - LastLerpPosition.getY()) <= 1024)))
                {
                    LastLerpPosition = NextLerpPoint;
                    LastLerpPositionWorldPoint = WorldPoint.fromLocal(client, LastLerpPosition);
                }

                else if (NextLerpPoint != null &&
                        (Math.abs(NextLerpPoint.getX() - RequestedLerpPoint.getX()) <= 1024) &&
                        (Math.abs(NextLerpPoint.getY() - RequestedLerpPoint.getY()) <= 1024))
                {
                    LastLerpPosition = NextLerpPosition;
                    LastLerpPositionWorldPoint = WorldPoint.fromLocal(client, LastLerpPosition);
                }
                // Lerp point does not exist! Teleport or something like that
                else if (IsPlayerOwner())
                {
                    LastLerpPosition = RequestedLerpPoint;
                    LastLerpPositionWorldPoint = WorldPoint.fromLocal(client, LastLerpPosition);
                    LastTrueTilePosition = CurrentTrueTilePosition;

                    // Teleport fallback (Not covered by animation in plugin)
                    if (IsPlayerOwner() && CurrentTime - overlay.LastTimeTeleport >= 1800)
                    {
                        overlay.LastTimeTeleport = System.currentTimeMillis() - 600; // (We are at this location already, offset expected 1 tick animation time)
                        overlay.bShouldPlayTeleportAnimation = false; // Fallback, do not play animation
                    }
                }

                NextLerpPosition = RequestedLerpPoint;

                NextLerpPositionWorldPoint = CurrentWorldPoint;

                MillisecondsSinceTileChange = 0;
                bNewTileMovementStarted = true;
                bLastMovementDestinationPotentiallyDirty = true;
            }

            // Decay bLastMovementDestinationPotentiallyDirty flag
            if (MillisecondsSinceTileChange > 5)
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
        if (MillisecondsSinceTileChange < 600 ) // 1 tick
        {

            // Analyze the type of movement we're doing
            CurrentAnimationRequest = AnimationRequestMoveset.GetDefaultIdleMoveAnimationRequest(config);

            // Only do special moves if actually attacking an NPC
            // TODO: Disable experimental feature for now
            boolean bSpecialMoveAnimation = false;// IsPlayerOwner() && !(bTooFarToSpecialMove || (devConfig.SpecialMovesOnlyInCombat() && currentTarget == null));

            // Did not click within the last time
            if (CurrentTime - LastTimeRecentlyClicked > 1199)
            {
                FramesSinceIdle = 0;
            }

            // Just teleported
            if (IsPlayerOwner() && CurrentTime - overlay.LastTimeTeleport < 1800)
            {
                if (overlay.bShouldPlayTeleportAnimation && bIsDefaultHumanAnimationSet)
                {
                    if (CurrentTime - overlay.LastTimeTeleport < 600) // Blend with the first tick
                    {
                        // Handle normal walking
                        CurrentAnimationRequest = AnimationRequestDetails.NewObject(AnimationRequestMovesetCache.GetAnimationRequestMovesetFromAnimationSet(OldAnimationSet, config).MovesetArray[2 + RotatedDirectionX][2 + RotatedDirectionY]);
                        CurrentAnimationRequest.bShouldTeleportToLocation = false;
                    }
                    else
                    {
                        CurrentAnimationRequest.bShouldTeleportToLocation = true;
                        CurrentAnimationRequest.AnimationToPlay = 715; // Teleport in
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
                        CurrentAnimationRequest.AnimationToPlay = OldAnimationSet.IdlePoseAnimation;
                    }
                    else
                    {
                        int TempRotatedDirectionX = Math.max(-2, Math.min(2, Math.toIntExact(Math.round((DirectionX * cos - DirectionY * sin) / 128.0))));
                        int TempRotatedDirectionY = Math.max(-2, Math.min(2, Math.toIntExact(Math.round((DirectionX * sin + DirectionY * cos) / 128.0))));

                        CurrentAnimationRequest = AnimationRequestDetails.NewObject(AnimationRequestMovesetCache.GetAnimationRequestMovesetFromAnimationSet(OldAnimationSet, config).MovesetArray[2 + TempRotatedDirectionX][2 + TempRotatedDirectionY]);
                    }
                    bShouldUseTrueLocationOrientation = true;
                    CurrentAnimationRequest.bShouldTeleportToLocation = true;
                }
                CurrentAnimationRequest.bUseLinearTween = true;
                CurrentAnimationRequest.MovementSpeedMultiplier = 1.0;
                CurrentAnimationRequest.StartingFrame = 0;
                CurrentAnimationRequest.AnimationSpeed = 1;
            }
            else if (bCurrentlyWooxWalking && config.AllowWooxWalkDetection() && bIsDefaultHumanAnimationSet)
            {
                // Handle woox walking
                CurrentAnimationRequest = AnimationRequestDetails.NewObject(AnimationRequestMovesetCache.GetAnimationRequestMovesetFromUniqueKey(OldAnimationSet,"WooxWalk", config).MovesetArray[2 + RotatedDirectionX][2 + RotatedDirectionY]);

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
                CurrentAnimationRequest = AnimationRequestDetails.NewObject(AnimationRequestMovesetCache.GetAnimationRequestMovesetFromUniqueKey(OldAnimationSet,"TickPerfectMovement", config).MovesetArray[2 + RotatedDirectionX][2 + RotatedDirectionY]);
            }
            else
            {
                // Special move activated
                if (bSpecialMoveAnimation && bIsDefaultHumanAnimationSet)
                {
                    // Handle normal walking
                    CurrentAnimationRequest = AnimationRequestDetails.NewObject(AnimationRequestMovesetCache.GetAnimationRequestMovesetFromUniqueKey(OldAnimationSet,"SpecialMoves", config).MovesetArray[2 + RotatedDirectionX][2 + RotatedDirectionY]);
                }
                else
                {
                    // Handle normal walking
                    CurrentAnimationRequest = AnimationRequestDetails.NewObject(AnimationRequestMovesetCache.GetAnimationRequestMovesetFromAnimationSet(OldAnimationSet, config).MovesetArray[2 + RotatedDirectionX][2 + RotatedDirectionY]);
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
                CurrentAnimationRequest.AnimationToPlay = 2106; // Jig
            }
            else if (LastNPCCombatLevel > 200)
            {
                // 2,387->Fist pump
                CurrentAnimationRequest.AnimationToPlay = 866; // Dance
            }
            else if (LastNPCCombatLevel > 150)
            {
                // 2,387->Fist pump
                CurrentAnimationRequest.AnimationToPlay = 8917; // Flex
            }
            else if (LastNPCCombatLevel > 100)
            {
                // 2,387->Fist pump
                CurrentAnimationRequest.AnimationToPlay = 862; // Cheer
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
            CurrentAnimationRequest = AnimationRequestMoveset.GetDefaultIdleMoveAnimationRequest(config);
            CurrentAnimationRequest.bUseLinearTween = true;
            CurrentAnimationRequest.MovementSpeedMultiplier = 1.0;
            CurrentAnimationRequest.AnimationSpeed = 1;
            CurrentAnimationRequest.StartingFrame = 0;
            ChangeLastLerpPointForRotation();
            int ShortestAngle = ShortestAngleDifference(CurrentOrientation, TargetOrientation);
            if (ShortestAngle >= 10)
            {;
                CurrentAnimationRequest.AnimationToPlay = OldAnimationSet.IdleRotateRight;
            }
            else if (ShortestAngle <= -10)
            {
                CurrentAnimationRequest.AnimationToPlay = OldAnimationSet.IdleRotateLeft;
            }
            else
            {;
                CurrentAnimationRequest.AnimationToPlay = OldAnimationSet.IdlePoseAnimation;
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
            CurrentAnimationIDPlaying = 0; // Reset animation
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

    private void ApplyTweening()
    {
        // 600ms a tick, interpolate between true local point and last true tile position
        double TweenValue = 0;
        if (CurrentAnimationRequest.bShouldTeleportToLocation)
        {
            TweenValue = 1.0;
        }
        else if (CurrentAnimationRequest.bUseLinearTween)
        {
            TweenValue = linearTween(0L, (long) (600 / CurrentAnimationRequest.MovementSpeedMultiplier), MillisecondsSinceTileChange);
        }
        else
        {
            TweenValue = quadraticTween(0L, (long) (600 / CurrentAnimationRequest.MovementSpeedMultiplier), MillisecondsSinceTileChange);
        }

        NewLocalPointToDraw = new LocalPoint((int) (LastLerpPosition.getX() + (NextLerpPosition.getX() - LastLerpPosition.getX()) * TweenValue),
                (int) (LastLerpPosition.getY() + (NextLerpPosition.getY() - LastLerpPosition.getY()) * TweenValue),
                LastLerpPosition.getWorldView());

        if (currentTarget != null)
        {
            TargetOrientation = (getOrientationBetweenPoints(NewLocalPointToDraw.getX(), NewLocalPointToDraw.getY(), currentTarget.getLocalLocation().getX(), currentTarget.getLocalLocation().getY(), 90));
        }
        else if (Owner.getAnimation() != -1 || // Not walking animation, face towards wherever the client is
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
                    cameraModel.setModel(null);
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

                CurrentArrowPointingAnimationFrame += CurrentFrameDelta * config.ArrowPointingAnimationSpeed() * 0.0001;
                int AnimationOffsetStrength = (int) (Math.sin(CurrentArrowPointingAnimationFrame) * config.ArrowPointingAnimationStrength());

                LocalPoint CameraFinalLocation = new LocalPoint(
                        (int) (Owner.getLocalLocation().getX() + DirectionVectorX * AnimationOffsetStrength)
                        , (int) (Owner.getLocalLocation().getY() + DirectionVectorY * AnimationOffsetStrength), Owner.getLocalLocation().getWorldView());

                cameraModel.setLocation(CameraFinalLocation, Math.min(4, Owner.getWorldView().getPlane() + config.CameraModelHeight()));

                cameraModel.setActive(true); // fails most of the time
            }
            else if (cameraModel != null)
            {
                cameraModel.setModel(null);
            }
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
            if (!bAttemptToRenderOwner)
            {
                Owner.setIdleRotateLeft(NO_ANIMATION);
                Owner.setIdleRotateRight(NO_ANIMATION);
                Owner.setWalkAnimation(NO_ANIMATION);
                Owner.setWalkRotateLeft(NO_ANIMATION);
                Owner.setWalkRotateRight(NO_ANIMATION);
                Owner.setWalkRotate180(NO_ANIMATION);
                Owner.setIdlePoseAnimation(NO_ANIMATION);
                Owner.setRunAnimation(NO_ANIMATION);
                Owner.setPoseAnimation(NO_ANIMATION);
            }

            // Animation has opted to use the true location/orientation (probably agility obstacle)
            int OwnerAnimation = Owner.getAnimation();
            bShouldUseTrueLocationOrientation |= (OwnerAnimation != -1 &&
                    currentTarget == null &&
                    UniqueAnimationLocationAndOrientationExceptionList.contains(OwnerAnimation));

            if (bShouldUseTrueLocationOrientation || (CurrentTime - LastTimeUniqueAnimationLocationOrientationWasUsed) < 600) // A little bit of time before going to other animation
            {
                Model.setLocation(Owner.getLocalLocation(), Owner.getWorldView().getPlane());
                Model.setOrientation(Owner.getOrientation());
                CurrentOrientation = Owner.getOrientation();

                if (bShouldUseTrueLocationOrientation)
                {
                    LastTimeUniqueAnimationLocationOrientationWasUsed = CurrentTime;
                }
            }
            else
            {
                Model.setLocation(NewLocalPointToDraw, Owner.getWorldView().getPlane());

                // Find best direction to go, offset by 10000 for comparison to avoid negatives
                int ShortestAngle = ShortestAngleDifference(CurrentOrientation, TargetOrientation);

                // Need to rotate to our target rotation smoothly
                double AdjustedOrientationSpeed = CurrentAnimationRequest.OrientationSpeed * (CurrentFrameDelta / 16.667);// Speed value centered at 60FPS
                if (ShortestAngle > 0)
                {
                    CurrentOrientation += Math.min(ShortestAngle, AdjustedOrientationSpeed);
                }
                else if (ShortestAngle != 0)
                {
                    CurrentOrientation -= Math.min(-ShortestAngle, AdjustedOrientationSpeed);
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
                Model.setOrientation(CurrentOrientation);
            }


            if (CurrentAnimationIDPlaying != CurrentAnimationRequest.AnimationToPlay)
            {
                CurrentAnimationIDPlaying = CurrentAnimationRequest.AnimationToPlay;
                AnimController.setAnimation(client.loadAnimation(CurrentAnimationIDPlaying));
                AnimController.setFrame(CurrentAnimationRequest.StartingFrame);
            }

            if (CurrentTime - LastAnimationTickTime >= 17) // 17ms per frame->60FPS
            {
                LastAnimationTickTime = CurrentTime;
                int CurrentFrame = AnimController.getFrame();
                if (CurrentFrame >= CurrentAnimationRequest.EndingFrame) {
                    AnimController.setFrame(CurrentAnimationRequest.EndingFrame);
                } else {
                    AnimController.tick(CurrentAnimationRequest.AnimationSpeed);
                }
            }

            // Do not lerp on unique animations outside of combat
            if (Owner.getAnimation() != -1 &&
                    currentTarget == null &&
                    (!UniqueAnimationExceptionList.contains(Owner.getAnimation()) || CurrentAnimationIDPlaying == OldAnimationSet.IdlePoseAnimation))
            {
                Model.setModel(client.mergeModels(Owner.getModel()));
            }
            else
            {
                Model.setModel(client.mergeModels(AnimController.animate(Owner.getModel())));
            }
            Model.getModel().setModelHeight(Owner.getModel().getModelHeight());
            Model.getModel().setUvBufferOffset(Owner.getModel().getUvBufferOffset());
            Model.getModel().setBufferOffset(Owner.getModel().getBufferOffset());
            Model.getModel().setSceneId(Owner.getModel().getSceneId());

            int FootprintHeight = Perspective.getFootprintTileHeight(client, Model.getLocation(), Owner.getWorldView().getPlane(), Owner.getFootprintSize());
            if (Owner.getAnimation() != -1)
            {
                FootprintHeight -= Owner.getAnimationHeightOffset();
            }
            else
            {
                FootprintHeight -= OldAnimationHeight;
            }

            Model.setZ(FootprintHeight);
            Model.setActive(true);

            UpdateCamera();
        }
        else
        {
            if (Owner.getIdleRotateLeft() == NO_ANIMATION)
            {
                Owner.setIdleRotateLeft(OldAnimationSet.IdleRotateLeft);
            }

            if (Owner.getIdleRotateRight() == NO_ANIMATION)
            {
                Owner.setIdleRotateRight(OldAnimationSet.IdleRotateRight);
            }

            if (Owner.getWalkAnimation() == NO_ANIMATION)
            {
                Owner.setWalkAnimation(OldAnimationSet.WalkAnimation);
            }

            if (Owner.getWalkRotateLeft() == NO_ANIMATION)
            {
                Owner.setWalkRotateLeft(OldAnimationSet.WalkRotateLeft);
            }

            if (Owner.getWalkRotateRight() == NO_ANIMATION)
            {
                Owner.setWalkRotateRight(OldAnimationSet.WalkRotateRight);
            }

            if (Owner.getWalkRotate180() == NO_ANIMATION)
            {
                Owner.setWalkRotate180(OldAnimationSet.WalkRotate180);
            }

            if (Owner.getIdlePoseAnimation() == NO_ANIMATION)
            {
                Owner.setIdlePoseAnimation(OldAnimationSet.IdlePoseAnimation);
            }

            if (Owner.getPoseAnimation() == NO_ANIMATION)
            {
                Owner.setPoseAnimation(OldAnimationSet.PoseAnimation);
            }

            if (Owner.getRunAnimation() == NO_ANIMATION)
            {
                Owner.setRunAnimation(OldAnimationSet.RunAnimation);
            }

            Model.setModel(null);
            if (cameraModel != null)
            {
                cameraModel.setModel(null);
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
