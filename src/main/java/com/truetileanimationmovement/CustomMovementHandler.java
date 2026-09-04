package com.truetileanimationmovement;

import com.truetileanimationmovement.movement.SpecialAnimationPreset;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.AnimationID;

import javax.inject.Inject;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.IntPredicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CustomMovementHandler
{
    private static final Logger log =
            LoggerFactory.getLogger(CustomMovementHandler.class);
    private static final int NO_ANIMATION = -1;
    private static final int BASE_MOVEMENT_TWEEN_MILLIS = 600;
    private static final int ORIENTATION_UNITS = 2048;
    private static final int ORIENTATION_MASK = ORIENTATION_UNITS - 1;
    // [TMA-CONTINUOUS-MOVEMENT-SPEED] A multiplier cannot make the visible
    // model move faster than RuneScape publishes collision-valid route steps
    // indefinitely without either waiting at the endpoint or predicting a
    // path through walls/objects. Keep the published 600 ms segment clock and
    // use the user-facing multiplier only as a bounded within-segment lead.
    // The cap keeps the C1 progress curve strictly monotonic even when a user
    // enters a value above the range that can be represented safely.
    private static final double MAX_CONTINUOUS_MOVEMENT_LEAD_MULTIPLIER =
            1.75;
    private static final double CONTINUOUS_MOVEMENT_LEAD_STRENGTH_SCALE =
            3.0 * Math.sqrt(3.0);
    // RuneScape rebuilds the scene as the player crosses the 16-tile margin
    // on either side of the 104-tile scene. Route data can disappear for the
    // last fraction of a tick at exactly that boundary.
    private static final int SCENE_REBUILD_MARGIN_TILES = 16;
    private static final int SCENE_BOUNDARY_BRIDGE_MAX_MILLIS = 180;
    private static final int SCENE_BOUNDARY_BRIDGE_MAX_DISTANCE =
            Perspective.LOCAL_TILE_SIZE / 2;
    // The Player-Owned House instance is allocated from these two map
    // regions. RuneLite does not currently expose gameval RegionID constants
    // for them, so keep the raw values named and isolated here.
    private static final int POH_INSTANCE_REGION_WEST = 8046;
    private static final int POH_INSTANCE_REGION_EAST = 8047;
    private static final int SCENE_PRESENTATION_MAX_FRAME_DELTA_MILLIS = 34;
    private static final int SCENE_PRESENTATION_MAX_TIME_DEBT_MILLIS = 600;
    private static final int SCENE_PRESENTATION_DEBT_PAYBACK_DIVISOR = 5;
    // [TMA-UNFINISHED-ROUTE-ANIMATION-CONTINUITY] Client/game tick scheduling
    // is not perfectly aligned with overlay rendering. The 2026-07-30 traces
    // captured 72 cases where the custom model stayed ready, active and
    // authoritative, but an unfinished route selected idle/turn immediately
    // after its 600 ms segment expired. Most next segments arrived at
    // 608-619 ms. A later area-transition capture found five valid routes
    // selecting idle at 810-816 ms, including one clean post-load case where
    // the next authoritative segment arrived about 22 ms later. The next
    // validation run found five more valid publications at 852-858 ms.
    //
    // Keep only the locomotion animation alive during this bounded feed gap,
    // and only while the yellow-walk continuity arm remains active. Red-click
    // interactions cancel that arm. ApplyTweening remains clamped to the
    // completed segment, so this cannot extrapolate through scenery or move
    // the model onto an interaction object.
    private static final int MOVEMENT_ANIMATION_CONTINUITY_GRACE_MILLIS = 300;
    // [TMA-STOP-FACING-SETTLE] Reaching the final tile and settling the
    // native actor's facing are separate client events. Give the hidden
    // actor one game tick with no orientation changes before a later target
    // orientation is treated as a genuinely new facing command.
    private static final int WALK_STOP_NATIVE_FACING_SETTLE_MILLIS =
            Constants.GAME_TICK_LENGTH;
    // [TMA-TELEPORT] Restored original teleport presentation state. A genuine
    // teleport is identified by the teleport animation the client publishes in
    // onGameTick. Timestamps are compared in System.nanoTime() domain like the
    // original so the millisecond-based frame timer never scales the windows.
    private static final long TELEPORT_ANIMATION_WINDOW_NANOS =
            1_800_000_000L; // 1.8s: full teleport-in presentation window
    private static final long TELEPORT_ANIMATION_FIRST_TICK_NANOS =
            600_000_000L; // 0.6s: blend with the true-tile movement first tick

    private long GetTeleportElapsedNanoseconds()
    {
        return System.nanoTime() - overlay.LastTimeTeleport;
    }
    // General
    private final Client client;
    private final TrueTileMovementPlugin plugin;
    private final TrueTileMovementConfig config;
    // [TMA-VALID-POSE-FRAME-PUBLICATION] RuneLite can briefly publish pose
    // frame -1 at a locomotion handoff while leaving the animation ID intact.
    // Remember the last drawable frame for that same pose so Owner.getModel()
    // is never asked to build the visible character from an invalid frame.
    private int LastValidOwnerPoseAnimation = NO_ANIMATION;
    private int LastValidOwnerPoseFrame = 0;
    // [TMA-ENDPOINT-IDLE-PRESENTATION] The game's native player-model builder
    // can compose a correct idle keyframe with the current appearance. Keep a
    // detached copy while the visible player is spatially stopped and advance
    // it from a controller used only as an authored-frame clock. The clock is
    // never attached to, or used to transform, the detached mesh: every frame
    // is built once by Owner.getModel(), so hidden locomotion cannot leak into
    // the visible endpoint and the old synthetic-controller bind pose cannot
    // reappear.
    private net.runelite.api.Model HeldEndpointIdleModel = null;
    private PlayerAppearanceKey HeldEndpointIdleAppearance = null;
    private int HeldEndpointIdleAnimation = NO_ANIMATION;
    private int HeldEndpointIdleFrame = -1;
    private int EndpointIdlePresentationStartGameCycle = -1;
    private AnimationController EndpointIdleFrameClock = null;
    private int EndpointIdleFrameClockAnimation = NO_ANIMATION;
    private int EndpointIdleFrameClockGameCycle = -1;
    private int EndpointIdleDesiredFrame = 0;
    private boolean bEndpointIdlePresentationActive = false;
    private boolean bUsingHeldEndpointIdleModel = false;
    private boolean bEndpointIdlePoseWasDisplayed = false;
    private boolean bEndpointIdleFrameNeedsPublication = false;
    // Animation Smoothing can only interpolate the native actor's private
    // pose clock. While the hidden owner catches up, temporarily map every
    // movement selector to the same idle sequence so that clock keeps running
    // without exposing a locomotion pose. Restoring the selectors never
    // touches the pose ID/frame, so the stationary handoff keeps its phase.
    private boolean bEndpointNativeIdlePoseOverrideActive = false;
    // RuneLite's native actor update chooses from its movement selectors every
    // client cycle. During ordinary custom locomotion the directional movement
    // selectors are temporarily mapped to the already-selected pose so the
    // hidden actor cannot replace (and then make us restore) that pose every
    // 20 ms. The native idle selector remains semantically truthful. Keeping
    // the actual plugin-owned ID lets UpdateOldIdleAnimations still observe a
    // genuine equipment/movement-set publication while the override is active.
    private int NativePoseSelectorOverrideAnimation = NO_ANIMATION;
    private boolean bEndpointNativeHandoffReadyThisFrame = false;
    // A successful native-geometry handoff owns this completed segment until
    // real movement or a scene reset. A one-frame readiness flag is insufficient:
    // clearing the captured mesh also clears its frame identity, which would
    // otherwise make endpoint presentation re-arm two renders later.
    private boolean bEndpointNativeHandoffEstablished = false;
    private boolean bLocomotionResetPendingAfterEndpointIdle = false;
    TrueMovementOverlay overlay;

    // Time management
    private long CurrentTime;
    public int CurrentFrameDelta;
    private long LastTimeMilliseconds = 0;
    // AnimationController.tick() is expressed in RuneLite client-game
    // cycles, not render frames. Keeping this clock on the client cycle makes
    // controller-driven special/action animations independent of FPS while
    // AnimationController.animate() remains available to the native
    // animation interpolation filter at render time.
    private int LastAnimationGameCycle = -1;
    private long LastGcCollectionTimeMillis = 0;
    private int MillisecondsSinceTileChange = 1000;
    // Diagnostic provenance for the latest authoritative route segment. The
    // wall-clock tween can expire a few render updates before the next player
    // position is published; recording RuneLite's native clocks distinguishes
    // that ordering seam from a genuinely missed/late route update.
    private int MovementSegmentPublicationTick = -1;
    private int MovementSegmentPublicationGameCycle = -1;
    // Runelite object management
    public Actor Owner = null;
    public AnimationController AnimController = null; // Used to blend additional animations
    public RuneLiteObject Model = null;
    // Actor-attached spot animations are submitted separately from
    // Player.getModel(). Preserve each native effect as its own renderable when
    // the native player is suppressed, changing only its scene anchor to the
    // custom player's transform. ActorSpotAnim.getModel() already includes the
    // authored frame and height offset, so it must not be transformed again.
    private final Map<Long, RuneLiteObject> MirroredActorSpotModels =
            new HashMap<>();
    private final Set<Long> PublishedActorSpotSlots = new HashSet<>();

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
    // [TMA-MOTION-CONTINUITY] Keep the last displayed world-space point so a
    // scene rebuild can restore the visible sub-tile position, not just the
    // hidden actor's current tile.
    private WorldPoint LastRenderedWorldPoint;
    private int LastRenderedWorldOffsetX = 0;
    private int LastRenderedWorldOffsetY = 0;

    private boolean bSceneRebasePending = false;
    private int LastInitializedSceneGeneration = -1;
    // [TMA-SCENE-LOAD-CONTINUITY] A region rebuild can consume part of the
    // current movement tick. The first route change after the rebuild must
    // start at the point actually drawn, not an endpoint the recovery tween
    // has not reached yet.
    private boolean bSceneRecoveryRetargetPending = false;
    // [TMA-SCENE-LOAD-CONTINUITY] Derived every frame. This is deliberately
    // limited to an outward yellow-click route at the scene rebuild margin;
    // red-click interactions and ordinary movement can never extrapolate.
    private boolean bSceneBoundaryBridgeActive = false;
    // Snapshot the last presentation which was genuinely progressing along a
    // non-zero segment. LOADING suspends Update(), so this survives the gap
    // without confusing route-gap locomotion at a clamped endpoint for motion.
    private boolean bRetainedSceneMovementSegmentInProgress = false;
    private int RetainedMovementProgressTick = -1;
    private int RetainedMovementProgressGameCycle = -1;
    private boolean bFreshMovementPublicationBoundaryBridgeActive = false;
    private int FreshMovementPublicationBoundaryGameCycle = -1;
    // A non-zero post-scene recovery is real visible movement even though the
    // replacement scene is still awaiting its first authoritative route point.
    // Let only that proven recovery use the ordinary bounded route-gap grace;
    // zero-distance waits and fresh clicks retain stable endpoint idle.
    private boolean bPostSceneRecoveryRouteGapEligible = false;
    // [TMA-SCENE-PRESENTATION-CLOCK] A scene can be prepared at
    // BeforeRender and then spend ~200 ms finishing its first drawable frame.
    // Defer that missing time and repay it gradually instead of applying the
    // whole interval as one position/orientation jump on the following frame.
    private boolean bScenePresentationClockActive = false;
    private boolean bSceneLoadFramePresentationPending = false;
    private int ScenePresentationTimeDebtMilliseconds = 0;
    // Preserve sub-millisecond-share debt entitlement across render frames.
    // Rounding each frame upward makes 5/6 ms frames repay 1/2 ms, producing
    // an alternating recovery velocity at high FPS instead of a steady 20%.
    private int ScenePresentationDebtPaybackRemainder = 0;
    // When a newer route point arrives while presentation is intentionally
    // behind, preserve the established movement velocity by extending the
    // tween duration rather than accelerating toward the newest point.
    private double SceneRecoveryBaseVelocity = 0;
    private long SceneRecoveryTweenDurationOverride = 0;
    // The native owner is deliberately shown while a replacement
    // RuneLiteObject is being prepared. Once that frame is presented, its
    // position becomes the authoritative visual anchor for the rebase.
    private boolean bNativeSceneLoadHandoffPresented = false;
    private boolean bLastSceneRebaseUsedNativeHandoffAnchor = false;
    // A POH first publishes a temporary arrival coordinate and can replace it
    // later with the constructed portal room's actual coordinate. Keep this
    // armed for the whole destination scene: a short timeout or an ordinary
    // one-tile update can occur before that replacement arrives.
    private boolean bPohArrivalCoordinateGuardArmed = false;
    private int PohArrivalCoordinateGuardSceneGeneration = -1;
    // Cleanup can run transiently while a first-time POH instance is still
    // being assembled. Remember only an explicit user release so that such a
    // cleanup can safely re-arm the destination-scene guard.
    private int PohArrivalGuardReleasedSceneGeneration = -1;
    // Animation Handling
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
    // [TMA-TURN-CONTINUITY] RuneLiteObject orientation is integer-valued, but
    // the configured render-frame turn step is fractional at high FPS. Keep
    // that sub-unit phase here so it is not discarded on every publication.
    private double PreciseCurrentOrientation = 0;
    // [TMA-STOP-FACING] Yellow Walk clicks may leave the hidden native player
    // finishing its route behind the visible model. During that catch-up only,
    // retain the visible model's final movement direction instead of adopting
    // the native actor's stale orientation and spinning at the destination.
    //
    // State meanings are intentionally kept separate:
    //   Armed       = a yellow-click route is eligible for the hold.
    //   Observed    = visible movement actually started for that route.
    //   Pending     = a yellow route is waiting for an authoritative visible
    //                 segment, either after a re-click or a scene rebuild.
    //   AwaitingSegment = a destination is published before its authoritative
    //                 segment, after either a fresh click or a scene rebuild;
    //                 do not treat that delay as an animation-only route gap.
    //   Preserve    = catch-up ended; keep the released facing until native
    //                 code issues a different orientation command.
    //   HoldThisFrame = the derived per-frame decision used by rendering.
    private boolean bWalkStopFacingHoldArmed = false;
    private boolean bWalkMovementObserved = false;
    private boolean bWalkStartPendingDuringCatchUp = false;
    private boolean bWalkSegmentAwaitingMovement = false;
    // A new yellow click can arrive while the old route is already using its
    // proven, bounded animation-only feed-gap bridge. Let only that existing
    // bridge finish its original deadline; the new click receives no fresh
    // grace and an ordinary mid-segment re-click still settles to idle.
    private boolean bPendingWalkInheritedActiveRouteGap = false;
    // Provenance for the RuneLiteObject's current base mesh. Existence alone
    // is insufficient: a completed action/spot model must never be retained as
    // the stopped fallback while a yellow segment is pending.
    private boolean bPublishedStoppedGeometrySafe = false;
    private boolean bPreserveReleasedWalkFacing = false;
    private boolean bHoldWalkStopFacingThisFrame = false;
    private boolean bNativeWalkFacingSettled = false;

    // [TMA-YELLOW-RECLICK-ROUTE-GRACE] Route-gap animation continuity belongs
    // only to the yellow click which produced the displayed segment. A newer
    // click can publish its destination before its first movement segment;
    // letting the old segment inherit that destination produces up to 300 ms
    // of locomotion at a stationary endpoint.
    private long WalkClickRevision = 0;
    private long WalkClickRevisionAtMovementSegment = 0;
    private int NativeTargetOrientationAtWalkFacingRelease = 0;
    private int NativeCurrentOrientationAtWalkFacingRelease = 0;
    private long LastNativeWalkFacingChangeTime = 0;


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
    private boolean bCameraModelNeedsPublish = false;
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
        // [TMA-TELEPORT-CORRECT] Only genuine teleport animations are treated
        // as unique lerped animations. The original list also contained
        // ZAROS_VERTICAL_CASTING (1979, Ancient Magick cast) and
        // ARCEUUS_NECROMANCY_ANIM (3865, Arceuus spell cast), which are
        // ordinary spell casts and falsely armed the teleport-in snap during
        // PvP combat. Combat spells (entangle, fire surge, Flames of Zamorak,
        // Claws of Guthix, etc.) were never in this list and are unaffected.
        UniqueAnimationExceptionList.add(AnimationID.HUMAN_CASTTELEPORT); // Teleport. 714
        UniqueAnimationExceptionList.add(AnimationID.AHOY_ECTO_TELEPORT); // Teleport. 878
        UniqueAnimationExceptionList.add(AnimationID.HUMAN_TELEPORT_OTHER_IMPACT); // Teleport. 1816
        UniqueAnimationExceptionList.add(AnimationID.TELEPORT_NARDAH_HUMAN); // Teleport. 3872
        UniqueAnimationExceptionList.add(AnimationID.HUMAN_COWBOSS_TELEPORT); // Teleport. 13811
        UniqueAnimationExceptionList.add(AnimationID.POH_SMASH_MAGIC_TABLET); // Teleport. 4069
        UniqueAnimationExceptionList.add(AnimationID.POH_ABSORB_TABLET_TELEPORT); // Teleport. 4071
        UniqueAnimationExceptionList.add(AnimationID.TELEPORT_CABBAGE_HUMAN); // Teleport. 3869
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

    static double GetConfiguredMovementLeadMultiplier(
            double ConfiguredMultiplier)
    {
        if (!Double.isFinite(ConfiguredMultiplier))
        {
            return 1.0;
        }

        return Math.min(
                Math.max(1.0, ConfiguredMultiplier),
                MAX_CONTINUOUS_MOVEMENT_LEAD_MULTIPLIER);
    }

    static long GetRequestMovementTweenDurationMilliseconds(
            double RequestMultiplier)
    {
        // Animation-request multipliers are authored choreography for the
        // optional leap/Woox animations. Preserve their established travel
        // timing; only the user-facing config multiplier is reinterpreted as
        // a continuous within-segment lead.
        if (!Double.isFinite(RequestMultiplier) ||
                RequestMultiplier <= 1.0)
        {
            return BASE_MOVEMENT_TWEEN_MILLIS;
        }
        return Math.max(
                1L,
                (long) (BASE_MOVEMENT_TWEEN_MILLIS /
                        RequestMultiplier));
    }

    static boolean ShouldApplyContinuousMovementSpeedLead(
            boolean ShouldTeleport,
            boolean SceneBoundaryBridgeActive,
            boolean SceneRebasePending,
            long SceneRecoveryDurationOverride,
            boolean SceneRecoveryRetargetPending,
            boolean ScenePresentationClockActive,
            boolean SceneLoadFramePresentationPending)
    {
        return !ShouldTeleport &&
                !SceneBoundaryBridgeActive &&
                !SceneRebasePending &&
                SceneRecoveryDurationOverride == 0 &&
                !SceneRecoveryRetargetPending &&
                !ScenePresentationClockActive &&
                !SceneLoadFramePresentationPending;
    }

    static boolean ShouldUseAuthoritativeTeleportAction(
            int OwnerAnimation)
    {
        // The live action is sufficient authority. Do not wait for the
        // game-tick detector to arm the wider arrival window: a render between
        // those events must still show the teleport, never locomotion.
        return TrueTileMovementPlugin.IsGenuineTeleportAnimation(
                OwnerAnimation);
    }

    static boolean ShouldUseIdlePoseDuringTeleportLeadIn(
            boolean TeleportPresentationActive,
            long TeleportElapsedNanoseconds)
    {
        return TeleportPresentationActive &&
                TeleportElapsedNanoseconds >= 0 &&
                TeleportElapsedNanoseconds <
                        TELEPORT_ANIMATION_FIRST_TICK_NANOS;
    }

    static double ApplyContinuousMovementSpeedLead(
            double BaseProgress,
            double CombinedMultiplier)
    {
        if (!Double.isFinite(BaseProgress))
        {
            return 0.0;
        }
        double Progress = Math.max(
                0.0,
                Math.min(1.0, BaseProgress));
        if (!Double.isFinite(CombinedMultiplier) ||
                CombinedMultiplier <= 1.0 ||
                Progress == 0.0 ||
                Progress == 1.0)
        {
            return Progress;
        }

        double EffectiveMultiplier = Math.min(
                CombinedMultiplier,
                MAX_CONTINUOUS_MOVEMENT_LEAD_MULTIPLIER);
        double CurveStrength =
                (EffectiveMultiplier - 1.0) *
                        CONTINUOUS_MOVEMENT_LEAD_STRENGTH_SCALE;
        double Remaining = 1.0 - Progress;
        double LedProgress = Progress +
                CurveStrength *
                        Progress * Progress *
                        Remaining * Remaining;
        return Math.max(0.0, Math.min(1.0, LedProgress));
    }

    static boolean IsSceneBoundaryExitSegment(
            LocalPoint From,
            LocalPoint To,
            LocalPoint Destination)
    {
        if (From == null ||
                To == null ||
                Destination == null ||
                From.getWorldView() != To.getWorldView() ||
                To.getWorldView() != Destination.getWorldView())
        {
            return false;
        }

        int LowBoundary =
                SCENE_REBUILD_MARGIN_TILES *
                        Perspective.LOCAL_TILE_SIZE +
                        Perspective.LOCAL_TILE_SIZE / 2;
        int HighBoundary =
                (Constants.SCENE_SIZE -
                        SCENE_REBUILD_MARGIN_TILES - 1) *
                        Perspective.LOCAL_TILE_SIZE +
                        Perspective.LOCAL_TILE_SIZE / 2;
        int DirectionX = To.getX() - From.getX();
        int DirectionY = To.getY() - From.getY();

        return (To.getX() == LowBoundary &&
                        DirectionX < 0 &&
                        Destination.getX() < To.getX()) ||
                (To.getX() == HighBoundary &&
                        DirectionX > 0 &&
                        Destination.getX() > To.getX()) ||
                (To.getY() == LowBoundary &&
                        DirectionY < 0 &&
                        Destination.getY() < To.getY()) ||
                (To.getY() == HighBoundary &&
                        DirectionY > 0 &&
                        Destination.getY() > To.getY());
    }

    static double GetSceneBoundaryBridgeTweenValue(
            long ElapsedMilliseconds,
            long TweenDurationMilliseconds,
            double SegmentDistance)
    {
        if (TweenDurationMilliseconds <= 0 ||
                SegmentDistance <= 0 ||
                ElapsedMilliseconds <= TweenDurationMilliseconds)
        {
            return 1.0;
        }

        long BridgeMilliseconds = Math.min(
                ElapsedMilliseconds - TweenDurationMilliseconds,
                SCENE_BOUNDARY_BRIDGE_MAX_MILLIS);
        double TimeFraction =
                (double) BridgeMilliseconds /
                        TweenDurationMilliseconds;
        double DistanceFraction =
                SCENE_BOUNDARY_BRIDGE_MAX_DISTANCE /
                        SegmentDistance;
        return 1.0 + Math.min(TimeFraction, DistanceFraction);
    }

    static int GetScenePresentationImmediateFrameDelta(
            int RawDeltaMilliseconds)
    {
        return Math.max(
                0,
                Math.min(
                        RawDeltaMilliseconds,
                        SCENE_PRESENTATION_MAX_FRAME_DELTA_MILLIS));
    }

    static int GetScenePresentationDebtPayback(
            int ImmediateDeltaMilliseconds,
            int TimeDebtMilliseconds,
            int PaybackRemainder)
    {
        if (ImmediateDeltaMilliseconds <= 0 ||
                TimeDebtMilliseconds <= 0)
        {
            return 0;
        }

        int SafeRemainder = Math.max(
                0,
                Math.min(
                        SCENE_PRESENTATION_DEBT_PAYBACK_DIVISOR - 1,
                        PaybackRemainder));
        int MaximumPaybackThisFrame =
                (SafeRemainder + ImmediateDeltaMilliseconds) /
                        SCENE_PRESENTATION_DEBT_PAYBACK_DIVISOR;
        return Math.min(
                TimeDebtMilliseconds,
                MaximumPaybackThisFrame);
    }

    static int GetScenePresentationDebtPaybackRemainder(
            int ImmediateDeltaMilliseconds,
            int TimeDebtMilliseconds,
            int PaybackRemainder)
    {
        if (TimeDebtMilliseconds <= 0)
        {
            return 0;
        }

        int SafeRemainder = Math.max(
                0,
                Math.min(
                        SCENE_PRESENTATION_DEBT_PAYBACK_DIVISOR - 1,
                        PaybackRemainder));
        int PaybackNumerator =
                SafeRemainder +
                        Math.max(0, ImmediateDeltaMilliseconds);
        int Payback = Math.min(
                TimeDebtMilliseconds,
                PaybackNumerator /
                        SCENE_PRESENTATION_DEBT_PAYBACK_DIVISOR);
        return TimeDebtMilliseconds <= Payback
                ? 0
                : PaybackNumerator %
                        SCENE_PRESENTATION_DEBT_PAYBACK_DIVISOR;
    }

    static long GetSceneRecoveryTweenDuration(
            double Distance,
            double BaseVelocity,
            long FallbackDurationMilliseconds)
    {
        long SafeFallbackDuration =
                Math.max(1L, FallbackDurationMilliseconds);
        long MaximumRecoveryDuration =
                SafeFallbackDuration > Long.MAX_VALUE / 2
                        ? Long.MAX_VALUE
                        : SafeFallbackDuration * 2;
        if (!Double.isFinite(Distance) ||
                Distance <= 0 ||
                !Double.isFinite(BaseVelocity) ||
                BaseVelocity <= 0)
        {
            return SafeFallbackDuration;
        }

        double CalculatedDuration = Math.ceil(
                Distance / BaseVelocity);
        if (!Double.isFinite(CalculatedDuration) ||
                CalculatedDuration >= MaximumRecoveryDuration)
        {
            return MaximumRecoveryDuration;
        }

        return Math.max(1L, (long) CalculatedDuration);
    }

    static double GetPreservedSceneMovementVelocity(
            boolean MovementCanContinue,
            LocalPoint SegmentStart,
            LocalPoint SegmentEnd,
            long TweenDurationMilliseconds)
    {
        if (!MovementCanContinue ||
                TweenDurationMilliseconds <= 0 ||
                SegmentStart == null ||
                SegmentEnd == null ||
                !IsSameWorldView(SegmentStart, SegmentEnd) ||
                SegmentStart.equals(SegmentEnd))
        {
            return 0;
        }

        // Keep this division explicitly floating point. The old production
        // calculation divided two integers, which reduced every normal
        // sub-600-local-unit recovery velocity to zero.
        return SegmentStart.distanceTo(SegmentEnd) /
                (double) TweenDurationMilliseconds;
    }

    static double GetSceneRecoveryBaseVelocity(
            double PreservedMovementVelocity,
            double RecoveryDistance,
            long FallbackDurationMilliseconds)
    {
        if (!Double.isFinite(RecoveryDistance) ||
                RecoveryDistance <= 0)
        {
            return 0;
        }

        long SafeFallbackDuration =
                Math.max(1L, FallbackDurationMilliseconds);
        double MinimumRecoveryVelocity =
                Perspective.LOCAL_TILE_SIZE /
                        (double) SafeFallbackDuration;
        if (Double.isFinite(PreservedMovementVelocity) &&
                PreservedMovementVelocity > 0)
        {
            return Math.max(
                    PreservedMovementVelocity,
                    MinimumRecoveryVelocity);
        }

        // A fractional native handoff offset can be only one or two local
        // units. Do not treat that tiny offset as the speed of the next real
        // segment or its recovery could take several seconds.
        return Math.max(
                RecoveryDistance /
                        (double) SafeFallbackDuration,
                MinimumRecoveryVelocity);
    }

    static double GetRetainedSceneRecoveryBaseVelocity(
            double PreservedMovementVelocity,
            double RecoveryDistance,
            long FallbackDurationMilliseconds)
    {
        return Double.isFinite(PreservedMovementVelocity) &&
                PreservedMovementVelocity > 0
                ? GetSceneRecoveryBaseVelocity(
                        PreservedMovementVelocity,
                        RecoveryDistance,
                        FallbackDurationMilliseconds)
                : 0;
    }

    static boolean CanUseSceneBoundaryBridge(
            boolean SceneRecoveryPending,
            boolean ScenePresentationClockActive,
            long SceneRecoveryDurationOverride)
    {
        return !SceneRecoveryPending &&
                !ScenePresentationClockActive &&
                SceneRecoveryDurationOverride <= 0;
    }

    static boolean ShouldReleaseScenePresentationClock(
            int ScenePresentationTimeDebtMilliseconds,
            boolean SceneRecoveryPending,
            long SceneRecoveryDurationOverride,
            boolean SceneLoadFramePresentationPending)
    {
        return ScenePresentationTimeDebtMilliseconds <= 0 &&
                !SceneRecoveryPending &&
                SceneRecoveryDurationOverride <= 0 &&
                !SceneLoadFramePresentationPending;
    }

    static boolean IsSceneMovementDiscontinuity(
            int OwnerAnimation,
            int RequestedAnimation,
            boolean RequestedTeleport,
            Set<Integer> AnimationExceptions,
            Set<Integer> LocationAndOrientationExceptions)
    {
        if (RequestedTeleport)
        {
            return true;
        }

        return OwnerAnimation != NO_ANIMATION &&
                        (AnimationExceptions.contains(OwnerAnimation) ||
                                LocationAndOrientationExceptions.contains(
                                        OwnerAnimation)) ||
                RequestedAnimation != NO_ANIMATION &&
                        (AnimationExceptions.contains(RequestedAnimation) ||
                                LocationAndOrientationExceptions.contains(
                                        RequestedAnimation));
    }

    static boolean CanPreserveSceneMovementVelocity(
            boolean BoundaryBridgeActive,
            boolean RetainedPresentationMoving,
            boolean YellowWalkRouteActive,
            boolean SamePlane,
            boolean SpecialMovementDiscontinuity)
    {
        return (BoundaryBridgeActive ||
                RetainedPresentationMoving) &&
                YellowWalkRouteActive &&
                SamePlane &&
                !SpecialMovementDiscontinuity;
    }

    static boolean IsPlausibleAuthoritativeSceneSegment(
            boolean YellowWalkRouteActive,
            boolean SamePlane,
            boolean SpecialMovementDiscontinuity,
            LocalPoint SegmentStart,
            LocalPoint SegmentEnd)
    {
        if (!YellowWalkRouteActive ||
                !SamePlane ||
                SpecialMovementDiscontinuity ||
                SegmentStart == null ||
                SegmentEnd == null ||
                !IsSameWorldView(SegmentStart, SegmentEnd) ||
                SegmentStart.equals(SegmentEnd))
        {
            return false;
        }

        int MaximumNativeStep =
                Perspective.LOCAL_TILE_SIZE * 2;
        return Math.abs(
                SegmentEnd.getX() - SegmentStart.getX()) <=
                        MaximumNativeStep &&
                Math.abs(
                        SegmentEnd.getY() - SegmentStart.getY()) <=
                        MaximumNativeStep;
    }

    static double SelectSceneRecoveryVelocity(
            double AuthoritativeSegmentVelocity,
            double PreservedMovementVelocity)
    {
        if (Double.isFinite(AuthoritativeSegmentVelocity) &&
                AuthoritativeSegmentVelocity > 0)
        {
            return AuthoritativeSegmentVelocity;
        }
        return Double.isFinite(PreservedMovementVelocity) &&
                PreservedMovementVelocity > 0
                ? PreservedMovementVelocity
                : 0;
    }

    static boolean ExceedsSceneRecoverySnapDistance(
            LocalPoint DisplayedLocation,
            LocalPoint AuthoritativeLocation,
            int SnapDistanceInTiles)
    {
        if (!IsSameWorldView(
                DisplayedLocation,
                AuthoritativeLocation))
        {
            return true;
        }

        // [TMA-SCENE-RECOVERY-TILE-DISTANCE] RuneScape movement distance is
        // measured in tile steps, where a diagonal (2, 2) displacement is
        // still two tiles. Euclidean distance classified that valid run as
        // sqrt(8) tiles and snapped the model forward whenever the configured
        // threshold was two. Keep the setting authoritative on each axis so
        // ordinary diagonal scene advances recover while a displacement that
        // exceeds the configured tile count in either direction still snaps.
        long MaximumLocalDifference =
                (long) Math.max(1, SnapDistanceInTiles) *
                        Perspective.LOCAL_TILE_SIZE;
        long DifferenceX = Math.abs(
                (long) AuthoritativeLocation.getX() -
                        DisplayedLocation.getX());
        long DifferenceY = Math.abs(
                (long) AuthoritativeLocation.getY() -
                        DisplayedLocation.getY());
        return DifferenceX > MaximumLocalDifference ||
                DifferenceY > MaximumLocalDifference;
    }

    static long GetSceneMovementAnimationDuration(
            long SceneRecoveryDurationOverride)
    {
        return Math.max(
                BASE_MOVEMENT_TWEEN_MILLIS,
                SceneRecoveryDurationOverride);
    }

    static boolean ShouldCompleteSceneRecoveryOverride(
            int ElapsedMilliseconds,
            long SceneRecoveryDurationOverride)
    {
        return SceneRecoveryDurationOverride > 0 &&
                ElapsedMilliseconds >=
                        SceneRecoveryDurationOverride;
    }

    static boolean ShouldKeepMovementAnimationDuringRouteGap(
            int MillisecondsSinceTileChange,
            long MovementDurationMilliseconds,
            boolean WasMoving,
            boolean WalkRouteContinuityArmed,
            boolean WalkSegmentAwaitingMovement,
            LocalPoint CurrentSegmentDestination,
            LocalPoint RouteDestination)
    {
        long TimeSinceSegmentCompleted =
                (long) MillisecondsSinceTileChange -
                        Math.max(
                                BASE_MOVEMENT_TWEEN_MILLIS,
                                MovementDurationMilliseconds);
        // A route destination alone is not enough: red-click interactions
        // publish destinations too. Their synchronous cancel clears this
        // yellow-walk arm so object/NPC stops cannot inherit locomotion grace
        // and appear to run in place.
        return WasMoving &&
                WalkRouteContinuityArmed &&
                !WalkSegmentAwaitingMovement &&
                TimeSinceSegmentCompleted >= 0 &&
                TimeSinceSegmentCompleted <
                        MOVEMENT_ANIMATION_CONTINUITY_GRACE_MILLIS &&
                CurrentSegmentDestination != null &&
                RouteDestination != null &&
                IsSameWorldView(
                        CurrentSegmentDestination,
                        RouteDestination) &&
                !CurrentSegmentDestination.equals(RouteDestination);
    }

    static boolean ShouldKeepMovementAnimationDuringRouteGap(
            int MillisecondsSinceTileChange,
            boolean WasMoving,
            boolean WalkRouteContinuityArmed,
            boolean WalkSegmentAwaitingMovement,
            LocalPoint CurrentSegmentDestination,
            LocalPoint RouteDestination)
    {
        return ShouldKeepMovementAnimationDuringRouteGap(
                MillisecondsSinceTileChange,
                BASE_MOVEMENT_TWEEN_MILLIS,
                WasMoving,
                WalkRouteContinuityArmed,
                WalkSegmentAwaitingMovement,
                CurrentSegmentDestination,
                RouteDestination);
    }

    static boolean ShouldKeepMovementAnimationDuringRouteGap(
            int MillisecondsSinceTileChange,
            long MovementDurationMilliseconds,
            boolean WasMoving,
            boolean WalkRouteContinuityArmed,
            boolean WalkSegmentAwaitingMovement,
            boolean HoldingStopFacing,
            LocalPoint CurrentSegmentDestination,
            LocalPoint RouteDestination)
    {
        // A deliberate stop-facing handoff takes priority over animation-only
        // route grace. Combining both states leaves the position clamped while
        // locomotion continues, which is the post-scene run-in-place seam.
        return !HoldingStopFacing &&
                ShouldKeepMovementAnimationDuringRouteGap(
                        MillisecondsSinceTileChange,
                        MovementDurationMilliseconds,
                        WasMoving,
                        WalkRouteContinuityArmed,
                        WalkSegmentAwaitingMovement,
                        CurrentSegmentDestination,
                        RouteDestination);
    }

    static boolean ShouldKeepMovementAnimationDuringRouteGap(
            int MillisecondsSinceTileChange,
            boolean WasMoving,
            boolean WalkRouteContinuityArmed,
            boolean WalkSegmentAwaitingMovement,
            boolean HoldingStopFacing,
            LocalPoint CurrentSegmentDestination,
            LocalPoint RouteDestination)
    {
        return ShouldKeepMovementAnimationDuringRouteGap(
                MillisecondsSinceTileChange,
                BASE_MOVEMENT_TWEEN_MILLIS,
                WasMoving,
                WalkRouteContinuityArmed,
                WalkSegmentAwaitingMovement,
                HoldingStopFacing,
                CurrentSegmentDestination,
                RouteDestination);
    }

    static boolean ShouldArmPostSceneRecoveryRouteGap(
            boolean AwaitingPostSceneWalkSegment,
            boolean HasRecoveryDistance)
    {
        return AwaitingPostSceneWalkSegment &&
                HasRecoveryDistance;
    }

    static boolean ShouldKeepMovementAnimationDuringPostSceneRecoveryRouteGap(
            boolean PostSceneRecoveryRouteGapEligible,
            int MillisecondsSinceTileChange,
            long MovementDurationMilliseconds,
            boolean WasMoving,
            boolean WalkRouteContinuityArmed,
            boolean MovementSegmentFromLatestWalkClick,
            LocalPoint CurrentSegmentDestination,
            LocalPoint RouteDestination)
    {
        // Eligibility is created only by a non-zero recovery which inherited
        // this same observed yellow route. It may therefore bypass the
        // scene-created awaiting flag, but it retains the existing duration,
        // ownership, destination, and 300 ms expiry checks.
        return PostSceneRecoveryRouteGapEligible &&
                ShouldKeepMovementAnimationDuringRouteGap(
                        MillisecondsSinceTileChange,
                        MovementDurationMilliseconds,
                        WasMoving,
                        WalkRouteContinuityArmed &&
                                MovementSegmentFromLatestWalkClick,
                        false,
                        CurrentSegmentDestination,
                        RouteDestination);
    }

    static boolean ShouldKeepMovementAnimationDuringInheritedPendingRouteGap(
            boolean InheritedActiveRouteGap,
            int MillisecondsSinceTileChange,
            long MovementDurationMilliseconds,
            boolean WasMoving,
            boolean WalkRouteContinuityArmed,
            boolean WalkStartPending,
            boolean WalkSegmentAwaitingMovement,
            boolean MovementSegmentFromLatestWalkClick,
            boolean HoldingStopFacing,
            LocalPoint CurrentSegmentDestination,
            LocalPoint RouteDestination)
    {
        // The latch is created only while the old click is already inside the
        // ordinary bounded route-gap bridge. Bypass the newer click's awaiting
        // flag, but keep the old segment clock, route, facing, and 300 ms expiry
        // checks. This cannot create or extend locomotion grace.
        return InheritedActiveRouteGap &&
                WalkStartPending &&
                WalkSegmentAwaitingMovement &&
                !MovementSegmentFromLatestWalkClick &&
                ShouldKeepMovementAnimationDuringRouteGap(
                        MillisecondsSinceTileChange,
                        MovementDurationMilliseconds,
                        WasMoving,
                        WalkRouteContinuityArmed,
                        false,
                        HoldingStopFacing,
                        CurrentSegmentDestination,
                        RouteDestination);
    }

    static boolean HasUnfinishedRoute(
            LocalPoint SegmentDestination,
            LocalPoint RouteDestination)
    {
        return SegmentDestination != null &&
                RouteDestination != null &&
                IsSameWorldView(SegmentDestination, RouteDestination) &&
                !SegmentDestination.equals(RouteDestination);
    }

    static boolean ShouldBridgeFreshMovementPublicationBoundary(
            int MillisecondsSinceTileChange,
            long MovementDurationMilliseconds,
            boolean PlayerOwner,
            boolean RetainedMovementSegmentInProgress,
            boolean WalkSegmentAwaitingMovement,
            boolean HoldingStopFacing,
            boolean SpecialPresentationActive,
            int OwnerActionAnimation,
            LocalPoint CurrentSegmentDestination,
            LocalPoint RouteDestination)
    {
        // This is eligibility for a one-shot render-boundary bridge. Frame
        // pacing can make the first render after 600 ms arrive one or several
        // native cycles after the last moving render, so clock distance is not
        // reliable provenance. The retained snapshot proves that the previous
        // presented frame was genuinely traversing this non-zero segment. The
        // caller latches only the first completed render's current game cycle;
        // the snapshot is not refreshed, so the bridge cannot re-arm.
        return PlayerOwner &&
                RetainedMovementSegmentInProgress &&
                MillisecondsSinceTileChange >=
                        Math.max(
                                BASE_MOVEMENT_TWEEN_MILLIS,
                                MovementDurationMilliseconds) &&
                // A newer yellow click can arm the pending-facing hold while
                // this old segment is still visibly moving. At an unfinished
                // route that is publication ordering, not a final stop. A
                // genuine stop-facing hold remains excluded.
                (!HoldingStopFacing || WalkSegmentAwaitingMovement) &&
                !SpecialPresentationActive &&
                OwnerActionAnimation == NO_ANIMATION &&
                HasUnfinishedRoute(
                        CurrentSegmentDestination,
                        RouteDestination);
    }

    static boolean IsFreshMovementPublicationBoundaryBridgeCycle(
            int BoundaryGameCycle,
            int CurrentGameCycle)
    {
        return BoundaryGameCycle >= 0 &&
                BoundaryGameCycle == CurrentGameCycle;
    }

    static int SelectFreshMovementPublicationBoundaryGameCycle(
            boolean BridgeEligible,
            int ExistingBoundaryGameCycle,
            int CurrentGameCycle)
    {
        return BridgeEligible && ExistingBoundaryGameCycle < 0
                ? CurrentGameCycle
                : ExistingBoundaryGameCycle;
    }

    static boolean ShouldAwaitPostSceneWalkSegment(
            boolean RealDiscontinuity,
            boolean SpecialMovementDiscontinuity,
            boolean WalkRouteArmed,
            boolean MovementWasObserved,
            LocalPoint CurrentSegmentDestination,
            LocalPoint RouteDestination)
    {
        return !RealDiscontinuity &&
                !SpecialMovementDiscontinuity &&
                WalkRouteArmed &&
                MovementWasObserved &&
                HasUnfinishedRoute(
                        CurrentSegmentDestination,
                        RouteDestination);
    }

    static boolean ShouldUseOriginalOwnerPresentation(
            boolean AllowOriginalModel,
            boolean Moving,
            boolean HoldingStopFacing,
            boolean UsedCustomAnimation,
            int OwnerActionAnimation,
            int DistanceX,
            int DistanceY,
            int OrientationDifference,
            int DistanceThreshold,
            int OrientationThreshold)
    {
        boolean ExactPresentationMatch =
                DistanceX == 0 &&
                        DistanceY == 0 &&
                        OrientationDifference == 0;
        // [TMA-STEADY-PRESENTATION] Never change render authority during
        // locomotion. An exact stationary transform can safely use RuneScape's
        // native player pass; the configurable tolerance still excludes actions.
        return !Moving &&
                !HoldingStopFacing &&
                !UsedCustomAnimation &&
                (ExactPresentationMatch ||
                        AllowOriginalModel &&
                                OwnerActionAnimation == -1 &&
                                Math.abs(DistanceX) <=
                                        DistanceThreshold &&
                                Math.abs(DistanceY) <=
                                        DistanceThreshold &&
                                Math.abs(OrientationDifference) <=
                                        OrientationThreshold);
    }

    static boolean ShouldReplaceAnimationController(
            int CurrentAnimationId,
            int RequestedAnimationId,
            boolean ExplicitReset)
    {
        // RuneLite may replace an Animation wrapper without changing the
        // underlying animation. Object identity must not restart its phase.
        return ExplicitReset ||
                CurrentAnimationId != RequestedAnimationId;
    }

    private static int ShortestAngleDifference(int from, int to)
    {
        return ((to - from + ORIENTATION_UNITS / 2) &
                ORIENTATION_MASK) - ORIENTATION_UNITS / 2;
    }

    static double AdvanceOrientationPhase(
            double CurrentOrientationPhase,
            int TargetOrientation,
            double MaximumStep)
    {
        int PublishedOrientation =
                ((int) CurrentOrientationPhase) & ORIENTATION_MASK;
        int ShortestAngle = ShortestAngleDifference(
                PublishedOrientation,
                TargetOrientation);
        if (ShortestAngle == 0)
        {
            return TargetOrientation & ORIENTATION_MASK;
        }
        if (!Double.isFinite(MaximumStep) || MaximumStep <= 0)
        {
            return CurrentOrientationPhase;
        }
        if (Math.abs(ShortestAngle) <= MaximumStep)
        {
            return TargetOrientation & ORIENTATION_MASK;
        }

        double NextOrientation = CurrentOrientationPhase +
                Math.copySign(MaximumStep, ShortestAngle);
        if (NextOrientation < 0)
        {
            NextOrientation += ORIENTATION_UNITS;
        }
        else if (NextOrientation >= ORIENTATION_UNITS)
        {
            NextOrientation -= ORIENTATION_UNITS;
        }
        return NextOrientation;
    }

    static int getOrientationBetweenPoints(double point1X, double point1Y, double point2X, double point2Y, int OffsetAngle)
    {
        // Calculate the difference in X and Y coordinates
        double deltaX = point2X - point1X;
        double deltaY = point2Y - point1Y;

        // Calculate the angle in radians
        double angleInRadians = Math.atan2(deltaY, deltaX);

        // Convert to degrees and normalize to RuneLite's 2048-unit ring.
        double angleInDegrees = Math.toDegrees(angleInRadians);
        angleInDegrees += OffsetAngle;

        angleInDegrees = (360.0 - angleInDegrees) % 360.0;
        if (angleInDegrees < 0)
        {
            angleInDegrees += 360.0;
        }

        return ((int) ((angleInDegrees / 360.0) *
                ORIENTATION_UNITS)) & ORIENTATION_MASK;
    }

    private boolean IsPlayerOwner()
    {
        return (Owner instanceof Player);
    }

    public void Initialize(
            boolean bRuneliteObjectsStale,
            int SceneGeneration)
    {
        // A stale flag can survive more than one overlay pass while the new
        // scene is not ready for world/local conversion. Replace the
        // scene-owned objects once per scene generation, then retain them
        // until rebase succeeds.
        boolean bReplaceSceneObjects =
                bRuneliteObjectsStale &&
                        LastInitializedSceneGeneration !=
                                SceneGeneration;

        if (AnimController == null)
        {
            AnimController = new AnimationController(client, NO_ANIMATION);
            AnimController.setOnFinished((AnimationController InController) ->
            {
                // [TMA-NATIVE-ANIMATION-LOOPS] Animation frame 0 may be a
                // one-time lead-in. RuneLite's loop() honours frameStep and
                // returns to the sequence's authored loop point. Forcing 0
                // can replay that lead-in and seam controller-driven action or
                // locomotion cycles. (Native idle 808 has no authored loop
                // point and is documented separately.)
                if (CurrentAnimationRequest == null ||
                        CurrentAnimationRequest.bAllowAnimationLoop)
                {
                    InController.loop();
                }
                bTargetWasKilled = false;
            });
        }

        if (Model == null || bReplaceSceneObjects)
        {
            RuneLiteObject OldModel = Model;
            if (NativePoseSelectorOverrideAnimation != NO_ANIMATION)
            {
                SetAllIdlePosesDefault();
            }
            ClearHeldEndpointIdleModel();
            bEndpointIdlePresentationActive = false;
            bEndpointNativeHandoffReadyThisFrame = false;
            bEndpointNativeHandoffEstablished = false;
            bLocomotionResetPendingAfterEndpointIdle = false;
            bPublishedStoppedGeometrySafe = false;
            bPostSceneRecoveryRouteGapEligible = false;
            bPendingWalkInheritedActiveRouteGap = false;
            Model = client.createRuneLiteObject();
            // [TMA-TRANSPARENCY-DEPTH] Force depth-sorted rendering
            // so transparent faces draw after body faces.
            Model.setRenderMode(Renderable.RENDERMODE_SORTED);
            LastValidOwnerPoseAnimation = NO_ANIMATION;
            LastValidOwnerPoseFrame = 0;

            if (OldModel != null)
            {
                Model.setLocation(OldModel.getLocation(), OldModel.getLevel());
                Model.setOrientation(CurrentOrientation);
                Model.setAnimationController(OldModel.getAnimationController());
                client.removeRuneLiteObject(OldModel);
            }
        }

        if (bReplaceSceneObjects)
        {
            RemoveMirroredActorSpotAnimations();
        }

        if (IsPlayerOwner())
        {
            if (bReplaceSceneObjects &&
                    !config.SpawnModelAtCameraTile() &&
                    cameraModel != null)
            {
                // A disabled auxiliary model still belongs to the old scene.
                // Drop that stale object now so enabling the option later
                // creates and registers a current-scene replacement.
                client.removeRuneLiteObject(cameraModel);
                cameraModel = null;
                bCameraModelNeedsPublish = false;
            }
            if (config.SpawnModelAtCameraTile())
            {
                if (cameraModelAnimController == null)
                {
                    cameraModelAnimController = new AnimationController(client, NO_ANIMATION);
                    cameraModelAnimController.setOnFinished((AnimationController InController) ->
                    {
                        // Keep auxiliary model loops consistent with the
                        // animation's authored frameStep as well.
                        InController.loop();
                    });
                }

                if (cameraModel == null || bReplaceSceneObjects)
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
                    bCameraModelNeedsPublish = true;

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

        // RuneLiteObjects belong to the scene. Preserve handler state and
        // remap it after a replacement instead of starting from the hidden
        // player's local point (which causes the visible pop).
        if (bReplaceSceneObjects)
        {
            LastInitializedSceneGeneration = SceneGeneration;
            bSceneRebasePending = true;
            // The controller's last rendered phase remains valid, but time
            // spent while the old scene was unavailable must not be consumed
            // as one large animation jump in the replacement scene.
            LastAnimationGameCycle = -1;
        }
    }

    public void Cleanup()
    {
        // Render once with should render owner back on
        bShouldRenderOwner = true;
        bAttemptToRenderOwner = true;
        CancelWalkStopFacingHold();
        bPublishedStoppedGeometrySafe = false;
        bSceneRebasePending = false;
        bSceneRecoveryRetargetPending = false;
        bSceneBoundaryBridgeActive = false;
        bRetainedSceneMovementSegmentInProgress = false;
        RetainedMovementProgressTick = -1;
        RetainedMovementProgressGameCycle = -1;
        bFreshMovementPublicationBoundaryBridgeActive = false;
        FreshMovementPublicationBoundaryGameCycle = -1;
        bPostSceneRecoveryRouteGapEligible = false;
        MovementSegmentPublicationTick = -1;
        MovementSegmentPublicationGameCycle = -1;
        bScenePresentationClockActive = false;
        bSceneLoadFramePresentationPending = false;
        ScenePresentationTimeDebtMilliseconds = 0;
        ScenePresentationDebtPaybackRemainder = 0;
        LastAnimationGameCycle = -1;
        SceneRecoveryBaseVelocity = 0;
        SceneRecoveryTweenDurationOverride = 0;
        bNativeSceneLoadHandoffPresented = false;
        bLastSceneRebaseUsedNativeHandoffAnchor = false;
        bPohArrivalCoordinateGuardArmed = false;
        PohArrivalCoordinateGuardSceneGeneration = -1;
        LastValidOwnerPoseAnimation = NO_ANIMATION;
        LastValidOwnerPoseFrame = 0;
        if (NativePoseSelectorOverrideAnimation != NO_ANIMATION)
        {
            SetAllIdlePosesDefault();
        }
        ClearHeldEndpointIdleModel();
        bEndpointIdlePresentationActive = false;
        bEndpointNativeHandoffReadyThisFrame = false;
        bEndpointNativeHandoffEstablished = false;
        bLocomotionResetPendingAfterEndpointIdle = false;
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

        RemoveMirroredActorSpotAnimations();
    }

    void ArmWalkStopFacingHold()
    {
        if (!IsPlayerOwner())
        {
            return;
        }

        long MovementDuration =
                GetSceneMovementAnimationDuration(
                        SceneRecoveryTweenDurationOverride);
        boolean bActiveRouteGapBeforeClick =
                ShouldKeepMovementAnimationDuringInheritedPendingRouteGap() ||
                        ShouldKeepMovementAnimationDuringPostSceneRecoveryRouteGap() ||
                        ShouldKeepMovementAnimationDuringRouteGap(
                                MillisecondsSinceTileChange,
                                MovementDuration,
                                bMovingThisAction,
                                bWalkStopFacingHoldArmed &&
                                        bWalkMovementObserved &&
                                        IsMovementSegmentFromLatestWalkClick(
                                                WalkClickRevision,
                                                WalkClickRevisionAtMovementSegment),
                                bWalkSegmentAwaitingMovement,
                                bHoldWalkStopFacingThisFrame,
                                NextLerpPosition,
                                client.getLocalDestinationLocation());
        // A newer click cannot start a replacement grace window. If the old
        // route was already inside its valid bridge, remember only that fact so
        // it can reach the unchanged original expiry without an idle flash.
        // Repeated clicks may keep this same latch, but its segment clock is
        // never reset or extended.
        bPendingWalkInheritedActiveRouteGap =
                bActiveRouteGapBeforeClick;
        bPostSceneRecoveryRouteGapEligible = false;

        boolean bContinueActiveCatchUp =
                ShouldContinueActiveWalkStopCatchUp(
                        bWalkStopFacingHoldArmed,
                        bWalkMovementObserved,
                        bHoldWalkStopFacingThisFrame);
        boolean bVisibleMovementSegmentInProgress =
                IsVisibleMovementSegmentInProgress(
                        bMovingThisAction,
                        MillisecondsSinceTileChange,
                        GetMovementTweenDurationMilliseconds(),
                        LastLerpPosition,
                        NextLerpPosition);
        boolean bContinueReleasedFacingAsPendingIdle =
                ShouldContinueReleasedFacingForPendingWalk(
                        bPreserveReleasedWalkFacing,
                        bNativeWalkFacingSettled);
        if (bPreserveReleasedWalkFacing &&
                !bContinueReleasedFacingAsPendingIdle)
        {
            // [TMA-FRESH-WALK-RESPONSE] A fully settled old route no longer
            // owns presentation after the next yellow click. Keep the new
            // route armed/awaiting authority, but do not make that click look
            // inert by carrying an obsolete facing hold into established
            // idle. An active, not-yet-settled catch-up still keeps its hold.
            bPreserveReleasedWalkFacing = false;
            bNativeWalkFacingSettled = false;
        }
        boolean bContinuePendingWalkPresentation =
                bActiveRouteGapBeforeClick ||
                        ShouldContinuePendingWalkPresentation(
                                bContinueActiveCatchUp,
                                bVisibleMovementSegmentInProgress,
                                bContinueReleasedFacingAsPendingIdle,
                                bEndpointIdlePresentationActive,
                                bEndpointNativeHandoffEstablished,
                                bRenderOriginalOwnerDueToProximity,
                                !bMovingThisAction &&
                                        Model != null &&
                                        Model.isActive() &&
                                        Model.getBaseModel() != null);
        ++WalkClickRevision;
        // Every yellow click waits for a segment published after that click.
        // The already-visible segment continues normally until its endpoint;
        // only stale route-gap locomotion is excluded.
        bWalkSegmentAwaitingMovement = true;
        bWalkStopFacingHoldArmed = true;
        bWalkMovementObserved = bContinuePendingWalkPresentation;
        bWalkStartPendingDuringCatchUp =
                bContinuePendingWalkPresentation;
        // [TMA-FRESH-WALK-PRESENTATION] World input can arrive after a frame's
        // pre-render snapshot but before its draw callback. Retire only native
        // render authority synchronously with the click. An
        // established native-geometry path remains on the custom object so its
        // current idle phase, location, and orientation survive until a real
        // segment is published.
        bEndpointNativeHandoffReadyThisFrame = false;
        bRenderOriginalOwnerDueToProximity = false;
        // If the previous route is already preserving its released facing,
        // keep that stable during the short click-to-movement delay. A click
        // during a genuinely moving segment also retains its observed state
        // through that segment's endpoint. The first segment published after
        // this click clears both pending flags. A click made from established
        // idle still cannot revive an old controller.
    }

    void CancelWalkStopFacingHold()
    {
        bWalkStopFacingHoldArmed = false;
        bWalkMovementObserved = false;
        bWalkStartPendingDuringCatchUp = false;
        bWalkSegmentAwaitingMovement = false;
        bPostSceneRecoveryRouteGapEligible = false;
        bPendingWalkInheritedActiveRouteGap = false;
        bPreserveReleasedWalkFacing = false;
        bHoldWalkStopFacingThisFrame = false;
        bNativeWalkFacingSettled = false;
        WalkClickRevision = 0;
        WalkClickRevisionAtMovementSegment = 0;
    }

    private boolean ShouldKeepMovementAnimationDuringPostSceneRecoveryRouteGap()
    {
        return ShouldKeepMovementAnimationDuringPostSceneRecoveryRouteGap(
                bPostSceneRecoveryRouteGapEligible,
                MillisecondsSinceTileChange,
                GetSceneMovementAnimationDuration(
                        SceneRecoveryTweenDurationOverride),
                bMovingThisAction,
                bWalkStopFacingHoldArmed &&
                        bWalkMovementObserved,
                IsMovementSegmentFromLatestWalkClick(
                        WalkClickRevision,
                        WalkClickRevisionAtMovementSegment),
                NextLerpPosition,
                client.getLocalDestinationLocation());
    }

    private boolean ShouldKeepMovementAnimationDuringInheritedPendingRouteGap()
    {
        return ShouldKeepMovementAnimationDuringInheritedPendingRouteGap(
                bPendingWalkInheritedActiveRouteGap,
                MillisecondsSinceTileChange,
                GetSceneMovementAnimationDuration(
                        SceneRecoveryTweenDurationOverride),
                bMovingThisAction,
                bWalkStopFacingHoldArmed &&
                        bWalkMovementObserved,
                bWalkStartPendingDuringCatchUp,
                bWalkSegmentAwaitingMovement,
                IsMovementSegmentFromLatestWalkClick(
                        WalkClickRevision,
                        WalkClickRevisionAtMovementSegment),
                bHoldWalkStopFacingThisFrame,
                NextLerpPosition,
                client.getLocalDestinationLocation());
    }

    private void UpdateWalkStopFacingHold()
    {
        bHoldWalkStopFacingThisFrame = false;
        if (!IsPlayerOwner())
        {
            return;
        }

        // Scene recovery can deliberately take longer than one normal game
        // tick. Use the same duration as animation selection so stop-facing
        // cannot begin while the visible model is still traversing recovery.
        long MovementDuration =
                GetSceneMovementAnimationDuration(
                        SceneRecoveryTweenDurationOverride);
        boolean bPostSceneRecoveryRouteGapGraceActive =
                ShouldKeepMovementAnimationDuringPostSceneRecoveryRouteGap();
        boolean bInheritedPendingRouteGapGraceActive =
                ShouldKeepMovementAnimationDuringInheritedPendingRouteGap();
        boolean VisibleModelIsMoving =
                MillisecondsSinceTileChange < MovementDuration ||
                        bPostSceneRecoveryRouteGapGraceActive ||
                        bInheritedPendingRouteGapGraceActive;
        if (bPostSceneRecoveryRouteGapEligible &&
                !bPostSceneRecoveryRouteGapGraceActive &&
                MillisecondsSinceTileChange >= MovementDuration)
        {
            // The bounded seam expired (or its route ownership stopped being
            // valid). Restore the existing pending idle/facing handoff.
            bPostSceneRecoveryRouteGapEligible = false;
        }
        if (bPendingWalkInheritedActiveRouteGap &&
                !bInheritedPendingRouteGapGraceActive)
        {
            // Expiry and every invalidating condition restore the established
            // pending-idle handoff. The latch can never feed itself forward.
            bPendingWalkInheritedActiveRouteGap = false;
        }
        if (VisibleModelIsMoving)
        {
            if (bWalkStopFacingHoldArmed)
            {
                bWalkMovementObserved = true;
            }

            // Forced movement or another route can begin without a fresh
            // yellow click. It must not inherit a completed route's facing.
            bPreserveReleasedWalkFacing = false;
            bNativeWalkFacingSettled = false;
            return;
        }

        if (bPreserveReleasedWalkFacing)
        {
            int NativeTargetOrientation = Owner.getOrientation();
            int NativeCurrentOrientation = Owner.getCurrentOrientation();

            if (ShouldPreserveReleasedWalkFacing(
                    bNativeWalkFacingSettled,
                    NativeTargetOrientationAtWalkFacingRelease,
                    NativeTargetOrientation))
            {
                bHoldWalkStopFacingThisFrame = true;

                if (!bNativeWalkFacingSettled)
                {
                    // [TMA-STOP-FACING-SETTLE] The native route can publish a
                    // final target orientation after its position has already
                    // caught up. Absorb that route-end churn while it is
                    // hidden; otherwise it is mistaken for a new command and
                    // the custom model turns immediately after stopping.
                    if (NativeTargetOrientation !=
                            NativeTargetOrientationAtWalkFacingRelease ||
                            NativeCurrentOrientation !=
                                    NativeCurrentOrientationAtWalkFacingRelease)
                    {
                        NativeTargetOrientationAtWalkFacingRelease =
                                NativeTargetOrientation;
                        NativeCurrentOrientationAtWalkFacingRelease =
                                NativeCurrentOrientation;
                        LastNativeWalkFacingChangeTime = CurrentTime;
                    }
                    else if (HasNativeWalkFacingSettled(
                            CurrentTime,
                            LastNativeWalkFacingChangeTime))
                    {
                        bNativeWalkFacingSettled = true;
                    }
                }
            }
            else
            {
                // A target-orientation change after the native actor has
                // settled is a new command, so ordinary smooth turning can
                // resume. Red interactions still cancel synchronously in the
                // menu-click handler and do not wait for this branch.
                bPreserveReleasedWalkFacing = false;
                if (ShouldHoldPendingWalkStart(
                        bWalkStartPendingDuringCatchUp,
                        bWalkStopFacingHoldArmed,
                        bWalkMovementObserved,
                        false))
                {
                    // A yellow re-click can change target orientation before
                    // its first authoritative segment. Preserve endpoint idle
                    // for that publication seam; never revive locomotion.
                    bHoldWalkStopFacingThisFrame = true;
                }
            }
            return;
        }

        if (!bWalkStopFacingHoldArmed ||
                !bWalkMovementObserved)
        {
            return;
        }

        // [TMA-YELLOW-RECLICK-HANDOFF] [TMA-POST-SCENE-YELLOW-HANDOFF]
        // A second yellow click or replacement scene can publish a route
        // destination before the client publishes its authoritative movement
        // segment. During that delay, IsAtFinalWalkDestination() is false even
        // though the visible model is stopped. Keep the existing facing and
        // native pose presentation until movement genuinely begins.
        if (ShouldHoldPendingWalkStart(
                bWalkStartPendingDuringCatchUp,
                bWalkStopFacingHoldArmed,
                bWalkMovementObserved,
                VisibleModelIsMoving))
        {
            bHoldWalkStopFacingThisFrame = true;
            if (HasHiddenOwnerCaughtUp(
                    Owner.getLocalLocation(),
                    NextLerpPosition))
            {
                BeginWalkStopFacingPreservation(true);
            }
            return;
        }

        if (!IsAtFinalWalkDestination())
        {
            return;
        }

        // The visible model has stopped at the final requested tile while the
        // hidden native actor is still approaching it. Freeze only this
        // short-lived orientation mismatch; normal turning resumes afterward.
        bHoldWalkStopFacingThisFrame = true;
        if (HasHiddenOwnerCaughtUp(
                Owner.getLocalLocation(),
                NextLerpPosition))
        {
            // Release the temporary catch-up block without immediately
            // reapplying the stale native orientation on the next frame.
            BeginWalkStopFacingPreservation(false);
        }
    }

    private void BeginWalkStopFacingPreservation(
            boolean RetainPendingWalkArm)
    {
        bWalkStopFacingHoldArmed = RetainPendingWalkArm;
        bWalkMovementObserved = false;
        bWalkStartPendingDuringCatchUp = false;
        bPendingWalkInheritedActiveRouteGap = false;
        bPreserveReleasedWalkFacing = true;
        bNativeWalkFacingSettled = false;
        NativeTargetOrientationAtWalkFacingRelease =
                Owner.getOrientation();
        NativeCurrentOrientationAtWalkFacingRelease =
                Owner.getCurrentOrientation();
        LastNativeWalkFacingChangeTime = CurrentTime;
    }

    private boolean IsAtFinalWalkDestination()
    {
        LocalPoint WalkDestination = client.getLocalDestinationLocation();
        return WalkDestination == null ||
                (NextLerpPosition != null &&
                        NextLerpPosition.equals(WalkDestination));
    }

    static boolean ShouldUseEndpointIdlePresentation(
            boolean CustomPresentationActive,
            boolean SegmentComplete,
            boolean SegmentHasDistance,
            boolean SceneBoundaryBridgeActive,
            boolean OrdinaryPoseRequest,
            boolean SpecialPresentationActive,
            boolean HoldingWalkStopFacing,
            boolean AtFinalDestination,
            boolean HiddenOwnerReadyForIdleHandoff,
            int OwnerActionAnimation)
    {
        // [TMA-ENDPOINT-IDLE-PRESENTATION] Route intent is deliberately separate
        // from visible velocity. A route may continue, but a model clamped at
        // the end of its last published segment is spatially stationary and
        // must not select locomotion from its previous pose state.
        return CustomPresentationActive &&
                SegmentComplete &&
                SegmentHasDistance &&
                !SceneBoundaryBridgeActive &&
                OrdinaryPoseRequest &&
                !SpecialPresentationActive &&
                OwnerActionAnimation == NO_ANIMATION &&
                (AtFinalDestination
                        ? !HiddenOwnerReadyForIdleHandoff
                        : HoldingWalkStopFacing);
    }

    static boolean ShouldContinuePendingWalkPresentation(
            boolean ContinueActiveCatchUp,
            boolean VisibleMovementSegmentInProgress,
            boolean PreservingReleasedFacing,
            boolean EndpointIdlePresentationActive,
            boolean NativeEndpointGeometryEstablished,
            boolean RenderingOriginalOwner,
            boolean StationaryCustomPresentationActive)
    {
        return ContinueActiveCatchUp ||
                VisibleMovementSegmentInProgress ||
                PreservingReleasedFacing ||
                EndpointIdlePresentationActive ||
                NativeEndpointGeometryEstablished ||
                RenderingOriginalOwner ||
                StationaryCustomPresentationActive;
    }

    static boolean ShouldContinueReleasedFacingForPendingWalk(
            boolean PreservingReleasedFacing,
            boolean NativeFacingSettled)
    {
        return PreservingReleasedFacing &&
                !NativeFacingSettled;
    }

    static boolean ShouldUseDetachedEndpointIdleForFacingHold(
            boolean HoldingWalkStopFacing,
            boolean NativeEndpointGeometryEstablished)
    {
        return HoldingWalkStopFacing &&
                !NativeEndpointGeometryEstablished;
    }

    static boolean ShouldBlockOriginalOwnerForPendingWalk(
            boolean WalkSegmentAwaitingMovement,
            boolean HoldingWalkStopFacing,
            boolean NativeHandoffReadyThisFrame,
            boolean NativeEndpointGeometryEstablished)
    {
        return WalkSegmentAwaitingMovement ||
                (HoldingWalkStopFacing &&
                        !NativeHandoffReadyThisFrame &&
                        !NativeEndpointGeometryEstablished);
    }

    static boolean ShouldPreservePendingWalkEndpointGeometry(
            boolean WalkSegmentAwaitingMovement,
            boolean HoldingStoppedPresentation,
            boolean EndpointIdlePresentationActive,
            boolean PublishedStoppedModelIsSafe,
            int OwnerActionAnimation,
            boolean SpecialPresentationActive,
            int OwnerPoseAnimation,
            int IdlePoseAnimation)
    {
        // A yellow click can expose the hidden actor's locomotion pose before
        // its first authoritative segment. Keep refreshing the ordinary
        // smoothed idle while that actor is still idle, but retain the last
        // safe published geometry throughout this premature pose seam. A real
        // action remains authoritative and bypasses this hold. Dedicated
        // detached endpoint idle also has priority so locomotion can never be
        // retained as its fallback geometry.
        return WalkSegmentAwaitingMovement &&
                HoldingStoppedPresentation &&
                !EndpointIdlePresentationActive &&
                PublishedStoppedModelIsSafe &&
                OwnerActionAnimation == NO_ANIMATION &&
                !SpecialPresentationActive &&
                OwnerPoseAnimation != IdlePoseAnimation;
    }

    static boolean ShouldMarkPublishedStoppedGeometrySafe(
            boolean Moving,
            int OwnerActionAnimation,
            boolean SpecialPresentationActive,
            int OwnerPoseAnimation,
            int OwnerPoseFrame,
            int IdlePoseAnimation,
            boolean PublishedBaseModelAvailable)
    {
        return !Moving &&
                OwnerActionAnimation == NO_ANIMATION &&
                !SpecialPresentationActive &&
                IdlePoseAnimation != NO_ANIMATION &&
                OwnerPoseAnimation == IdlePoseAnimation &&
                OwnerPoseFrame >= 0 &&
                PublishedBaseModelAvailable;
    }

    static boolean ShouldSelectMovementPose(
            boolean EndpointIdlePresentationActive,
            boolean SegmentPositionMoving,
            boolean SceneBoundaryBridgeActive,
            boolean RouteGapGraceActive)
    {
        return !EndpointIdlePresentationActive &&
                (SegmentPositionMoving ||
                        SceneBoundaryBridgeActive ||
                        RouteGapGraceActive);
    }

    static boolean ShouldResetLocomotionAfterEndpointIdle(
            boolean HeldEndpointIdleWasDisplayed,
            boolean NewAuthoritativeSegmentStarted)
    {
        return HeldEndpointIdleWasDisplayed &&
                NewAuthoritativeSegmentStarted;
    }

    static boolean ShouldResetLocomotionForNewSegment(
            boolean EndpointIdlePresentationActive,
            boolean ResetAlreadyPending)
    {
        return EndpointIdlePresentationActive ||
                ResetAlreadyPending;
    }

    static boolean UpdateEndpointNativeHandoffLatch(
            boolean HandoffEstablished,
            boolean HandoffReadyNow,
            boolean NewAuthoritativeSegment,
            boolean AllowNativeEndpointGeometry)
    {
        return AllowNativeEndpointGeometry &&
                !NewAuthoritativeSegment &&
                (HandoffEstablished || HandoffReadyNow);
    }

    static boolean CanUseNativeEndpointGeometry(
            boolean AllowOriginalModel,
            boolean AnimationSmoothingActive)
    {
        // Smoothing is implemented by the ordinary Player.getModel() path.
        // The custom RuneLiteObject can consume that geometry while retaining
        // its own position/orientation even when drawing the original actor is
        // disabled by configuration.
        return AllowOriginalModel ||
                AnimationSmoothingActive;
    }

    static boolean DoesEndpointIdleGeometryMatch(
            boolean NativeIdleClockShared,
            int OwnerPoseFrame,
            int HeldEndpointIdleFrame)
    {
        return NativeIdleClockShared ||
                (HeldEndpointIdleFrame >= 0 &&
                        OwnerPoseFrame == HeldEndpointIdleFrame);
    }

    static boolean IsEndpointNativeIdleClockShared(
            boolean AnimationSmoothingActive,
            boolean NativeIdlePoseOverrideActive)
    {
        return AnimationSmoothingActive &&
                NativeIdlePoseOverrideActive;
    }

    static boolean ShouldInvalidateEndpointIdleCache(
            boolean NativeIdlePoseOverrideActive,
            int PreviousIdlePoseAnimation,
            int CurrentIdlePoseAnimation)
    {
        // The native-smoothed path refreshes Owner.getModel every render. A
        // walk/run/turn selector update therefore needs caching, but must not
        // restart the unchanged idle clock. A different idle sequence has no
        // compatible phase and still requires the ordinary reset.
        return !NativeIdlePoseOverrideActive ||
                PreviousIdlePoseAnimation != CurrentIdlePoseAnimation;
    }

    static int GetNativeEndpointGeometryOrientationThreshold(
            boolean AnimationSmoothingActive,
            int OriginalModelOrientationThreshold)
    {
        // Orientation is a render transform, not part of Player model-local
        // geometry. Smoothed geometry may therefore move to the custom object
        // before the actual original actor is orientation-safe to display.
        return AnimationSmoothingActive
                ? 2047
                : OriginalModelOrientationThreshold;
    }

    static boolean IsEndpointNativeHandoffReady(
            boolean AllowNativeEndpointGeometry,
            boolean AtFinalDestination,
            boolean HiddenOwnerCaughtUp,
            boolean HiddenOwnerIdlePoseReady,
            boolean HiddenOwnerIdleGeometryMatches,
            boolean NativeStateObservedAfterEntry,
            boolean NativeFacingReadyForHandoff,
            int OwnerActionAnimation,
            boolean HasActiveSpotAnimation,
            int OrientationDifference,
            int OrientationThreshold)
    {
        return AllowNativeEndpointGeometry &&
                AtFinalDestination &&
                HiddenOwnerCaughtUp &&
                HiddenOwnerIdlePoseReady &&
                HiddenOwnerIdleGeometryMatches &&
                NativeStateObservedAfterEntry &&
                NativeFacingReadyForHandoff &&
                OwnerActionAnimation == NO_ANIMATION &&
                !HasActiveSpotAnimation &&
                Math.abs(OrientationDifference) <=
                        OrientationThreshold;
    }

    static boolean HasHiddenOwnerCaughtUp(
            LocalPoint OwnerLocation,
            LocalPoint RenderDestination)
    {
        return OwnerLocation != null &&
                RenderDestination != null &&
                OwnerLocation.equals(RenderDestination);
    }

    static boolean ShouldPreserveReleasedWalkFacing(
            boolean NativeFacingSettled,
            int SettledNativeTargetOrientation,
            int CurrentNativeTargetOrientation)
    {
        return !NativeFacingSettled ||
                SettledNativeTargetOrientation ==
                        CurrentNativeTargetOrientation;
    }

    static boolean HasNativeWalkFacingSettled(
            long CurrentTime,
            long LastNativeFacingChangeTime)
    {
        return CurrentTime - LastNativeFacingChangeTime >=
                WALK_STOP_NATIVE_FACING_SETTLE_MILLIS;
    }

    static boolean ShouldContinueActiveWalkStopCatchUp(
            boolean WalkRouteArmed,
            boolean MovementWasObserved,
            boolean HoldFacingThisFrame)
    {
        return WalkRouteArmed &&
                MovementWasObserved &&
                HoldFacingThisFrame;
    }

    static boolean ShouldHoldPendingWalkStart(
            boolean WalkStartPendingDuringCatchUp,
            boolean WalkRouteArmed,
            boolean PreviousMovementWasObserved,
            boolean VisibleModelIsMoving)
    {
        return WalkStartPendingDuringCatchUp &&
                WalkRouteArmed &&
                PreviousMovementWasObserved &&
                !VisibleModelIsMoving;
    }

    static boolean IsMovementSegmentFromLatestWalkClick(
            long LatestWalkClickRevision,
            long MovementSegmentWalkClickRevision)
    {
        return LatestWalkClickRevision ==
                MovementSegmentWalkClickRevision;
    }

    static boolean IsVisibleMovementSegmentInProgress(
            boolean MovementSelected,
            int MillisecondsSinceTileChange,
            long MovementTweenDurationMilliseconds,
            LocalPoint SegmentStart,
            LocalPoint SegmentEnd)
    {
        return MovementSelected &&
                MillisecondsSinceTileChange <
                        MovementTweenDurationMilliseconds &&
                IsSameWorldView(SegmentStart, SegmentEnd) &&
                !SegmentStart.equals(SegmentEnd);
    }

    static int SelectPoseFrameForPublication(
            int CurrentPoseAnimation,
            int CurrentPoseFrame,
            int RequestedPoseAnimation,
            int LastValidPoseAnimation,
            int LastValidPoseFrame,
            int AnimationFrameCount,
            int StartingFrame,
            boolean ResetRequested,
            boolean ForceRequestedEntryFrame)
    {
        if (!ResetRequested &&
                !ForceRequestedEntryFrame &&
                CurrentPoseFrame >= 0 &&
                CurrentPoseFrame < AnimationFrameCount)
        {
            return CurrentPoseFrame;
        }

        if (!ResetRequested &&
                !ForceRequestedEntryFrame &&
                CurrentPoseFrame < 0 &&
                CurrentPoseAnimation == RequestedPoseAnimation &&
                LastValidPoseAnimation == RequestedPoseAnimation &&
                LastValidPoseFrame >= 0 &&
                LastValidPoseFrame < AnimationFrameCount)
        {
            return LastValidPoseFrame;
        }

        return StartingFrame >= 0 &&
                StartingFrame < AnimationFrameCount
                ? StartingFrame
                : 0;
    }

    static boolean ShouldRestartStationaryIdlePose(
            boolean Moving,
            int OwnerActionAnimation,
            int CurrentPoseAnimation,
            int RequestedPoseAnimation,
            int IdlePoseAnimation)
    {
        // [TMA-STATIONARY-IDLE-ENTRY] The hidden actor can still publish a
        // locomotion pose after the responsive custom model has stopped. This
        // happens after both yellow movement and red-click approaches, so the
        // correction is keyed to the actual stationary run-to-idle mismatch,
        // not to click colour. Directional walk/run changes remain untouched
        // because cross-animation phase is rejected only after movement ends.
        return !Moving &&
                OwnerActionAnimation == NO_ANIMATION &&
                IdlePoseAnimation != NO_ANIMATION &&
                RequestedPoseAnimation == IdlePoseAnimation &&
                CurrentPoseAnimation != RequestedPoseAnimation;
    }

    private PlayerAppearanceKey GetCurrentPlayerAppearance()
    {
        if (!(Owner instanceof Player))
        {
            return null;
        }
        return PlayerAppearanceKey.From(
                ((Player) Owner).getPlayerComposition());
    }

    private boolean HeldEndpointIdleAppearanceMatches()
    {
        return Owner instanceof Player &&
                HeldEndpointIdleAppearance != null &&
                HeldEndpointIdleAppearance.Matches(
                        ((Player) Owner).getPlayerComposition());
    }

    private boolean HasActiveSpotAnimation()
    {
        IterableHashTable<ActorSpotAnim> SpotAnimations =
                Owner.getSpotAnims();
        if (SpotAnimations == null)
        {
            return false;
        }

        for (ActorSpotAnim SpotAnimation : SpotAnimations)
        {
            if (SpotAnimation != null &&
                    SpotAnimation.getId() != NO_ANIMATION &&
                    SpotAnimation.getFrame() >= 0 &&
                    SpotAnimation.getStartCycle() <=
                            client.getGameCycle())
            {
                return true;
            }
        }
        return false;
    }

    static boolean ShouldMirrorActorSpotAnimation(
            int SpotAnimationId,
            int SpotAnimationFrame,
            int SpotAnimationStartCycle,
            int GameCycle,
            boolean NativeOwnerSuppressed)
    {
        return NativeOwnerSuppressed &&
                SpotAnimationId != NO_ANIMATION &&
                SpotAnimationFrame >= 0 &&
                SpotAnimationStartCycle <= GameCycle;
    }

    private void ClearMirroredActorSpotAnimations()
    {
        PublishedActorSpotSlots.clear();
        for (RuneLiteObject SpotModel :
                MirroredActorSpotModels.values())
        {
            if (SpotModel.getBaseModel() != null)
            {
                // Keep the registered object available for its bounded actor
                // spot slot, but make it inert between native effects.
                SpotModel.setModel(null);
            }
        }
    }

    private void RemoveMirroredActorSpotAnimations()
    {
        for (RuneLiteObject SpotModel :
                MirroredActorSpotModels.values())
        {
            SpotModel.setActive(false);
            client.removeRuneLiteObject(SpotModel);
        }
        MirroredActorSpotModels.clear();
        PublishedActorSpotSlots.clear();
    }

    private void UpdateMirroredActorSpotAnimations(
            boolean NativeOwnerSuppressed)
    {
        if (!NativeOwnerSuppressed ||
                Model == null ||
                Model.getLocation() == null)
        {
            ClearMirroredActorSpotAnimations();
            return;
        }

        IterableHashTable<ActorSpotAnim> SpotAnimations =
                Owner.getSpotAnims();
        if (SpotAnimations == null)
        {
            ClearMirroredActorSpotAnimations();
            return;
        }

        PublishedActorSpotSlots.clear();
        int GameCycle = client.getGameCycle();
        for (ActorSpotAnim SpotAnimation : SpotAnimations)
        {
            if (SpotAnimation == null ||
                    !ShouldMirrorActorSpotAnimation(
                            SpotAnimation.getId(),
                            SpotAnimation.getFrame(),
                            SpotAnimation.getStartCycle(),
                            GameCycle,
                            NativeOwnerSuppressed))
            {
                continue;
            }

            net.runelite.api.Model SourceSpotModel =
                    SpotAnimation.getModel();
            net.runelite.api.Model DetachedSpotModel =
                    SourceSpotModel == null
                            ? null
                            : client.mergeModels(SourceSpotModel);
            if (DetachedSpotModel == null)
            {
                continue;
            }
            DetachedSpotModel.calculateBoundsCylinder();

            long SpotSlot = SpotAnimation.getHash();
            RuneLiteObject SpotModel =
                    MirroredActorSpotModels.get(SpotSlot);
            if (SpotModel == null)
            {
                SpotModel = client.createRuneLiteObject();
                MirroredActorSpotModels.put(SpotSlot, SpotModel);
            }

            // Match the native actor-spot submission: the effect model already
            // owns its height offset; only the actor's scene transform remains.
            SpotModel.setRenderMode(SpotAnimation.getRenderMode());
            SpotModel.setLocation(
                    Model.getLocation(),
                    Model.getLevel());
            SpotModel.setOrientation(Model.getOrientation());
            SpotModel.setZ(Model.getZ());
            SpotModel.setModel(DetachedSpotModel);
            if (!SpotModel.isActive())
            {
                SpotModel.setActive(true);
            }
            PublishedActorSpotSlots.add(SpotSlot);
        }

        for (Map.Entry<Long, RuneLiteObject> Entry :
                MirroredActorSpotModels.entrySet())
        {
            if (!PublishedActorSpotSlots.contains(Entry.getKey()) &&
                    Entry.getValue().getBaseModel() != null)
            {
                Entry.getValue().setModel(null);
            }
        }
    }

    private boolean IsAnimationInterpolationActive(int AnimationId)
    {
        IntPredicate AnimationInterpolationFilter =
                client.getAnimationInterpolationFilter();
        return AnimationId != NO_ANIMATION &&
                AnimationInterpolationFilter != null &&
                AnimationInterpolationFilter.test(AnimationId);
    }

    static boolean ShouldOverrideNativeLocomotionPoseSelectors(
            boolean CustomPlayerPresentationActive,
            boolean Moving,
            boolean EndpointIdlePresentationActive,
            boolean AnimationSmoothingActive,
            boolean SpecialPresentationActive,
            int OwnerActionAnimation,
            int RequestedActionAnimation,
            int RequestedPoseAnimation)
    {
        // The native actor selects a movement pose again every client cycle.
        // If a directional selector differs from the pose already chosen by
        // the custom movement path, forcing the requested pose afterward
        // restarts the native interpolation clock every 20 ms. Pointing those
        // movement selectors at the same pose lets RuneLite's existing actor
        // clock and Animation Smoothing advance without a competing write.
        return CustomPlayerPresentationActive &&
                Moving &&
                !EndpointIdlePresentationActive &&
                AnimationSmoothingActive &&
                !SpecialPresentationActive &&
                OwnerActionAnimation == NO_ANIMATION &&
                RequestedActionAnimation == NO_ANIMATION &&
                RequestedPoseAnimation != NO_ANIMATION;
    }

    static int SelectNativeIdlePoseSelectorAnimation(
            boolean OverrideIdlePoseAnimation,
            int RequestedPoseAnimation,
            int NativeIdlePoseAnimation)
    {
        // Endpoint presentation genuinely represents idle and needs every
        // native selector on the same sequence. During locomotion, retaining
        // the real idle selector preserves the actor's observable movement
        // semantics without affecting walk/run/turn smoothing.
        return OverrideIdlePoseAnimation
                ? RequestedPoseAnimation
                : NativeIdlePoseAnimation;
    }

    private void StopUsingHeldEndpointIdleModel()
    {
        if (bUsingHeldEndpointIdleModel)
        {
            bUsingHeldEndpointIdleModel = false;
        }
    }

    private void ClearHeldEndpointIdleModel()
    {
        StopUsingHeldEndpointIdleModel();
        bEndpointIdlePoseWasDisplayed = false;
        HeldEndpointIdleModel = null;
        HeldEndpointIdleAppearance = null;
        HeldEndpointIdleAnimation = NO_ANIMATION;
        HeldEndpointIdleFrame = -1;
        EndpointIdlePresentationStartGameCycle = -1;
        EndpointIdleFrameClock = null;
        EndpointIdleFrameClockAnimation = NO_ANIMATION;
        EndpointIdleFrameClockGameCycle = -1;
        EndpointIdleDesiredFrame = 0;
        bEndpointIdleFrameNeedsPublication = false;
    }

    private void InvalidateHeldEndpointIdleForRecapture()
    {
        StopUsingHeldEndpointIdleModel();
        HeldEndpointIdleModel = null;
        HeldEndpointIdleAppearance = null;
        HeldEndpointIdleAnimation = NO_ANIMATION;
        HeldEndpointIdleFrame = -1;
        if (bEndpointIdlePresentationActive)
        {
            bEndpointIdleFrameNeedsPublication = true;
            bResetCurrentAnimation = true;
        }
    }

    static int GetEndpointIdleClockDelta(
            int CurrentGameCycle,
            int PreviousGameCycle)
    {
        return PreviousGameCycle < 0 ||
                CurrentGameCycle <= PreviousGameCycle
                ? 0
                : CurrentGameCycle - PreviousGameCycle;
    }

    static int GetControllerAnimationClockDelta(
            int CurrentGameCycle,
            int PreviousGameCycle)
    {
        if (PreviousGameCycle < 0 ||
                CurrentGameCycle <= PreviousGameCycle)
        {
            return 0;
        }

        // A stalled client should not turn one render update into an
        // unbounded animation catch-up loop. This still covers two seconds
        // of normal client-cycle progress while bounding recovery work after
        // a longer gap.
        return Math.min(100, CurrentGameCycle - PreviousGameCycle);
    }

    static boolean ShouldPublishEndpointIdleFrame(
            int HeldAnimation,
            int HeldFrame,
            int RequestedAnimation,
            int RequestedFrame,
            boolean AppearanceMatches)
    {
        return !AppearanceMatches ||
                HeldAnimation != RequestedAnimation ||
                HeldFrame != RequestedFrame;
    }

    private boolean UpdateEndpointIdleFrameClock()
    {
        if (!bEndpointIdlePresentationActive ||
                CurrentAnimationRequest == null ||
                CurrentAnimationRequest.PoseAnimationToPlay !=
                        OldAnimationSet.IdlePoseAnimation)
        {
            return false;
        }

        int RequestedIdleAnimation =
                OldAnimationSet.IdlePoseAnimation;
        Animation IdleAnimation =
                client.loadAnimation(RequestedIdleAnimation);
        if (IdleAnimation == null ||
                IdleAnimation.getNumFrames() <= 0)
        {
            return false;
        }

        int CurrentGameCycle = client.getGameCycle();
        boolean bResetClock =
                EndpointIdleFrameClock == null ||
                        EndpointIdleFrameClockAnimation !=
                                RequestedIdleAnimation ||
                        EndpointIdleFrameClock.getAnimation() == null ||
                        EndpointIdleFrameClock.getAnimation().getId() !=
                                RequestedIdleAnimation;
        if (bResetClock)
        {
            EndpointIdleFrameClock =
                    new AnimationController(client, IdleAnimation);
            EndpointIdleFrameClock.setOnFinished(
                    AnimationController::loop);
            EndpointIdleDesiredFrame =
                    CurrentAnimationRequest.StartingFrame >= 0 &&
                            CurrentAnimationRequest.StartingFrame <
                                    IdleAnimation.getNumFrames()
                            ? CurrentAnimationRequest.StartingFrame
                            : 0;
            EndpointIdleFrameClock.setFrame(
                    EndpointIdleDesiredFrame);
            EndpointIdleFrameClockAnimation =
                    RequestedIdleAnimation;
            EndpointIdleFrameClockGameCycle =
                    CurrentGameCycle;
            bEndpointIdleFrameNeedsPublication = true;
            return true;
        }

        int ElapsedClientCycles = GetEndpointIdleClockDelta(
                CurrentGameCycle,
                EndpointIdleFrameClockGameCycle);
        EndpointIdleFrameClockGameCycle = CurrentGameCycle;
        if (ElapsedClientCycles > 0)
        {
            try
            {
                EndpointIdleFrameClock.tick(
                        ElapsedClientCycles);
            }
            catch (RuntimeException ClockFailure)
            {
                // A malformed/replaced animation must not expose hidden
                // locomotion. Restart the independent idle clock and retry
                // the native keyframe build below.
                log.debug(
                        "Unable to advance endpoint idle animation",
                        ClockFailure);
                EndpointIdleFrameClock.setAnimation(
                        IdleAnimation);
                EndpointIdleFrameClock.setFrame(0);
            }
        }

        int NextDesiredFrame = EndpointIdleFrameClock.getFrame();
        if (NextDesiredFrame < 0 ||
                NextDesiredFrame >= IdleAnimation.getNumFrames())
        {
            NextDesiredFrame = 0;
            EndpointIdleFrameClock.reset();
        }
        if (EndpointIdleDesiredFrame != NextDesiredFrame)
        {
            EndpointIdleDesiredFrame = NextDesiredFrame;
            bEndpointIdleFrameNeedsPublication = true;
        }
        return true;
    }

    private boolean IsHeldEndpointIdleModelPublished()
    {
        return HeldEndpointIdleModel != null &&
                Model.getBaseModel() == HeldEndpointIdleModel;
    }

    private boolean TryPublishHeldEndpointIdleModel()
    {
        if (bEndpointIdlePresentationActive &&
                IsAnimationInterpolationActive(
                        OldAnimationSet.IdlePoseAnimation))
        {
            StopUsingHeldEndpointIdleModel();
            return false;
        }

        if (!bEndpointIdlePresentationActive ||
                Owner.getAnimation() != NO_ANIMATION ||
                HasActiveSpotAnimation() ||
                AnimController == null ||
                AnimController.getAnimation() != null)
        {
            StopUsingHeldEndpointIdleModel();
            return false;
        }

        if (!UpdateEndpointIdleFrameClock())
        {
            StopUsingHeldEndpointIdleModel();
            return false;
        }

        boolean bAppearanceMatches =
                HeldEndpointIdleAppearanceMatches();
        if (ShouldPublishEndpointIdleFrame(
                HeldEndpointIdleAnimation,
                HeldEndpointIdleFrame,
                OldAnimationSet.IdlePoseAnimation,
                EndpointIdleDesiredFrame,
                bAppearanceMatches))
        {
            if (!bAppearanceMatches &&
                    HeldEndpointIdleAppearance != null)
            {
                InvalidateHeldEndpointIdleForRecapture();
            }
            else
            {
                bEndpointIdleFrameNeedsPublication = true;
            }
            return false;
        }

        if (bEndpointIdleFrameNeedsPublication ||
                HeldEndpointIdleModel == null)
        {
            return false;
        }

        if (!bUsingHeldEndpointIdleModel ||
                Model.getBaseModel() != HeldEndpointIdleModel)
        {
            // Both null is RuneLiteObject's static-mesh mode. The mesh itself
            // advances at authored idle keyframes through the independent
            // clock above; it is never transformed a second time here.
            Model.setAnimationController(null);
            Model.setPoseAnimationController(null);
            Model.setModel(HeldEndpointIdleModel);
        }
        bUsingHeldEndpointIdleModel = true;
        bEndpointIdlePoseWasDisplayed = true;
        // This mesh was built explicitly from the authored endpoint idle and
        // is always safe stopped presentation geometry.
        bPublishedStoppedGeometrySafe = true;
        return true;
    }

    private boolean CanCaptureEndpointIdleModel()
    {
        if (bEndpointIdlePresentationActive &&
                IsAnimationInterpolationActive(
                        OldAnimationSet.IdlePoseAnimation))
        {
            return false;
        }

        if (!UpdateEndpointIdleFrameClock())
        {
            return false;
        }
        return ShouldCaptureEndpointIdleModel(
                Owner instanceof Player,
                bEndpointIdlePresentationActive,
                CurrentAnimationRequest != null &&
                        CurrentAnimationRequest.AnimationToPlay ==
                                NO_ANIMATION,
                CurrentAnimationRequest != null &&
                        CurrentAnimationRequest.PoseAnimationToPlay ==
                                OldAnimationSet.IdlePoseAnimation,
                Owner.getAnimation() == NO_ANIMATION,
                Owner.getPoseAnimation() ==
                        OldAnimationSet.IdlePoseAnimation,
                Owner.getPoseAnimationFrame() ==
                        EndpointIdleDesiredFrame,
                HasActiveSpotAnimation());
    }

    static boolean ShouldCaptureEndpointIdleModel(
            boolean PlayerOwner,
            boolean EndpointIdlePresentationActive,
            boolean OrdinaryAnimationRequest,
            boolean RequestedPoseIsIdle,
            boolean OwnerActionFree,
            boolean OwnerPoseIsIdle,
            boolean OwnerPoseAtRequestedFrame,
            boolean HasActiveSpotAnimation)
    {
        return PlayerOwner &&
                EndpointIdlePresentationActive &&
                OrdinaryAnimationRequest &&
                RequestedPoseIsIdle &&
                OwnerActionFree &&
                OwnerPoseIsIdle &&
                OwnerPoseAtRequestedFrame &&
                !HasActiveSpotAnimation;
    }

    private boolean TryCaptureEndpointIdleModel()
    {
        if (!CanCaptureEndpointIdleModel())
        {
            return false;
        }

        PlayerAppearanceKey Appearance = GetCurrentPlayerAppearance();
        if (Appearance == null)
        {
            return false;
        }

        // Explicit pose publication is continuity-sensitive in the ordinary
        // render path, and an integer frame alone does not guarantee that
        // Animation Smoothing will build the requested discrete geometry.
        // Temporarily disable smoothing for this one synchronous native model
        // build, then restore the caller's filter before any other client work
        // can run. Do not restore the owner's pose fields afterward: doing so
        // previously produced malformed limbs, and the hidden native update
        // will repopulate them normally.
        IntPredicate AnimationInterpolationFilter =
                client.getAnimationInterpolationFilter();
        net.runelite.api.Model DetachedIdleModel;
        try
        {
            client.setAnimationInterpolationFilter(null);
            net.runelite.api.Model SourceOwnerModel = Owner.getModel();
            DetachedIdleModel = SourceOwnerModel == null
                    ? null
                    : client.mergeModels(SourceOwnerModel);
        }
        catch (RuntimeException CaptureFailure)
        {
            log.debug(
                    "Unable to capture endpoint idle model",
                    CaptureFailure);
            return false;
        }
        finally
        {
            client.setAnimationInterpolationFilter(
                    AnimationInterpolationFilter);
        }
        if (DetachedIdleModel == null)
        {
            return false;
        }
        DetachedIdleModel.calculateBoundsCylinder();

        HeldEndpointIdleModel = DetachedIdleModel;
        HeldEndpointIdleAppearance = Appearance;
        HeldEndpointIdleAnimation =
                OldAnimationSet.IdlePoseAnimation;
        HeldEndpointIdleFrame =
                EndpointIdleDesiredFrame;
        bEndpointIdleFrameNeedsPublication = false;
        return true;
    }

    private boolean TrySetModel(net.runelite.api.Model SourceModel)
    {
        if (SourceModel == null)
        {
            return false;
        }
        net.runelite.api.Model MergedModel =
                client.mergeModels(SourceModel);
        if (MergedModel == null)
        {
            return false;
        }

        Model.setModel(MergedModel);
        // Any replacement must prove that it is stopped-idle geometry before
        // a pending yellow-click seam may retain it. This prevents a completed
        // action or spot model from becoming the frozen fallback.
        bPublishedStoppedGeometrySafe = false;
        return true;
    }

    private void UpdateOldIdleAnimations()
    {
        // Ignore the pose value currently owned by the plugin, while still
        // accepting any different selector values the game publishes during
        // the brief override (for example, an equipment/movement-set change).
        int PluginOwnedMovementPoseAnimation =
                NativePoseSelectorOverrideAnimation != NO_ANIMATION
                        ? NativePoseSelectorOverrideAnimation
                        : CurrentPoseAnimation;
        int PluginOwnedIdlePoseAnimation =
                bEndpointNativeIdlePoseOverrideActive
                        ? NativePoseSelectorOverrideAnimation
                        : CurrentPoseAnimation;
        int PreviousIdlePoseAnimation =
                OldAnimationSet.IdlePoseAnimation;
        boolean bAnyChanges = false;
        if (Owner.getIdleRotateLeft() != NO_ANIMATION &&
                Owner.getIdleRotateLeft() !=
                        PluginOwnedMovementPoseAnimation &&
                OldAnimationSet.IdleRotateLeft != Owner.getIdleRotateLeft())
        {
            OldAnimationSet.IdleRotateLeft = Owner.getIdleRotateLeft();
            bAnyChanges = true;
        }

        if (Owner.getIdleRotateRight() != NO_ANIMATION &&
                Owner.getIdleRotateRight() !=
                        PluginOwnedMovementPoseAnimation &&
                OldAnimationSet.IdleRotateRight != Owner.getIdleRotateRight())
        {
            OldAnimationSet.IdleRotateRight = Owner.getIdleRotateRight();
            bAnyChanges = true;
        }

        if (Owner.getWalkAnimation() != NO_ANIMATION &&
                Owner.getWalkAnimation() !=
                        PluginOwnedMovementPoseAnimation &&
                OldAnimationSet.WalkAnimation != Owner.getWalkAnimation())
        {
            OldAnimationSet.WalkAnimation = Owner.getWalkAnimation();
            bAnyChanges = true;
        }

        if (Owner.getWalkRotateLeft() != NO_ANIMATION &&
                Owner.getWalkRotateLeft() !=
                        PluginOwnedMovementPoseAnimation &&
                OldAnimationSet.WalkRotateLeft != Owner.getWalkRotateLeft())
        {
            OldAnimationSet.WalkRotateLeft = Owner.getWalkRotateLeft();
            bAnyChanges = true;
        }

        if (Owner.getWalkRotateRight() != NO_ANIMATION &&
                Owner.getWalkRotateRight() !=
                        PluginOwnedMovementPoseAnimation &&
                OldAnimationSet.WalkRotateRight != Owner.getWalkRotateRight())
        {
            OldAnimationSet.WalkRotateRight = Owner.getWalkRotateRight();
            bAnyChanges = true;
        }

        if (Owner.getWalkRotate180() != NO_ANIMATION &&
                Owner.getWalkRotate180() !=
                        PluginOwnedMovementPoseAnimation &&
                OldAnimationSet.WalkRotate180 != Owner.getWalkRotate180())
        {
            OldAnimationSet.WalkRotate180 = Owner.getWalkRotate180();
            bAnyChanges = true;
        }

        if (Owner.getIdlePoseAnimation() != NO_ANIMATION &&
                Owner.getIdlePoseAnimation() !=
                        PluginOwnedIdlePoseAnimation &&
                OldAnimationSet.IdlePoseAnimation != Owner.getIdlePoseAnimation())
        {
            OldAnimationSet.IdlePoseAnimation = Owner.getIdlePoseAnimation();
            bAnyChanges = true;
        }

        if (Owner.getRunAnimation() != NO_ANIMATION &&
                Owner.getRunAnimation() !=
                        PluginOwnedMovementPoseAnimation &&
                OldAnimationSet.RunAnimation != Owner.getRunAnimation())
        {
            OldAnimationSet.RunAnimation = Owner.getRunAnimation();
            bAnyChanges = true;
        }

        if (bAnyChanges)
        {
            // A different movement set can carry a different authored idle
            // pose even when the equipment arrays did not change.
            if (ShouldInvalidateEndpointIdleCache(
                    bEndpointNativeIdlePoseOverrideActive,
                    PreviousIdlePoseAnimation,
                    OldAnimationSet.IdlePoseAnimation))
            {
                InvalidateHeldEndpointIdleForRecapture();
            }
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
    private static long GetTotalGcCollectionTimeMillis()
    {
        long TotalCollectionTimeMillis = 0;
        for (GarbageCollectorMXBean CollectorBean :
                ManagementFactory.getGarbageCollectorMXBeans())
        {
            TotalCollectionTimeMillis += CollectorBean.getCollectionTime();
        }
        return TotalCollectionTimeMillis;
    }

    private void UpdateFrameTimer()
    {
        CurrentTime = System.currentTimeMillis();
        // A freshly constructed handler has no previous frame to measure
        // against. Treating it as one huge delta would overflow the elapsed
        // clock and corrupt the first-frame movement state.
        int RawFrameDelta = 0;
        if (LastTimeMilliseconds > 0)
        {
            RawFrameDelta = (int) Math.max(
                    0,
                    Math.min(
                            Integer.MAX_VALUE,
                            CurrentTime - LastTimeMilliseconds));
        }
        LastTimeMilliseconds = CurrentTime;
        CurrentFrameDelta = RawFrameDelta;

        // [TMA-STALL-TRACE] A long gap between consecutive Update() calls
        // means the hitch happened outside this handler (client tick, GPU
        // draw/upload, other plugins, or a GC pause), not in a traced stage.
        // The GC collector clock tells those apart: a pause reports gcMs in
        // the same magnitude as the gap, a GPU/driver stall reports ~0.
        if (config.DebugStallTrace())
        {
            long GcCollectionTimeMillis =
                    GetTotalGcCollectionTimeMillis();
            if (RawFrameDelta >=
                    DebugFileLogger.STALL_TRACE_FRAME_GAP_THRESHOLD_MILLIS)
            {
                long GcTimeDeltaMillis =
                        GcCollectionTimeMillis -
                                LastGcCollectionTimeMillis;
                Runtime RuntimeSnapshot = Runtime.getRuntime();
                long HeapUsedMb =
                        (RuntimeSnapshot.totalMemory() -
                                RuntimeSnapshot.freeMemory()) /
                                (1024L * 1024L);
                DebugFileLogger.Append(
                        DebugFileLogger.STALL_TRACE_LOG_FILE,
                        "[TMA-STALL-TRACE] frame-gap ms=" + RawFrameDelta +
                                " gcMs=" + GcTimeDeltaMillis +
                                " heapUsedMB=" + HeapUsedMb +
                                " moving=" + bMovingThisAction +
                                " walkArmed=" +
                                bWalkStopFacingHoldArmed +
                                " awaiting=" +
                                bWalkSegmentAwaitingMovement +
                                " elapsed=" +
                                MillisecondsSinceTileChange);
            }
            LastGcCollectionTimeMillis = GcCollectionTimeMillis;
        }

        if (bScenePresentationClockActive)
        {
            int ImmediateDelta =
                    GetScenePresentationImmediateFrameDelta(
                            RawFrameDelta);
            ScenePresentationTimeDebtMilliseconds =
                    Math.min(
                            SCENE_PRESENTATION_MAX_TIME_DEBT_MILLIS,
                            ScenePresentationTimeDebtMilliseconds +
                                    Math.max(
                                            0,
                                            RawFrameDelta -
                                                    ImmediateDelta));
            int DebtPayback =
                    GetScenePresentationDebtPayback(
                            ImmediateDelta,
                            ScenePresentationTimeDebtMilliseconds,
                            ScenePresentationDebtPaybackRemainder);
            ScenePresentationDebtPaybackRemainder =
                    GetScenePresentationDebtPaybackRemainder(
                            ImmediateDelta,
                            ScenePresentationTimeDebtMilliseconds,
                            ScenePresentationDebtPaybackRemainder);
            CurrentFrameDelta = ImmediateDelta + DebtPayback;
            ScenePresentationTimeDebtMilliseconds -= DebtPayback;

            if (ShouldReleaseScenePresentationClock(
                    ScenePresentationTimeDebtMilliseconds,
                    bSceneRecoveryRetargetPending,
                    SceneRecoveryTweenDurationOverride,
                    bSceneLoadFramePresentationPending))
            {
                bScenePresentationClockActive = false;
                ScenePresentationDebtPaybackRemainder = 0;
                SceneRecoveryBaseVelocity = 0;
            }
        }

        if (CurrentFrameDelta > 0)
        {
            MillisecondsSinceTileChange += CurrentFrameDelta;
        }
    }

    void MarkSceneLoadFramePresented()
    {
        if (!bScenePresentationClockActive ||
                LastTimeMilliseconds <= 0)
        {
            return;
        }

        long PresentationTime = System.currentTimeMillis();
        int DeferredMilliseconds = (int) Math.max(
                0,
                Math.min(
                        SCENE_PRESENTATION_MAX_TIME_DEBT_MILLIS,
                        PresentationTime - LastTimeMilliseconds));
        ScenePresentationTimeDebtMilliseconds =
                Math.min(
                        SCENE_PRESENTATION_MAX_TIME_DEBT_MILLIS,
                        ScenePresentationTimeDebtMilliseconds +
                                DeferredMilliseconds);
        bSceneLoadFramePresentationPending = false;
        // The prepared state has now actually reached the screen. Start the
        // next delta here; the elapsed scene-build time is represented by the
        // debt above and will be repaid at a bounded rate.
        CurrentTime = PresentationTime;
        LastTimeMilliseconds = PresentationTime;
    }

    private boolean UpdateTrueTileLocation()
    {
        CurrentWorldPoint = Owner.getWorldLocation();
        if (CurrentWorldPoint == null)
        {
            return false;
        }

        LocalPoint LocalCurrentTrueTilePosition = LocalPoint.fromWorld(client, CurrentWorldPoint);
        if (LocalCurrentTrueTilePosition == null)
        {
            // Region loading may temporarily have no local conversion for a
            // valid world point. Keep the previous fully rendered frame until
            // the new scene can be rebased.
            return false;
        }
        if (!LocalCurrentTrueTilePosition.equals(CurrentTrueTilePosition))
        {
            // Also record the last one
            LastTrueTilePosition = CurrentTrueTilePosition;
            CurrentTrueTilePosition = LocalCurrentTrueTilePosition;
        }
        return true;
    }

    private static boolean IsSameWorldView(
            LocalPoint First,
            LocalPoint Second)
    {
        return First != null &&
                Second != null &&
                First.getWorldView() ==
                        Second.getWorldView();
    }

    static boolean ContainsPlayerOwnedHouseRegions(int[] MapRegions)
    {
        if (MapRegions == null)
        {
            return false;
        }

        boolean bHasWestRegion = false;
        boolean bHasEastRegion = false;
        for (int Region : MapRegions)
        {
            bHasWestRegion |= Region == POH_INSTANCE_REGION_WEST;
            bHasEastRegion |= Region == POH_INSTANCE_REGION_EAST;
        }
        return bHasWestRegion && bHasEastRegion;
    }

    private boolean IsInPlayerOwnedHouseInstance()
    {
        WorldView WorldView = Owner == null
                ? null
                : Owner.getWorldView();
        return WorldView != null &&
                WorldView.isInstance() &&
                ContainsPlayerOwnedHouseRegions(
                        WorldView.getMapRegions());
    }

    static boolean ShouldSynchronizePohArrivalPresentation(
            boolean bGuardArmed,
            int GuardSceneGeneration,
            int CurrentSceneGeneration,
            LocalPoint NativeLocation,
            LocalPoint... PresentationLocations)
    {
        if (!bGuardArmed ||
                GuardSceneGeneration != CurrentSceneGeneration ||
                NativeLocation == null)
        {
            return false;
        }

        for (LocalPoint PresentationLocation : PresentationLocations)
        {
            if (PresentationLocation != null &&
                    !NativeLocation.equals(PresentationLocation))
            {
                return true;
            }
        }
        return false;
    }

    static boolean ShouldArmPohArrivalCoordinateGuard(
            boolean bInPlayerOwnedHouse,
            boolean bGuardArmed,
            int ReleasedSceneGeneration,
            int CurrentSceneGeneration)
    {
        return bInPlayerOwnedHouse &&
                !bGuardArmed &&
                ReleasedSceneGeneration != CurrentSceneGeneration;
    }

    private void ArmPohArrivalCoordinateGuard()
    {
        int CurrentSceneGeneration = plugin.GetSceneGeneration();
        if (!ShouldArmPohArrivalCoordinateGuard(
                IsPlayerOwner() &&
                        IsInPlayerOwnedHouseInstance(),
                bPohArrivalCoordinateGuardArmed,
                PohArrivalGuardReleasedSceneGeneration,
                CurrentSceneGeneration))
        {
            return;
        }

        bPohArrivalCoordinateGuardArmed = true;
        PohArrivalCoordinateGuardSceneGeneration =
                CurrentSceneGeneration;
    }

    private void SynchronizePohArrivalCoordinateToNative()
    {
        if (!bPohArrivalCoordinateGuardArmed ||
                PohArrivalCoordinateGuardSceneGeneration !=
                        plugin.GetSceneGeneration())
        {
            return;
        }

        LocalPoint NativeLocation = Owner.getLocalLocation();
        LocalPoint ModelLocation = Model == null
                ? null
                : Model.getLocation();
        boolean bOutOfSync =
                ShouldSynchronizePohArrivalPresentation(
                        bPohArrivalCoordinateGuardArmed,
                        PohArrivalCoordinateGuardSceneGeneration,
                        plugin.GetSceneGeneration(),
                        NativeLocation,
                        LastLerpPosition,
                        NextLerpPosition,
                        NewLocalPointToDraw,
                        ModelLocation);
        if (!bOutOfSync)
        {
            return;
        }

        WorldPoint NativeWorldPoint = Owner.getWorldLocation();
        LastLerpPosition = NativeLocation;
        NextLerpPosition = NativeLocation;
        NewLocalPointToDraw = NativeLocation;
        LastLerpPositionWorldPoint = NativeWorldPoint;
        NextLerpPositionWorldPoint = NativeWorldPoint;
        LastTrueTilePosition = NativeLocation;
        CurrentTrueTilePosition = NativeLocation;
        CurrentWorldPoint = NativeWorldPoint;
        MillisecondsSinceTileChange = BASE_MOVEMENT_TWEEN_MILLIS;
        bRetainedSceneMovementSegmentInProgress = false;
        RetainedMovementProgressTick = -1;
        RetainedMovementProgressGameCycle = -1;
        bFreshMovementPublicationBoundaryBridgeActive = false;
        FreshMovementPublicationBoundaryGameCycle = -1;
        MovementSegmentPublicationTick = -1;
        MovementSegmentPublicationGameCycle = -1;
        bSceneRecoveryRetargetPending = false;
        bSceneBoundaryBridgeActive = false;
        bPostSceneRecoveryRouteGapEligible = false;
        bPendingWalkInheritedActiveRouteGap = false;
        bScenePresentationClockActive = false;
        bSceneLoadFramePresentationPending = false;
        ScenePresentationTimeDebtMilliseconds = 0;
        ScenePresentationDebtPaybackRemainder = 0;
        SceneRecoveryBaseVelocity = 0;
        SceneRecoveryTweenDurationOverride = 0;
        if (Model != null)
        {
            Model.setLocation(
                    NativeLocation,
                    Owner.getWorldView().getPlane());
        }
    }

    void DisarmPohArrivalCoordinateGuardForUserInteraction()
    {
        // Record the interaction even if a transient handler cleanup cleared
        // the live flag immediately beforehand. This prevents a later update
        // from re-arming arrival behavior after the user has started moving.
        PohArrivalGuardReleasedSceneGeneration =
                plugin.GetSceneGeneration();
        if (!bPohArrivalCoordinateGuardArmed)
        {
            return;
        }

        bPohArrivalCoordinateGuardArmed = false;
        PohArrivalCoordinateGuardSceneGeneration = -1;
    }

    boolean ShouldUseNativeCameraForPohArrival()
    {
        ArmPohArrivalCoordinateGuard();
        return bPohArrivalCoordinateGuardArmed &&
                PohArrivalCoordinateGuardSceneGeneration ==
                        plugin.GetSceneGeneration();
    }

    private boolean HasSceneMovementDiscontinuity()
    {
        int OwnerAnimation = Owner == null
                ? NO_ANIMATION
                : Owner.getAnimation();
        int RequestedAnimation =
                CurrentAnimationRequest == null
                        ? NO_ANIMATION
                        : CurrentAnimationRequest.AnimationToPlay;
        boolean RequestedTeleport =
                CurrentAnimationRequest != null &&
                        CurrentAnimationRequest
                                .bShouldTeleportToLocation;
        return IsSceneMovementDiscontinuity(
                OwnerAnimation,
                RequestedAnimation,
                RequestedTeleport,
                UniqueAnimationExceptionList,
                UniqueAnimationLocationAndOrientationExceptionList);
    }

    static int GetSceneRebaseElapsedMilliseconds(int FrameDelta)
    {
        return Math.max(0, Math.min(
                BASE_MOVEMENT_TWEEN_MILLIS,
                FrameDelta));
    }

    static int GetSceneRebaseElapsedMilliseconds(
            int FrameDelta,
            boolean bHasRecoveryDistance)
    {
        return bHasRecoveryDistance
                ? GetSceneRebaseElapsedMilliseconds(FrameDelta)
                : BASE_MOVEMENT_TWEEN_MILLIS;
    }

    static int GetSceneRebaseElapsedMilliseconds(
            int FrameDelta,
            boolean bHasRecoveryDistance,
            boolean bUseNativeHandoffAnchor)
    {
        if (!bHasRecoveryDistance)
        {
            return BASE_MOVEMENT_TWEEN_MILLIS;
        }
        if (bUseNativeHandoffAnchor)
        {
            // The native owner was just presented. Advancing before the first
            // custom draw would create a smaller version of the same seam.
            return 0;
        }
        return GetSceneRebaseElapsedMilliseconds(FrameDelta);
    }

    static int GetSceneRebaseImmediateElapsedMilliseconds(
            int FrameDelta,
            boolean bHasRecoveryDistance,
            boolean bUseNativeHandoffAnchor)
    {
        int RebaseElapsedMilliseconds =
                GetSceneRebaseElapsedMilliseconds(
                        FrameDelta,
                        bHasRecoveryDistance,
                        bUseNativeHandoffAnchor);
        return bHasRecoveryDistance &&
                !bUseNativeHandoffAnchor
                ? GetScenePresentationImmediateFrameDelta(
                        RebaseElapsedMilliseconds)
                : RebaseElapsedMilliseconds;
    }

    static int GetSceneRebaseDeferredMilliseconds(
            int FrameDelta,
            boolean bHasRecoveryDistance,
            boolean bUseNativeHandoffAnchor)
    {
        if (!bHasRecoveryDistance ||
                bUseNativeHandoffAnchor)
        {
            return 0;
        }

        int RebaseElapsedMilliseconds =
                GetSceneRebaseElapsedMilliseconds(
                        FrameDelta,
                        true,
                        false);
        return Math.max(
                0,
                RebaseElapsedMilliseconds -
                        GetSceneRebaseImmediateElapsedMilliseconds(
                                FrameDelta,
                                true,
                                false));
    }

    static int AddScenePresentationDebt(
            int ExistingDebtMilliseconds,
            int AdditionalDebtMilliseconds)
    {
        return (int) Math.min(
                SCENE_PRESENTATION_MAX_TIME_DEBT_MILLIS,
                (long) Math.max(0, ExistingDebtMilliseconds) +
                        Math.max(0, AdditionalDebtMilliseconds));
    }

    static int SelectScenePresentationDebtAfterRebase(
            int ExistingDebtMilliseconds,
            int AdditionalDebtMilliseconds,
            boolean bHasRecoveryDistance,
            boolean bUseNativeHandoffAnchor)
    {
        // Debt describes time still owed to a retained custom movement path.
        // A native handoff or a zero-distance rebase has no such path, so
        // carrying old debt into it would leave stale debt with no active
        // presentation clock.
        return bHasRecoveryDistance &&
                !bUseNativeHandoffAnchor
                ? AddScenePresentationDebt(
                        ExistingDebtMilliseconds,
                        AdditionalDebtMilliseconds)
                : 0;
    }

    static boolean ShouldReanchorSceneRecovery(
            boolean bRecoveryPending,
            int ElapsedMilliseconds,
            long TweenDurationMilliseconds,
            boolean bHasDisplayedPoint)
    {
        return bRecoveryPending &&
                bHasDisplayedPoint &&
                ElapsedMilliseconds < TweenDurationMilliseconds;
    }

    static LocalPoint SelectRouteUpdateOrigin(
            boolean ReanchorSceneRecovery,
            boolean PreviousEndpointWithinSnapDistance,
            LocalPoint DisplayedPoint,
            LocalPoint PreviousAuthoritativeEndpoint,
            LocalPoint ConvertedPreviousEndpoint,
            LocalPoint RequestedEndpoint)
    {
        if (ReanchorSceneRecovery && DisplayedPoint != null)
        {
            return DisplayedPoint;
        }
        if (PreviousEndpointWithinSnapDistance &&
                ConvertedPreviousEndpoint != null)
        {
            return PreviousAuthoritativeEndpoint;
        }
        return ConvertedPreviousEndpoint == null
                ? RequestedEndpoint
                : ConvertedPreviousEndpoint;
    }

    static boolean ShouldResetRouteDirectionBaseline(
            boolean ReanchorSceneRecovery,
            boolean PreviousEndpointWithinSnapDistance)
    {
        return !ReanchorSceneRecovery &&
                !PreviousEndpointWithinSnapDistance;
    }

    static LocalPoint SelectSceneRebaseAnchor(
            boolean bNativeHandoffPresented,
            LocalPoint OwnerLocation,
            LocalPoint RetainedCustomLocation)
    {
        return bNativeHandoffPresented &&
                OwnerLocation != null
                ? OwnerLocation
                : RetainedCustomLocation;
    }

    void MarkNativeSceneLoadHandoffPresented()
    {
        bNativeSceneLoadHandoffPresented = true;
    }

    boolean DidLastSceneRebaseUseNativeHandoffAnchor()
    {
        return bLastSceneRebaseUsedNativeHandoffAnchor;
    }

    private boolean RebaseAfterSceneLoad()
    {
        LocalPoint OwnerLocation = Owner.getLocalLocation();
        WorldPoint OwnerWorldPoint = Owner.getWorldLocation();
        LocalPoint CurrentTrueLocation = OwnerWorldPoint == null
                ? null
                : LocalPoint.fromWorld(client, OwnerWorldPoint);
        if (OwnerLocation == null ||
                OwnerWorldPoint == null ||
                CurrentTrueLocation == null)
        {
            return false;
        }

        long PreservedTweenDuration =
                GetMovementTweenDurationMilliseconds();
        boolean bSpecialMovementDiscontinuity =
                HasSceneMovementDiscontinuity();
        boolean bRetainedSegmentOnCurrentPlane =
                LastLerpPositionWorldPoint != null &&
                        NextLerpPositionWorldPoint != null &&
                        LastLerpPositionWorldPoint.getPlane() ==
                                NextLerpPositionWorldPoint.getPlane() &&
                        NextLerpPositionWorldPoint.getPlane() ==
                                OwnerWorldPoint.getPlane();
        boolean bCanPreserveMovementVelocity =
                CanPreserveSceneMovementVelocity(
                        bSceneBoundaryBridgeActive,
                        bRetainedSceneMovementSegmentInProgress,
                        bWalkStopFacingHoldArmed &&
                                bWalkMovementObserved,
                        bRetainedSegmentOnCurrentPlane,
                        bSpecialMovementDiscontinuity);
        // [TMA-SCENE-RECOVERY-VELOCITY] LOADING soft-suspends Update(), so
        // these endpoints still describe the last pre-load segment. A cached
        // rebuild can begin while that segment is visibly moving, before the
        // overdue boundary bridge arms. Capture its scalar speed in either
        // case before the rebase overwrites it. Direction is deliberately not
        // retained or extrapolated.
        double PreservedMovementVelocity =
                GetPreservedSceneMovementVelocity(
                        bCanPreserveMovementVelocity,
                        LastLerpPosition,
                        NextLerpPosition,
                        PreservedTweenDuration);
        // This proof belongs only to the pre-load presentation just consumed.
        // A successful destination-scene update records a fresh snapshot below.
        bRetainedSceneMovementSegmentInProgress = false;
        RetainedMovementProgressTick = -1;
        RetainedMovementProgressGameCycle = -1;
        bFreshMovementPublicationBoundaryBridgeActive = false;
        FreshMovementPublicationBoundaryGameCycle = -1;
        MovementSegmentPublicationTick = -1;
        MovementSegmentPublicationGameCycle = -1;

        LocalPoint RetainedCustomLocation =
                LastRenderedWorldPoint == null
                ? null
                : LocalPoint.fromWorld(client, LastRenderedWorldPoint);
        if (RetainedCustomLocation != null)
        {
            RetainedCustomLocation = new LocalPoint(
                    RetainedCustomLocation.getX() +
                            LastRenderedWorldOffsetX,
                    RetainedCustomLocation.getY() +
                            LastRenderedWorldOffsetY,
                    RetainedCustomLocation.getWorldView());
        }
        boolean bUseNativeHandoffAnchor =
                bNativeSceneLoadHandoffPresented;
        bLastSceneRebaseUsedNativeHandoffAnchor =
                bUseNativeHandoffAnchor;
        LocalPoint RenderedLocation = SelectSceneRebaseAnchor(
                bUseNativeHandoffAnchor,
                OwnerLocation,
                RetainedCustomLocation);
        boolean bRealDiscontinuity =
                !IsSameWorldView(RenderedLocation, CurrentTrueLocation) ||
                (!bUseNativeHandoffAnchor &&
                        (LastRenderedWorldPoint == null ||
                                LastRenderedWorldPoint.getPlane() !=
                                        OwnerWorldPoint.getPlane()));
        if (!bRealDiscontinuity &&
                !bUseNativeHandoffAnchor)
        {
            bRealDiscontinuity =
                    ExceedsSceneRecoverySnapDistance(
                            RenderedLocation,
                            CurrentTrueLocation,
                            config.PlayerModelSnapDistance());
        }
        if (bRealDiscontinuity)
        {
            // A real teleport/instance change has no shared scene location.
            // It is correct to snap once to the new native point in that case.
            RenderedLocation = CurrentTrueLocation;
            LastRenderedWorldOffsetX = 0;
            LastRenderedWorldOffsetY = 0;
        }

        bPohArrivalCoordinateGuardArmed = false;
        PohArrivalCoordinateGuardSceneGeneration = -1;

        LastLerpPosition = RenderedLocation;
        NewLocalPointToDraw = RenderedLocation;
        // Use the true-tile center here. Seeding this with OwnerLocation (the
        // hidden actor's fractional position) made generic destination logic
        // overwrite RenderedLocation during this same update.
        NextLerpPosition = CurrentTrueLocation;
        LastLerpPositionWorldPoint = WorldPoint.fromLocal(client,
                RenderedLocation);
        NextLerpPositionWorldPoint = OwnerWorldPoint;
        LastTrueTilePosition = RenderedLocation;
        CurrentTrueTilePosition = CurrentTrueLocation;
        CurrentWorldPoint = OwnerWorldPoint;
        // Carry only time for which rendering was missed. This prevents the
        // model pausing at the restored point and then skipping when the next
        // server route step arrives.
        boolean bHasRecoveryDistance =
                !RenderedLocation.equals(CurrentTrueLocation);
        boolean bAwaitingPostSceneWalkSegment =
                ShouldAwaitPostSceneWalkSegment(
                        bRealDiscontinuity,
                        bSpecialMovementDiscontinuity,
                        bWalkStopFacingHoldArmed,
                        bWalkMovementObserved,
                        CurrentTrueLocation,
                        client.getLocalDestinationLocation());
        bPostSceneRecoveryRouteGapEligible =
                ShouldArmPostSceneRecoveryRouteGap(
                        bAwaitingPostSceneWalkSegment,
                        bHasRecoveryDistance);
        if (bRealDiscontinuity ||
                bSpecialMovementDiscontinuity)
        {
            // A teleport, plane/view replacement, or animation-authoritative
            // displacement must not inherit a yellow route from the old scene.
            CancelWalkStopFacingHold();
        }
        else if (bAwaitingPostSceneWalkSegment)
        {
            // [TMA-POST-SCENE-YELLOW-HANDOFF] The replacement scene may expose
            // the old destination before its first new route segment. Reuse
            // the pending-yellow state so the endpoint keeps a stable idle and
            // facing instead of running in place and then chasing the hidden
            // actor's orientation. A genuinely moving recovery may retain
            // locomotion through only the existing bounded publication seam;
            // after that, this stable handoff resumes. No position is invented,
            // and the state releases as soon as a real segment arrives.
            bWalkStartPendingDuringCatchUp = true;
            bWalkSegmentAwaitingMovement = true;
        }
        double RecoveryDistance = bHasRecoveryDistance
                ? RenderedLocation.distanceTo(CurrentTrueLocation)
                : 0;
        // [TMA-SCENE-REBASE-FIRST-DELTA] UpdateFrameTimer runs before the
        // scene presentation clock becomes active. Without applying the same
        // cap here, the first recovered frame could consume a raw 20-49 ms
        // delta while every later frame was capped at 34 ms. Preserve that
        // time as debt instead of turning it into a larger first position and
        // camera step after an unavoidable native scene pause.
        ScenePresentationTimeDebtMilliseconds =
                SelectScenePresentationDebtAfterRebase(
                        ScenePresentationTimeDebtMilliseconds,
                        GetSceneRebaseDeferredMilliseconds(
                                CurrentFrameDelta,
                                bHasRecoveryDistance,
                                bUseNativeHandoffAnchor),
                        bHasRecoveryDistance,
                        bUseNativeHandoffAnchor);
        SceneRecoveryBaseVelocity =
                GetRetainedSceneRecoveryBaseVelocity(
                        PreservedMovementVelocity,
                        RecoveryDistance,
                        PreservedTweenDuration);
        // A partial rebased segment must take a proportional fraction of the
        // original segment time. Previously it always took a full 600 ms,
        // visibly slowing after the scene-edge bridge before catching up.
        SceneRecoveryTweenDurationOverride =
                bHasRecoveryDistance &&
                        PreservedMovementVelocity > 0
                        ? GetSceneRecoveryTweenDuration(
                                RecoveryDistance,
                                SceneRecoveryBaseVelocity,
                                PreservedTweenDuration)
                        : 0;
        bScenePresentationClockActive =
                bHasRecoveryDistance;
        ScenePresentationDebtPaybackRemainder = 0;
        bSceneLoadFramePresentationPending =
                bHasRecoveryDistance;
        MillisecondsSinceTileChange =
                GetSceneRebaseImmediateElapsedMilliseconds(
                        CurrentFrameDelta,
                        bHasRecoveryDistance,
                        bUseNativeHandoffAnchor);
        bSceneRecoveryRetargetPending =
                bHasRecoveryDistance &&
                        MillisecondsSinceTileChange <
                                GetMovementTweenDurationMilliseconds();

        if (Model != null)
        {
            Model.setLocation(RenderedLocation,
                    Owner.getWorldView().getPlane());
            Model.setOrientation(CurrentOrientation);
        }

        bNativeSceneLoadHandoffPresented = false;
        return true;
    }

    boolean IsSceneLoadVisualReady()
    {
        if (bSceneRebasePending ||
                Owner == null ||
                Owner.getLocalLocation() == null)
        {
            return false;
        }

        if (bShouldRenderOwner)
        {
            return true;
        }

        return Model != null &&
                Model.getModel() != null &&
                Model.getLocation() != null &&
                IsSameWorldView(
                        Model.getLocation(),
                        Owner.getLocalLocation());
    }

    boolean CanSuppressOwnerInCurrentScene()
    {
        return !bShouldRenderOwner &&
                !bRenderOriginalOwnerDueToProximity &&
                IsSceneLoadVisualReady() &&
                Model.isActive();
    }

    private void RecordLastRenderedLocation(LocalPoint RenderLocation)
    {
        if (RenderLocation == null)
        {
            return;
        }

        LastRenderedWorldPoint = WorldPoint.fromLocal(client, RenderLocation);
        LocalPoint TileLocation = LastRenderedWorldPoint == null
                ? null
                : LocalPoint.fromWorld(client, LastRenderedWorldPoint);
        if (!IsSameWorldView(TileLocation, RenderLocation))
        {
            LastRenderedWorldOffsetX = 0;
            LastRenderedWorldOffsetY = 0;
            return;
        }

        LastRenderedWorldOffsetX = RenderLocation.getX() -
                TileLocation.getX();
        LastRenderedWorldOffsetY = RenderLocation.getY() -
                TileLocation.getY();
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
            if (RequestedLerpPoint == null)
            {
                return;
            }
            int RequestedLerpPlane = CurrentWorldPoint.getPlane();
            if (NextLerpPosition == null)
            {
                NextLerpPosition = RequestedLerpPoint;
            }
            if (LastLerpPosition == null)
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

                long CurrentTweenDuration =
                        GetMovementTweenDurationMilliseconds();
                long NormalTweenDuration =
                        GetNormalMovementTweenDurationMilliseconds();
                boolean bSameAuthoritativeSegmentPlane =
                        NextLerpPositionWorldPoint != null &&
                                CurrentWorldPoint != null &&
                                NextLerpPositionWorldPoint.getPlane() ==
                                        CurrentWorldPoint.getPlane();
                boolean bSpecialMovementDiscontinuity =
                        HasSceneMovementDiscontinuity();
                boolean bPlausibleAuthoritativeSegment =
                        IsPlausibleAuthoritativeSceneSegment(
                                bWalkStopFacingHoldArmed &&
                                        bWalkMovementObserved,
                                bSameAuthoritativeSegmentPlane,
                                bSpecialMovementDiscontinuity,
                                NextLerpPoint,
                                RequestedLerpPoint);
                double AuthoritativeSegmentVelocity =
                        GetPreservedSceneMovementVelocity(
                                bPlausibleAuthoritativeSegment,
                                NextLerpPoint,
                                RequestedLerpPoint,
                                NormalTweenDuration);
                boolean bReanchorBoundaryBridge =
                        bSceneBoundaryBridgeActive &&
                                CanUseSceneBoundaryBridge(
                                        bSceneRecoveryRetargetPending,
                                        bScenePresentationClockActive,
                                        SceneRecoveryTweenDurationOverride) &&
                                NewLocalPointToDraw != null;
                boolean bReanchorRecovery =
                        bReanchorBoundaryBridge ||
                                ShouldReanchorSceneRecovery(
                                bSceneRecoveryRetargetPending ||
                                        bScenePresentationClockActive ||
                                        SceneRecoveryTweenDurationOverride > 0,
                                MillisecondsSinceTileChange,
                                CurrentTweenDuration,
                                NewLocalPointToDraw != null);
                boolean bPreviousEndpointWithinSnapDistance =
                        NextLerpPoint != null &&
                        DistanceInTilesToNextLerp <=
                                config.PlayerModelSnapDistance() &&
                        DistanceInTilesToLast <=
                                config.PlayerModelSnapDistance();
                // [TMA-AUTHORITATIVE-ROUTE-ORIGIN] Only a real scene-recovery
                // bridge continues from the fractional displayed point.
                // Ordinary route segments begin at the previous authoritative
                // endpoint; otherwise the rendered fraction contaminates the
                // direction baseline and can reclassify a two-tile run as a
                // one-tile walk on the next segment.
                LastLerpPosition = SelectRouteUpdateOrigin(
                        bReanchorRecovery,
                        bPreviousEndpointWithinSnapDistance,
                        NewLocalPointToDraw,
                        NextLerpPosition,
                        NextLerpPoint,
                        RequestedLerpPoint);
                LastLerpPositionWorldPoint = WorldPoint.fromLocal(
                        client,
                        LastLerpPosition);
                if (ShouldResetRouteDirectionBaseline(
                        bReanchorRecovery,
                        bPreviousEndpointWithinSnapDistance))
                {
                    // Missing/far converted endpoints represent a true
                    // discontinuity, so snap the authoritative baseline once.
                    LastTrueTilePosition = CurrentTrueTilePosition;
                }
                // A generic route change is not scene recovery. Recovery is
                // armed only by the scene/rebase paths that own that state.
                bSceneRecoveryRetargetPending = false;

                NextLerpPosition = RequestedLerpPoint;

                NextLerpPositionWorldPoint = CurrentWorldPoint;

                if (bReanchorRecovery)
                {
                    // Prefer the newly published native segment. This matters
                    // when the player changes from walking to running (or the
                    // reverse) across the load. The pre-load scalar is only a
                    // fallback when the old endpoint cannot be converted in
                    // the replacement scene.
                    boolean bCanUsePreLoadVelocity =
                            NextLerpPoint == null &&
                                    bWalkStopFacingHoldArmed &&
                                    bWalkMovementObserved &&
                                    bSameAuthoritativeSegmentPlane &&
                                    !bSpecialMovementDiscontinuity;
                    double SelectedVelocity =
                            SelectSceneRecoveryVelocity(
                                    AuthoritativeSegmentVelocity,
                                    bCanUsePreLoadVelocity
                                            ? SceneRecoveryBaseVelocity
                                            : 0);
                    double RecoveryDistance =
                            LastLerpPosition.distanceTo(
                                    NextLerpPosition);
                    if (SelectedVelocity > 0)
                    {
                        SceneRecoveryBaseVelocity =
                                GetSceneRecoveryBaseVelocity(
                                        SelectedVelocity,
                                        RecoveryDistance,
                                        NormalTweenDuration);
                        SceneRecoveryTweenDurationOverride =
                                GetSceneRecoveryTweenDuration(
                                        RecoveryDistance,
                                        SceneRecoveryBaseVelocity,
                                        NormalTweenDuration);
                    }
                    else
                    {
                        SceneRecoveryBaseVelocity = 0;
                        SceneRecoveryTweenDurationOverride = 0;
                    }
                }
                else
                {
                    SceneRecoveryTweenDurationOverride = 0;
                    if (!bScenePresentationClockActive)
                    {
                        SceneRecoveryBaseVelocity = 0;
                    }
                }

                MillisecondsSinceTileChange = bReanchorRecovery
                        ? (int) Math.max(
                                0,
                                Math.min(
                                        CurrentFrameDelta,
                                        CurrentTweenDuration))
                        : 0;
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

    private boolean IsTeleportPresentationActive()
    {
        return IsPlayerOwner() &&
                overlay.bShouldPlayTeleportAnimation &&
                GetTeleportElapsedNanoseconds() <
                        TELEPORT_ANIMATION_WINDOW_NANOS &&
                !overlay.bTeleportInterrupted;
    }

    private void UpdateAnimationSelection()
    {
        bShouldUseTrueLocationOrientation = false;
        boolean bEndpointIdleSmoothingActive =
                IsAnimationInterpolationActive(
                        OldAnimationSet.IdlePoseAnimation);
        boolean bAllowNativeEndpointGeometry =
                CanUseNativeEndpointGeometry(
                        config.AllowOriginalModelWhenCloseProximity(),
                        bEndpointIdleSmoothingActive);
        bEndpointNativeHandoffEstablished =
                UpdateEndpointNativeHandoffLatch(
                        bEndpointNativeHandoffEstablished,
                        false,
                        false,
                        bAllowNativeEndpointGeometry);
        if (!bAllowNativeEndpointGeometry)
        {
            bEndpointNativeHandoffReadyThisFrame = false;
        }
        bSceneBoundaryBridgeActive =
                ShouldBridgeSceneBoundaryMovement(
                        GetMovementTweenDurationMilliseconds());
        long MovementDuration =
                GetSceneMovementAnimationDuration(
                        SceneRecoveryTweenDurationOverride);
        boolean bKeepMovementAnimationDuringPostSceneRecoveryRouteGap =
                ShouldKeepMovementAnimationDuringPostSceneRecoveryRouteGap();
        boolean bKeepMovementAnimationDuringInheritedPendingRouteGap =
                ShouldKeepMovementAnimationDuringInheritedPendingRouteGap();
        boolean bKeepMovementAnimationDuringRouteGap =
                bKeepMovementAnimationDuringPostSceneRecoveryRouteGap ||
                bKeepMovementAnimationDuringInheritedPendingRouteGap ||
                ShouldKeepMovementAnimationDuringRouteGap(
                        MillisecondsSinceTileChange,
                        MovementDuration,
                        bMovingThisAction,
                        bWalkStopFacingHoldArmed &&
                                bWalkMovementObserved &&
                                IsMovementSegmentFromLatestWalkClick(
                                        WalkClickRevision,
                                        WalkClickRevisionAtMovementSegment),
                        bWalkSegmentAwaitingMovement,
                        bHoldWalkStopFacingThisFrame,
                        NextLerpPosition,
                        client.getLocalDestinationLocation());
        boolean bSegmentHasDistance =
                IsSameWorldView(LastLerpPosition, NextLerpPosition) &&
                        !LastLerpPosition.equals(NextLerpPosition);
        boolean bOrdinaryPoseRequest =
                CurrentAnimationRequest != null &&
                        CurrentAnimationRequest.AnimationToPlay ==
                                NO_ANIMATION;
        boolean bTeleportPresentationActive =
                IsTeleportPresentationActive();
        boolean bCelebrationPresentationActive =
                bTargetWasKilled &&
                        config.AllowNPCKilledCelebrationEmote() &&
                        LastNPCCombatLevel > 50 &&
                        bIsDefaultHumanAnimationSet;
        boolean bSpotAnimationPresentationActive =
                HasActiveSpotAnimation();
        boolean bSpecialPresentationActive =
                bTeleportPresentationActive ||
                        bCelebrationPresentationActive ||
                        bSpotAnimationPresentationActive;
        boolean bFreshMovementPublicationBoundaryBridgeEligible =
                ShouldBridgeFreshMovementPublicationBoundary(
                        MillisecondsSinceTileChange,
                        MovementDuration,
                        IsPlayerOwner(),
                        bRetainedSceneMovementSegmentInProgress,
                        bWalkSegmentAwaitingMovement,
                        bHoldWalkStopFacingThisFrame,
                        bSpecialPresentationActive,
                        Owner.getAnimation(),
                        NextLerpPosition,
                        client.getLocalDestinationLocation());
        FreshMovementPublicationBoundaryGameCycle =
                SelectFreshMovementPublicationBoundaryGameCycle(
                        bFreshMovementPublicationBoundaryBridgeEligible,
                        FreshMovementPublicationBoundaryGameCycle,
                        client.getGameCycle());
        bFreshMovementPublicationBoundaryBridgeActive =
                bFreshMovementPublicationBoundaryBridgeEligible &&
                        IsFreshMovementPublicationBoundaryBridgeCycle(
                                FreshMovementPublicationBoundaryGameCycle,
                                client.getGameCycle());
        boolean bWasEndpointIdlePresentationActive =
                bEndpointIdlePresentationActive;
        bEndpointIdlePresentationActive =
                ShouldUseEndpointIdlePresentation(
                        IsPlayerOwner() &&
                                !bAttemptToRenderOwner,
                        MillisecondsSinceTileChange >= MovementDuration &&
                                !bFreshMovementPublicationBoundaryBridgeActive,
                        bSegmentHasDistance,
                        bSceneBoundaryBridgeActive,
                        bOrdinaryPoseRequest,
                        bSpecialPresentationActive,
                        ShouldUseDetachedEndpointIdleForFacingHold(
                                bHoldWalkStopFacingThisFrame,
                                bEndpointNativeHandoffEstablished),
                        IsAtFinalWalkDestination(),
                        bEndpointNativeHandoffReadyThisFrame ||
                                bEndpointNativeHandoffEstablished,
                        Owner.getAnimation());
        if (bEndpointIdlePresentationActive &&
                !bWasEndpointIdlePresentationActive)
        {
            // Begin at the authored idle entry. Animation Smoothing keeps the
            // native actor clock from there; the fallback detached path uses
            // its independent client-cycle keyframe clock.
            ClearHeldEndpointIdleModel();
            EndpointIdlePresentationStartGameCycle =
                    client.getGameCycle();
            bEndpointIdleFrameNeedsPublication =
                    !bEndpointIdleSmoothingActive;
            bResetCurrentAnimation = true;
        }
        else if (!bEndpointIdlePresentationActive &&
                bWasEndpointIdlePresentationActive)
        {
            boolean bEndpointIdleWasDisplayed =
                    bEndpointIdlePoseWasDisplayed;
            boolean bResetLocomotionEntry =
                    ShouldResetLocomotionAfterEndpointIdle(
                            bEndpointIdleWasDisplayed,
                            bNewTileMovementStarted);
            if (bEndpointNativeIdlePoseOverrideActive)
            {
                SetAllIdlePosesDefault();
            }
            ClearHeldEndpointIdleModel();
            if (bResetLocomotionEntry)
            {
                // Continuous segment-to-segment movement retains phase. A
                // endpoint idle has no compatible locomotion phase, so resume
                // the new authoritative route at its authored entry.
                bResetCurrentAnimation = true;
            }
            else if (bEndpointIdleWasDisplayed)
            {
                // A fresh click can release endpoint presentation before its
                // first authoritative segment exists. Remember that phase
                // boundary so the later segment cannot inherit an arbitrary
                // idle frame.
                bLocomotionResetPendingAfterEndpointIdle = true;
            }
        }

        if (bEndpointIdlePresentationActive &&
                bEndpointIdleSmoothingActive)
        {
            // The ordinary Player.getModel path below owns presentation while
            // smoothing is active. Never force another integer keyframe after
            // the one-time idle entry publication.
            StopUsingHeldEndpointIdleModel();
            bEndpointIdleFrameNeedsPublication = false;
        }
        else if (bEndpointNativeIdlePoseOverrideActive)
        {
            // Animation Smoothing may be toggled while catch-up is active.
            // Return to the existing detached-keyframe path without leaving
            // the hidden actor's movement selectors remapped.
            SetAllIdlePosesDefault();
            InvalidateHeldEndpointIdleForRecapture();
        }

        // Override all animations
        //if (devConfig.DebugAnimation() != 0)
        //{
        //    CurrentAnimationRequest = AnimationRequestMoveset.GetDefaultIdleMoveAnimationRequest(config);
        //    CurrentAnimationRequest.AnimationToPlay = devConfig.DebugAnimation();
        //}
        //else

        // Currently moving
        if (ShouldSelectMovementPose(
                bEndpointIdlePresentationActive,
                MillisecondsSinceTileChange < MovementDuration ||
                        bTeleportPresentationActive,
                bSceneBoundaryBridgeActive,
                bKeepMovementAnimationDuringRouteGap ||
                        bFreshMovementPublicationBoundaryBridgeActive))
        {
            bMovingThisAction = true;

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

        // [TMA-TELEPORT] Restored original teleport animation selection.
        // A genuine teleport plays the teleport-in presentation within the
        // nanoTime window set by onGameTick/UpdateLerpDestinations. This
        // branch is gated on bShouldPlayTeleportAnimation being explicitly
        // set by a genuine teleport detection in onGameTick. Spell casting
        // animations are no longer in the teleport detection list, so this
        // branch can never be armed by ordinary PvP combat casting.
        if (IsPlayerOwner() &&
                overlay.bShouldPlayTeleportAnimation &&
                GetTeleportElapsedNanoseconds() <
                        TELEPORT_ANIMATION_WINDOW_NANOS &&
                !overlay.bTeleportInterrupted)
            {
                if (overlay.bShouldPlayTeleportAnimation &&
                        bIsDefaultHumanAnimationSet)
                {
                    if (ShouldUseIdlePoseDuringTeleportLeadIn(
                            bTeleportPresentationActive,
                            GetTeleportElapsedNanoseconds()))
                    {
                        // The native Player model owns the real cast/tablet
                        // action. If that action ends just before the arrival
                        // phase begins, hold ordinary idle rather than filling
                        // the stationary seam with a stale walk/run selector.
                        CurrentAnimationRequest =
                                AnimationRequestMoveset
                                        .GetDefaultIdleMoveAnimationRequest(
                                                config);
                        CurrentAnimationRequest.PoseAnimationToPlay =
                                OldAnimationSet.IdlePoseAnimation;
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
                            Owner.getLocalLocation().getY() == CurrentTrueTilePosition.getY())
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
            else if (config.AllowLeaping() &&
                    bCurrentlyWooxWalking &&
                    config.AllowWooxWalkDetection() &&
                    bIsDefaultHumanAnimationSet)
            {
                // [TMA-CAST-MOVEMENT-ORDERING] Owner.getAnimation() is the
                // single authority for an active cast. The old implementation
                // also remembered selected cast/teleport animation IDs and
                // later synthesized HUMAN_CASTTELEPORT_REVERSE (animation
                // 715). If the player clicked to move first, locomotion could
                // begin and that delayed animation would then replay the cast
                // and request a false positional snap. Keep locomotion
                // advancing underneath the real action animation instead.
                // Genuine teleports and other authoritative discontinuities
                // still use the normal movement request path; only the
                // delayed duplicate authority was removed.
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
            else if (config.AllowLeaping() &&
                    (config.AlwaysHoppingMode() ||
                            FramesSinceIdle > config.TickPerfectMovesUntilJumping()) &&
                    bIsDefaultHumanAnimationSet)
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
            if (bHoldWalkStopFacingThisFrame ||
                    bEndpointIdlePresentationActive)
            {
                // [TMA-STOP-FACING] Do not select a turn-in-place pose while
                // the hidden actor is completing a yellow-click route.
                CurrentAnimationRequest.PoseAnimationToPlay = OldAnimationSet.IdlePoseAnimation;
            }
            else
            {
                ChangeLastLerpPointForRotation();
                int ShortestAngle = ShortestAngleDifference(CurrentOrientation, TargetOrientation);
                if (ShortestAngle >= 10)
                {
                    CurrentAnimationRequest.PoseAnimationToPlay = OldAnimationSet.IdleRotateRight;
                }
                else if (ShortestAngle <= -10)
                {
                    CurrentAnimationRequest.PoseAnimationToPlay = OldAnimationSet.IdleRotateLeft;
                }
                else
                {
                    CurrentAnimationRequest.PoseAnimationToPlay = OldAnimationSet.IdlePoseAnimation;
                }
            }

            bWooxWalkBroken = true;
            FramesSinceIdle = 0;

            // We can transition to render the owner
            if (bAttemptToRenderOwner)
            {
                bShouldRenderOwner = true;
            }
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

    private boolean ShouldRenderOriginalOwner(
            boolean bUsedCustomAnimation)
    {
        LocalPoint OwnerLocation = Owner.getLocalLocation();
        LocalPoint ModelLocation = Model.getLocation();
        if (OwnerLocation == null ||
                ModelLocation == null ||
                !IsSameWorldView(
                        OwnerLocation,
                        ModelLocation))
        {
            return false;
        }

        return ShouldUseOriginalOwnerPresentation(
                config.AllowOriginalModelWhenCloseProximity(),
                bMovingThisAction,
                // Native-smoothed geometry is still being drawn at the custom
                // endpoint transform. Do not expose the actor's catch-up
                // location merely because it entered the proximity tolerance.
                bEndpointIdlePresentationActive ||
                        ShouldBlockOriginalOwnerForPendingWalk(
                                bWalkSegmentAwaitingMovement,
                                bHoldWalkStopFacingThisFrame,
                                bEndpointNativeHandoffReadyThisFrame,
                                bEndpointNativeHandoffEstablished),
                bUsedCustomAnimation,
                Owner.getAnimation(),
                OwnerLocation.getX() - ModelLocation.getX(),
                OwnerLocation.getY() - ModelLocation.getY(),
                ShortestAngleDifference(
                        Owner.getCurrentOrientation(),
                        Model.getOrientation()),
                config.OriginalModelProximityDistanceThreshold(),
                config.OriginalModelProximityOrientationThreshold());
    }

    private void UpdateEndpointNativeHandoffReadiness()
    {
        boolean bEndpointIdleSmoothingActive =
                IsAnimationInterpolationActive(
                        OldAnimationSet.IdlePoseAnimation);
        boolean bNativeIdleClockShared =
                IsEndpointNativeIdleClockShared(
                        bEndpointIdleSmoothingActive,
                        bEndpointNativeIdlePoseOverrideActive);
        boolean bAllowNativeEndpointGeometry =
                CanUseNativeEndpointGeometry(
                        config.AllowOriginalModelWhenCloseProximity(),
                        bEndpointIdleSmoothingActive);
        boolean bNativeStateObservedAfterEntry =
                EndpointIdlePresentationStartGameCycle < 0 ||
                        client.getGameCycle() !=
                                EndpointIdlePresentationStartGameCycle;
        boolean bNativeHandoffReadyNow =
                IsEndpointNativeHandoffReady(
                        bAllowNativeEndpointGeometry,
                        IsAtFinalWalkDestination(),
                        HasHiddenOwnerCaughtUp(
                                Owner.getLocalLocation(),
                                Model.getLocation()),
                        Owner.getPoseAnimation() ==
                                OldAnimationSet.IdlePoseAnimation &&
                                Owner.getPoseAnimationFrame() >= 0,
                        DoesEndpointIdleGeometryMatch(
                                bNativeIdleClockShared,
                                Owner.getPoseAnimationFrame(),
                                HeldEndpointIdleFrame),
                        bNativeStateObservedAfterEntry,
                        !bPreserveReleasedWalkFacing ||
                                bNativeWalkFacingSettled,
                        Owner.getAnimation(),
                        HasActiveSpotAnimation(),
                        ShortestAngleDifference(
                                Owner.getCurrentOrientation(),
                                Model.getOrientation()),
                        GetNativeEndpointGeometryOrientationThreshold(
                                bEndpointIdleSmoothingActive,
                                config.OriginalModelProximityOrientationThreshold()));
        bEndpointNativeHandoffReadyThisFrame =
                bEndpointNativeHandoffEstablished ||
                        bNativeHandoffReadyNow;

        if (bEndpointIdlePresentationActive &&
                bNativeHandoffReadyNow)
        {
            // Position, native idle geometry, and facing settlement are ready.
            // Leave catch-up ownership for RuneLite's ordinary Player.getModel
            // geometry path. The custom object retains its transform; actual
            // original-player display remains independently orientation-gated.
            bLocomotionResetPendingAfterEndpointIdle |=
                    bEndpointIdlePoseWasDisplayed;
            bEndpointNativeHandoffEstablished =
                    UpdateEndpointNativeHandoffLatch(
                            bEndpointNativeHandoffEstablished,
                            true,
                            false,
                            bAllowNativeEndpointGeometry);
            if (bEndpointNativeIdlePoseOverrideActive)
            {
                // Restore only the selector table. The native actor is now
                // stationary and already on this same idle sequence, so its
                // pose frame and smoothing subframe continue uninterrupted.
                SetAllIdlePosesDefault();
            }
            bEndpointIdlePresentationActive = false;
            ClearHeldEndpointIdleModel();
        }
    }

    private long GetNormalMovementTweenDurationMilliseconds()
    {
        // [TMA-CONTINUOUS-MOVEMENT-SPEED] Authoritative walking/running route
        // steps arrive on the game-tick clock. Shortening this duration made
        // the model reach NextLerpPosition early and wait there with its legs
        // still moving. ApplyTweening now expresses the user-facing
        // multiplier as bounded progress lead over the ordinary duration.
        // Animation-specific request multipliers retain their authored
        // leap/Woox choreography.
        double RequestMultiplier =
                CurrentAnimationRequest == null
                        ? 1.0
                        : CurrentAnimationRequest.MovementSpeedMultiplier;
        return GetRequestMovementTweenDurationMilliseconds(
                RequestMultiplier);
    }

    private double GetCurrentMovementLeadMultiplier()
    {
        return GetConfiguredMovementLeadMultiplier(
                config.MovementSpeedMultiplier());
    }

    private long GetMovementTweenDurationMilliseconds()
    {
        return SceneRecoveryTweenDurationOverride > 0
                ? SceneRecoveryTweenDurationOverride
                : GetNormalMovementTweenDurationMilliseconds();
    }

    private boolean ShouldBridgeSceneBoundaryMovement(
            long TweenDurationMilliseconds)
    {
        if (!CanUseSceneBoundaryBridge(
                bSceneRecoveryRetargetPending,
                bScenePresentationClockActive,
                SceneRecoveryTweenDurationOverride) ||
                !IsPlayerOwner() ||
                !bWalkStopFacingHoldArmed ||
                !bWalkMovementObserved ||
                MillisecondsSinceTileChange <
                        TweenDurationMilliseconds)
        {
            return false;
        }

        return IsSceneBoundaryExitSegment(
                LastLerpPosition,
                NextLerpPosition,
                client.getLocalDestinationLocation());
    }

    private void ApplyTweening()
    {
        // 600ms a tick, interpolate between true local point and last true tile position
        double TweenValue = 0;
        long TweenDurationMilliseconds =
                GetMovementTweenDurationMilliseconds();
        boolean bApplyNormalMovementLead =
                ShouldApplyContinuousMovementSpeedLead(
                        CurrentAnimationRequest
                                .bShouldTeleportToLocation,
                        bSceneBoundaryBridgeActive,
                        bSceneRebasePending,
                        SceneRecoveryTweenDurationOverride,
                        bSceneRecoveryRetargetPending,
                        bScenePresentationClockActive,
                        bSceneLoadFramePresentationPending);
        if (CurrentAnimationRequest.bShouldTeleportToLocation)
        {
            TweenValue = 1.0;
        }
        else if (bSceneBoundaryBridgeActive)
        {
            // [TMA-SCENE-LOAD-CONTINUITY] At a rebuild boundary the final
            // route point can be overdue before the next scene publishes its
            // continuation. Extend the already-authoritative segment for at
            // most 180 ms / half a tile so the model and adaptive camera do
            // not visibly stop. The strict yellow-click + boundary test above
            // prevents this from reviving interaction-object overshoot.
            TweenValue = GetSceneBoundaryBridgeTweenValue(
                    MillisecondsSinceTileChange,
                    TweenDurationMilliseconds,
                    LastLerpPosition.distanceTo(NextLerpPosition));
        }
        else if (CurrentAnimationRequest.bUseLinearTween)
        {
            TweenValue = linearTween(
                    0L,
                    TweenDurationMilliseconds,
                    MillisecondsSinceTileChange);
            if (bApplyNormalMovementLead)
            {
                TweenValue = ApplyContinuousMovementSpeedLead(
                        TweenValue,
                        GetCurrentMovementLeadMultiplier());
            }
        }
        else
        {
            TweenValue = quadraticTween(
                    0L,
                    TweenDurationMilliseconds,
                    MillisecondsSinceTileChange);
            if (bApplyNormalMovementLead)
            {
                TweenValue = ApplyContinuousMovementSpeedLead(
                        TweenValue,
                        GetCurrentMovementLeadMultiplier());
            }
        }

        NewLocalPointToDraw = new LocalPoint((int) (LastLerpPosition.getX() + (NextLerpPosition.getX() - LastLerpPosition.getX()) * TweenValue),
                (int) (LastLerpPosition.getY() + (NextLerpPosition.getY() - LastLerpPosition.getY()) * TweenValue),
                LastLerpPosition.getWorldView());

        if (MillisecondsSinceTileChange >=
                TweenDurationMilliseconds)
        {
            bSceneRecoveryRetargetPending = false;
            if (ShouldCompleteSceneRecoveryOverride(
                    MillisecondsSinceTileChange,
                    SceneRecoveryTweenDurationOverride))
            {
                // [TMA-SCENE-RECOVERY-COMPLETION] A short proportional
                // recovery may finish before the normal 600 ms movement
                // duration. Collapse the completed segment before clearing
                // its override; otherwise the next frame would reinterpret
                // the same elapsed time against 600 ms and move backward.
                LastLerpPosition = NextLerpPosition;
                LastLerpPositionWorldPoint =
                        NextLerpPositionWorldPoint;
                NewLocalPointToDraw = NextLerpPosition;
                MillisecondsSinceTileChange =
                        BASE_MOVEMENT_TWEEN_MILLIS;
                SceneRecoveryTweenDurationOverride = 0;
                if (!bScenePresentationClockActive)
                {
                    SceneRecoveryBaseVelocity = 0;
                }
            }
        }

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

        if (bHoldWalkStopFacingThisFrame)
        {
            TargetOrientation = CurrentOrientation;
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
                    bCameraModelNeedsPublish = true;
                }

                if (CurrentCameraModelIndex == 0)
                {
                    if (cameraModel.isActive())
                    {
                        cameraModel.setActive(false);
                    }
                }
                else
                {
                    // Re-merging and re-publishing an unchanged model every
                    // frame is pure client-thread churn (model copy + GPU
                    // upload attempt). Publish once per index switch.
                    if (bCameraModelNeedsPublish)
                    {
                        net.runelite.api.Model CameraSourceModel =
                                client.loadModel(CurrentCameraModelIndex);
                        net.runelite.api.Model DetachedCameraModel =
                                CameraSourceModel == null
                                        ? null
                                        : client.mergeModels(
                                                CameraSourceModel);
                        if (DetachedCameraModel != null)
                        {
                            cameraModel.setModel(DetachedCameraModel);
                            bCameraModelNeedsPublish = false;
                        }
                    }
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
                    CurrentCameraObjectOrientation += ORIENTATION_UNITS;
                }
                else if (CurrentCameraObjectOrientation >= ORIENTATION_UNITS)
                {
                    CurrentCameraObjectOrientation -= ORIENTATION_UNITS;
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
        if (NativePoseSelectorOverrideAnimation != NO_ANIMATION)
        {
            // A scene/equipment update can publish a new movement set before
            // the next ordinary handler update. Preserve any non-sentinel
            // native values before restoring the selector table.
            UpdateOldIdleAnimations();
        }
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
        bEndpointNativeIdlePoseOverrideActive = false;
        NativePoseSelectorOverrideAnimation = NO_ANIMATION;
    }

    private void SetAllMovementPosesToIdleAnimation()
    {
        SetAllMovementPoseSelectors(
                OldAnimationSet.IdlePoseAnimation,
                true);
        bEndpointNativeIdlePoseOverrideActive = true;
    }

    private void SetAllMovementPosesToLocomotionAnimation(
            int PoseAnimation)
    {
        SetAllMovementPoseSelectors(PoseAnimation, false);
        bEndpointNativeIdlePoseOverrideActive = false;
    }

    private void SetAllMovementPoseSelectors(
            int PoseAnimation,
            boolean OverrideIdlePoseAnimation)
    {
        if (Owner.getIdleRotateLeft() != PoseAnimation)
        {
            Owner.setIdleRotateLeft(PoseAnimation);
        }
        if (Owner.getIdleRotateRight() != PoseAnimation)
        {
            Owner.setIdleRotateRight(PoseAnimation);
        }
        if (Owner.getWalkAnimation() != PoseAnimation)
        {
            Owner.setWalkAnimation(PoseAnimation);
        }
        if (Owner.getWalkRotateLeft() != PoseAnimation)
        {
            Owner.setWalkRotateLeft(PoseAnimation);
        }
        if (Owner.getWalkRotateRight() != PoseAnimation)
        {
            Owner.setWalkRotateRight(PoseAnimation);
        }
        if (Owner.getWalkRotate180() != PoseAnimation)
        {
            Owner.setWalkRotate180(PoseAnimation);
        }
        int IdlePoseAnimation =
                SelectNativeIdlePoseSelectorAnimation(
                        OverrideIdlePoseAnimation,
                        PoseAnimation,
                        OldAnimationSet.IdlePoseAnimation);
        if (Owner.getIdlePoseAnimation() != IdlePoseAnimation)
        {
            Owner.setIdlePoseAnimation(IdlePoseAnimation);
        }
        if (Owner.getRunAnimation() != PoseAnimation)
        {
            Owner.setRunAnimation(PoseAnimation);
        }
        NativePoseSelectorOverrideAnimation = PoseAnimation;
    }

    private void SetAllIdlePosesNoAnimation()
    {

        if (NativePoseSelectorOverrideAnimation != NO_ANIMATION)
        {
            UpdateOldIdleAnimations();
        }

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
        bEndpointNativeIdlePoseOverrideActive = false;
        NativePoseSelectorOverrideAnimation = NO_ANIMATION;
    }

    private void UpdateModelVisibleState()
    {
        int CurrentAnimationGameCycle = client.getGameCycle();
        int ElapsedAnimationGameCycles =
                GetControllerAnimationClockDelta(
                        CurrentAnimationGameCycle,
                        LastAnimationGameCycle);
        LastAnimationGameCycle = CurrentAnimationGameCycle;

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

            if (bShouldUseTrueLocationOrientation || (CurrentTime - LastTimeUniqueAnimationLocationOrientationWasUsed) < 600) // A little bit of time before going to other animation
            {
                LocalPoint ownerLocation = Owner.getLocalLocation();
                if (!Objects.equals(Model.getLocation(), ownerLocation))
                {
                    Model.setLocation(ownerLocation, Owner.getWorldView().getPlane());
                }
                if (Model.getOrientation() != Owner.getOrientation())
                {
                    Model.setOrientation(Owner.getOrientation());
                }

                CurrentOrientation = Owner.getOrientation();
                PreciseCurrentOrientation = CurrentOrientation;

                if (bShouldUseTrueLocationOrientation)
                {
                    LastTimeUniqueAnimationLocationOrientationWasUsed = CurrentTime;
                }
            }
            else
            {
                if (!Objects.equals(Model.getLocation(), NewLocalPointToDraw))
                {
                    Model.setLocation(NewLocalPointToDraw,
                            Owner.getWorldView().getPlane());
                }
                // Need to rotate to our target rotation smoothly
                double AdjustedOrientationSpeed = CurrentAnimationRequest.OrientationSpeed * (CurrentFrameDelta / 16.667);// Speed value centered at 60FPS
                PreciseCurrentOrientation = AdvanceOrientationPhase(
                        PreciseCurrentOrientation,
                        TargetOrientation,
                        AdjustedOrientationSpeed);
                CurrentOrientation = (int) PreciseCurrentOrientation;

                // Don't rotate if we are at the destination when we are not in battle mode
                if (Model.getOrientation() != CurrentOrientation)
                {
                    Model.setOrientation(CurrentOrientation);
                }
            }

            UpdateEndpointNativeHandoffReadiness();

            boolean bUseNativeSmoothedEndpointIdle =
                    bEndpointIdlePresentationActive &&
                            IsAnimationInterpolationActive(
                                    OldAnimationSet.IdlePoseAnimation);

            // [TMA-ENDPOINT-IDLE-PRESENTATION] Without Animation Smoothing, a
            // held endpoint pose is an already composed native idle keyframe
            // and bypasses every transform controller. With smoothing, this
            // cache is skipped and the ordinary native model path is refreshed.
            net.runelite.api.Model SourceOwnerModel = null;
            boolean bUsedHeldEndpointIdleModel =
                    TryPublishHeldEndpointIdleModel();
            boolean bPreservePendingWalkEndpointGeometry =
                    ShouldPreservePendingWalkEndpointGeometry(
                            bWalkSegmentAwaitingMovement,
                            bHoldWalkStopFacingThisFrame,
                            bEndpointIdlePresentationActive,
                            bPublishedStoppedGeometrySafe,
                            Owner.getAnimation(),
                            HasActiveSpotAnimation() ||
                                    CurrentAnimationRequest.AnimationToPlay !=
                                            NO_ANIMATION,
                            Owner.getPoseAnimation(),
                            OldAnimationSet.IdlePoseAnimation);
            boolean bUsedCustomAnimation =
                    bUsedHeldEndpointIdleModel ||
                            bPreservePendingWalkEndpointGeometry;
            boolean bUseAuthoritativeTeleportAction =
                    ShouldUseAuthoritativeTeleportAction(
                            Owner.getAnimation());
            // [TMA-FRESH-WALK-PRESENTATION] Do not let a hidden early
            // walk/turn pose replace the smoothed idle which was actually
            // displayed when the click occurred. A real segment clears both
            // pending and handoff state before reaching this branch.
            boolean bControllerAnimationRequested =
                    !bUsedCustomAnimation &&
                            !bUseAuthoritativeTeleportAction &&
                            ((UniqueAnimationExceptionList.contains(
                                     Owner.getAnimation()) &&
                                    bMovingThisAction) ||
                                    CurrentAnimationRequest.AnimationToPlay !=
                                            -1);
            if (bControllerAnimationRequested)
            {
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

                if (CustomAnim != null)
                {
                    bUsedCustomAnimation = true;
                    boolean bControllerAnimationReplaced = false;
                    int CurrentControllerAnimationId =
                            AnimController.getAnimation() == null
                                    ? NO_ANIMATION
                                    : AnimController.getAnimation().getId();
                    if (ShouldReplaceAnimationController(
                            CurrentControllerAnimationId,
                            CustomAnim.getId(),
                            bResetCurrentAnimation))
                    {
                        AnimController.setAnimation(CustomAnim);

                        if (bUsingPoseAnim &&
                                Owner.getPoseAnimationFrame() >= 0 &&
                                Owner.getPoseAnimationFrame() <
                                        CustomAnim.getNumFrames() &&
                                !bResetCurrentAnimation)
                        {
                            AnimController.setFrame(
                                    Owner.getPoseAnimationFrame());
                        }
                        else
                        {
                            AnimController.setFrame(
                                    CurrentAnimationRequest.StartingFrame);
                        }
                        bResetCurrentAnimation = false;
                        bControllerAnimationReplaced = true;
                    }

                    SetAllIdlePosesNoAnimation();
                    Owner.setPoseAnimation(NO_ANIMATION);
                    Owner.setPoseAnimationFrame(0);

                    if (!bControllerAnimationReplaced &&
                            ElapsedAnimationGameCycles > 0)
                    {
                        int CurrentFrame = AnimController.getFrame();
                        if (CurrentFrame >=
                                CurrentAnimationRequest.EndingFrame)
                        {
                            AnimController.setFrame(
                                    CurrentAnimationRequest.EndingFrame);
                        }
                        else
                        {
                            AnimController.tick(
                                    ElapsedAnimationGameCycles *
                                            CurrentAnimationRequest.AnimationSpeed);
                        }
                    }

                    SourceOwnerModel = Owner.getModel();
                    if (SourceOwnerModel == null ||
                            !TrySetModel(
                                    AnimController.animate(
                                            SourceOwnerModel)))
                    {
                        // Preserve the last drawable model and use the normal
                        // controller for this frame. Loading/model replacement
                        // should never create a null-frame hole.
                        bUsedCustomAnimation = false;
                    }
                }
            }
            if (bUsedCustomAnimation &&
                    NativePoseSelectorOverrideAnimation != NO_ANIMATION &&
                    !bEndpointNativeIdlePoseOverrideActive)
            {
                // A held mesh or controller-driven presentation no longer
                // consumes the hidden actor's ordinary locomotion model.
                // Release the selector table before leaving that native path.
                SetAllIdlePosesDefault();
            }
            if (!bUsedCustomAnimation)
            {
                // Normal controller takes back over
                boolean bCelebrationPresentationActive =
                        bTargetWasKilled &&
                                config.AllowNPCKilledCelebrationEmote() &&
                                LastNPCCombatLevel > 50 &&
                                bIsDefaultHumanAnimationSet;
                boolean bUseNativeSmoothedLocomotion =
                        ShouldOverrideNativeLocomotionPoseSelectors(
                                !bAttemptToRenderOwner,
                                bMovingThisAction,
                                bEndpointIdlePresentationActive,
                                IsAnimationInterpolationActive(
                                        CurrentAnimationRequest
                                                .PoseAnimationToPlay),
                                IsTeleportPresentationActive() ||
                                        HasActiveSpotAnimation() ||
                                        bCelebrationPresentationActive,
                                Owner.getAnimation(),
                                CurrentAnimationRequest.AnimationToPlay,
                                CurrentAnimationRequest
                                        .PoseAnimationToPlay);
                bTargetWasKilled = false; // If normal controller is taking it, cancel target killed animation
                if (bUseNativeSmoothedEndpointIdle)
                {
                    SetAllMovementPosesToIdleAnimation();
                }
                else if (bUseNativeSmoothedLocomotion)
                {
                    // Keep the selected locomotion ID on RuneLite's native
                    // pose clock. Animation Smoothing can now interpolate its
                    // authored frames without a competing 20 ms selector swap.
                    SetAllMovementPosesToLocomotionAnimation(
                            CurrentAnimationRequest.PoseAnimationToPlay);
                }
                else
                {
                    SetAllIdlePosesDefault();
                }
                if (AnimController.getAnimation() != null)
                {
                    Owner.setPoseAnimation(AnimController.getAnimation().getId());
                    Owner.setPoseAnimationFrame(AnimController.getFrame());
                    CurrentPoseAnimation = AnimController.getAnimation().getId();
                    AnimController.setAnimation(null);
                    AnimController.setFrame(0);
                }

                // [TMA-INTERPOLATION-CONTINUITY] A transient -1 pose
                // frame (which the game publishes at route handoffs)
                // must NOT trigger an explicit setPoseAnimationFrame
                // call — that resets the client's internal interpolation
                // timer and causes visible stutter in smoothed
                // locomotion.  The TrySetModel fallback below already
                // preserves the last valid model when the Owner cannot
                // supply one for a single frame.
                if (bEndpointIdlePresentationActive &&
                        bEndpointIdleFrameNeedsPublication &&
                        !HasActiveSpotAnimation() &&
                        CurrentAnimationRequest.PoseAnimationToPlay !=
                                NO_ANIMATION &&
                        (Owner.getPoseAnimation() !=
                                CurrentAnimationRequest.PoseAnimationToPlay ||
                                Owner.getPoseAnimationFrame() !=
                                        EndpointIdleDesiredFrame))
                {
                    bEndpointIdleFrameNeedsPublication = true;
                }
                if (CurrentAnimationRequest.PoseAnimationToPlay != -1 &&
                        (Owner.getPoseAnimation() !=
                                CurrentAnimationRequest.PoseAnimationToPlay ||
                                bResetCurrentAnimation ||
                                (bEndpointIdlePresentationActive &&
                                        bEndpointIdleFrameNeedsPublication &&
                                        !HasActiveSpotAnimation())))
                {
                    int RequestedPoseAnimation =
                            CurrentAnimationRequest.PoseAnimationToPlay;
                    Animation CustomAnim =
                            client.loadAnimation(RequestedPoseAnimation);

                    if (CustomAnim != null)
                    {
                        // A spatially stopped model uses the independent
                        // authored idle frame even when another route segment
                        // is pending. The native builder composes that exact
                        // keyframe below; hidden locomotion never supplies the
                        // visible endpoint geometry.
                        boolean bRestartStationaryIdle =
                                ShouldRestartStationaryIdlePose(
                                        bMovingThisAction,
                                        Owner.getAnimation(),
                                        Owner.getPoseAnimation(),
                                        RequestedPoseAnimation,
                                        OldAnimationSet.IdlePoseAnimation);
                        boolean bForceEndpointIdleFrame =
                                bEndpointIdlePresentationActive &&
                                        bEndpointIdleFrameNeedsPublication &&
                                        !HasActiveSpotAnimation();
                        int RequestedStartingFrame =
                                bForceEndpointIdleFrame
                                        ? EndpointIdleDesiredFrame
                                        : CurrentAnimationRequest.StartingFrame;
                        int SafePoseFrame = SelectPoseFrameForPublication(
                                Owner.getPoseAnimation(),
                                Owner.getPoseAnimationFrame(),
                                RequestedPoseAnimation,
                                LastValidOwnerPoseAnimation,
                                LastValidOwnerPoseFrame,
                                CustomAnim.getNumFrames(),
                                RequestedStartingFrame,
                                bResetCurrentAnimation,
                                bRestartStationaryIdle ||
                                        bForceEndpointIdleFrame);
                        if (Owner.getPoseAnimationFrame() != SafePoseFrame ||
                                bForceEndpointIdleFrame)
                        {
                            Owner.setPoseAnimationFrame(SafePoseFrame);
                        }

                        if (Owner.getPoseAnimation() != RequestedPoseAnimation)
                        {
                            Owner.setPoseAnimation(RequestedPoseAnimation);
                        }
                        CurrentPoseAnimation = NO_ANIMATION;
                        bResetCurrentAnimation = false;
                    }
                }
                // [TMA-STEADY-PRESENTATION] Owner.getModel() can be
                // momentarily unavailable while RuneLite rebuilds equipment
                // or animation state. Keep the last complete model for that
                // frame instead of assigning null and making the player pop.
                boolean bOwnerModelPublished = false;
                boolean bEndpointIdleCaptureAttempted =
                        CanCaptureEndpointIdleModel();
                if (bEndpointIdleCaptureAttempted &&
                        TryCaptureEndpointIdleModel())
                {
                    bUsedHeldEndpointIdleModel =
                            TryPublishHeldEndpointIdleModel();
                    bUsedCustomAnimation =
                            bUsedHeldEndpointIdleModel;
                    bOwnerModelPublished =
                            bUsedHeldEndpointIdleModel;
                }
                else if (bEndpointIdleCaptureAttempted &&
                        IsHeldEndpointIdleModelPublished())
                {
                    // A transient native-builder miss must retain the previous
                    // valid idle keyframe, never fall through to the hidden
                    // owner's locomotion model.
                    bUsedHeldEndpointIdleModel = true;
                    bUsedCustomAnimation = true;
                    bOwnerModelPublished = true;
                }

                if (!bUsedHeldEndpointIdleModel &&
                        !bEndpointIdleCaptureAttempted &&
                        bEndpointIdlePresentationActive &&
                        bEndpointIdleFrameNeedsPublication &&
                        Model.getModel() != null)
                {
                    // The requested idle animation/model is temporarily
                    // unavailable. Preserve the last drawable custom frame
                    // for this render rather than exposing native locomotion.
                    bUsedCustomAnimation = true;
                }

                if (!bUsedCustomAnimation &&
                        !bEndpointIdleCaptureAttempted)
                {
                    SourceOwnerModel = Owner.getModel();
                    if (TrySetModel(SourceOwnerModel))
                    {
                        bOwnerModelPublished = true;
                    }
                }

                if (bOwnerModelPublished)
                {
                    if (bUseNativeSmoothedEndpointIdle)
                    {
                        bEndpointIdlePoseWasDisplayed = true;
                    }
                    int PublishedPoseAnimation = Owner.getPoseAnimation();
                    int PublishedPoseFrame = Owner.getPoseAnimationFrame();
                    if (PublishedPoseAnimation != NO_ANIMATION &&
                            PublishedPoseFrame >= 0)
                    {
                        LastValidOwnerPoseAnimation = PublishedPoseAnimation;
                        LastValidOwnerPoseFrame = PublishedPoseFrame;
                    }

                    if (ShouldMarkPublishedStoppedGeometrySafe(
                            bMovingThisAction,
                            Owner.getAnimation(),
                            HasActiveSpotAnimation() ||
                                    CurrentAnimationRequest.AnimationToPlay !=
                                            NO_ANIMATION,
                            Owner.getPoseAnimation(),
                            Owner.getPoseAnimationFrame(),
                            OldAnimationSet.IdlePoseAnimation,
                            Model.getBaseModel() != null))
                    {
                        // Ordinary Player.getModel() supplied another safe
                        // smoothed idle sample. It may replace the previous
                        // sample without losing stopped-model provenance.
                        bPublishedStoppedGeometrySafe = true;
                    }
                }
            }

            net.runelite.api.Model RenderedModel = Model.getModel();
            if (RenderedModel != null &&
                    SourceOwnerModel != null &&
                    !bUsedHeldEndpointIdleModel &&
                    RenderedModel.getModelHeight() !=
                            SourceOwnerModel.getModelHeight())
            {
                RenderedModel.setModelHeight(
                        SourceOwnerModel.getModelHeight());
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

            // [TMA-NO-SPAWN-IN] The native presentation is still useful
            // while genuinely idle, but do NOT toggle the custom model
            // inactive during proximity. setActive(false) -> setActive(true)
            // triggers RuneLite's entity spawn-in animation (small -> normal
            // scale), and the model may be reactivated at a stale segment
            // destination rather than its last rendered location. Instead,
            // keep the custom model active in the background and let the
            // native player draw on top when within proximity thresholds.
            // This prevents every stationary -> moving transition from
            // producing a visible shrink/grow at the wrong tile.
            if (RenderedModel != null &&
                    ShouldRenderOriginalOwner(
                            bUsedCustomAnimation))
            {
                if (!Model.isActive())
                {
                    Model.setActive(true);
                }
                bRenderOriginalOwnerDueToProximity = true;
            }
            else
            {
                if (RenderedModel != null &&
                        !Model.isActive())
                {
                    Model.setActive(true);
                }
                bRenderOriginalOwnerDueToProximity =
                        RenderedModel == null;
            }

            UpdateMirroredActorSpotAnimations(
                    CanSuppressOwnerInCurrentScene());

            RecordLastRenderedLocation(Model.getLocation());
            StallTraceStageBegin();
            UpdateCamera();
            StallTraceStageEnd("UpdateCamera");
        }
        else
        {
            SetAllIdlePosesDefault();
            Model.setActive(false);
            ClearMirroredActorSpotAnimations();
            if (cameraModel != null)
            {
                cameraModel.setActive(false);
            }
        }

    }

    private long StallTraceStageStartNanos = 0;

    private void StallTraceStageBegin()
    {
        if (!config.DebugStallTrace())
        {
            return;
        }
        StallTraceStageStartNanos = System.nanoTime();
    }

    private void StallTraceStageEnd(String StageName)
    {
        if (!config.DebugStallTrace())
        {
            return;
        }
        long ElapsedMillis =
                (System.nanoTime() - StallTraceStageStartNanos) / 1_000_000L;
        if (ElapsedMillis <
                DebugFileLogger.STALL_TRACE_STAGE_THRESHOLD_MILLIS)
        {
            return;
        }
        DebugFileLogger.Append(
                DebugFileLogger.STALL_TRACE_LOG_FILE,
                "[TMA-STALL-TRACE] stage=" + StageName +
                        " ms=" + ElapsedMillis +
                        " frameGap=" + CurrentFrameDelta +
                        " moving=" + bMovingThisAction +
                        " walkArmed=" + bWalkStopFacingHoldArmed +
                        " awaiting=" + bWalkSegmentAwaitingMovement);
    }

    private void MaybeLogMovementIdleFlick(boolean WasMovingThisAction)
    {
        if (!config.DebugMovementIdleFlick() ||
                !WasMovingThisAction ||
                bMovingThisAction ||
                !HasUnfinishedRoute(
                        NextLerpPosition,
                        client.getLocalDestinationLocation()))
        {
            return;
        }

        String Message = "[MovementIdleFlick] moving->idle en route: reason=" +
                (bEndpointIdlePresentationActive
                        ? "endpoint-presentation"
                        : "pose-selection") +
                " elapsed=" +
                MillisecondsSinceTileChange +
                "ms frameDelta=" + CurrentFrameDelta +
                " movementDuration=" +
                GetSceneMovementAnimationDuration(
                        SceneRecoveryTweenDurationOverride) +
                " segmentTick=" + MovementSegmentPublicationTick +
                " currentTick=" + client.getTickCount() +
                " segmentCycle=" + MovementSegmentPublicationGameCycle +
                " currentCycle=" + client.getGameCycle() +
                " retainedMoving=" +
                bRetainedSceneMovementSegmentInProgress +
                " retainedTick=" + RetainedMovementProgressTick +
                " retainedCycle=" + RetainedMovementProgressGameCycle +
                " boundaryCycle=" +
                FreshMovementPublicationBoundaryGameCycle +
                " recoveryDuration=" + SceneRecoveryTweenDurationOverride +
                " newTile=" + bNewTileMovementStarted +
                " walkArmed=" + bWalkStopFacingHoldArmed +
                " observed=" + bWalkMovementObserved +
                " awaiting=" + bWalkSegmentAwaitingMovement +
                " inheritedGap=" +
                bPendingWalkInheritedActiveRouteGap +
                " ownerAnim=" +
                (Owner == null ? NO_ANIMATION : Owner.getAnimation()) +
                " nextLerp=" + NextLerpPosition +
                " dest=" + client.getLocalDestinationLocation();

        log.debug(Message);
        AppendMovementIdleFlickLog(Message);
    }

    private void AppendMovementIdleFlickLog(String Message)
    {
        DebugFileLogger.Append(
                "tma-movement-idle-flick.log",
                Message);
    }

    public void Update()
    {
        if (client.getGameState() == GameState.LOADING)
        {
            // [TMA-MOTION-CONTINUITY] Scene-owned objects may be replaced
            // during this interval, but the last valid handler state remains
            // authoritative. Initialize marks a pending rebase; wait until
            // world/local conversion is valid instead of resetting or moving
            // the visible player to a transient local coordinate.
            LastAnimationGameCycle = -1;
            return;
        }

        UpdateFrameTimer();

        UpdateOldIdleAnimations();

        UpdateTargetStatus();

        if (bSceneRebasePending && RebaseAfterSceneLoad())
        {
            bSceneRebasePending = false;
        }

        StallTraceStageBegin();
        boolean bTrueTileAvailable = UpdateTrueTileLocation();
        StallTraceStageEnd("UpdateTrueTileLocation");
        if (!bTrueTileAvailable)
        {
            // Soft suspension while the client is rebuilding a scene: retain
            // the last valid model/interpolation state until conversion from
            // world coordinates is available again.
            LastAnimationGameCycle = -1;
            return;
        }

        // First-time POH construction can publish the final portal-room
        // coordinate through presentation paths which bypass normal route
        // interpolation. Re-arm after a transient handler cleanup and mirror
        // the native actor before any interpolation branch can consume the
        // temporary centre-of-instance coordinate.
        ArmPohArrivalCoordinateGuard();
        SynchronizePohArrivalCoordinateToNative();

        StallTraceStageBegin();
        UpdateLerpDestinations();
        StallTraceStageEnd("UpdateLerpDestinations");

        if (bNewTileMovementStarted)
        {
            FreshMovementPublicationBoundaryGameCycle = -1;
            MovementSegmentPublicationTick = client.getTickCount();
            MovementSegmentPublicationGameCycle = client.getGameCycle();
            // [TMA-FRESH-WALK-START] A real authoritative movement segment has
            // arrived. This clears both an ordinary re-click delay and the
            // first-segment wait carried through a scene rebuild. Recovery
            // tweening alone never clears either state.
            WalkClickRevisionAtMovementSegment = WalkClickRevision;
            bWalkSegmentAwaitingMovement = false;
            bWalkStartPendingDuringCatchUp = false;
            bPostSceneRecoveryRouteGapEligible = false;
            bPendingWalkInheritedActiveRouteGap = false;
            bPublishedStoppedGeometrySafe = false;
            bEndpointNativeHandoffReadyThisFrame = false;
            bEndpointNativeHandoffEstablished =
                    UpdateEndpointNativeHandoffLatch(
                            bEndpointNativeHandoffEstablished,
                            false,
                            true,
                            config.AllowOriginalModelWhenCloseProximity());
            boolean bResetLocomotionForNewSegment =
                    ShouldResetLocomotionForNewSegment(
                            bEndpointIdlePresentationActive,
                            bLocomotionResetPendingAfterEndpointIdle);
            if (bEndpointIdlePresentationActive)
            {
                // New authoritative position always outranks every old-route
                // catch-up/idle state. Release the endpoint model before
                // animation selection so movement begins in this same update.
                if (bEndpointNativeIdlePoseOverrideActive)
                {
                    SetAllIdlePosesDefault();
                }
                bEndpointIdlePresentationActive = false;
                ClearHeldEndpointIdleModel();
            }
            bResetCurrentAnimation |=
                    bResetLocomotionForNewSegment;
            bLocomotionResetPendingAfterEndpointIdle = false;
        }

        StallTraceStageBegin();
        UpdateWalkStopFacingHold();
        StallTraceStageEnd("UpdateWalkStopFacingHold");

        boolean bWasMovingThisAction = bMovingThisAction;
        StallTraceStageBegin();
        UpdateAnimationSelection();
        StallTraceStageEnd("UpdateAnimationSelection");

        MaybeLogMovementIdleFlick(bWasMovingThisAction);

        StallTraceStageBegin();
        UpdateMovementType();
        StallTraceStageEnd("UpdateMovementType");

        StallTraceStageBegin();
        ApplyTweening();
        StallTraceStageEnd("ApplyTweening");

        StallTraceStageBegin();
        UpdateModelVisibleState();
        StallTraceStageEnd("UpdateModelVisibleState");

        boolean bVisibleMovementSegmentInProgress =
                IsVisibleMovementSegmentInProgress(
                        bMovingThisAction,
                        MillisecondsSinceTileChange,
                        GetMovementTweenDurationMilliseconds(),
                        LastLerpPosition,
                        NextLerpPosition);
        if (bVisibleMovementSegmentInProgress)
        {
            bRetainedSceneMovementSegmentInProgress = true;
            RetainedMovementProgressTick = client.getTickCount();
            RetainedMovementProgressGameCycle = client.getGameCycle();
            FreshMovementPublicationBoundaryGameCycle = -1;
        }
        else if (!bFreshMovementPublicationBoundaryBridgeActive)
        {
            bRetainedSceneMovementSegmentInProgress = false;
            RetainedMovementProgressTick = -1;
            RetainedMovementProgressGameCycle = -1;
            FreshMovementPublicationBoundaryGameCycle = -1;
        }
    }
}
