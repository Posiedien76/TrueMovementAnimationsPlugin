package com.truetileanimationmovement;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("TrueTileMovement")
public interface TrueTileMovementConfig extends Config
{

	// Developer only configs
	@ConfigItem(
			keyName = "DebugAnimation",
			name = "z (DEV_ONLY) Debug Animation",
			description = "Debug Animation ID for testing"
	)
	default int DebugAnimation()
	{
		return 0;
	}

	@ConfigItem(
			keyName = "DebugMovementSpeedModifier",
			name = "z (DEV_ONLY) Debug Movement Speed Modifier",
			description = "Debug Default Movement Speed Modifier"
	)
	default double MovementSpeedModifier()
	{
		return 3;
	}

	@ConfigItem(
			keyName = "DebugStartDelayMs",
			name = "z (DEV_ONLY) Debug Start Delay Ms",
			description = "Debug Start Delay on animations"
	)
	default int DebugStartDelayMs()
	{
		return 0;
	}

	@ConfigItem(
			keyName = "DebugEndDelayMs",
			name = "z (DEV_ONLY) Debug End Delay Ms",
			description = "Debug End Delay on animations"
	)
	default int DebugEndDelayMs()
	{
		return 0;
	}

	@ConfigItem(
			keyName = "DebugStartFrame",
			name = "z (DEV_ONLY) Debug Start Frame",
			description = "Debug Start Frame on animations"
	)
	default int DebugStartFrame()
	{
		return 0;
	}

	@ConfigItem(
			keyName = "DebugEndFrame",
			name = "z (DEV_ONLY) Debug End Frame",
			description = "Debug End Frame on animations"
	)
	default int DebugEndFrame()
	{
		return 1000;
	}

	@ConfigItem(
			keyName = "DebugAnimationSpeed",
			name = "z (DEV_ONLY) Debug Animation Speed",
			description = "Debug speed on animations"
	)
	default int DebugAnimationSpeed()
	{
		return 1;
	}

	@ConfigItem(
			keyName = "DisablePlugin",
			name = "  Disable Plugin ",
			description = "Force disable the plugin without uninstalling"
	)
	default boolean DisablePlugin()
	{
		return false;
	}

	@ConfigItem(
			keyName = "SpecialMovesOnlyInCombat",
			name = "z (DEV_ONLY) Debug Special Moves Only In Combat",
			description = "Only enable experimental special moves in combat"
	)
	default boolean SpecialMovesOnlyInCombat()
	{
		return true;
	}

	@ConfigItem(
			keyName = "OrientationRotationSpeed",
			name = "Default Orientation Rotation Speed",
			description = "Speed for rotating our character"
	)
	default int OrientationRotationSpeed()
	{
		return 30;
	}

	@ConfigItem(
			keyName = "CameraObjectOrientationRotationSpeed",
			name = "Camera Object Orientation Rotation Speed",
			description = "Speed for rotating our optional camera"
	)
	default int CameraObjectOrientationRotationSpeed()
	{
		return 20;
	}

	@ConfigItem(
			keyName = "StopEngagingInCombatTime",
			name = "Stop Engaging In Combat Time",
			description = "Amount of time to de-agro when over 4 tiles from the enemy (1 tick = 60 units)"
	)
	default int StopEngagingInCombatTime()
	{
		return 1200;
	} // 20 ticks

	@ConfigItem(
			keyName = "StopEngagingInCombatTimeFromCloseDistance",
			name = "Stop Engaging In Combat Time From Close Distance",
			description = "Amount of time to de-agro when under 4 tiles from the enemy (1 tick = 60 units)"
	)
	default int StopEngagingInCombatTimeFromCloseDistance()
	{
		return 7200;
	} // 120 ticks

	@ConfigItem(
			keyName = "SpawnModelAtCameraTile",
			name = " Spawn Camera Model",
			description = "Whether or not to spawn a camera arrow model for the original location"
	)
	default boolean SpawnModelAtCameraTile()
	{
		return false;
	}

	@ConfigItem(
			keyName = "StationaryCameraModelIndex",
			name = "Stationary Camera Model",
			description = "Index of what geometry to render the camera when stationary"
	)
	default int StationaryCameraModelIndex()
	{
		return 0; // no icon
	}

	@ConfigItem(
			keyName = "MovingCameraModelIndex",
			name = "Moving Camera Model",
			description = "Index of what geometry to render the camera when moving"
	)
	default int MovingCameraModelIndex()
	{
		return 3351; // Orb
	}

	@ConfigItem(
			keyName = "ArrowPointingAnimationSpeed",
			name = "Camera Model Animation Speed",
			description = "The speed the camera model moves back and forth"
	)
	default double ArrowPointingAnimationSpeed()
	{
		return 150;
	}

	@ConfigItem(
			keyName = "ArrowPointingAnimationStrength",
			name = "Camera Model Animation Strength",
			description = "The distance the camera model will move when oscillating"
	)
	default double ArrowPointingAnimationStrength()
	{
		return 15;
	}

	@ConfigItem(
			keyName = "CameraModelHeight",
			name = "Camera Model Height",
			description = "The height to render the camera model"
	)
	default int CameraModelHeight()
	{
		return 1;
	}

	@ConfigItem(
			keyName = "OnlyEnabledInCombat",
			name = " Disable Plugin outside Combat",
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
		return true;
	}

	@ConfigItem(
			keyName = "AllowWooxWalkDetection",
			name = " Woox Walk Detection Animation",
			description = "Whether or not to enable the 'jump' behavior when detecting a woox walk"
	)
	default boolean AllowWooxWalkDetection()
	{
		return true;
	}

	@ConfigItem(
			keyName = "TickPerfectMovesUntilJumping",
			name = " Tick Perfect Movement Animation Combo Start",
			description = "Amount of perfect moves before activating tick perfect animation jumps (turn off feature by making this value really large)"
	)
	default int TickPerfectMovesUntilJumping()
	{
		return 3;
	}

	@ConfigItem(
			keyName = "AllowSpecialMovesToTrigger",
			name = "z (DEV_ONLY) Allow Experimental Special Moves",
			description = "This will allow automatic 'special animations' to trigger. Very experimental, will look silly."
	)
	default boolean AllowSpecialMovesToTrigger()
	{
		return false;
	}

	@ConfigItem(
			keyName = "PrintRecentAnimationID",
			name = "z (DEV_ONLY) Print Most Recent Animation ID",
			description = "Print the most recent animation Id performed"
	)
	default boolean PrintRecentAnimationID()
	{
		return false;
	}

}
