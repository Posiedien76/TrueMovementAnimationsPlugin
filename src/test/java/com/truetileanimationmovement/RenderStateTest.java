package com.truetileanimationmovement;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RenderStateTest
{
	@Test
	public void onlyMatchingLocalPlayerTileObjectIsSuppressed()
	{
		int LocalPlayerId = 42;
		int LocalWorldViewId = 7;
		long PlayerHash = (long) LocalWorldViewId << 52;
		long OtherWorldViewPlayerHash =
				(long) (LocalWorldViewId + 1) << 52;
		long GameObjectHash = PlayerHash | 2L << 16;

		assertFalse(TrueTileMovementPlugin.ShouldDrawTileObject(
				true,
				LocalPlayerId,
				LocalWorldViewId,
				PlayerHash,
				LocalPlayerId));
		assertTrue(TrueTileMovementPlugin.ShouldDrawTileObject(
				true,
				LocalPlayerId,
				LocalWorldViewId,
				PlayerHash,
				LocalPlayerId + 1));
		assertTrue(TrueTileMovementPlugin.ShouldDrawTileObject(
				true,
				LocalPlayerId,
				LocalWorldViewId,
				GameObjectHash,
				LocalPlayerId));
		assertTrue(TrueTileMovementPlugin.ShouldDrawTileObject(
				true,
				LocalPlayerId,
				LocalWorldViewId,
				OtherWorldViewPlayerHash,
				LocalPlayerId));
		assertTrue(TrueTileMovementPlugin.ShouldDrawTileObject(
				false,
				LocalPlayerId,
				LocalWorldViewId,
				PlayerHash,
				LocalPlayerId));
	}
}
