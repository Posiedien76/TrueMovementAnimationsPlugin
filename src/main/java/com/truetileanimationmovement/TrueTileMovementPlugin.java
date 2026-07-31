package com.truetileanimationmovement;

import com.google.inject.Provides;
import javax.inject.Inject;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.*;
import net.runelite.api.gameval.VarClientID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.callback.RenderCallback;
import net.runelite.client.callback.RenderCallbackManager;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.input.KeyListener;
import net.runelite.client.input.KeyManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.DrawManager;
import net.runelite.client.ui.overlay.OverlayManager;

import java.awt.image.BufferedImage;
import java.util.*;
import java.util.List;

import net.runelite.api.Perspective;
import net.runelite.client.util.ImageUtil;

import static net.runelite.api.HitsplatID.*;
import static net.runelite.api.MenuAction.*;

@Slf4j
@PluginDescriptor(
	name = "True Tile Movement"
)
public class TrueTileMovementPlugin extends Plugin
{
	@Inject
	private Client client;

	@Inject
	private TrueTileMovementConfig config;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private TrueMovementOverlay OverlayRenderer;

	@Inject
	private RenderCallbackManager renderCallbackManager;

	@Inject
	private ClientThread clientThread;

	@Inject
	private DrawManager drawManager;

	private Set<Integer> CharacterIDs = new HashSet<>();
	public List<Hitsplat> CurrentHitsplats = new ArrayList<>();
	public boolean bIsPluginSupportedCurrently = true;
	public int TicksSincePluginWasSupport = 0;
	private final RenderCallback renderCallback = new RenderCallback()
	{
		@Override
		public boolean addEntity(Renderable renderable, boolean ui)
		{
			if (bForceEarlyOut || !bIsPluginSupportedCurrently || !config.CustomOverheadRendering() || client.getLocalPlayer() == null)
			{
				return true;
			}

			CustomMovementHandler FoundHandler = OverlayRenderer.MovementHandlerCache.get(client.getLocalPlayer().getId());
			if (ui && FoundHandler != null && !FoundHandler.bShouldRenderOwner && renderable != null)
			{
				if (Objects.equals(renderable.toString(), client.getLocalPlayer().toString()))
				{
					return !(renderable instanceof Player);
				}
				else
				{
					// Other player's UI
					for (Player player : client.getPlayers())
					{
						if (player != null && renderable.toString().equals(player.toString()))
						{
							String ChatOverhead = player.getOverheadText();

							// hide player UI if needed
							int PlayerID = client.getLocalPlayer().getId();
							CustomMovementHandler PlayerHandler = OverlayRenderer.MovementHandlerCache.get(PlayerID);
							if (PlayerHandler != null && PlayerHandler.Model != null && ChatOverhead == null)
							{
								LocalPoint CurrentModelPoint = PlayerHandler.Model.getLocation();
								LocalPoint TileLocation = player.getLocalLocation();
								int Tolerance = config.HideUnderPlayerDistanceTolerance();

								if (CurrentModelPoint != null &&
										TileLocation != null &&
										(Math.abs(CurrentModelPoint.getX() - TileLocation.getX()) < Tolerance) &&
										(Math.abs(CurrentModelPoint.getY() - TileLocation.getY()) < Tolerance))
								{
									return false;
								}
							}

							break;
						}
					}
				}
			}

			return true;
		}

		@Override
		public boolean drawObject(Scene scene, TileObject object)
		{
			if (bForceEarlyOut || client.getLocalPlayer() == null)
			{
				return true;
			}

			// Only supported with GPU plugin
			TicksSincePluginWasSupport = 0;
			bIsPluginSupportedCurrently = true;

			// hide player
			int ObjectID = object.getId();
			int PlayerID = client.getLocalPlayer().getId();
			CustomMovementHandler FoundHandler = OverlayRenderer.MovementHandlerCache.get(ObjectID);
			CustomMovementHandler PlayerHandler = OverlayRenderer.MovementHandlerCache.get(PlayerID);
			if (PlayerHandler != null)

			{
				if (FoundHandler != null &&
						!FoundHandler.bShouldRenderOwner && !FoundHandler.bRenderOriginalOwnerDueToProximity)
				{
					return false;
				}

				if (PlayerHandler.Model != null)
				{
					LocalPoint CurrentModelPoint = PlayerHandler.Model.getLocation();
					LocalPoint TileLocation = object.getLocalLocation();
					int Tolerance = config.HideUnderPlayerDistanceTolerance();

					if (CurrentModelPoint != null &&
							ObjectID != -1 /* -1 = Runelite object */ &&
							CharacterIDs.contains(ObjectID) &&
							(Math.abs(CurrentModelPoint.getX() - TileLocation.getX()) < Tolerance) &&
							(Math.abs(CurrentModelPoint.getY() - TileLocation.getY()) < Tolerance) &&
							!PlayerHandler.bShouldRenderOwner && !PlayerHandler.bRenderOriginalOwnerDueToProximity) {
						return false;
					}
				}
			}

			return true;
        }
	};

	public boolean bForceEarlyOut = false;

	public boolean bForceAdaptiveCameraOff = false;

	private float CurrentCameraPositionX = -1; // Offset in "sudo world space" (see adaptive camera function)
	private float CurrentCameraPositionZ = -1;
	private static final float ADAPTIVE_CAMERA_REFERENCE_FRAME_MILLISECONDS = 16.667f;
	private static final float MAX_ADAPTIVE_CAMERA_FRAME_DELTA_MILLISECONDS = 100.0f;
	private long LastAdaptiveCameraUpdateNanos = 0;
	private volatile boolean bAdaptiveCameraRenderedThisFrame = false;
	private final Runnable PostDrawCameraModeHandoff = () ->
	{
		boolean AdaptiveCameraWasRendered = bAdaptiveCameraRenderedThisFrame;
		if (AdaptiveCameraWasRendered)
		{
			bAdaptiveCameraRenderedThisFrame = false;
			client.setCameraMode(0);
		}
	};

	private static final int CAMERA_VIEWPORT_BASE_HEIGHT = 334;
	private static final int CAMERA_VIEWPORT_ZOOM_BLEND_RANGE = 100;
	private static final int CAMERA_FOLLOW_HEIGHT_BASE = 25;
	private static final int CAMERA_FOLLOW_HEIGHT_SCALE = 25;
	private static final int CAMERA_FOLLOW_HEIGHT_DIVISOR = 256;

	private WorldView currentWorldView = null;
	private int LastPrintedAnimation = 0;

	private boolean IsAdaptiveCameraOn()
	{
		return !bForceAdaptiveCameraOff && config.AdaptiveCameraOn();
	}

	private float GetAdaptiveCameraFrameDeltaMilliseconds()
	{
		long CurrentUpdateNanos = System.nanoTime();
		float FrameDeltaMilliseconds = ADAPTIVE_CAMERA_REFERENCE_FRAME_MILLISECONDS;

		if (LastAdaptiveCameraUpdateNanos != 0 && CurrentUpdateNanos > LastAdaptiveCameraUpdateNanos)
		{
			FrameDeltaMilliseconds = Math.min(
					(CurrentUpdateNanos - LastAdaptiveCameraUpdateNanos) / 1_000_000.0f,
					MAX_ADAPTIVE_CAMERA_FRAME_DELTA_MILLISECONDS);
		}

		LastAdaptiveCameraUpdateNanos = CurrentUpdateNanos;
		return FrameDeltaMilliseconds;
	}

	@Subscribe
	public void onClientTick(ClientTick event)
	{
		// Update the minimap, it doesn't update in free cam
		if (IsAdaptiveCameraOn())
		{
			client.setCameraMode(0);
		}

		if (client.getLocalPlayer() == null || client.getWorldView(-1) != client.getLocalPlayer().getWorldView())
		{
			bForceAdaptiveCameraOff = true;
		}
		else
		{
			bForceAdaptiveCameraOff = false;
		}

		// Plugin no longer supported (Need GPU plugin)
		if (TicksSincePluginWasSupport > 5)
		{
			bIsPluginSupportedCurrently = false;
		}
		else
		{
			bIsPluginSupportedCurrently = true;
		}
		++TicksSincePluginWasSupport;
	}

	private void UpdateAdaptiveCamera(
			CustomMovementHandler PlayerMovementHandler,
			float FootprintHeight,
			int CameraFollowHeight)
	{
		Player player = client.getLocalPlayer();
		WorldPoint trueWorldTile = player.getWorldLocation();
		LocalPoint trueLocalTile = LocalPoint.fromWorld(client, trueWorldTile);
		if (trueLocalTile == null)
		{
			LastAdaptiveCameraUpdateNanos = 0;
			return;
		}
		float CameraFrameDeltaMilliseconds = GetAdaptiveCameraFrameDeltaMilliseconds();

		// Store in sudo world space to prevent jumps when loading new chunks
		double CalculationOffsetVectorX = trueLocalTile.getX() - trueWorldTile.getX() * 128;
		double CalculationOffsetVectorY = trueLocalTile.getY() - trueWorldTile.getY() * 128;

		// Update our focal point Y (probably can calculate this somehow)
		if (CurrentCameraPositionX == -1 || CurrentCameraPositionZ == -1)
		{
			CurrentCameraPositionX = client.getCameraFocalPointX();
			CurrentCameraPositionZ = client.getCameraFocalPointZ();
		}
		else
		{
			CurrentCameraPositionX += (float) CalculationOffsetVectorX;
			CurrentCameraPositionZ += (float) CalculationOffsetVectorY;
		}

        LocalPoint CameraDestination = PlayerMovementHandler.Model.getLocation();
		LocalPoint CurrentCameraPositionLp = new LocalPoint((int) CurrentCameraPositionX, (int) CurrentCameraPositionZ, CameraDestination.getWorldView());
		float DistanceToTarget = CameraDestination.distanceTo(CurrentCameraPositionLp);
		float TileMaxDistanceAllowed = config.AdaptiveCameraMaxDistanceAllowed(); // Edge of circle

		// Slower the closer we are to the center
		float Velocity = (float) config.AdaptiveCameraReturnVelocity() * (DistanceToTarget / TileMaxDistanceAllowed);

		// So far, just teleport
		if (DistanceToTarget > config.AdaptiveCameraSnapDistance() * 128)
		{
			CurrentCameraPositionX = CameraDestination.getX();
			CurrentCameraPositionZ = CameraDestination.getY();
		}

		float DirectionX = CameraDestination.getX() - CurrentCameraPositionX;
		float DirectionZ = CameraDestination.getY() - CurrentCameraPositionZ;

		float DistanceX = Math.abs(DirectionX);
		float DistanceZ = Math.abs(DirectionZ);

		// Scale with the interval for this rendered camera frame. The movement handler is
		// updated later in overlay rendering, so its CurrentFrameDelta belongs to the prior frame.
		Velocity *= CameraFrameDeltaMilliseconds / ADAPTIVE_CAMERA_REFERENCE_FRAME_MILLISECONDS;

		if (DistanceToTarget != 0)
		{
			DirectionX /= DistanceToTarget;
			DirectionZ /= DistanceToTarget;

			float DistanceToMoveX = DirectionX * Velocity;
			float DistanceToMoveZ = DirectionZ * Velocity;

			if (DistanceX < Math.abs(DistanceToMoveX))
			{
				CurrentCameraPositionX = CameraDestination.getX();
			}
			else
			{
				CurrentCameraPositionX += DistanceToMoveX;
			}

			if (DistanceZ < Math.abs(DistanceToMoveZ))
			{
				CurrentCameraPositionZ = CameraDestination.getY();
			}
			else
			{
				CurrentCameraPositionZ += DistanceToMoveZ;
			}
		}

		client.setCameraMode(1);
		client.setFreeCameraSpeed(0);

		client.setCameraFocalPointX(CurrentCameraPositionX);
		client.setCameraFocalPointY(FootprintHeight - CameraFollowHeight);
		client.setCameraFocalPointZ(CurrentCameraPositionZ);
		bAdaptiveCameraRenderedThisFrame = true;

		// Store in sudo-world space to prevent jumps
		CurrentCameraPositionX -= (float) CalculationOffsetVectorX;
		CurrentCameraPositionZ -= (float) CalculationOffsetVectorY;
	}

	private static int Clamp(int value, int minimum, int maximum)
	{
		return Math.max(minimum, Math.min(maximum, value));
	}

	/**
	 * Mirrors the normal camera settings script. In particular, its final
	 * multiply and divide use integer arithmetic; the follow height is not a
	 * floating-point zoom delta.
	 */
	private int GetCameraFollowHeight()
	{
		int SmallZoom = Clamp(
				client.getVarcIntValue(VarClientID.CAMERA_ZOOM_SMALL),
				client.getVarcIntValue(VarClientID.CAMERA_ZOOM_SMALL_MIN),
				client.getVarcIntValue(VarClientID.CAMERA_ZOOM_SMALL_MAX));
		int BigZoom = Clamp(
				client.getVarcIntValue(VarClientID.CAMERA_ZOOM_BIG),
				client.getVarcIntValue(VarClientID.CAMERA_ZOOM_BIG_MIN),
				client.getVarcIntValue(VarClientID.CAMERA_ZOOM_BIG_MAX));
		int ViewportBlend = Clamp(
				client.getViewportHeight() - CAMERA_VIEWPORT_BASE_HEIGHT,
				0,
				CAMERA_VIEWPORT_ZOOM_BLEND_RANGE);
		int EffectiveZoom = SmallZoom +
				(BigZoom - SmallZoom) * ViewportBlend / CAMERA_VIEWPORT_ZOOM_BLEND_RANGE;
		return CAMERA_FOLLOW_HEIGHT_BASE +
				CAMERA_FOLLOW_HEIGHT_SCALE * EffectiveZoom / CAMERA_FOLLOW_HEIGHT_DIVISOR;
	}

	/**
	 * Matches the native client's floating-point terrain interpolation used by
	 * its camera follow calculation. The public Perspective helper returns an
	 * integer and loses the fractional terrain component on sloped tiles.
	 */
	private static float GetCameraTileHeight(
			WorldView worldView,
			float localX,
			float localY,
			int plane)
	{
		int TileX = (int) (localX / Perspective.LOCAL_TILE_SIZE);
		int TileY = (int) (localY / Perspective.LOCAL_TILE_SIZE);
		if (TileX < 0 || TileY < 0 || TileX >= worldView.getSizeX() || TileY >= worldView.getSizeY())
		{
			return 0;
		}

		int EffectivePlane = plane;
		byte[][][] TileSettings = worldView.getTileSettings();
		if (plane < 3 && (TileSettings[1][TileX][TileY] & 2) == 2)
		{
			EffectivePlane++;
		}

		int[][] TileHeights = worldView.getTileHeights()[EffectivePlane];
		float TileOffsetX = localX % Perspective.LOCAL_TILE_SIZE;
		float TileOffsetY = localY % Perspective.LOCAL_TILE_SIZE;
		float SouthHeight =
				(Perspective.LOCAL_TILE_SIZE - TileOffsetX) * TileHeights[TileX][TileY] +
						TileOffsetX * TileHeights[TileX + 1][TileY];
		SouthHeight /= Perspective.LOCAL_TILE_SIZE;
		float NorthHeight =
				(Perspective.LOCAL_TILE_SIZE - TileOffsetX) * TileHeights[TileX][TileY + 1] +
						TileOffsetX * TileHeights[TileX + 1][TileY + 1];
		NorthHeight /= Perspective.LOCAL_TILE_SIZE;

		return (TileOffsetY * NorthHeight +
				(Perspective.LOCAL_TILE_SIZE - TileOffsetY) * SouthHeight) /
				Perspective.LOCAL_TILE_SIZE;
	}

	private static float GetCameraFootprintTileHeight(
			WorldView worldView,
			LocalPoint localLocation,
			int plane,
			int footprintSize)
	{
		float LocalX = localLocation.getX();
		float LocalY = localLocation.getY();
		if (footprintSize == 0)
		{
			return GetCameraTileHeight(worldView, LocalX, LocalY, plane);
		}

		int HalfFootprint = footprintSize / 2;
		float Left = LocalX - HalfFootprint;
		float Bottom = LocalY - HalfFootprint;
		float Right = LocalX + HalfFootprint;
		float Top = LocalY + HalfFootprint;
		float MinimumHeight = Float.MAX_VALUE;

		for (float TileX = Left / Perspective.LOCAL_TILE_SIZE + 1;
		     TileX <= Right / Perspective.LOCAL_TILE_SIZE;
		     TileX++)
		{
			for (float TileY = Bottom / Perspective.LOCAL_TILE_SIZE + 1;
			     TileY <= Top / Perspective.LOCAL_TILE_SIZE;
			     TileY++)
			{
				MinimumHeight = Math.min(
						MinimumHeight,
						GetCameraTileHeight(
								worldView,
								TileX * Perspective.LOCAL_TILE_SIZE,
								TileY * Perspective.LOCAL_TILE_SIZE,
								plane));
			}
		}

		MinimumHeight = Math.min(MinimumHeight, GetCameraTileHeight(worldView, LocalX, LocalY, plane));
		MinimumHeight = Math.min(MinimumHeight, GetCameraTileHeight(worldView, Left, Bottom, plane));
		MinimumHeight = Math.min(MinimumHeight, GetCameraTileHeight(worldView, Left, Top, plane));
		MinimumHeight = Math.min(MinimumHeight, GetCameraTileHeight(worldView, Right, Bottom, plane));
		MinimumHeight = Math.min(MinimumHeight, GetCameraTileHeight(worldView, Right, Top, plane));
		return MinimumHeight;
	}

	static boolean ShouldRenderAdaptiveCamera(
			boolean AdaptiveCameraOn,
			boolean ShouldRenderOwner)
	{
		return AdaptiveCameraOn && !ShouldRenderOwner;
	}
	@Subscribe
	public void onBeforeRender(BeforeRender beforeRender)
	{
		bAdaptiveCameraRenderedThisFrame = false;
		if (bForceEarlyOut || !bIsPluginSupportedCurrently || client.getLocalPlayer() == null)
		{
			LastAdaptiveCameraUpdateNanos = 0;
			return;
		}

		CharacterIDs.clear();
		for (Player player : client.getPlayers())
		{
			if (player != null)
			{
				CharacterIDs.add(player.getId());
			}
		}
		for (NPC npc : client.getNpcs())
		{
			if (npc != null && npc.getComposition().getSize() == 1)
			{
				CharacterIDs.add(npc.getId());
			}
		}

		Player player = client.getLocalPlayer();
		CustomMovementHandler PlayerMovementHandler = OverlayRenderer.MovementHandlerCache.get(player.getId());
		if (PlayerMovementHandler == null)
		{
			LastAdaptiveCameraUpdateNanos = 0;
			return;
		}
		LocalPoint CameraHeightLocation = PlayerMovementHandler.Model == null
				? null
				: PlayerMovementHandler.Model.getLocation();
		if (CameraHeightLocation == null)
		{
			LastAdaptiveCameraUpdateNanos = 0;
			return;
		}

		float FootprintHeight = GetCameraFootprintTileHeight(
				player.getWorldView(),
				CameraHeightLocation,
				player.getWorldView().getPlane(),
				player.getFootprintSize());
		if (player.getAnimation() != -1)
		{
			FootprintHeight -= player.getAnimationHeightOffset();
		}
		else
		{
			FootprintHeight -= PlayerMovementHandler.OldAnimationHeight;
		}

		int CameraFollowHeight = GetCameraFollowHeight();

		if (ShouldRenderAdaptiveCamera(
				IsAdaptiveCameraOn(),
				PlayerMovementHandler.bShouldRenderOwner))
		{
			UpdateAdaptiveCamera(PlayerMovementHandler, FootprintHeight, CameraFollowHeight);
		}
		else
		{
			LastAdaptiveCameraUpdateNanos = 0;
			if (client.getCameraMode() == 0)
			{
				// Store in sudo world space
				WorldPoint trueWorldTile = client.getLocalPlayer().getWorldLocation();
				LocalPoint trueLocalTile = LocalPoint.fromWorld(client, trueWorldTile);
				if (trueLocalTile == null)
				{
					return;
				}
				double CalculationOffsetVectorX = trueLocalTile.getX() - trueWorldTile.getX() * 128;
				double CalculationOffsetVectorY = trueLocalTile.getY() - trueWorldTile.getY() * 128;

				CurrentCameraPositionX = (float) (client.getCameraFocalPointX() - CalculationOffsetVectorX);
				CurrentCameraPositionZ = (float) (client.getCameraFocalPointZ() - CalculationOffsetVectorY);
			}
			client.setCameraMode(0);
		}
	}
	private long LastTimeHitSplatApplied = 0;
	@Subscribe
	public void onHitsplatApplied(HitsplatApplied event)
	{
		if (event.getActor() == client.getLocalPlayer())
		{
			LastTimeHitSplatApplied = System.currentTimeMillis();
			if (!CurrentHitsplats.contains(event.getHitsplat()))
			{
				CurrentHitsplats.add(event.getHitsplat());
			}
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (bForceEarlyOut || !bIsPluginSupportedCurrently)
		{
			CurrentCameraPositionX = -1;
			CurrentCameraPositionZ = -1;
			client.setCameraMode(0);
			return;
		}

		// Manage our current hitsplats
        CurrentHitsplats.removeIf(hitsplat -> client.getGameCycle() >= hitsplat.getDisappearsOnGameCycle());

		// Recently been in combat
		if (System.currentTimeMillis() - LastTimeHitSplatApplied < 6000) // 6 seconds
		{
			// Show this one
			OverlayRenderer.bShowHPBar = true;
		}
		else
		{
			OverlayRenderer.bShowHPBar = false;
		}

		// Teleports
		if (client.getLocalPlayer().getAnimation() == 714 ||
				client.getLocalPlayer().getAnimation() == 878 ||
				client.getLocalPlayer().getAnimation() == 1816 ||
				client.getLocalPlayer().getAnimation() == 1979 ||
				client.getLocalPlayer().getAnimation() == 3872 ||
				client.getLocalPlayer().getAnimation() == 13811 ||
				client.getLocalPlayer().getAnimation() == 4069 ||
				client.getLocalPlayer().getAnimation() == 4071 ||
				client.getLocalPlayer().getAnimation() == 3869 ||
				client.getLocalPlayer().getAnimation() == 3865 ||
				client.getLocalPlayer().getAnimation() == 2881
		)
		{
			OverlayRenderer.LastTimeTeleport = System.currentTimeMillis();
			OverlayRenderer.bShouldPlayTeleportAnimation = true;
			OverlayRenderer.bTeleportInterrupted = false;
		}

		// Print recent animation for convenience
		if (config.PrintCurrentAnimationIDsToChat() && LastPrintedAnimation != client.getLocalPlayer().getAnimation())
		{
			LastPrintedAnimation = client.getLocalPlayer().getAnimation();
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "Current Animation ID " + client.getLocalPlayer().getAnimation(), null);
		}

		Player player = client.getLocalPlayer();
		if (player == null)
		{
			return;
		}

		WorldView newWorldView = player.getWorldView();
		if (newWorldView != currentWorldView)
		{
			WorldView old = currentWorldView;
			currentWorldView = newWorldView;
			OverlayRenderer.bEverythingIsStale = true;
		}
	}

	public Map<HeadIcon, BufferedImage> prayerImages;
	public Map<Integer, BufferedImage> skullImages;
	public Map<Integer, BufferedImage> hitsplatImages;
	public void InitializePrayerImages()
	{
		prayerImages = Map.ofEntries(
				Map.entry(HeadIcon.MAGIC, ImageUtil.loadImageResource(getClass(), "/Magic.png")),
				Map.entry(HeadIcon.MELEE, ImageUtil.loadImageResource(getClass(), "/Melee.png")),
				Map.entry(HeadIcon.RANGED, ImageUtil.loadImageResource(getClass(), "/Ranged.png")),
				Map.entry(HeadIcon.SMITE, ImageUtil.loadImageResource(getClass(), "/Smite.png")),
				Map.entry(HeadIcon.RETRIBUTION, ImageUtil.loadImageResource(getClass(), "/Retribution.png")),
				Map.entry(HeadIcon.REDEMPTION, ImageUtil.loadImageResource(getClass(), "/Redemption.png"))
		);
	}

	public void InitializeSkullImages()
	{
		skullImages = Map.ofEntries(
				Map.entry(SkullIcon.SKULL, ImageUtil.loadImageResource(getClass(), "/skulls/Skull.png")),
				Map.entry(SkullIcon.SKULL_HIGH_RISK, ImageUtil.loadImageResource(getClass(), "/skulls/SkullHighRisk.png")),
				Map.entry(SkullIcon.SKULL_FIGHT_PIT, ImageUtil.loadImageResource(getClass(), "/skulls/SkullFightPits.png")),
				Map.entry(SkullIcon.SKULL_DEADMAN, ImageUtil.loadImageResource(getClass(), "/skulls/SkullDeadman.png")),
				Map.entry(SkullIcon.LOOT_KEYS_ONE, ImageUtil.loadImageResource(getClass(), "/skulls/SkullLootKey1.png")),
				Map.entry(SkullIcon.LOOT_KEYS_TWO, ImageUtil.loadImageResource(getClass(), "/skulls/SkullLootKey2.png")),
				Map.entry(SkullIcon.LOOT_KEYS_THREE, ImageUtil.loadImageResource(getClass(), "/skulls/SkullLootKey3.png")),
				Map.entry(SkullIcon.LOOT_KEYS_FOUR, ImageUtil.loadImageResource(getClass(), "/skulls/SkullLootKey4.png")),
				Map.entry(SkullIcon.LOOT_KEYS_FIVE, ImageUtil.loadImageResource(getClass(), "/skulls/SkullLootKey5.png")),
				Map.entry(SkullIcon.FORINTHRY_SURGE, ImageUtil.loadImageResource(getClass(), "/skulls/SkullForinthrySurge.png")),
				Map.entry(SkullIcon.FORINTHRY_SURGE_DEADMAN, ImageUtil.loadImageResource(getClass(), "/skulls/SkullForinthrySurgeDeadman.png")),
				Map.entry(SkullIcon.FORINTHRY_SURGE_KEYS_ONE, ImageUtil.loadImageResource(getClass(), "/skulls/SkullForinthrySurgeDeadmanKey1.png")),
				Map.entry(SkullIcon.FORINTHRY_SURGE_KEYS_TWO, ImageUtil.loadImageResource(getClass(), "/skulls/SkullForinthrySurgeDeadmanKey2.png")),
				Map.entry(SkullIcon.FORINTHRY_SURGE_KEYS_THREE, ImageUtil.loadImageResource(getClass(), "/skulls/SkullForinthrySurgeDeadmanKey3.png")),
				Map.entry(SkullIcon.FORINTHRY_SURGE_KEYS_FOUR, ImageUtil.loadImageResource(getClass(), "/skulls/SkullForinthrySurgeDeadmanKey4.png")),
				Map.entry(SkullIcon.FORINTHRY_SURGE_KEYS_FIVE, ImageUtil.loadImageResource(getClass(), "/skulls/SkullForinthrySurgeDeadmanKey5.png"))
		);
	}

	public void InitializeHitsplatImages()
	{
		// Use same for me/other because we only handle ourself anyway
		hitsplatImages = Map.ofEntries(
				Map.entry(DAMAGE_ME, ImageUtil.loadImageResource(getClass(), "/hitsplats/DamageMe.png")),
				Map.entry(DAMAGE_OTHER, ImageUtil.loadImageResource(getClass(), "/hitsplats/DamageMe.png")),
				Map.entry(BLOCK_ME, ImageUtil.loadImageResource(getClass(), "/hitsplats/BlockMe.png")),
				Map.entry(BLOCK_OTHER, ImageUtil.loadImageResource(getClass(), "/hitsplats/BlockMe.png")),
				Map.entry(POISON, ImageUtil.loadImageResource(getClass(), "/hitsplats/Poison.png")),
				Map.entry(VENOM, ImageUtil.loadImageResource(getClass(), "/hitsplats/Venom.png")),
				Map.entry(DISEASE, ImageUtil.loadImageResource(getClass(), "/hitsplats/Disease.png")),
				Map.entry(BLEED, ImageUtil.loadImageResource(getClass(), "/hitsplats/Bleed.png")),
				Map.entry(CORRUPTION, ImageUtil.loadImageResource(getClass(), "/hitsplats/Corruption.png")),
				Map.entry(BURN, ImageUtil.loadImageResource(getClass(), "/hitsplats/Burn.png")),
				Map.entry(SANITY_DRAIN, ImageUtil.loadImageResource(getClass(), "/hitsplats/SanityDrain.png")),
				Map.entry(HEAL, ImageUtil.loadImageResource(getClass(), "/hitsplats/Heal.png")),
				Map.entry(DOOM, ImageUtil.loadImageResource(getClass(), "/hitsplats/Doom.png")),
				Map.entry(SANITY_RESTORE, ImageUtil.loadImageResource(getClass(), "/hitsplats/SanityRestore.png")),
				Map.entry(DISEASE_BLOCKED, ImageUtil.loadImageResource(getClass(), "/hitsplats/Disease.png")),
				Map.entry(PRAYER_DRAIN, ImageUtil.loadImageResource(getClass(), "/hitsplats/PrayerDrain.png")),


				// TODO: The rest of these images, use default hitsplat for now
				Map.entry(DAMAGE_MAX_ME, ImageUtil.loadImageResource(getClass(), "/hitsplats/DamageMe.png")),
				Map.entry(DAMAGE_MAX_ME_CYAN, ImageUtil.loadImageResource(getClass(), "/hitsplats/DamageMe.png")),
				Map.entry(DAMAGE_MAX_ME_ORANGE, ImageUtil.loadImageResource(getClass(), "/hitsplats/DamageMe.png")),
				Map.entry(DAMAGE_MAX_ME_YELLOW, ImageUtil.loadImageResource(getClass(), "/hitsplats/DamageMe.png")),
				Map.entry(DAMAGE_MAX_ME_WHITE, ImageUtil.loadImageResource(getClass(), "/hitsplats/DamageMe.png")),
				Map.entry(CYAN_UP, ImageUtil.loadImageResource(getClass(), "/hitsplats/DamageMe.png")),
				Map.entry(CYAN_DOWN, ImageUtil.loadImageResource(getClass(), "/hitsplats/DamageMe.png")),
				Map.entry(DAMAGE_ME_CYAN, ImageUtil.loadImageResource(getClass(), "/hitsplats/DamageMe.png")),
				Map.entry(DAMAGE_OTHER_CYAN, ImageUtil.loadImageResource(getClass(), "/hitsplats/DamageMe.png")),
				Map.entry(DAMAGE_ME_ORANGE, ImageUtil.loadImageResource(getClass(), "/hitsplats/DamageMe.png")),
				Map.entry(DAMAGE_OTHER_ORANGE, ImageUtil.loadImageResource(getClass(), "/hitsplats/DamageMe.png")),
				Map.entry(DAMAGE_ME_YELLOW, ImageUtil.loadImageResource(getClass(), "/hitsplats/DamageMe.png")),
				Map.entry(DAMAGE_OTHER_YELLOW, ImageUtil.loadImageResource(getClass(), "/hitsplats/DamageMe.png")),
				Map.entry(DAMAGE_ME_WHITE, ImageUtil.loadImageResource(getClass(), "/hitsplats/DamageMe.png")),
				Map.entry(DAMAGE_OTHER_WHITE, ImageUtil.loadImageResource(getClass(), "/hitsplats/DamageMe.png")),
				Map.entry(DAMAGE_ME_POISE, ImageUtil.loadImageResource(getClass(), "/hitsplats/DamageMe.png")),
				Map.entry(DAMAGE_OTHER_POISE, ImageUtil.loadImageResource(getClass(), "/hitsplats/DamageMe.png")),
				Map.entry(DAMAGE_MAX_ME_POISE, ImageUtil.loadImageResource(getClass(), "/hitsplats/DamageMe.png"))
		);
	}



	@Override
	protected void startUp() throws Exception
	{
		InitializePrayerImages();
		InitializeSkullImages();
		InitializeHitsplatImages();

		renderCallbackManager.register(renderCallback);
		drawManager.registerEveryFrameListener(PostDrawCameraModeHandoff);
		overlayManager.add(OverlayRenderer);
		bForceEarlyOut = false;
		CurrentCameraPositionX = -1;
		CurrentCameraPositionZ = -1;
		LastAdaptiveCameraUpdateNanos = 0;
		bAdaptiveCameraRenderedThisFrame = false;
	}

	public BufferedImage GetPrayerIcon(HeadIcon currentHeadIcon)
	{
		return prayerImages.get(currentHeadIcon);
	}

	public BufferedImage GetSkullIcon(int skullIcon)
	{
		return skullImages.get(skullIcon);
	}

	@Override
	protected void shutDown() throws Exception
	{
		CurrentCameraPositionX = -1;
		CurrentCameraPositionZ = -1;
		LastAdaptiveCameraUpdateNanos = 0;
		bAdaptiveCameraRenderedThisFrame = false;

		clientThread.invoke(() ->
		{
			OverlayRenderer.Cleanup();
			renderCallbackManager.unregister(renderCallback);
			drawManager.unregisterEveryFrameListener(PostDrawCameraModeHandoff);
			overlayManager.remove(OverlayRenderer);
			bForceEarlyOut = true;
			client.setCameraMode(0);
		});
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		if (bForceEarlyOut || !bIsPluginSupportedCurrently)
		{
			return;
		}

		// TODO make less manual
		if (event.getMenuOption().equals("Walk here") ||
				event.getMenuOption().equals("Attack") ||
				event.getMenuOption().equals("Jump") ||
				event.getMenuOption().equals("Talk to") ||
				event.getMenuOption().equals("Pickpocket"))
		{
			OverlayRenderer.bRecentlyClickedEvent = true;
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged gameStateChanged)
	{
		if (bForceEarlyOut || !bIsPluginSupportedCurrently)
		{
			return;
		}

		// Runelite objects are stale
		if (gameStateChanged.getGameState() == GameState.LOADING ||
				gameStateChanged.getGameState() == GameState.CONNECTION_LOST ||
				gameStateChanged.getGameState() == GameState.HOPPING)
		{
			OverlayRenderer.bRuneliteObjectsStale = true;
		}
	}

	@Provides
	TrueTileMovementConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(TrueTileMovementConfig.class);
	}

}
