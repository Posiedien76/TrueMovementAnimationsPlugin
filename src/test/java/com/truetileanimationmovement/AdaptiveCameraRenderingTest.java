package com.truetileanimationmovement;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AdaptiveCameraRenderingTest
{
	@Test
	public void hiddenOwnerUsesAdaptiveCamera()
	{
		assertTrue(TrueTileMovementPlugin.ShouldRenderAdaptiveCamera(
				true,
				false));
	}

	@Test
	public void disabledAdaptiveCameraUsesNativeCamera()
	{
		assertFalse(TrueTileMovementPlugin.ShouldRenderAdaptiveCamera(
				false,
				false));
	}

	@Test
	public void visibleOwnerUsesNativeCamera()
	{
		assertFalse(TrueTileMovementPlugin.ShouldRenderAdaptiveCamera(
				true,
				true));
	}

	@Test
	public void sceneOrPohArrivalHandoffUsesNativeCamera()
	{
		assertFalse(TrueTileMovementPlugin.ShouldRenderAdaptiveCamera(
				true,
				false,
				true));
	}

	@Test
	public void verticalCameraHeightEasesWithoutJumpingOrOvershooting()
	{
		float EasedHeight =
				TrueTileMovementPlugin.EaseAdaptiveCameraHeight(
						-200,
						-140,
						16.667f);

		assertTrue(EasedHeight > -200);
		assertTrue(EasedHeight < -140);
	}

	@Test
	public void verticalCameraHeightUsesElapsedRenderTime()
	{
		float OneFrame =
				TrueTileMovementPlugin.EaseAdaptiveCameraHeight(
						0,
						100,
						16.667f);
		float FourFrames =
				TrueTileMovementPlugin.EaseAdaptiveCameraHeight(
						0,
						100,
						66.668f);

		assertTrue(FourFrames > OneFrame);
		assertTrue(FourFrames < 100);
	}

	@Test
	public void unavailableVerticalCameraStateStartsAtTarget()
	{
		assertEquals(
				75,
				TrueTileMovementPlugin.EaseAdaptiveCameraHeight(
						Float.NaN,
						75,
						16.667f),
				0.001f);
	}
}
