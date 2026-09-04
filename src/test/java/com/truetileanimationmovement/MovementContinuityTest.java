package com.truetileanimationmovement;

import net.runelite.api.GameState;
import net.runelite.api.Point;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.gameval.AnimationID;
import net.runelite.api.gameval.SpotanimID;
import org.junit.Test;

import java.awt.Rectangle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MovementContinuityTest
{
	@Test
	public void genuineTeleportActionsKeepNativeAnimationAuthority()
	{
		assertTrue(TrueTileMovementPlugin.IsGenuineTeleportAnimation(
				AnimationID.HUMAN_CASTTELEPORT));
		assertTrue(TrueTileMovementPlugin.IsGenuineTeleportAnimation(
				AnimationID.AHOY_ECTO_TELEPORT));
		assertTrue(TrueTileMovementPlugin.IsGenuineTeleportAnimation(
				AnimationID.HUMAN_TELEPORT_OTHER_IMPACT));
		assertTrue(TrueTileMovementPlugin.IsGenuineTeleportAnimation(
				AnimationID.TELEPORT_NARDAH_HUMAN));
		assertTrue(TrueTileMovementPlugin.IsGenuineTeleportAnimation(
				AnimationID.HUMAN_COWBOSS_TELEPORT));
		assertTrue(TrueTileMovementPlugin.IsGenuineTeleportAnimation(
				AnimationID.POH_SMASH_MAGIC_TABLET));
		assertTrue(TrueTileMovementPlugin.IsGenuineTeleportAnimation(
				AnimationID.POH_ABSORB_TABLET_TELEPORT));
		assertTrue(TrueTileMovementPlugin.IsGenuineTeleportAnimation(
				AnimationID.TELEPORT_CABBAGE_HUMAN));
		assertTrue(TrueTileMovementPlugin.IsGenuineTeleportAnimation(
				AnimationID.NTK_HUMAN_TELE));
		assertFalse(TrueTileMovementPlugin.IsGenuineTeleportAnimation(
				AnimationID.HUMAN_CASTTELEPORT_REVERSE));
		assertFalse(TrueTileMovementPlugin.IsGenuineTeleportAnimation(
				AnimationID.ZAROS_VERTICAL_CASTING));

		assertTrue(CustomMovementHandler
				.ShouldUseAuthoritativeTeleportAction(
						AnimationID.POH_SMASH_MAGIC_TABLET));
		assertFalse(CustomMovementHandler
				.ShouldUseAuthoritativeTeleportAction(
						AnimationID.ZAROS_VERTICAL_CASTING));
	}

	@Test
	public void activeActorSpotEffectsFollowTheSuppressedCustomPlayer()
	{
		assertTrue(CustomMovementHandler
				.ShouldMirrorActorSpotAnimation(
						SpotanimID.TELEPORT_CASTING,
						3,
						100,
						110,
						true));
		// Teleport effects are not all the standard purple casting orb. The
		// POH tablet's floor-up effect must use the same native path.
		assertTrue(CustomMovementHandler
				.ShouldMirrorActorSpotAnimation(
						SpotanimID.POH_ABSORB_TABLET_MAGIC,
						2,
						100,
						110,
						true));
		// The decision is deliberately actor-spot based, not a growing list of
		// teleport IDs; other native effects attached to the hidden player are
		// preserved by the same presentation path.
		assertTrue(CustomMovementHandler
				.ShouldMirrorActorSpotAnimation(
						SpotanimID.TELEGRAB_CASTING,
						1,
						100,
						110,
						true));
		assertFalse(CustomMovementHandler
				.ShouldMirrorActorSpotAnimation(
						SpotanimID.TELEPORT_CASTING,
						3,
						100,
						110,
						false));
		assertFalse(CustomMovementHandler
				.ShouldMirrorActorSpotAnimation(
						-1,
						0,
						100,
						110,
						true));
		assertFalse(CustomMovementHandler
				.ShouldMirrorActorSpotAnimation(
						SpotanimID.TELEPORT_CASTING,
						-1,
						100,
						110,
						true));
		assertFalse(CustomMovementHandler
				.ShouldMirrorActorSpotAnimation(
						SpotanimID.TELEPORT_CASTING,
						3,
						111,
						110,
						true));
	}

	@Test
	public void teleportLeadInUsesIdleOnlyInsideItsExistingWindow()
	{
		assertTrue(CustomMovementHandler
				.ShouldUseIdlePoseDuringTeleportLeadIn(
						true,
						0));
		assertTrue(CustomMovementHandler
				.ShouldUseIdlePoseDuringTeleportLeadIn(
						true,
						599_999_999L));
		assertFalse(CustomMovementHandler
				.ShouldUseIdlePoseDuringTeleportLeadIn(
						true,
						600_000_000L));
		assertFalse(CustomMovementHandler
				.ShouldUseIdlePoseDuringTeleportLeadIn(
						false,
						0));
	}

	@Test
	public void minimapPressHitTestExcludesNonMapCorners()
	{
		Rectangle MinimapBounds = new Rectangle(100, 100, 150, 150);
		assertTrue(TrueTileMovementPlugin.IsInsideMinimapEllipse(
				new Point(175, 175),
				MinimapBounds));
		assertFalse(TrueTileMovementPlugin.IsInsideMinimapEllipse(
				new Point(100, 100),
				MinimapBounds));
	}

	@Test
	public void unfinishedRouteBridgesOnlyTheAnimationTickGap()
	{
		LocalPoint SegmentDestination =
				new LocalPoint(1280, 2560, 0);
		LocalPoint RouteDestination =
				new LocalPoint(1536, 2560, 0);

		assertTrue(CustomMovementHandler
				.ShouldKeepMovementAnimationDuringRouteGap(
						620,
						true,
						true,
						false,
						SegmentDestination,
						RouteDestination));
		// The latest area-transition captures reached 852-858 ms before the
		// next authoritative route segment was published.
		assertTrue(CustomMovementHandler
				.ShouldKeepMovementAnimationDuringRouteGap(
						858,
						true,
						true,
						false,
						SegmentDestination,
						RouteDestination));
		// Scene recovery can own a longer proportional segment. Its bounded
		// publication grace begins when that segment actually completes, not at
		// the ordinary 600 ms boundary.
		assertTrue(CustomMovementHandler
				.ShouldKeepMovementAnimationDuringRouteGap(
						960,
						960,
						true,
						true,
						false,
						SegmentDestination,
						RouteDestination));
		assertTrue(CustomMovementHandler
				.ShouldKeepMovementAnimationDuringRouteGap(
						1259,
						960,
						true,
						true,
						false,
						SegmentDestination,
						RouteDestination));
		assertFalse(CustomMovementHandler
				.ShouldKeepMovementAnimationDuringRouteGap(
						1260,
						960,
						true,
						true,
						false,
						SegmentDestination,
						RouteDestination));
		// Red-click interactions cancel the yellow-walk continuity arm.
		assertFalse(CustomMovementHandler
				.ShouldKeepMovementAnimationDuringRouteGap(
						620,
						true,
						false,
						false,
						SegmentDestination,
						RouteDestination));
		assertFalse(CustomMovementHandler
				.ShouldKeepMovementAnimationDuringRouteGap(
						620,
						true,
						true,
						false,
						SegmentDestination,
						SegmentDestination));
		assertFalse(CustomMovementHandler
				.ShouldKeepMovementAnimationDuringRouteGap(
						620,
						false,
						true,
						false,
						SegmentDestination,
						RouteDestination));
		assertFalse(CustomMovementHandler
				.ShouldKeepMovementAnimationDuringRouteGap(
						599,
						true,
						true,
						false,
						SegmentDestination,
						RouteDestination));
		assertFalse(CustomMovementHandler
				.ShouldKeepMovementAnimationDuringRouteGap(
						900,
						true,
						true,
						false,
						SegmentDestination,
						RouteDestination));
		assertFalse(CustomMovementHandler
				.ShouldKeepMovementAnimationDuringRouteGap(
						620,
						true,
						true,
						false,
						SegmentDestination,
						new LocalPoint(1536, 2560, 1)));
		assertFalse(CustomMovementHandler
				.ShouldKeepMovementAnimationDuringRouteGap(
						620,
						true,
						true,
						false,
						SegmentDestination,
						null));
		// A stop-facing handoff owns the endpoint pose. Route-gap grace must
		// never keep locomotion alive at the same time.
		assertTrue(CustomMovementHandler
				.ShouldKeepMovementAnimationDuringRouteGap(
						620,
						true,
						true,
						false,
						false,
						SegmentDestination,
						RouteDestination));
		assertFalse(CustomMovementHandler
				.ShouldKeepMovementAnimationDuringRouteGap(
						620,
						true,
						true,
						false,
						true,
						SegmentDestination,
						RouteDestination));
	}

	@Test
	public void freshWalkClickCannotRunInPlaceBeforeItsFirstSegment()
	{
		LocalPoint SegmentDestination =
				new LocalPoint(1280, 2560, 0);
		LocalPoint RouteDestination =
				new LocalPoint(1536, 2560, 0);

		assertFalse(CustomMovementHandler
				.ShouldKeepMovementAnimationDuringRouteGap(
						620,
						true,
						true,
						true,
						SegmentDestination,
						RouteDestination));
		assertFalse(CustomMovementHandler
				.IsMovementSegmentFromLatestWalkClick(2, 1));
		assertTrue(CustomMovementHandler
				.IsMovementSegmentFromLatestWalkClick(2, 2));

		assertTrue(CustomMovementHandler
				.IsVisibleMovementSegmentInProgress(
						true,
						599,
						600,
						new LocalPoint(1280, 2560, 0),
						new LocalPoint(1536, 2560, 0)));
		assertFalse(CustomMovementHandler
				.IsVisibleMovementSegmentInProgress(
						true,
						600,
						600,
						new LocalPoint(1280, 2560, 0),
						new LocalPoint(1536, 2560, 0)));
		assertFalse(CustomMovementHandler
				.IsVisibleMovementSegmentInProgress(
						true,
						599,
						600,
						SegmentDestination,
						SegmentDestination));
	}

	@Test
	public void freshRenderBoundaryUsesOnlyItsFirstCompletedRenderCycle()
	{
		LocalPoint SegmentDestination =
				new LocalPoint(4544, 6336, 0);
		LocalPoint RouteDestination =
				new LocalPoint(3904, 5440, 0);

		// The 21:44/21:50 captures first rendered the completed segment at
		// 620-635 ms. Native-clock distance varied, but the immediately preceding
		// presented frame was still proven movement on this same segment.
		assertTrue(CustomMovementHandler
				.ShouldBridgeFreshMovementPublicationBoundary(
						601, 600, true, true,
						false, false, false, -1,
						SegmentDestination, RouteDestination));
		assertTrue(CustomMovementHandler
				.ShouldBridgeFreshMovementPublicationBoundary(
						635, 600, true, true,
						false, false, false, -1,
						SegmentDestination, RouteDestination));

		// The caller records the game cycle of that first completed render. All
		// high-FPS renders in that cycle may use it, but the next cycle cannot.
		assertTrue(CustomMovementHandler
				.IsFreshMovementPublicationBoundaryBridgeCycle(
						34632, 34632));
		int LatchedBoundaryCycle = CustomMovementHandler
				.SelectFreshMovementPublicationBoundaryGameCycle(
						true, -1, 34632);
		assertEquals(34632, LatchedBoundaryCycle);
		// Eligibility on the following cycle cannot move the one-shot latch.
		assertEquals(34632, CustomMovementHandler
				.SelectFreshMovementPublicationBoundaryGameCycle(
						true, LatchedBoundaryCycle, 34633));
		assertFalse(CustomMovementHandler
				.IsFreshMovementPublicationBoundaryBridgeCycle(
						LatchedBoundaryCycle, 34633));
		assertFalse(CustomMovementHandler
				.ShouldBridgeFreshMovementPublicationBoundary(
						606, 600, true, false,
						false, false, false, -1,
						SegmentDestination, RouteDestination));
		assertFalse(CustomMovementHandler
				.ShouldBridgeFreshMovementPublicationBoundary(
						599, 600, true, true,
						false, false, false, -1,
						SegmentDestination, RouteDestination));
		assertFalse(CustomMovementHandler
				.ShouldBridgeFreshMovementPublicationBoundary(
						601, 600, false, true,
						false, false, false, -1,
						SegmentDestination, RouteDestination));
	}

	@Test
	public void freshRenderBoundaryAllowsPendingWalkButDefersToTrueStopsAndSpecialPresentation()
	{
		LocalPoint SegmentDestination =
				new LocalPoint(4544, 6336, 0);
		LocalPoint RouteDestination =
				new LocalPoint(3904, 5440, 0);

		// A newer yellow click can arm pending-facing while the previous segment
		// is still moving. This synthetic hold may not turn the first completed
		// render into endpoint idle.
		boolean PendingWalkBoundaryBridge = CustomMovementHandler
				.ShouldBridgeFreshMovementPublicationBoundary(
						621, 600, true, true,
						true, true, false, -1,
						SegmentDestination, RouteDestination);
		assertTrue(PendingWalkBoundaryBridge);
		boolean EndpointIdlePresentation = CustomMovementHandler
				.ShouldUseEndpointIdlePresentation(
						true,
						!PendingWalkBoundaryBridge,
						true,
						false,
						true,
						false,
						true,
						false,
						false,
						-1);
		assertFalse(EndpointIdlePresentation);
		assertTrue(CustomMovementHandler.ShouldSelectMovementPose(
				EndpointIdlePresentation,
				false,
				false,
				PendingWalkBoundaryBridge));
		// A genuine stop-facing hold, without a pending replacement walk
		// segment, still takes priority over locomotion continuity.
		assertFalse(CustomMovementHandler
				.ShouldBridgeFreshMovementPublicationBoundary(
						601, 600, true, true,
						false, true, false, -1,
						SegmentDestination, RouteDestination));
		assertFalse(CustomMovementHandler
				.ShouldBridgeFreshMovementPublicationBoundary(
						601, 600, true, true,
						false, false, true, -1,
						SegmentDestination, RouteDestination));
		assertFalse(CustomMovementHandler
				.ShouldBridgeFreshMovementPublicationBoundary(
						601, 600, true, true,
						false, false, false, 714,
						SegmentDestination, RouteDestination));
		assertFalse(CustomMovementHandler
				.ShouldBridgeFreshMovementPublicationBoundary(
						601, 600, true, true,
						false, false, false, -1,
						SegmentDestination, SegmentDestination));
	}

	@Test
	public void clickDuringActiveRouteGapKeepsOnlyItsExistingRemainder()
	{
		LocalPoint SegmentDestination =
				new LocalPoint(1280, 2560, 0);
		LocalPoint RouteDestination =
				new LocalPoint(1536, 2560, 0);

		// The captured failures clicked at 658/679 ms while the old route's
		// already-valid 600-900 ms publication bridge was being displayed.
		assertTrue(CustomMovementHandler
				.ShouldKeepMovementAnimationDuringInheritedPendingRouteGap(
						true, 679, 600, true, true, true, true,
						false, false,
						SegmentDestination, RouteDestination));
		assertTrue(CustomMovementHandler
				.ShouldKeepMovementAnimationDuringInheritedPendingRouteGap(
						true, 899, 600, true, true, true, true,
						false, false,
						SegmentDestination, RouteDestination));
		// Re-evaluating the same inherited state for another rapid minimap click
		// retains the old clock; there is still no click-relative extension.
		assertTrue(CustomMovementHandler
				.ShouldKeepMovementAnimationDuringInheritedPendingRouteGap(
						true, 700, 600, true, true, true, true,
						false, false,
						SegmentDestination, RouteDestination));
		// No new 300 ms window is created: the inherited bridge expires at the
		// old segment's original deadline.
		assertFalse(CustomMovementHandler
				.ShouldKeepMovementAnimationDuringInheritedPendingRouteGap(
						true, 900, 600, true, true, true, true,
						false, false,
						SegmentDestination, RouteDestination));
		// A mid-segment re-click never creates this latch, preserving stable idle
		// if its segment ends before the new authoritative segment arrives.
		assertFalse(CustomMovementHandler
				.ShouldKeepMovementAnimationDuringInheritedPendingRouteGap(
						false, 620, 600, true, true, true, true,
						false, false,
						SegmentDestination, RouteDestination));
		// The new segment and deliberate facing/endpoint handoffs immediately
		// take ownership from the old route.
		assertFalse(CustomMovementHandler
				.ShouldKeepMovementAnimationDuringInheritedPendingRouteGap(
						true, 679, 600, true, true, true, false,
						true, false,
						SegmentDestination, RouteDestination));
		assertFalse(CustomMovementHandler
				.ShouldKeepMovementAnimationDuringInheritedPendingRouteGap(
						true, 679, 600, true, true, true, true,
						false, true,
						SegmentDestination, RouteDestination));
	}

	@Test
	public void completedVisibleSegmentUsesIdleInsteadOfPoseFeedback()
	{
		// A pending re-click owns facing while the model is clamped at the
		// last published endpoint. Route intent does not make that position
		// locomotion.
		assertTrue(CustomMovementHandler
				.ShouldUseEndpointIdlePresentation(
						true, true, true, false, true, false,
						true, false, false, -1));
		assertTrue(CustomMovementHandler
				.ShouldUseEndpointIdlePresentation(
						true, true, true, false, true, false,
						true, false, true, -1));
		assertFalse(CustomMovementHandler
				.ShouldUseEndpointIdlePresentation(
						true, true, true, false, true, false,
						false, false, false, -1));

		// Final stops use the same rest presentation until the hidden native
		// player has both reached the endpoint and exposed a valid idle pose.
		assertTrue(CustomMovementHandler
				.ShouldUseEndpointIdlePresentation(
						true, true, true, false, true, false,
						false, true, false, -1));
		assertFalse(CustomMovementHandler
				.ShouldUseEndpointIdlePresentation(
						true, true, true, false, true, false,
						false, true, true, -1));
		// After the settle gate has approved the final native-geometry handoff, the
		// stable released-facing hold no longer keeps endpoint idle active.
		assertFalse(CustomMovementHandler
				.ShouldUseEndpointIdlePresentation(
						true, true, true, false, true, false,
						true, true, true, -1));

		// Actual position, scene, current special presentation, and action state
		// are authoritative. No prior moving boolean is an input to this
		// decision.
		assertFalse(CustomMovementHandler
				.ShouldUseEndpointIdlePresentation(
						true, false, true, false, true, false,
						true, false, false, -1));
		assertFalse(CustomMovementHandler
				.ShouldUseEndpointIdlePresentation(
						true, true, false, false, true, false,
						true, false, false, -1));
		assertFalse(CustomMovementHandler
				.ShouldUseEndpointIdlePresentation(
						true, true, true, true, true, false,
						true, false, false, -1));
		assertFalse(CustomMovementHandler
				.ShouldUseEndpointIdlePresentation(
						true, true, true, false, false, false,
						true, false, false, -1));
		assertFalse(CustomMovementHandler
				.ShouldUseEndpointIdlePresentation(
						true, true, true, false, true, true,
						true, false, false, -1));
		assertFalse(CustomMovementHandler
				.ShouldUseEndpointIdlePresentation(
						true, true, true, false, true, false,
						true, false, false, 422));

		// Native geometry is permitted only when the final body presentation can
		// take over: co-located, authored idle at a matching discrete frame,
		// observed after endpoint entry, facing-settled, and action/effect free.
		// The supplied threshold remains the authority for paths which may draw
		// the original actor.
		assertTrue(CustomMovementHandler.IsEndpointNativeHandoffReady(
				true, true, true, true, true, true, true,
				-1, false, 5, 10));
		assertFalse(CustomMovementHandler.IsEndpointNativeHandoffReady(
				false, true, true, true, true, true, true,
				-1, false, 5, 10));
		assertFalse(CustomMovementHandler.IsEndpointNativeHandoffReady(
				true, false, true, true, true, true, true,
				-1, false, 5, 10));
		assertFalse(CustomMovementHandler.IsEndpointNativeHandoffReady(
				true, true, false, true, true, true, true,
				-1, false, 5, 10));
		assertFalse(CustomMovementHandler.IsEndpointNativeHandoffReady(
				true, true, true, false, true, true, true,
				-1, false, 5, 10));
		assertFalse(CustomMovementHandler.IsEndpointNativeHandoffReady(
				true, true, true, true, false, true, true,
				-1, false, 5, 10));
		assertFalse(CustomMovementHandler.IsEndpointNativeHandoffReady(
				true, true, true, true, true, false, true,
				-1, false, 5, 10));
		assertFalse(CustomMovementHandler.IsEndpointNativeHandoffReady(
				true, true, true, true, true, true, false,
				-1, false, 5, 10));
		assertFalse(CustomMovementHandler.IsEndpointNativeHandoffReady(
				true, true, true, true, true, true, true,
				422, false, 5, 10));
		assertFalse(CustomMovementHandler.IsEndpointNativeHandoffReady(
				true, true, true, true, true, true, true,
				-1, true, 5, 10));
		assertFalse(CustomMovementHandler.IsEndpointNativeHandoffReady(
				true, true, true, true, true, true, true,
				-1, false, 11, 10));

		// Animation Smoothing is permission to leave the detached keyframe
		// cache for RuneLite's ordinary Player.getModel geometry even when the
		// user has disabled drawing the original player. Model orientation is a
		// separate render transform, so only actual original-player display keeps
		// the configured orientation threshold.
		assertTrue(CustomMovementHandler.CanUseNativeEndpointGeometry(
				false, true));
		assertTrue(CustomMovementHandler.CanUseNativeEndpointGeometry(
				true, false));
		assertFalse(CustomMovementHandler.CanUseNativeEndpointGeometry(
				false, false));
		assertTrue(CustomMovementHandler.IsEndpointNativeIdleClockShared(
				true, true));
		assertFalse(CustomMovementHandler.IsEndpointNativeIdleClockShared(
				true, false));
		assertFalse(CustomMovementHandler.IsEndpointNativeIdleClockShared(
				false, true));
		assertFalse(CustomMovementHandler.ShouldInvalidateEndpointIdleCache(
				true, 808, 808));
		assertTrue(CustomMovementHandler.ShouldInvalidateEndpointIdleCache(
				true, 808, 813));
		assertTrue(CustomMovementHandler.ShouldInvalidateEndpointIdleCache(
				false, 808, 808));
		// The smoothed catch-up model and final idle both come from the same
		// native actor clock, so takeover does not need a detached integer-frame
		// match. The smoothing-disabled cache still does.
		assertTrue(CustomMovementHandler.DoesEndpointIdleGeometryMatch(
				true, 7, -1));
		assertTrue(CustomMovementHandler.DoesEndpointIdleGeometryMatch(
				false, 7, 7));
		assertFalse(CustomMovementHandler.DoesEndpointIdleGeometryMatch(
				false, 7, -1));
		assertFalse(CustomMovementHandler.DoesEndpointIdleGeometryMatch(
				false, 7, 6));
		assertEquals(2047, CustomMovementHandler
				.GetNativeEndpointGeometryOrientationThreshold(true, 10));
		assertEquals(10, CustomMovementHandler
				.GetNativeEndpointGeometryOrientationThreshold(false, 10));
		assertTrue(CustomMovementHandler.IsEndpointNativeHandoffReady(
				true, true, true, true, true, true, true,
				-1, false, 512, 2047));
		assertFalse(CustomMovementHandler.ShouldUseOriginalOwnerPresentation(
				true, false, false, false, -1,
				0, 0, 512, 1, 10));

		// Endpoint idle has explicit priority over the older bounded route-gap
		// grace. That grace can no longer revive locomotion at zero velocity.
		assertFalse(CustomMovementHandler.ShouldSelectMovementPose(
				true, false, false, true));
		assertTrue(CustomMovementHandler.ShouldSelectMovementPose(
				false, false, false, true));

		assertTrue(CustomMovementHandler.ShouldCaptureEndpointIdleModel(
				true, true, true, true, true, true, true, false));
		assertFalse(CustomMovementHandler.ShouldCaptureEndpointIdleModel(
				false, true, true, true, true, true, true, false));
		assertFalse(CustomMovementHandler.ShouldCaptureEndpointIdleModel(
				true, false, true, true, true, true, true, false));
		assertFalse(CustomMovementHandler.ShouldCaptureEndpointIdleModel(
				true, true, false, true, true, true, true, false));
		assertFalse(CustomMovementHandler.ShouldCaptureEndpointIdleModel(
				true, true, true, false, true, true, true, false));
		assertFalse(CustomMovementHandler.ShouldCaptureEndpointIdleModel(
				true, true, true, true, false, true, true, false));
		assertFalse(CustomMovementHandler.ShouldCaptureEndpointIdleModel(
				true, true, true, true, true, false, true, false));
		assertFalse(CustomMovementHandler.ShouldCaptureEndpointIdleModel(
				true, true, true, true, true, true, false, false));
		assertFalse(CustomMovementHandler.ShouldCaptureEndpointIdleModel(
				true, true, true, true, true, true, true, true));

		assertTrue(CustomMovementHandler
				.ShouldResetLocomotionAfterEndpointIdle(true, true));
		assertFalse(CustomMovementHandler
				.ShouldResetLocomotionAfterEndpointIdle(true, false));
		assertFalse(CustomMovementHandler
				.ShouldResetLocomotionAfterEndpointIdle(false, true));
		assertTrue(CustomMovementHandler
				.ShouldResetLocomotionForNewSegment(true, false));
		assertTrue(CustomMovementHandler
				.ShouldResetLocomotionForNewSegment(false, true));
		assertFalse(CustomMovementHandler
				.ShouldResetLocomotionForNewSegment(false, false));

		// Once accepted, native endpoint geometry persists across later renders
		// even though clearing the detached mesh makes one-frame readiness false.
		// A real segment (or loss of every native-geometry path) retires the latch.
		boolean NativeHandoffEstablished =
				CustomMovementHandler.UpdateEndpointNativeHandoffLatch(
						false, true, false, true);
		assertTrue(NativeHandoffEstablished);
		NativeHandoffEstablished =
				CustomMovementHandler.UpdateEndpointNativeHandoffLatch(
						NativeHandoffEstablished, false, false, true);
		assertTrue(NativeHandoffEstablished);
		assertFalse(CustomMovementHandler
				.ShouldUseEndpointIdlePresentation(
						true, true, true, false, true, false,
						true, true, NativeHandoffEstablished, -1));
		assertFalse(CustomMovementHandler.UpdateEndpointNativeHandoffLatch(
				NativeHandoffEstablished, false, true, true));
		assertFalse(CustomMovementHandler.UpdateEndpointNativeHandoffLatch(
				NativeHandoffEstablished, false, false, false));

		// Active catch-up may retain its released facing for a pending click. A
		// fully settled facing hold is retired, but whichever stopped model was
		// actually displayed remains pending until a real segment arrives.
		assertTrue(CustomMovementHandler
				.ShouldContinuePendingWalkPresentation(
						false, false, true, false, false, false,
						false));
		assertFalse(CustomMovementHandler
				.ShouldContinuePendingWalkPresentation(
						false, false, false, false, false, false,
						false));
		assertTrue(CustomMovementHandler
				.ShouldContinuePendingWalkPresentation(
						false, false, false, true, false, false,
						false));
		assertTrue(CustomMovementHandler
				.ShouldContinuePendingWalkPresentation(
						false, false, false, false, true, false,
						false));
		assertTrue(CustomMovementHandler
				.ShouldContinuePendingWalkPresentation(
						false, false, false, false, false, true,
						false));
		assertTrue(CustomMovementHandler
				.ShouldContinuePendingWalkPresentation(
						false, false, false, false, false, false,
						true));

		// A handed-off smoothed idle remains on the ordinary geometry path while
		// its facing is held; it must not restart the detached idle clock. The
		// awaiting flag nevertheless blocks the actual original actor even when
		// that geometry handoff is already established.
		assertTrue(CustomMovementHandler
				.ShouldUseDetachedEndpointIdleForFacingHold(true, false));
		assertFalse(CustomMovementHandler
				.ShouldUseDetachedEndpointIdleForFacingHold(true, true));
		assertTrue(CustomMovementHandler
				.ShouldBlockOriginalOwnerForPendingWalk(
						true, true, true, true));
		assertTrue(CustomMovementHandler
				.ShouldBlockOriginalOwnerForPendingWalk(
						false, true, false, false));
		assertFalse(CustomMovementHandler
				.ShouldBlockOriginalOwnerForPendingWalk(
						false, true, true, true));

		// Keep RuneLite's smoothed idle publishing normally while the hidden
		// actor remains idle. If it exposes locomotion before a real route segment,
		// retain the last published stopped mesh instead of copying that seam. The
		// facing hold covers both endpoint handoff and initial stationary custom
		// presentation; it does not depend on an endpoint-handoff latch.
		assertFalse(CustomMovementHandler
				.ShouldPreservePendingWalkEndpointGeometry(
						true, true, false, true, -1, false, 808, 808));
		assertTrue(CustomMovementHandler
				.ShouldPreservePendingWalkEndpointGeometry(
						true, true, false, true, -1, false, 1661, 808));
		// Dedicated detached endpoint idle must capture ahead of this fallback;
		// otherwise the previous locomotion mesh would freeze indefinitely.
		assertFalse(CustomMovementHandler
				.ShouldPreservePendingWalkEndpointGeometry(
						true, true, true, true, -1, false, 1661, 808));
		// An action is real presentation authority, not a locomotion-pose seam.
		assertFalse(CustomMovementHandler
				.ShouldPreservePendingWalkEndpointGeometry(
						true, true, false, true, 422, false, 808, 808));
		assertFalse(CustomMovementHandler
				.ShouldPreservePendingWalkEndpointGeometry(
						true, true, false, true, -1, true, 1661, 808));
		assertFalse(CustomMovementHandler
				.ShouldPreservePendingWalkEndpointGeometry(
						false, true, false, true, -1, false, 1661, 808));
		assertFalse(CustomMovementHandler
				.ShouldPreservePendingWalkEndpointGeometry(
						true, false, false, true, -1, false, 1661, 808));
		assertFalse(CustomMovementHandler
				.ShouldPreservePendingWalkEndpointGeometry(
						true, true, false, false, -1, false, 1661, 808));

		// Provenance is restored only by a successfully published, ordinary
		// stationary idle model. Movement, actions, special/spot presentation,
		// non-idle pose, invalid idle ID, and a missing base all remain unsafe.
		assertTrue(CustomMovementHandler.ShouldMarkPublishedStoppedGeometrySafe(
				false, -1, false, 808, 4, 808, true));
		assertFalse(CustomMovementHandler.ShouldMarkPublishedStoppedGeometrySafe(
				true, -1, false, 808, 4, 808, true));
		assertFalse(CustomMovementHandler.ShouldMarkPublishedStoppedGeometrySafe(
				false, 422, false, 808, 4, 808, true));
		assertFalse(CustomMovementHandler.ShouldMarkPublishedStoppedGeometrySafe(
				false, -1, true, 808, 4, 808, true));
		assertFalse(CustomMovementHandler.ShouldMarkPublishedStoppedGeometrySafe(
				false, -1, false, 1661, 4, 808, true));
		assertFalse(CustomMovementHandler.ShouldMarkPublishedStoppedGeometrySafe(
				false, -1, false, 808, -1, 808, true));
		assertFalse(CustomMovementHandler.ShouldMarkPublishedStoppedGeometrySafe(
				false, -1, false, -1, 4, -1, true));
		assertFalse(CustomMovementHandler.ShouldMarkPublishedStoppedGeometrySafe(
				false, -1, false, 808, 4, 808, false));
		assertTrue(CustomMovementHandler
				.ShouldContinueReleasedFacingForPendingWalk(true, false));
		assertFalse(CustomMovementHandler
				.ShouldContinueReleasedFacingForPendingWalk(true, true));

		// The endpoint clock advances only with client cycles, never with extra
		// render calls in the same cycle, and republishes only changed keyframes
		// or changed appearance.
		assertEquals(0, CustomMovementHandler
				.GetEndpointIdleClockDelta(100, -1));
		assertEquals(0, CustomMovementHandler
				.GetEndpointIdleClockDelta(100, 100));
		assertEquals(3, CustomMovementHandler
				.GetEndpointIdleClockDelta(103, 100));
		assertFalse(CustomMovementHandler
				.ShouldPublishEndpointIdleFrame(808, 4, 808, 4, true));
		assertTrue(CustomMovementHandler
				.ShouldPublishEndpointIdleFrame(808, 4, 808, 5, true));
		assertTrue(CustomMovementHandler
				.ShouldPublishEndpointIdleFrame(808, 4, 808, 4, false));

		// A real segment immediately preempts endpoint idle and selects
		// locomotion; no catch-up/handoff readiness is consulted.
		assertFalse(CustomMovementHandler
				.ShouldUseEndpointIdlePresentation(
						true, false, true, false, true, false,
						true, false, false, -1));
		assertTrue(CustomMovementHandler
				.ShouldSelectMovementPose(false, true, false, false));
	}

	@Test
	public void debugRenderDiagnosticsAreOptIn()
	{
		TrueTileMovementConfig Config = new TrueTileMovementConfig() { };
		assertFalse(Config.DebugMovementIdleFlick());
		assertFalse(Config.DebugStallTrace());
	}

	@Test
	public void proximityHandoffOccursOnlyAtStableIdle()
	{
		assertTrue(CustomMovementHandler
				.ShouldUseOriginalOwnerPresentation(
						true,
						false,
						false,
						false,
						-1,
						0,
						0,
						5,
						1,
						10));

		assertFalse(CustomMovementHandler
				.ShouldUseOriginalOwnerPresentation(
						true,
						true,
						false,
						false,
						-1,
						0,
						0,
						5,
						1,
						10));
		assertFalse(CustomMovementHandler
				.ShouldUseOriginalOwnerPresentation(
						true,
						false,
						false,
						false,
						422,
						0,
						0,
						5,
						1,
						10));
		// The old one-sided orientation comparison accepted every sufficiently
		// negative difference, even when the models faced far apart.
		assertFalse(CustomMovementHandler
				.ShouldUseOriginalOwnerPresentation(
						true,
						false,
						false,
						false,
						-1,
						0,
						0,
						-200,
						1,
						10));
		// An exact stationary transform can use RuneScape's native player pass
		// even during an action or when the optional proximity handoff is off.
		assertTrue(CustomMovementHandler
				.ShouldUseOriginalOwnerPresentation(
						false,
						false,
						false,
						false,
						AnimationID.HUMAN_WOODCUTTING_RUNE_AXE,
						0,
						0,
						0,
						1,
						10));
	}

	@Test
	public void equivalentAnimationIdsDoNotResetTheController()
	{
		assertFalse(CustomMovementHandler
				.ShouldReplaceAnimationController(
						808,
						808,
						false));
		assertTrue(CustomMovementHandler
				.ShouldReplaceAnimationController(
						808,
						819,
						false));
		assertTrue(CustomMovementHandler
				.ShouldReplaceAnimationController(
						808,
						808,
						true));
	}

	@Test
	public void controllerAnimationClockUsesClientCyclesAndBoundsCatchup()
	{
		// Render FPS can vary without changing the number of RuneLite client
		// cycles that an AnimationController should consume.
		assertEquals(1, CustomMovementHandler
				.GetControllerAnimationClockDelta(121, 120));
		assertEquals(1, CustomMovementHandler
				.GetControllerAnimationClockDelta(122, 121));
		assertEquals(5, CustomMovementHandler
				.GetControllerAnimationClockDelta(125, 120));
		assertEquals(0, CustomMovementHandler
				.GetControllerAnimationClockDelta(120, 120));
		assertEquals(0, CustomMovementHandler
				.GetControllerAnimationClockDelta(119, 120));
		assertEquals(0, CustomMovementHandler
				.GetControllerAnimationClockDelta(120, -1));
		assertEquals(100, CustomMovementHandler
				.GetControllerAnimationClockDelta(500, 120));
	}

	@Test
	public void nativeLocomotionSelectorsShareTheSmoothedRequestedPose()
	{
		assertTrue(CustomMovementHandler
				.ShouldOverrideNativeLocomotionPoseSelectors(
						true, true, false, true, false,
						-1, -1, 1661));

		// The selector override belongs only to ordinary native-model
		// locomotion while RuneLite Animation Smoothing owns the pose clock.
		assertFalse(CustomMovementHandler
				.ShouldOverrideNativeLocomotionPoseSelectors(
						false, true, false, true, false,
						-1, -1, 1661));
		assertFalse(CustomMovementHandler
				.ShouldOverrideNativeLocomotionPoseSelectors(
						true, false, false, true, false,
						-1, -1, 1661));
		assertFalse(CustomMovementHandler
				.ShouldOverrideNativeLocomotionPoseSelectors(
						true, true, true, true, false,
						-1, -1, 1661));
		assertFalse(CustomMovementHandler
				.ShouldOverrideNativeLocomotionPoseSelectors(
						true, true, false, false, false,
						-1, -1, 1661));
		assertFalse(CustomMovementHandler
				.ShouldOverrideNativeLocomotionPoseSelectors(
						true, true, false, true, true,
						-1, -1, 1661));
		assertFalse(CustomMovementHandler
				.ShouldOverrideNativeLocomotionPoseSelectors(
						true, true, false, true, false,
						422, -1, 1661));
		assertFalse(CustomMovementHandler
				.ShouldOverrideNativeLocomotionPoseSelectors(
						true, true, false, true, false,
						-1, 2106, 1661));
		assertFalse(CustomMovementHandler
				.ShouldOverrideNativeLocomotionPoseSelectors(
						true, true, false, true, false,
						-1, -1, -1));

		// Directional locomotion selectors use the requested pose, but the
		// actor's idle selector must continue to identify genuine idle state.
		assertEquals(808, CustomMovementHandler
				.SelectNativeIdlePoseSelectorAnimation(
						false, 1661, 808));
		assertEquals(808, CustomMovementHandler
				.SelectNativeIdlePoseSelectorAnimation(
						true, 808, 1660));
	}

	@Test
	public void orientationUsesRuneLite2048UnitRing()
	{
		// Crossing the 0/2047 seam is one orientation unit, not zero.
		assertEquals(2047.0, CustomMovementHandler
				.AdvanceOrientationPhase(0, 2047, 1), 0.0);
		assertEquals(0.0, CustomMovementHandler
				.AdvanceOrientationPhase(2047, 0, 1), 0.0);

		// A one-unit target change below the real half-turn must not reverse
		// the selected turn direction.
		assertEquals(1.0, CustomMovementHandler
				.AdvanceOrientationPhase(0, 998, 1), 0.0);
		assertEquals(1.0, CustomMovementHandler
				.AdvanceOrientationPhase(0, 999, 1), 0.0);
		assertEquals(1.0, CustomMovementHandler
				.AdvanceOrientationPhase(0, 1023, 1), 0.0);
		assertEquals(2047.0, CustomMovementHandler
				.AdvanceOrientationPhase(0, 1024, 1), 0.0);

		// Fractional progress must survive either direction across the seam.
		assertEquals(0.25, CustomMovementHandler
				.AdvanceOrientationPhase(2047.75, 1, 0.5), 0.0);
		assertEquals(2047.75, CustomMovementHandler
				.AdvanceOrientationPhase(0.25, 2047, 0.5), 0.0);
	}

	@Test
	public void pointOrientationMapsCardinalsWithoutDuplicatingTheSeam()
	{
		assertEquals(0, CustomMovementHandler
				.getOrientationBetweenPoints(0, 0, 0, -1, 90));
		assertEquals(256, CustomMovementHandler
				.getOrientationBetweenPoints(0, 0, -1, -1, 90));
		assertEquals(512, CustomMovementHandler
				.getOrientationBetweenPoints(0, 0, -1, 0, 90));
		assertEquals(768, CustomMovementHandler
				.getOrientationBetweenPoints(0, 0, -1, 1, 90));
		assertEquals(1024, CustomMovementHandler
				.getOrientationBetweenPoints(0, 0, 0, 1, 90));
		assertEquals(1280, CustomMovementHandler
				.getOrientationBetweenPoints(0, 0, 1, 1, 90));
		assertEquals(1536, CustomMovementHandler
				.getOrientationBetweenPoints(0, 0, 1, 0, 90));
		assertEquals(1792, CustomMovementHandler
				.getOrientationBetweenPoints(0, 0, 1, -1, 90));

		// The auxiliary-camera offset can exceed 360 degrees before
		// normalization; it must still produce a valid orientation.
		assertEquals(1536, CustomMovementHandler
				.getOrientationBetweenPoints(0, 0, -1, 0, 270));
	}

	@Test
	public void poseFramePublicationCarriesOnlyCompatibleValidPhases()
	{
		assertEquals(5, CustomMovementHandler.SelectPoseFrameForPublication(
				1661, -1, 1661, 1661, 5, 8, 0, false, false));
		assertEquals(0, CustomMovementHandler.SelectPoseFrameForPublication(
				1661, -1, 1661, 1660, 5, 8, 0, false, false));
		assertEquals(5, CustomMovementHandler.SelectPoseFrameForPublication(
				1661, -2, 1661, 1661, 5, 8, 0, false, false));
		// An invalid frame cannot borrow a remembered phase from a different
		// animation; it uses the requested animation's authored entry frame.
		assertEquals(2, CustomMovementHandler.SelectPoseFrameForPublication(
				1661, -1, 808, 1661, 5, 12, 2, false, false));
		assertEquals(3, CustomMovementHandler.SelectPoseFrameForPublication(
				1661, 3, 1661, 1661, 5, 8, 0, false, false));
		// Directional locomotion variants deliberately keep phase when their
		// animation IDs change, preventing route-segment frame-zero resets.
		assertEquals(3, CustomMovementHandler.SelectPoseFrameForPublication(
				1661, 3, 1660, 1661, 3, 8, 0, false, false));
		// A stationary locomotion-to-idle mismatch deliberately starts from
		// idle's authored entry frame instead of racing the idle pose on the
		// hidden actor's still-active locomotion clock.
		assertEquals(0, CustomMovementHandler.SelectPoseFrameForPublication(
				1661, 6, 808, 1661, 6, 12, 0, false, true));
		// A retry after a transient model miss still republishes the authored
		// idle entry even if the numeric idle frame advanced meanwhile.
		assertEquals(0, CustomMovementHandler.SelectPoseFrameForPublication(
				808, 5, 808, 808, 5, 12, 0, false, true));
		assertEquals(2, CustomMovementHandler.SelectPoseFrameForPublication(
				1661, 3, 1661, 1661, 5, 8, 2, true, false));
		assertEquals(2, CustomMovementHandler.SelectPoseFrameForPublication(
				1661, 8, 1661, 1661, 5, 8, 2, false, false));
	}

	@Test
	public void stationaryIdleRestartIsLimitedToTheNativeLocomotionMismatch()
	{
		assertTrue(CustomMovementHandler.ShouldRestartStationaryIdlePose(
				false, -1, 1661, 808, 808));
		assertFalse(CustomMovementHandler.ShouldRestartStationaryIdlePose(
				true, -1, 1661, 808, 808));
		assertFalse(CustomMovementHandler.ShouldRestartStationaryIdlePose(
				false, 422, 1661, 808, 808));
		assertFalse(CustomMovementHandler.ShouldRestartStationaryIdlePose(
				false, -1, 1661, 1660, 808));
		assertFalse(CustomMovementHandler.ShouldRestartStationaryIdlePose(
				false, -1, 808, 808, 808));
	}

	@Test
	public void shortRenderCallbackGapDoesNotDisableThePlugin()
	{
		assertTrue(TrueTileMovementPlugin
				.IsGpuCallbackStillSupported(
						GameState.LOGGED_IN,
						50));
		assertFalse(TrueTileMovementPlugin
				.IsGpuCallbackStillSupported(
						GameState.LOGGED_IN,
						51));
		assertTrue(TrueTileMovementPlugin
				.IsGpuCallbackStillSupported(
						GameState.LOADING,
						500));
	}

	@Test
	public void movementSpeedLeadNeverCreatesAnEndpointPlateau()
	{
		double[] Multipliers = {1.0, 1.1, 1.2, 1.3, 2.0, 3.0};
		for (double Multiplier : Multipliers)
		{
			double PreviousProgress = -1.0;
			for (int Milliseconds = 0;
				 Milliseconds <= 600;
				 ++Milliseconds)
			{
				double BaseProgress = Milliseconds / 600.0;
				double Progress = CustomMovementHandler
						.ApplyContinuousMovementSpeedLead(
								BaseProgress,
								Multiplier);
				assertTrue(Progress >= PreviousProgress);
				if (Milliseconds > 0)
				{
					assertTrue(Progress > PreviousProgress);
				}
				assertTrue(Progress >= BaseProgress);
				if (Milliseconds < 600)
				{
					assertTrue(Progress < 1.0);
				}
				PreviousProgress = Progress;
			}
			assertEquals(1.0, PreviousProgress, 0.0);
		}
	}

	@Test
	public void movementSpeedLeadJoinsSegmentsAtNativeVelocity()
	{
		double SmallPhase = 0.000001;
		double Multiplier = 1.75;
		double StartSlope = CustomMovementHandler
				.ApplyContinuousMovementSpeedLead(
						SmallPhase,
						Multiplier) / SmallPhase;
		double EndSlope =
				(1.0 - CustomMovementHandler
						.ApplyContinuousMovementSpeedLead(
								1.0 - SmallPhase,
								Multiplier)) /
						SmallPhase;

		assertEquals(1.0, StartSlope, 0.00001);
		assertEquals(1.0, EndSlope, 0.00001);
		assertTrue(CustomMovementHandler
				.ApplyContinuousMovementSpeedLead(
						0.5,
						1.3) > 0.5);
	}

	@Test
	public void movementSpeedLeadIsBoundedWithoutChangingRequestChoreography()
	{
		assertEquals(1.3, CustomMovementHandler
				.GetConfiguredMovementLeadMultiplier(1.3), 0.0);
		assertEquals(1.75, CustomMovementHandler
				.GetConfiguredMovementLeadMultiplier(3.0), 0.0);
		assertEquals(1.0, CustomMovementHandler
				.GetConfiguredMovementLeadMultiplier(0.5), 0.0);
		assertEquals(1.0, CustomMovementHandler
				.GetConfiguredMovementLeadMultiplier(Double.NaN), 0.0);

		assertEquals(600, CustomMovementHandler
				.GetRequestMovementTweenDurationMilliseconds(1.0));
		assertEquals(400, CustomMovementHandler
				.GetRequestMovementTweenDurationMilliseconds(1.5));
		assertEquals(300, CustomMovementHandler
				.GetRequestMovementTweenDurationMilliseconds(2.0));
		assertEquals(200, CustomMovementHandler
				.GetRequestMovementTweenDurationMilliseconds(3.0));
		assertEquals(600, CustomMovementHandler
				.GetRequestMovementTweenDurationMilliseconds(Double.NaN));
		assertEquals(600, CustomMovementHandler
				.GetRequestMovementTweenDurationMilliseconds(
						Double.POSITIVE_INFINITY));

		double ProgressAt599 = CustomMovementHandler
				.ApplyContinuousMovementSpeedLead(
						599.0 / 600.0,
						3.0);
		int From = 1280;
		int To = 1536;
		int Draw = (int) (From + (To - From) * ProgressAt599);
		assertTrue(Draw >= From);
		assertTrue(Draw < To);
		assertEquals(0.0, CustomMovementHandler
				.ApplyContinuousMovementSpeedLead(Double.NaN, 1.3), 0.0);
	}

	@Test
	public void movementSpeedLeadBypassesSpecialSceneTiming()
	{
		assertTrue(CustomMovementHandler
				.ShouldApplyContinuousMovementSpeedLead(
						false, false, false, 0,
						false, false, false));
		assertFalse(CustomMovementHandler
				.ShouldApplyContinuousMovementSpeedLead(
						true, false, false, 0,
						false, false, false));
		assertFalse(CustomMovementHandler
				.ShouldApplyContinuousMovementSpeedLead(
						false, true, false, 0,
						false, false, false));
		assertFalse(CustomMovementHandler
				.ShouldApplyContinuousMovementSpeedLead(
						false, false, true, 0,
						false, false, false));
		assertFalse(CustomMovementHandler
				.ShouldApplyContinuousMovementSpeedLead(
						false, false, false, 450,
						false, false, false));
		assertFalse(CustomMovementHandler
				.ShouldApplyContinuousMovementSpeedLead(
						false, false, false, 0,
						true, false, false));
		assertFalse(CustomMovementHandler
				.ShouldApplyContinuousMovementSpeedLead(
						false, false, false, 0,
						false, true, false));
		assertFalse(CustomMovementHandler
				.ShouldApplyContinuousMovementSpeedLead(
						false, false, false, 0,
						false, false, true));
	}

	@Test
	public void highFpsTurnKeepsItsFractionalProgress()
	{
		double FiveMillisecondFrames = advanceOrientationForDuration(
				0,
				1024,
				400,
				new int[]{5});
		double MixedSixtyFpsFrames = advanceOrientationForDuration(
				0,
				1024,
				400,
				new int[]{16, 17});

		assertEquals(MixedSixtyFpsFrames, FiveMillisecondFrames, 0.0001);
	}

	@Test
	public void highFpsHalfTurnCompletesBeforeTheNextGameTick()
	{
		double FirstHalfTurn = advanceOrientationForDuration(
				0,
				1024,
				600,
				new int[]{5, 6, 6});
		double ReversedHalfTurn = advanceOrientationForDuration(
				FirstHalfTurn,
				0,
				600,
				new int[]{5, 6, 6});

		assertEquals(1024.0, FirstHalfTurn, 0.0);
		assertEquals(0.0, ReversedHalfTurn, 0.0);
	}

	private static double advanceOrientationForDuration(
			double InitialOrientation,
			int TargetOrientation,
			int DurationMilliseconds,
			int[] FrameCadenceMilliseconds)
	{
		double Orientation = InitialOrientation;
		int Elapsed = 0;
		int Frame = 0;
		while (Elapsed < DurationMilliseconds)
		{
			int FrameDelta = Math.min(
					FrameCadenceMilliseconds[
							Frame % FrameCadenceMilliseconds.length],
					DurationMilliseconds - Elapsed);
			Orientation = CustomMovementHandler.AdvanceOrientationPhase(
					Orientation,
					TargetOrientation,
					30 * (FrameDelta / 16.667));
			Elapsed += FrameDelta;
			++Frame;
		}
		return Orientation;
	}

}
