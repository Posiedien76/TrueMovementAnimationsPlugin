package com.truetileanimationmovement;

import net.runelite.api.coords.LocalPoint;
import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class SceneLoadContinuityTest
{
	@Test
	public void unfinishedYellowRouteWaitsForItsFirstPostSceneSegment()
	{
		LocalPoint CurrentSegmentDestination =
				new LocalPoint(6208, 6208, 0);
		LocalPoint RouteDestination =
				new LocalPoint(6208, 6464, 0);

		assertTrue(CustomMovementHandler
				.ShouldAwaitPostSceneWalkSegment(
						false,
						false,
						true,
						true,
						CurrentSegmentDestination,
						RouteDestination));
		assertFalse(CustomMovementHandler
				.ShouldAwaitPostSceneWalkSegment(
						true,
						false,
						true,
						true,
						CurrentSegmentDestination,
						RouteDestination));
		assertFalse(CustomMovementHandler
				.ShouldAwaitPostSceneWalkSegment(
						false,
						true,
						true,
						true,
						CurrentSegmentDestination,
						RouteDestination));
		assertFalse(CustomMovementHandler
				.ShouldAwaitPostSceneWalkSegment(
						false,
						false,
						false,
						true,
						CurrentSegmentDestination,
						RouteDestination));
		assertFalse(CustomMovementHandler
				.ShouldAwaitPostSceneWalkSegment(
						false,
						false,
						true,
						true,
						CurrentSegmentDestination,
						CurrentSegmentDestination));
		assertFalse(CustomMovementHandler
				.ShouldAwaitPostSceneWalkSegment(
						false,
						false,
						true,
						true,
						CurrentSegmentDestination,
						new LocalPoint(6208, 6464, 1)));
	}

	@Test
	public void movingPostSceneRecoveryBridgesOnlyItsBoundedRouteGap()
	{
		LocalPoint CurrentSegmentDestination =
				new LocalPoint(6208, 6208, 0);
		LocalPoint RouteDestination =
				new LocalPoint(6208, 6464, 0);

		assertTrue(CustomMovementHandler
				.ShouldArmPostSceneRecoveryRouteGap(true, true));
		// A zero-distance rebase and an ordinary fresh-click wait retain the
		// existing stable endpoint idle instead of running in place.
		assertFalse(CustomMovementHandler
				.ShouldArmPostSceneRecoveryRouteGap(true, false));
		assertFalse(CustomMovementHandler
				.ShouldArmPostSceneRecoveryRouteGap(false, true));

		assertTrue(CustomMovementHandler
				.ShouldKeepMovementAnimationDuringPostSceneRecoveryRouteGap(
						true, 600, 600, true, true, true,
						CurrentSegmentDestination, RouteDestination));
		assertTrue(CustomMovementHandler
				.ShouldKeepMovementAnimationDuringPostSceneRecoveryRouteGap(
						true, 899, 600, true, true, true,
						CurrentSegmentDestination, RouteDestination));
		assertFalse(CustomMovementHandler
				.ShouldKeepMovementAnimationDuringPostSceneRecoveryRouteGap(
						true, 900, 600, true, true, true,
						CurrentSegmentDestination, RouteDestination));

		// Eligibility cannot outlive its prior movement, yellow-route ownership,
		// click revision, or unfinished same-view destination.
		assertFalse(CustomMovementHandler
				.ShouldKeepMovementAnimationDuringPostSceneRecoveryRouteGap(
						false, 600, 600, true, true, true,
						CurrentSegmentDestination, RouteDestination));
		assertFalse(CustomMovementHandler
				.ShouldKeepMovementAnimationDuringPostSceneRecoveryRouteGap(
						true, 600, 600, false, true, true,
						CurrentSegmentDestination, RouteDestination));
		assertFalse(CustomMovementHandler
				.ShouldKeepMovementAnimationDuringPostSceneRecoveryRouteGap(
						true, 600, 600, true, false, true,
						CurrentSegmentDestination, RouteDestination));
		assertFalse(CustomMovementHandler
				.ShouldKeepMovementAnimationDuringPostSceneRecoveryRouteGap(
						true, 600, 600, true, true, false,
						CurrentSegmentDestination, RouteDestination));
		assertFalse(CustomMovementHandler
				.ShouldKeepMovementAnimationDuringPostSceneRecoveryRouteGap(
						true, 600, 600, true, true, true,
						CurrentSegmentDestination,
						CurrentSegmentDestination));
	}

	@Test
	public void rebaseCarriesOnlyMissedFrameTime()
	{
		assertEquals(
				0,
				CustomMovementHandler
						.GetSceneRebaseElapsedMilliseconds(-1));
		assertEquals(
				228,
				CustomMovementHandler
						.GetSceneRebaseElapsedMilliseconds(228));
		assertEquals(
				600,
				CustomMovementHandler
						.GetSceneRebaseElapsedMilliseconds(900));
		assertEquals(
				600,
				CustomMovementHandler
						.GetSceneRebaseElapsedMilliseconds(
								228,
								false));
		assertEquals(
				0,
				CustomMovementHandler
						.GetSceneRebaseElapsedMilliseconds(
								228,
								true,
								true));
		assertEquals(
				600,
				CustomMovementHandler
						.GetSceneRebaseElapsedMilliseconds(
								228,
								false,
								true));
		assertEquals(
				34,
				CustomMovementHandler
						.GetSceneRebaseImmediateElapsedMilliseconds(
								49,
								true,
								false));
		assertEquals(
				15,
				CustomMovementHandler
						.GetSceneRebaseDeferredMilliseconds(
								49,
								true,
								false));
		assertEquals(
				0,
				CustomMovementHandler
						.GetSceneRebaseImmediateElapsedMilliseconds(
								49,
								true,
								true));
		assertEquals(
				0,
				CustomMovementHandler
						.GetSceneRebaseDeferredMilliseconds(
								49,
								true,
								true));
		assertEquals(
				600,
				CustomMovementHandler
						.GetSceneRebaseImmediateElapsedMilliseconds(
								49,
								false,
								false));
		assertEquals(
				135,
				CustomMovementHandler.AddScenePresentationDebt(
						120,
						15));
		assertEquals(
				600,
				CustomMovementHandler.AddScenePresentationDebt(
						590,
						15));
		assertEquals(
				135,
				CustomMovementHandler
						.SelectScenePresentationDebtAfterRebase(
								120,
								15,
								true,
								false));
		assertEquals(
				0,
				CustomMovementHandler
						.SelectScenePresentationDebtAfterRebase(
								120,
								15,
								false,
								false));
		assertEquals(
				0,
				CustomMovementHandler
						.SelectScenePresentationDebtAfterRebase(
								120,
								15,
								true,
								true));
	}

	@Test
	public void unfinishedRecoveryReanchorsAtDisplayedPoint()
	{
		assertTrue(CustomMovementHandler.ShouldReanchorSceneRecovery(
				true,
				228,
				600,
				true));
	}

	@Test
	public void ordinaryOrCompletedMovementDoesNotUseRecoveryReanchor()
	{
		assertFalse(CustomMovementHandler.ShouldReanchorSceneRecovery(
				false,
				228,
				600,
				true));
		assertFalse(CustomMovementHandler.ShouldReanchorSceneRecovery(
				true,
				600,
				600,
				true));
		assertFalse(CustomMovementHandler.ShouldReanchorSceneRecovery(
				true,
				228,
				600,
				false));
	}

	@Test
	public void ordinaryRouteUpdateKeepsItsAuthoritativeOrigin()
	{
		LocalPoint PreviousAuthoritativeEndpoint =
				new LocalPoint(5056, 5696, 0);
		LocalPoint FractionalDisplayedPoint =
				new LocalPoint(4928, 5824, 0);
		LocalPoint RequestedEndpoint =
				new LocalPoint(4800, 5952, 0);

		LocalPoint SelectedOrigin = CustomMovementHandler
				.SelectRouteUpdateOrigin(
						false,
						true,
						FractionalDisplayedPoint,
						PreviousAuthoritativeEndpoint,
						PreviousAuthoritativeEndpoint,
						RequestedEndpoint);

		assertSame(PreviousAuthoritativeEndpoint, SelectedOrigin);
		assertEquals(2, Math.max(
				Math.abs(RequestedEndpoint.getX() -
						SelectedOrigin.getX()),
				Math.abs(RequestedEndpoint.getY() -
						SelectedOrigin.getY())) / 128);
		assertEquals(1, Math.max(
				Math.abs(RequestedEndpoint.getX() -
						FractionalDisplayedPoint.getX()),
				Math.abs(RequestedEndpoint.getY() -
						FractionalDisplayedPoint.getY())) / 128);
		assertFalse(CustomMovementHandler
				.ShouldResetRouteDirectionBaseline(false, true));
	}

	@Test
	public void recoveryAndFallbackSelectCorrectRouteOrigins()
	{
		LocalPoint PreviousAuthoritativeEndpoint =
				new LocalPoint(5056, 5696, 0);
		LocalPoint FractionalDisplayedPoint =
				new LocalPoint(4928, 5824, 0);
		LocalPoint ConvertedPreviousEndpoint =
				new LocalPoint(4992, 5760, 0);
		LocalPoint RequestedEndpoint =
				new LocalPoint(4800, 5952, 0);

		assertSame(
				FractionalDisplayedPoint,
				CustomMovementHandler.SelectRouteUpdateOrigin(
						true,
						false,
						FractionalDisplayedPoint,
						PreviousAuthoritativeEndpoint,
						PreviousAuthoritativeEndpoint,
						RequestedEndpoint));
		assertFalse(CustomMovementHandler
				.ShouldResetRouteDirectionBaseline(true, false));

		assertSame(
				RequestedEndpoint,
				CustomMovementHandler.SelectRouteUpdateOrigin(
						false,
						false,
						FractionalDisplayedPoint,
						PreviousAuthoritativeEndpoint,
						null,
						RequestedEndpoint));
		assertTrue(CustomMovementHandler
				.ShouldResetRouteDirectionBaseline(false, false));

		assertSame(
				ConvertedPreviousEndpoint,
				CustomMovementHandler.SelectRouteUpdateOrigin(
						false,
						false,
						FractionalDisplayedPoint,
						PreviousAuthoritativeEndpoint,
						ConvertedPreviousEndpoint,
						RequestedEndpoint));
		assertTrue(CustomMovementHandler
				.ShouldResetRouteDirectionBaseline(false, false));
	}

	@Test
	public void nativeOwnerRemainsVisibleUntilReplacementIsReady()
	{
		assertFalse(TrueTileMovementPlugin.ShouldSuppressNativeOwner(
				true,
				true,
				false));
		assertFalse(TrueTileMovementPlugin.ShouldSuppressNativeOwner(
				false,
				true,
				true));
		assertFalse(TrueTileMovementPlugin.ShouldSuppressNativeOwner(
				false,
				false,
				false));
		assertTrue(TrueTileMovementPlugin.ShouldSuppressNativeOwner(
				false,
				false,
				true));
	}

	@Test
	public void presentedNativeHandoffBecomesTheRebaseAnchor()
	{
		LocalPoint NativeOwner = new LocalPoint(100, 200, 0);
		LocalPoint RetainedCustom = new LocalPoint(300, 400, 0);

		assertSame(
				NativeOwner,
				CustomMovementHandler.SelectSceneRebaseAnchor(
						true,
						NativeOwner,
						RetainedCustom));
		assertSame(
				RetainedCustom,
				CustomMovementHandler.SelectSceneRebaseAnchor(
						false,
						NativeOwner,
						RetainedCustom));
	}

	@Test
	public void onlyOutwardMovementAtSceneMarginUsesBoundaryBridge()
	{
		int lowBoundary = 16 * 128 + 64;
		int highBoundary = 87 * 128 + 64;

		assertTrue(CustomMovementHandler.IsSceneBoundaryExitSegment(
				new LocalPoint(5000, lowBoundary + 128, 0),
				new LocalPoint(5000, lowBoundary, 0),
				new LocalPoint(5000, lowBoundary - 1024, 0)));
		assertTrue(CustomMovementHandler.IsSceneBoundaryExitSegment(
				new LocalPoint(highBoundary - 128, 5000, 0),
				new LocalPoint(highBoundary, 5000, 0),
				new LocalPoint(highBoundary + 1024, 5000, 0)));
		assertFalse(CustomMovementHandler.IsSceneBoundaryExitSegment(
				new LocalPoint(5000, lowBoundary + 128, 0),
				new LocalPoint(5000, lowBoundary, 0),
				new LocalPoint(5000, lowBoundary + 1024, 0)));
		assertFalse(CustomMovementHandler.IsSceneBoundaryExitSegment(
				new LocalPoint(5000, 5000, 0),
				new LocalPoint(5000, 5000 - 128, 0),
				new LocalPoint(5000, 1000, 0)));
	}

	@Test
	public void boundaryBridgeIsTimeAndDistanceBounded()
	{
		assertEquals(
				1.0,
				CustomMovementHandler.GetSceneBoundaryBridgeTweenValue(
						600,
						600,
						128),
				0.0001);
		assertEquals(
				1.2,
				CustomMovementHandler.GetSceneBoundaryBridgeTweenValue(
						720,
						600,
						128),
				0.0001);
		assertEquals(
				1.25,
				CustomMovementHandler.GetSceneBoundaryBridgeTweenValue(
						900,
						600,
						256),
				0.0001);
	}

	@Test
	public void scenePresentationClockDefersAndGraduallyRepaysLongFrames()
	{
		assertEquals(
				34,
				CustomMovementHandler
						.GetScenePresentationImmediateFrameDelta(212));
		assertEquals(
				22,
				CustomMovementHandler
						.GetScenePresentationImmediateFrameDelta(22));
		assertEquals(
				4,
				CustomMovementHandler
						.GetScenePresentationDebtPayback(
								22,
								190,
								0));
		assertEquals(
				2,
				CustomMovementHandler
						.GetScenePresentationDebtPayback(
								34,
								2,
								0));
		assertEquals(
				0,
				CustomMovementHandler
						.GetScenePresentationDebtPayback(
								0,
								190,
								3));
		assertEquals(
				3,
				CustomMovementHandler
						.GetScenePresentationDebtPaybackRemainder(
								0,
								190,
								3));
	}

	@Test
	public void scenePresentationDebtRepaysAtAStableFractionAcrossHighFpsFrames()
	{
		int[] FrameDeltas = {7, 7, 7, 7, 7};
		int[] ExpectedPayback = {1, 1, 2, 1, 2};
		int TimeDebt = 190;
		int PaybackRemainder = 0;
		int TotalPayback = 0;

		for (int Index = 0; Index < FrameDeltas.length; ++Index)
		{
			int Payback =
					CustomMovementHandler
							.GetScenePresentationDebtPayback(
									FrameDeltas[Index],
									TimeDebt,
									PaybackRemainder);
			int NextRemainder =
					CustomMovementHandler
							.GetScenePresentationDebtPaybackRemainder(
									FrameDeltas[Index],
									TimeDebt,
									PaybackRemainder);

			assertEquals(ExpectedPayback[Index], Payback);
			TimeDebt -= Payback;
			TotalPayback += Payback;
			PaybackRemainder = NextRemainder;
		}

		assertEquals(7, TotalPayback);
		assertEquals(0, PaybackRemainder);

		FrameDeltas = new int[]{5, 6, 5, 6};
		TimeDebt = 190;
		PaybackRemainder = 0;
		TotalPayback = 0;
		for (int FrameDelta : FrameDeltas)
		{
			int Payback =
					CustomMovementHandler
							.GetScenePresentationDebtPayback(
									FrameDelta,
									TimeDebt,
									PaybackRemainder);
			int NextRemainder =
					CustomMovementHandler
							.GetScenePresentationDebtPaybackRemainder(
									FrameDelta,
									TimeDebt,
									PaybackRemainder);
			assertEquals(1, Payback);
			TimeDebt -= Payback;
			TotalPayback += Payback;
			PaybackRemainder = NextRemainder;
		}

		assertEquals(4, TotalPayback);
		assertEquals(2, PaybackRemainder);
	}

	@Test
	public void scenePresentationDebtDiscardsFractionWhenDebtIsExhausted()
	{
		assertEquals(
				1,
				CustomMovementHandler
						.GetScenePresentationDebtPayback(
								10,
								1,
								0));
		assertEquals(
				0,
				CustomMovementHandler
						.GetScenePresentationDebtPaybackRemainder(
								10,
								1,
								0));
	}

	@Test
	public void recoveryRetargetPreservesEstablishedVelocity()
	{
		double runVelocity = 256.0 / 600.0;

		assertEquals(
				736,
				CustomMovementHandler.GetSceneRecoveryTweenDuration(
						314,
						runVelocity,
						600));
		assertEquals(
				600,
				CustomMovementHandler.GetSceneRecoveryTweenDuration(
						0,
						runVelocity,
						600));
	}

	@Test
	public void productionSceneVelocityRetainsFractionalPrecision()
	{
		LocalPoint Start = new LocalPoint(1280, 2560, 0);
		LocalPoint OneTileAway = new LocalPoint(1408, 2560, 0);

		assertEquals(
				128.0 / 600.0,
				CustomMovementHandler
						.GetPreservedSceneMovementVelocity(
								true,
								Start,
								OneTileAway,
								600),
				0.000001);
		assertEquals(
				0,
				CustomMovementHandler
						.GetPreservedSceneMovementVelocity(
								false,
								Start,
								OneTileAway,
								600),
				0.000001);
		assertEquals(
				0,
				CustomMovementHandler
						.GetPreservedSceneMovementVelocity(
								true,
								Start,
								new LocalPoint(1408, 2560, 1),
								600),
				0.000001);
	}

	@Test
	public void partialSceneRecoveryKeepsPreLoadMovementSpeed()
	{
		double RunVelocity = 256.0 / 600.0;
		double RecoveryVelocity =
				CustomMovementHandler.GetSceneRecoveryBaseVelocity(
						RunVelocity,
						192,
						600);

		assertEquals(RunVelocity, RecoveryVelocity, 0.000001);
		assertEquals(
				450,
				CustomMovementHandler.GetSceneRecoveryTweenDuration(
						192,
						RecoveryVelocity,
						600));
	}

	@Test
	public void tinyHandoffOffsetCannotCreateMultiSecondRecovery()
	{
		assertEquals(
				128.0 / 600.0,
				CustomMovementHandler.GetSceneRecoveryBaseVelocity(
						0,
						2,
						600),
				0.000001);
		assertEquals(
				0,
				CustomMovementHandler.GetSceneRecoveryBaseVelocity(
						256.0 / 600.0,
						0,
						600),
				0.000001);
		assertEquals(
				128.0 / 600.0,
				CustomMovementHandler.GetSceneRecoveryBaseVelocity(
						0.000001,
						128,
						600),
				0.000001);
		assertEquals(
				1200,
				CustomMovementHandler.GetSceneRecoveryTweenDuration(
						128,
						0.000001,
						600));
	}

	@Test
	public void recoveryAndBoundaryBridgeCannotOwnTheSameFrame()
	{
		assertTrue(CustomMovementHandler.CanUseSceneBoundaryBridge(
				false,
				false,
				0));
		assertFalse(CustomMovementHandler.CanUseSceneBoundaryBridge(
				true,
				false,
				0));
		assertFalse(CustomMovementHandler.CanUseSceneBoundaryBridge(
				false,
				true,
				0));
		assertFalse(CustomMovementHandler.CanUseSceneBoundaryBridge(
				false,
				false,
				450));
	}

	@Test
	public void presentationClockWaitsForPreparedFrameAndRecovery()
	{
		assertTrue(CustomMovementHandler
				.ShouldReleaseScenePresentationClock(
						0,
						false,
						0,
						false));
		assertFalse(CustomMovementHandler
				.ShouldReleaseScenePresentationClock(
						0,
						false,
						0,
						true));
		assertFalse(CustomMovementHandler
				.ShouldReleaseScenePresentationClock(
						1,
						false,
						0,
						false));
		assertFalse(CustomMovementHandler
				.ShouldReleaseScenePresentationClock(
						0,
						true,
						0,
						false));
		assertFalse(CustomMovementHandler
				.ShouldReleaseScenePresentationClock(
						0,
						false,
						450,
						false));
	}

	@Test
	public void onlyProvenYellowMovementPreservesPreLoadVelocity()
	{
		// Preserve the existing overdue boundary bridge path.
		assertTrue(CustomMovementHandler
				.CanPreserveSceneMovementVelocity(
						true,
						false,
						true,
						true,
						false));
		// A cached scene can load while a segment is still visibly moving,
		// before the overdue boundary bridge has had a chance to arm.
		assertTrue(CustomMovementHandler
				.CanPreserveSceneMovementVelocity(
						false,
						true,
						true,
						true,
						false));
		assertFalse(CustomMovementHandler
				.CanPreserveSceneMovementVelocity(
						false,
						false,
						true,
						true,
						false));
		assertFalse(CustomMovementHandler
				.CanPreserveSceneMovementVelocity(
						true,
						true,
						false,
						true,
						false));
		assertFalse(CustomMovementHandler
				.CanPreserveSceneMovementVelocity(
						true,
						true,
						true,
						false,
						false));
		assertFalse(CustomMovementHandler
				.CanPreserveSceneMovementVelocity(
						true,
						true,
						true,
						true,
						true));

		LocalPoint DiagonalRunStart = new LocalPoint(4096, 4096, 0);
		LocalPoint DiagonalRunEnd = new LocalPoint(4352, 4352, 0);
		boolean DiagonalRunStillVisible =
				CustomMovementHandler.IsVisibleMovementSegmentInProgress(
						true, 599, 600,
						DiagonalRunStart, DiagonalRunEnd);
		assertTrue(DiagonalRunStillVisible);
		assertFalse(CustomMovementHandler
				.IsVisibleMovementSegmentInProgress(
						true, 600, 600,
						DiagonalRunStart, DiagonalRunEnd));
		assertFalse(CustomMovementHandler
				.IsVisibleMovementSegmentInProgress(
						true, 599, 600,
						DiagonalRunStart, DiagonalRunStart));
		assertEquals(
				(int) Math.sqrt(256.0 * 256.0 + 256.0 * 256.0) /
						600.0,
				CustomMovementHandler.GetPreservedSceneMovementVelocity(
						CustomMovementHandler.CanPreserveSceneMovementVelocity(
								false, DiagonalRunStillVisible,
								true, true, false),
						DiagonalRunStart,
						DiagonalRunEnd,
						600),
				0.000001);
	}

	@Test
	public void rejectedVelocityIsNotReusedAsPreservedSpeed()
	{
		assertEquals(
				0,
				CustomMovementHandler
						.GetRetainedSceneRecoveryBaseVelocity(
								0,
								192,
								600),
				0.000001);
		assertEquals(
				0,
				CustomMovementHandler.SelectSceneRecoveryVelocity(
						0,
						CustomMovementHandler
								.GetRetainedSceneRecoveryBaseVelocity(
										0,
										192,
										600)),
				0.000001);
	}

	@Test
	public void completedShortRecoveryCannotRewindAgainstNormalDuration()
	{
		assertFalse(CustomMovementHandler
				.ShouldCompleteSceneRecoveryOverride(
						449,
						450));
		assertTrue(CustomMovementHandler
				.ShouldCompleteSceneRecoveryOverride(
						466,
						450));
		assertEquals(
				600,
				CustomMovementHandler
						.GetSceneMovementAnimationDuration(450));
		assertEquals(
				736,
				CustomMovementHandler
						.GetSceneMovementAnimationDuration(736));
	}

	@Test
	public void authoritativeWalkOrRunSpeedOverridesPreLoadSpeed()
	{
		double WalkVelocity = 128.0 / 600.0;
		double RunVelocity = 256.0 / 600.0;

		assertEquals(
				WalkVelocity,
				CustomMovementHandler.SelectSceneRecoveryVelocity(
						WalkVelocity,
						RunVelocity),
				0.000001);
		assertEquals(
				RunVelocity,
				CustomMovementHandler.SelectSceneRecoveryVelocity(
						RunVelocity,
						WalkVelocity),
				0.000001);
		assertEquals(
				RunVelocity,
				CustomMovementHandler.SelectSceneRecoveryVelocity(
						0,
						RunVelocity),
				0.000001);
	}

	@Test
	public void onlyPlausibleNativeStepsSetPostLoadVelocity()
	{
		LocalPoint Start = new LocalPoint(1280, 2560, 0);

		assertTrue(CustomMovementHandler
				.IsPlausibleAuthoritativeSceneSegment(
						true,
						true,
						false,
						Start,
						new LocalPoint(1536, 2816, 0)));
		assertFalse(CustomMovementHandler
				.IsPlausibleAuthoritativeSceneSegment(
						true,
						true,
						false,
						Start,
						new LocalPoint(1664, 2560, 0)));
		assertFalse(CustomMovementHandler
				.IsPlausibleAuthoritativeSceneSegment(
						false,
						true,
						false,
						Start,
						new LocalPoint(1408, 2560, 0)));
		assertFalse(CustomMovementHandler
				.IsPlausibleAuthoritativeSceneSegment(
						true,
						true,
						false,
						Start,
						new LocalPoint(1408, 2560, 1)));
	}

	@Test
	public void teleportAndSpecialAnimationsRejectVelocityContinuity()
	{
		assertTrue(CustomMovementHandler.IsSceneMovementDiscontinuity(
				714,
				-1,
				false,
				Collections.singleton(714),
				Collections.singleton(749)));
		assertTrue(CustomMovementHandler.IsSceneMovementDiscontinuity(
				-1,
				749,
				false,
				Collections.singleton(714),
				Collections.singleton(749)));
		assertTrue(CustomMovementHandler.IsSceneMovementDiscontinuity(
				-1,
				-1,
				true,
				Collections.singleton(714),
				Collections.singleton(749)));
		assertFalse(CustomMovementHandler.IsSceneMovementDiscontinuity(
				-1,
				-1,
				false,
				Collections.singleton(714),
				Collections.singleton(749)));
	}

	@Test
	public void playerOwnedHouseRegionsRequireTheCompleteInstancePair()
	{
		assertTrue(CustomMovementHandler
				.ContainsPlayerOwnedHouseRegions(
						new int[]{8046, 8047}));
		assertTrue(CustomMovementHandler
				.ContainsPlayerOwnedHouseRegions(
						new int[]{9999, 8047, 8046}));
		assertFalse(CustomMovementHandler
				.ContainsPlayerOwnedHouseRegions(
						new int[]{8046}));
		assertFalse(CustomMovementHandler
				.ContainsPlayerOwnedHouseRegions(null));
	}

	@Test
	public void delayedPohArrivalMirrorsEveryPreInteractionUpdate()
	{
		LocalPoint TemporaryArrival = new LocalPoint(6208, 6208, 0);
		LocalPoint PortalRoom = new LocalPoint(4544, 5824, 0);

		assertTrue(CustomMovementHandler
				.ShouldSynchronizePohArrivalPresentation(
						true,
						7,
						7,
						PortalRoom,
						TemporaryArrival,
						PortalRoom,
						PortalRoom));
		assertTrue(CustomMovementHandler
				.ShouldSynchronizePohArrivalPresentation(
						true,
						7,
						7,
						PortalRoom,
						TemporaryArrival));
		assertFalse(CustomMovementHandler
				.ShouldSynchronizePohArrivalPresentation(
						true,
						7,
						7,
						PortalRoom,
						PortalRoom,
						PortalRoom));
		assertFalse(CustomMovementHandler
				.ShouldSynchronizePohArrivalPresentation(
						true,
						7,
						8,
						PortalRoom,
						TemporaryArrival));
	}

	@Test
	public void pohArrivalGuardRecoversAfterCleanupButNotAfterInteraction()
	{
		assertTrue(CustomMovementHandler
				.ShouldArmPohArrivalCoordinateGuard(
						true,
						false,
						6,
						7));
		assertFalse(CustomMovementHandler
				.ShouldArmPohArrivalCoordinateGuard(
						true,
						false,
						7,
						7));
		assertFalse(CustomMovementHandler
				.ShouldArmPohArrivalCoordinateGuard(
						false,
						false,
						6,
						7));
	}

	@Test
	public void sceneSnapDistanceUsesRuneScapeTileSteps()
	{
		LocalPoint Origin = new LocalPoint(1280, 2560, 0);

		assertFalse(CustomMovementHandler
				.ExceedsSceneRecoverySnapDistance(
						Origin,
						new LocalPoint(
								1280 + 2 * 128,
								2560 + 2 * 128,
								0),
						2));
		assertTrue(CustomMovementHandler
				.ExceedsSceneRecoverySnapDistance(
						Origin,
						new LocalPoint(
								1280 + 2 * 128 + 1,
								2560,
								0),
						2));
		assertTrue(CustomMovementHandler
				.ExceedsSceneRecoverySnapDistance(
						Origin,
						new LocalPoint(1280, 2560, 1),
						5));
		assertTrue(CustomMovementHandler
				.ExceedsSceneRecoverySnapDistance(
						null,
						Origin,
						5));
	}
}
