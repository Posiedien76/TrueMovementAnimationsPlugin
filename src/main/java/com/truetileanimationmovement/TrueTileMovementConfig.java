package com.truetileanimationmovement;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("TrueTileMovement")
public interface TrueTileMovementConfig extends Config
{
	@ConfigItem(
			keyName = "OrientationRotationSpeed",
			name = "Orientation Rotation Speed",
			description = "Speed for rotating our character",
			hidden = true // Broken currently in some cases
	)
	default int OrientationRotationSpeed()
	{
		return 30;
	}

	// If the option is not just "walk here", swap to the old camera system for just a few frames or while the right click menu is open.
	// The plugin's camera is so close to the original camera view that the clickboxes are close enough.
	// The user loses some accuracy, but it allows the feature to be possible.
	@ConfigItem(
			keyName = "AdaptiveCameraOn",
			name = "  Adaptive Camera",
			description = "Adaptive Camera mode"
	)
	default boolean AdaptiveCameraOn()
	{
		return true;
	}

	@ConfigItem(
			keyName = "CustomOverheadRendering",
			name = "  Custom Overhead Rendering",
			description = "Whether or not for the plugin to handle the overhead, HP bar, and hitsplat rendering"
	)
	default boolean CustomOverheadRendering()
	{
		return true;
	}

	@ConfigItem(
			keyName = "OverheadObjectOffset",
			name = "Overhead Object Height Offset",
			description = "Overhead Object Height Render Offset",
			hidden = true
	)
	default int OverheadObjectOffset()
	{
		return 7;
	}

	@ConfigItem(
			keyName = "OverheadTextOffset",
			name = "Overhead Text Offset",
			description = "Overhead Text Offset",
			hidden = true
	)
	default int OverheadTextOffset()
	{
		return 9;
	}

	@ConfigItem(
			keyName = "OverheadHPBarOffset",
			name = "Overhead HP Bar Offset",
			description = "Overhead HP Bar Offset",
			hidden = true
	)
	default int OverheadHPBarOffset()
	{
		return 10;
	}

	@ConfigItem(
			keyName = "MultipleHitsplatOffset",
			name = "Overhead Multiple Hitsplat Offset",
			description = "Overhead Multiple Hitsplat Offset",
			hidden = true
	)
	default int MultipleHitsplatOffset()
	{
		return 25;
	}

	@ConfigItem(
			keyName = "AdaptiveCameraMaxDistanceAllowed",
			name = " Adaptive Camera Following Distance",
			description = "Ideal distance from the player for the camera to follow (1 tile = 128)"
	)
	default int AdaptiveCameraMaxDistanceAllowed()
	{
		return 64; // Half a tile
	}

	@ConfigItem(
			keyName = "AdaptiveCameraReturnVelocity",
			name = " Adaptive Camera Return Velocity (Velocity to follow the player at)",
			description = "Velocity the camera is allowed to return back to the player"
	)
	default double AdaptiveCameraReturnVelocity()
	{
		return 4;
	}

	@ConfigItem(
			keyName = "AdaptiveCameraSnapDistance",
			name = " Adaptive Camera Snap Distance",
			description = "Distance in tiles to just snap to camera to target"
	)
	default double AdaptiveCameraSnapDistance()
	{
		return 20;
	}

	@ConfigItem(
			keyName = "CameraObjectOrientationRotationSpeed",
			name = "Camera Object Orientation Rotation Speed",
			description = "Speed for rotating our optional camera",
			hidden = true // Camera model stuff isn't that great right now
	)
	default int CameraObjectOrientationRotationSpeed()
	{
		return 20;
	}

	@ConfigItem(
			keyName = "StopEngagingInCombatTime",
			name = "Stop Engaging In Combat Time",
			description = "Amount of time to de-agro when over 4 tiles from the enemy (1 tick = 60 units);\""
	)
	default int StopEngagingInCombatTime()
	{
		return 1200;
	} // 20 ticks

	@ConfigItem(
			keyName = "StopEngagingInCombatTimeFromCloseDistance",
			name = "Stop Engaging In Combat Time From Close Distance",
			description = "Amount of time to de-agro when under 4 tiles from the enemy (1 tick = 60 units);"
	)
	default int StopEngagingInCombatTimeFromCloseDistance()
	{
		return 7200;
	} // 120 ticks

	@ConfigItem(
			keyName = "SpawnModelAtCameraTile",
			name = " Spawn Camera Model at Original Location",
			description = "Whether or not to spawn a camera model for the original location",
			hidden = true // Camera model stuff isn't that great right now
	)
	default boolean SpawnModelAtCameraTile()
	{
		return false;
	}

	@ConfigItem(
			keyName = "CombatModeEnabled",
			name = "  Enhanced Combat Mode (fun)",
			description = "Whether or not to allow the plugin's combat mode feature."
	)
	default boolean CombatModeEnabled()
	{
		return false;
	}

	@ConfigItem(
			keyName = "AlwaysHoppingMode",
			name = "Always Hopping Mode (fun)",
			description = "Hop Hop Hop."
	)
	default boolean AlwaysHoppingMode()
	{
		return false;
	}

	@ConfigItem(
			keyName = "AllowOriginalModelWhenCloseProximity",
			name = "Original Model When Close",
			description = "Whether or not to allow the original model to be used directly when its close in proximity, orientation, and animation."
	)
	default boolean AllowOriginalModelWhenCloseProximity()
	{
		return true;
	}

	@ConfigItem(
			keyName = "OriginalModelProximityDistanceThreshold",
			name = "Original Model Proximity Distance Threshold",
			description = "Original Model Proximity Distance Threshold (128 units = 1 tile)"
	)
	default int OriginalModelProximityDistanceThreshold()
	{
		return 1;
	}

	@ConfigItem(
			keyName = "OriginalModelProximityOrientationThreshold",
			name = "Original Model Proximity Orientation Threshold",
			description = "Original Model Proximity Orientation Threshold (2047 = full rotation)"
	)
	default int OriginalModelProximityOrientationThreshold()
	{
		return 10;
	}


	@ConfigItem(
			keyName = "StationaryCameraModelIndex",
			name = "Stationary Camera Model",
			description = "Index of what geometry to render the camera when stationary",
			hidden = true // Camera model stuff isn't that great right now
	)
	default int StationaryCameraModelIndex()
	{
		return 0; // no icon
	}

	@ConfigItem(
			keyName = "MovingCameraModelIndex",
			name = "Moving Camera Model",
			description = "Index of what geometry to render the camera when moving",
			hidden = true // Camera model stuff isn't that great right now
	)
	default int MovingCameraModelIndex()
	{
		return 3351; // Orb
	}

	@ConfigItem(
			keyName = "ArrowPointingAnimationSpeed",
			name = "Camera Model Animation Speed",
			description = "The speed the camera model moves back and forth",
			hidden = true // Camera model stuff isn't that great right now
	)
	default double ArrowPointingAnimationSpeed()
	{
		return 150;
	}

	@ConfigItem(
			keyName = "ArrowPointingAnimationStrength",
			name = "Camera Model Animation Strength",
			description = "The distance the camera model will move when oscillating",
			hidden = true // Camera model stuff isn't that great right now
	)
	default double ArrowPointingAnimationStrength()
	{
		return 15;
	}

	@ConfigItem(
			keyName = "CameraModelHeight",
			name = "Camera Model Height",
			description = "The height to render the camera model",
			hidden = true // Camera model stuff isn't that great right now
	)
	default int CameraModelHeight()
	{
		return 1;
	}

	@ConfigItem(
			keyName = "OnlyEnabledInCombat",
			name = "  Disable Plugin outside Combat",
			description = "Whether or not to only enable the plugin movement in combat"
	)
	default boolean OnlyEnabledInCombat()
	{
		return false;
	}

	@ConfigItem(
			keyName = "AllowNPCKilledCelebrationEmote",
			name = " Enemy Killed Celebration",
			description = "Whether or not to enable the 'automatic celebration' emote on enemy kill"
	)
	default boolean AllowNPCKilledCelebrationEmote()
	{
		return false;
	}

	@ConfigItem(
			keyName = "AllowWooxWalkDetection",
			name = " Woox Walk Detection Animation",
			description = "Whether or not to enable the 'jump' behavior when detecting a woox walk"
	)
	default boolean AllowWooxWalkDetection()
	{
		return false;
	}

	@ConfigItem(
			keyName = "TickPerfectMovesUntilJumping",
			name = " Tick Perfect Movement Animation Combo Start (Disabled by default, change to a value like 3 to use)",
			description = "Amount of perfect moves before activating tick perfect animation jumps (turn off feature by making this value really large)"
	)
	default int TickPerfectMovesUntilJumping()
	{
		return 1000;
	}


}
