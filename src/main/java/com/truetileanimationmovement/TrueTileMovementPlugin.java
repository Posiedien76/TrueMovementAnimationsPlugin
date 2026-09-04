package com.truetileanimationmovement;

import com.google.inject.Provides;
import javax.inject.Inject;

import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.*;
import net.runelite.api.gameval.AnimationID;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarClientID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.callback.RenderCallback;
import net.runelite.client.callback.RenderCallbackManager;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.input.KeyListener;
import net.runelite.client.input.KeyManager;
import net.runelite.client.input.MouseAdapter;
import net.runelite.client.input.MouseManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.DrawManager;
import net.runelite.client.ui.overlay.OverlayManager;

import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.List;

import net.runelite.api.Perspective;
import net.runelite.client.util.ImageUtil;

import static net.runelite.api.HitsplatID.*;
import static net.runelite.api.MenuAction.*;

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
	@Inject
	private MouseManager mouseManager;

	// [TMA-STOP-FACING] These are the world actions which produce a red
	// interaction click. They deliberately exclude widgets and inventory
	// actions: only a new world interaction should cancel a yellow-click
	// stop-facing hold.
	private static final Set<MenuAction> RED_WORLD_INTERACTION_ACTIONS = EnumSet.of(
			ITEM_USE_ON_GAME_OBJECT,
			WIDGET_TARGET_ON_GAME_OBJECT,
			GAME_OBJECT_FIRST_OPTION,
			GAME_OBJECT_SECOND_OPTION,
			GAME_OBJECT_THIRD_OPTION,
			GAME_OBJECT_FOURTH_OPTION,
			GAME_OBJECT_FIFTH_OPTION,
			ITEM_USE_ON_NPC,
			WIDGET_TARGET_ON_NPC,
			NPC_FIRST_OPTION,
			NPC_SECOND_OPTION,
			NPC_THIRD_OPTION,
			NPC_FOURTH_OPTION,
			NPC_FIFTH_OPTION,
			ITEM_USE_ON_PLAYER,
			WIDGET_TARGET_ON_PLAYER,
			PLAYER_FIRST_OPTION,
			PLAYER_SECOND_OPTION,
			PLAYER_THIRD_OPTION,
			PLAYER_FOURTH_OPTION,
			PLAYER_FIFTH_OPTION,
			PLAYER_SIXTH_OPTION,
			PLAYER_SEVENTH_OPTION,
			PLAYER_EIGHTH_OPTION,
			ITEM_USE_ON_GROUND_ITEM,
			WIDGET_TARGET_ON_GROUND_ITEM,
			GROUND_ITEM_FIRST_OPTION,
			GROUND_ITEM_SECOND_OPTION,
			GROUND_ITEM_THIRD_OPTION,
			GROUND_ITEM_FOURTH_OPTION,
			GROUND_ITEM_FIFTH_OPTION,
			WORLD_ENTITY_FIRST_OPTION,
			WORLD_ENTITY_SECOND_OPTION,
			WORLD_ENTITY_THIRD_OPTION,
			WORLD_ENTITY_FOURTH_OPTION,
			WORLD_ENTITY_FIFTH_OPTION);

	public boolean bDelayedStartup = false;
	private boolean bStartupComplete = false;

	private Set<Integer> CharacterIDs = new HashSet<>();
	public List<Hitsplat> CurrentHitsplats = new ArrayList<>();
	public volatile boolean bIsPluginSupportedCurrently = true;
	public volatile int TicksSincePluginWasSupport = 0;
	// [TMA-STEADY-PRESENTATION] Render callbacks can pause briefly during an
	// ordinary long frame. The old six-client-tick threshold treated a
	// roughly 100 ms hitch as GPU removal and tore down the model/controller.
	// One second still detects an unsupported renderer promptly without
	// converting a recoverable frame hitch into a visible animation restart.
	private static final int GPU_CALLBACK_GRACE_CLIENT_TICKS = 50;
	// [TMA-PLAYER-ONLY-RENDER-FILTER] drawObject can run on RuneLite's
	// map-loader thread while the client thread owns all CustomMovementHandler
	// and RuneLiteObject state. Publish the small decision the callback needs
	// instead of reading live client/custom-object state from that callback.
	private static final int TILE_OBJECT_TYPE_PLAYER = 0;
	private volatile Player hiddenLocalPlayer = null;
	private volatile int hiddenLocalPlayerId = -1;
	private volatile int hiddenLocalPlayerWorldViewId = -1;
	private volatile boolean hideLocalPlayerScene = false;
	private volatile boolean hideLocalPlayerUi = false;
	private final RenderCallback renderCallback = new RenderCallback()
	{
		@Override
		public boolean addEntity(Renderable renderable, boolean ui)
		{
			if (hideLocalPlayerUi &&
					ui &&
					renderable == hiddenLocalPlayer)
			{
				return false;
			}

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

			long ObjectHash = object.getHash();
			// Unlike addEntity, drawObject is supplied by the GPU renderer. A
			// player entry occurs every rendered player frame, so use only that
			// entry for the heartbeat and keep even scalar writes off the static
			// map-loader upload path.
			if (GetTileObjectType(ObjectHash) == TILE_OBJECT_TYPE_PLAYER)
			{
				MarkGpuRenderCallbackObserved();
			}

			// TileObject IDs are object/player IDs from different namespaces.
			// A hash-qualified published snapshot ensures an ordinary object can
			// never collide with the local player's index and be suppressed.
			if (!ShouldDrawTileObject(
					hideLocalPlayerScene,
					hiddenLocalPlayerId,
					hiddenLocalPlayerWorldViewId,
					ObjectHash,
					object.getId()))
			{
				return false;
			}

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

	private void MarkGpuRenderCallbackObserved()
	{
		TicksSincePluginWasSupport = 0;
		bIsPluginSupportedCurrently = true;
	}

	static boolean ShouldDrawTileObject(
			boolean HideLocalPlayer,
			int HiddenLocalPlayerId,
			int HiddenLocalPlayerWorldViewId,
			long ObjectHash,
			int ObjectId)
	{
		int ObjectType = GetTileObjectType(ObjectHash);
		int ObjectWorldViewId = (int) ((ObjectHash >>> 52) & 4095L);
		return !HideLocalPlayer ||
				ObjectType != TILE_OBJECT_TYPE_PLAYER ||
				ObjectId != HiddenLocalPlayerId ||
				ObjectWorldViewId != HiddenLocalPlayerWorldViewId;
	}

	private static int GetTileObjectType(long ObjectHash)
	{
		return (int) ((ObjectHash >>> 16) & 7L);
	}

	private void PublishLocalPlayerRenderState(
			Player Player,
			boolean HideLocalPlayer)
	{
		WorldView PlayerWorldView = Player == null
				? null
				: Player.getWorldView();
		if (!HideLocalPlayer || PlayerWorldView == null)
		{
			// Disable both consumers before clearing the associated identity.
			// A callback racing this client-thread publication therefore fails
			// open and lets RuneLite draw its native content.
			hideLocalPlayerScene = false;
			hideLocalPlayerUi = false;
			hiddenLocalPlayer = null;
			hiddenLocalPlayerId = -1;
			hiddenLocalPlayerWorldViewId = -1;
			return;
		}

		// Publish identity first and the enable flags last. Volatile ordering
		// guarantees callbacks which observe a true flag also observe the
		// matching player/world-view snapshot.
		hiddenLocalPlayer = Player;
		hiddenLocalPlayerId = Player.getId();
		hiddenLocalPlayerWorldViewId = PlayerWorldView.getId();
		hideLocalPlayerUi = config.CustomOverheadRendering();
		hideLocalPlayerScene = true;
	}

	public boolean bForceEarlyOut = false;

	public boolean bForceAdaptiveCameraOff = false;

	private float CurrentCameraPositionX = -1; // Offset in "sudo world space" (see adaptive camera function)
	private float CurrentCameraPositionY = Float.NaN;
	private float CurrentCameraPositionZ = -1;
	private static final float ADAPTIVE_CAMERA_REFERENCE_FRAME_MILLISECONDS = 16.667f;
	private static final float MAX_ADAPTIVE_CAMERA_FRAME_DELTA_MILLISECONDS = 100.0f;
	private static final float ADAPTIVE_CAMERA_VERTICAL_HALF_LIFE_MILLISECONDS = 80.0f;
	private long LastAdaptiveCameraUpdateNanos = 0;
	// Keep free-camera mode confined to the adaptive frame: native input and menu
	// processing run after presentation, while the normal camera is never rendered.
	private volatile boolean bAdaptiveCameraRenderedThisFrame = false;
	private volatile Point PendingPrimaryMousePress = null;
	private final MouseAdapter MinimapClickListener = new MouseAdapter()
	{
		@Override
		public MouseEvent mousePressed(MouseEvent InMouseEvent)
		{
			if (InMouseEvent.getButton() == MouseEvent.BUTTON1)
			{
				// Do not touch client state from the AWT event thread. The next
				// ClientTick consumes this immutable canvas-coordinate snapshot.
				PendingPrimaryMousePress = new Point(
						InMouseEvent.getX(),
						InMouseEvent.getY());
			}
			return InMouseEvent;
		}
	};
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
	// [TMA-SCENE-LOAD-CONTINUITY] onBeforeRender runs before the overlay can
	// recreate and rebase scene-owned RuneLiteObjects. Keep one explicit
	// native-player/native-camera handoff until the replacement model is ready
	// so stale local coordinates can never be presented in the new scene.
	private boolean bSceneLoadVisualHandoffPending = false;
	private int SceneGeneration = 0;
	private CustomMovementHandler PreRenderedHandler = null;
	private boolean bPreRenderedSceneLoadFrame = false;

	int GetSceneGeneration()
	{
		return SceneGeneration;
	}

	private void InvalidateScenePresentation()
	{
		// Scene upload may begin on the map-loader thread immediately after
		// this event. Fail open until a replacement model has been prepared in
		// the destination scene and a new client-thread snapshot is published.
		PublishLocalPlayerRenderState(null, false);
		++SceneGeneration;
		PreRenderedHandler = null;
		bPreRenderedSceneLoadFrame = false;
		OverlayRenderer.bRuneliteObjectsStale = true;
		bSceneLoadVisualHandoffPending = true;
		LastAdaptiveCameraUpdateNanos = 0;
		bAdaptiveCameraRenderedThisFrame = false;
		client.setCameraMode(0);
	}

	// [TMA-MOTION-CONTINUITY] RuneLite can replace the WorldView wrapper while
	// retaining the same logical scene. Pointer identity would treat that as a
	// full world change and discard the visible player's interpolation state.
	static boolean IsSameWorldView(WorldView First, WorldView Second)
	{
		return First != null && Second != null &&
				First.getId() == Second.getId();
	}

	private boolean IsAdaptiveCameraOn()
	{
		return !bForceAdaptiveCameraOff && config.AdaptiveCameraOn();
	}

	static boolean IsGpuCallbackStillSupported(
			GameState CurrentGameState,
			int TicksSinceCallback)
	{
		return CurrentGameState != GameState.LOGGED_IN ||
				TicksSinceCallback <=
						GPU_CALLBACK_GRACE_CLIENT_TICKS;
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

	// [TMA-ADAPTIVE-CAMERA-VERTICAL-CONTINUITY] X/Z already approach the
	// visible model gradually, but focal Y used to be replaced outright every
	// frame. Scene terrain and animation-height changes could therefore turn
	// into a one-frame vertical camera jolt. Use a frame-rate-independent
	// half-life so the same height change has the same visual duration at
	// different frame rates.
	static float EaseAdaptiveCameraHeight(
			float CurrentHeight,
			float TargetHeight,
			float FrameDeltaMilliseconds)
	{
		if (!Float.isFinite(CurrentHeight))
		{
			return TargetHeight;
		}
		if (!Float.isFinite(TargetHeight))
		{
			return CurrentHeight;
		}

		float SafeFrameDelta = Math.max(
				0,
				Math.min(
						FrameDeltaMilliseconds,
						MAX_ADAPTIVE_CAMERA_FRAME_DELTA_MILLISECONDS));
		double Blend =
				1.0 -
						Math.pow(
								0.5,
								SafeFrameDelta /
										ADAPTIVE_CAMERA_VERTICAL_HALF_LIFE_MILLISECONDS);
		float EasedHeight = CurrentHeight +
				(TargetHeight - CurrentHeight) * (float) Blend;
		return Math.abs(TargetHeight - EasedHeight) < 0.01f
				? TargetHeight
				: EasedHeight;
	}

	@Subscribe
	public void onClientTick(ClientTick event)
	{
		// [TMA-CAMERA-INPUT-BOUNDARY] The adaptive focal point is presentation
		// only. RuneLite's native minimap and minimap-click conversion both use
		// the authoritative CameraFocusableEntity, not the free-camera focal
		// point. Keep those two meanings aligned and return to normal camera
		// before menu sorting/click detection; visually shifting only the map
		// would make the tile under the cursor differ from the tile acted on.
		if (IsAdaptiveCameraOn())
		{
			client.setCameraMode(0);
		}

		ArmMinimapWalkBeforeInputProcessing();

		if (client.getLocalPlayer() == null || client.getWorldView(-1) != client.getLocalPlayer().getWorldView())
		{
			bForceAdaptiveCameraOff = true;
		}
		else
		{
			bForceAdaptiveCameraOff = false;
		}

		// Plugin no longer supported (Need GPU plugin). Only age the callback
		// while a scene is expected to render; LOADING/HOPPING pauses are not
		// evidence that GPU support disappeared.
		bIsPluginSupportedCurrently =
				IsGpuCallbackStillSupported(
						client.getGameState(),
						TicksSincePluginWasSupport);
		if (client.getGameState() == GameState.LOGGED_IN)
		{
			TicksSincePluginWasSupport = Math.min(
					GPU_CALLBACK_GRACE_CLIENT_TICKS + 1,
					TicksSincePluginWasSupport + 1);
		}
		if (!bIsPluginSupportedCurrently ||
				client.getGameState() != GameState.LOGGED_IN ||
				client.getLocalPlayer() == null)
		{
			PublishLocalPlayerRenderState(null, false);
		}

	}

	static boolean IsGenuineTeleportAnimation(int Animation)
	{
		return Animation == AnimationID.HUMAN_CASTTELEPORT ||
				Animation == AnimationID.AHOY_ECTO_TELEPORT ||
				Animation == AnimationID.HUMAN_TELEPORT_OTHER_IMPACT ||
				Animation == AnimationID.TELEPORT_NARDAH_HUMAN ||
				Animation == AnimationID.HUMAN_COWBOSS_TELEPORT ||
				Animation == AnimationID.POH_SMASH_MAGIC_TABLET ||
				Animation == AnimationID.POH_ABSORB_TABLET_TELEPORT ||
				Animation == AnimationID.TELEPORT_CABBAGE_HUMAN ||
				Animation == AnimationID.NTK_HUMAN_TELE;
	}

	private void ArmMinimapWalkBeforeInputProcessing()
	{
		Point MousePosition = PendingPrimaryMousePress;
		PendingPrimaryMousePress = null;
		if (MousePosition == null)
		{
			return;
		}

		Widget Minimap = GetMinimapDrawWidget();
		boolean bMouseInsideMinimap =
				Minimap != null &&
				!Minimap.isHidden() &&
				IsInsideMinimapEllipse(
						MousePosition,
						Minimap.getBounds());
		if (!bMouseInsideMinimap ||
				bForceEarlyOut ||
				!bIsPluginSupportedCurrently)
		{
			return;
		}

		InterruptTeleportPresentationForUserInteraction();

		// Minimap movement is handled directly by the game client and does not
		// emit the WALK MenuOptionClicked event used by viewport yellow clicks.
		// Arm the same existing continuity state here, before RuneScape converts
		// this press into a destination, so the first segment owns this click.
		CustomMovementHandler LocalPlayerHandler =
				GetLocalPlayerMovementHandler();
		if (LocalPlayerHandler != null)
		{
			LocalPlayerHandler
					.DisarmPohArrivalCoordinateGuardForUserInteraction();
			LocalPlayerHandler.ArmWalkStopFacingHold();
		}
	}

	private void InterruptTeleportPresentationForUserInteraction()
	{
		if (OverlayRenderer.bShouldPlayTeleportAnimation)
		{
			OverlayRenderer.bTeleportInterrupted = true;
		}
	}

	private Widget GetMinimapDrawWidget()
	{
		if (!client.isResized())
		{
			return client.getWidget(InterfaceID.Toplevel.MINIMAP);
		}

		return client.getVarbitValue(
				VarbitID.RESIZABLE_STONE_ARRANGEMENT) == 1
				? client.getWidget(InterfaceID.ToplevelPreEoc.MINIMAP)
				: client.getWidget(InterfaceID.ToplevelOsrsStretch.MINIMAP);
	}

	static boolean IsInsideMinimapEllipse(
			Point MousePosition,
			Rectangle MinimapBounds)
	{
		if (MousePosition == null ||
				MinimapBounds == null ||
				MinimapBounds.width <= 0 ||
				MinimapBounds.height <= 0)
		{
			return false;
		}

		double RadiusX = MinimapBounds.width / 2.0;
		double RadiusY = MinimapBounds.height / 2.0;
		double NormalizedX =
				(MousePosition.getX() - MinimapBounds.getCenterX()) /
						RadiusX;
		double NormalizedY =
				(MousePosition.getY() - MinimapBounds.getCenterY()) /
						RadiusY;
		return NormalizedX * NormalizedX +
				NormalizedY * NormalizedY <= 1.0;
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
		if (!Float.isFinite(CurrentCameraPositionY))
		{
			CurrentCameraPositionY =
					client.getCameraFocalPointY();
		}
		float CameraHeightTarget = FootprintHeight - CameraFollowHeight;
		CurrentCameraPositionY = EaseAdaptiveCameraHeight(
				CurrentCameraPositionY,
				CameraHeightTarget,
				CameraFrameDeltaMilliseconds);

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
		client.setCameraFocalPointY(CurrentCameraPositionY);
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
		return ShouldRenderAdaptiveCamera(
				AdaptiveCameraOn,
				ShouldRenderOwner,
				false);
	}

	static boolean ShouldRenderAdaptiveCamera(
			boolean AdaptiveCameraOn,
			boolean ShouldRenderOwner,
			boolean NativeCameraHandoffPending)
	{
		return AdaptiveCameraOn &&
				!ShouldRenderOwner &&
				!NativeCameraHandoffPending;
	}

	static boolean ShouldSuppressNativeOwner(
			boolean SceneLoadVisualHandoffPending,
			boolean SceneObjectsStale,
			boolean CustomModelCanReplaceOwner)
	{
		return !SceneLoadVisualHandoffPending &&
				!SceneObjectsStale &&
				CustomModelCanReplaceOwner;
	}

	void CompleteSceneLoadVisualHandoff(
			CustomMovementHandler Handler)
	{
		if (!bSceneLoadVisualHandoffPending ||
				client.getGameState() != GameState.LOGGED_IN ||
				Handler == null ||
				!Handler.IsSceneLoadVisualReady())
		{
			return;
		}

		if (Handler.DidLastSceneRebaseUseNativeHandoffAnchor())
		{
			// The native camera can continue moving between onBeforeRender and
			// the overlay completing its replacement model. Capture its final
			// focal point at the exact transfer boundary before adaptive mode
			// resumes. An atomic custom rebase deliberately retains the prior
			// adaptive world-space camera instead.
			SynchronizeAdaptiveCameraToNativeCamera();
		}
		bSceneLoadVisualHandoffPending = false;
	}

	private boolean TryPrepareSceneLoadBeforeRender(
			Player Player,
			CustomMovementHandler Handler)
	{
		if (!bSceneLoadVisualHandoffPending ||
				!OverlayRenderer.bRuneliteObjectsStale ||
				OverlayRenderer.bEverythingIsStale ||
				client.getGameState() != GameState.LOGGED_IN ||
				client.getScene() == null ||
				Player.getLocalLocation() == null)
		{
			return false;
		}

		// [TMA-SCENE-LOAD-CONTINUITY] BeforeRender is the last safe point
		// before the destination scene is presented. Recreate, world-rebase,
		// and fully populate the custom object here so neither the character
		// nor adaptive camera ever consumes an old-scene local coordinate.
		Handler.Owner = Player;
		Handler.Initialize(
				true,
				SceneGeneration);
		Handler.Update();
		if (!Handler.IsSceneLoadVisualReady())
		{
			return false;
		}

		OverlayRenderer.bRuneliteObjectsStale = false;
		PreRenderedHandler = Handler;
		bPreRenderedSceneLoadFrame = true;
		CompleteSceneLoadVisualHandoff(Handler);
		return true;
	}

	boolean ConsumePreRenderUpdate(
			CustomMovementHandler Handler)
	{
		boolean bPreparedForThisPresentation =
				Handler != null &&
						Handler == PreRenderedHandler;
		if (bPreparedForThisPresentation &&
				bPreRenderedSceneLoadFrame)
		{
			// [TMA-SCENE-PRESENTATION-CLOCK] The first drawable scene frame
			// may finish long after BeforeRender prepared the model. Mark the
			// moment it was actually presented so the handler can defer that
			// time instead of applying it as one large update next frame.
			Handler.MarkSceneLoadFramePresented();
		}
		PreRenderedHandler = null;
		bPreRenderedSceneLoadFrame = false;
		return bPreparedForThisPresentation;
	}
	@Subscribe
	public void onBeforeRender(BeforeRender beforeRender)
	{
		if (!config.DebugStallTrace())
		{
			OnBeforeRenderImpl(beforeRender);
			return;
		}

		long StallTraceStartNanos = System.nanoTime();
		try
		{
			OnBeforeRenderImpl(beforeRender);
		}
		finally
		{
			long ElapsedMillis =
					(System.nanoTime() - StallTraceStartNanos) / 1_000_000L;
			if (ElapsedMillis >=
					DebugFileLogger.STALL_TRACE_STAGE_THRESHOLD_MILLIS)
			{
				DebugFileLogger.Append(
						DebugFileLogger.STALL_TRACE_LOG_FILE,
						"[TMA-STALL-TRACE] stage=plugin.onBeforeRender ms=" +
								ElapsedMillis);
			}
		}
	}

	private void OnBeforeRenderImpl(BeforeRender beforeRender)
	{
		// A prepared snapshot belongs to exactly one render. Clear any marker
		// left by a frame whose overlay was skipped before preparing this one.
		PreRenderedHandler = null;
		bPreRenderedSceneLoadFrame = false;
		bAdaptiveCameraRenderedThisFrame = false;
		Player player = client.getLocalPlayer();
		if (bForceEarlyOut ||
				!bIsPluginSupportedCurrently ||
				player == null ||
				client.getGameState() != GameState.LOGGED_IN)
		{
			PublishLocalPlayerRenderState(null, false);
			LastAdaptiveCameraUpdateNanos = 0;
			return;
		}
		// Start every prepared frame in fail-open mode. Suppression is enabled
		// below only after the current-scene replacement has passed every
		// readiness/handoff check.
		PublishLocalPlayerRenderState(player, false);

		CharacterIDs.clear();
		for (Player ScenePlayer : client.getPlayers())
		{
			if (ScenePlayer != null)
			{
				CharacterIDs.add(ScenePlayer.getId());
			}
		}
		for (NPC npc : client.getNpcs())
		{
			if (npc != null && npc.getComposition().getSize() == 1)
			{
				CharacterIDs.add(npc.getId());
			}
		}
		CustomMovementHandler PlayerMovementHandler = OverlayRenderer.MovementHandlerCache.get(player.getId());
		if (PlayerMovementHandler == null)
		{
			LastAdaptiveCameraUpdateNanos = 0;
			return;
		}
		boolean bSceneLoadHandoff =
				bSceneLoadVisualHandoffPending ||
						OverlayRenderer.bRuneliteObjectsStale;
		if (bSceneLoadHandoff &&
				TryPrepareSceneLoadBeforeRender(
						player,
						PlayerMovementHandler))
		{
			bSceneLoadHandoff = false;
		}
		if (bSceneLoadHandoff)
		{
			// The old RuneLiteObject location is expressed in the previous
			// scene's local basis. Do not even sample it for camera height or
			// destination until the overlay has recreated and rebased it.
			if (client.getGameState() == GameState.LOGGED_IN &&
					client.getScene() != null &&
					client.getLocalPlayer().getLocalLocation() != null)
			{
				PlayerMovementHandler
						.MarkNativeSceneLoadHandoffPresented();
			}
			SynchronizeAdaptiveCameraToNativeCamera();
			return;
		}

		if (PreRenderedHandler == null)
		{
			// [TMA-PRE-RENDER-SNAPSHOT] ABOVE_SCENE overlays run after 117 HD
			// has consumed scene models. Prepare the existing movement, facing,
			// and native-smoothed geometry here so all of them reach the same
			// displayed frame. The overlay consumes this marker and must not
			// advance the handler a second time.
			PlayerMovementHandler.Owner = player;
			PlayerMovementHandler.Initialize(
					false,
					SceneGeneration);
			PlayerMovementHandler.Update();
			PreRenderedHandler = PlayerMovementHandler;
		}
		LocalPoint CameraHeightLocation = PlayerMovementHandler.Model == null
				? null
				: PlayerMovementHandler.Model.getLocation();
		if (CameraHeightLocation == null)
		{
			LastAdaptiveCameraUpdateNanos = 0;
			return;
		}

		PublishLocalPlayerRenderState(
				player,
				ShouldSuppressNativeOwner(
						bSceneLoadVisualHandoffPending,
						OverlayRenderer.bRuneliteObjectsStale,
						PlayerMovementHandler
								.CanSuppressOwnerInCurrentScene()));
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
		boolean bPohArrivalNativeCamera =
				PlayerMovementHandler
						.ShouldUseNativeCameraForPohArrival();

		// Input processing uses the normal camera outside the draw interval. Never
		// expose that interaction camera during presentation: on uneven terrain its
		// focal height belongs to the hidden owner rather than the visible model.
		// POH arrival is the deliberate exception: Construction replaces its
		// temporary spawn coordinate after the first drawable frame, so retain
		// RuneScape's native camera until the arrival guard is released by the
		// first user world interaction.
		if (ShouldRenderAdaptiveCamera(
				IsAdaptiveCameraOn(),
				PlayerMovementHandler.bShouldRenderOwner,
				bPohArrivalNativeCamera))
		{
			if (config.DebugStallTrace())
			{
				long StallTraceStageStartNanos = System.nanoTime();
				UpdateAdaptiveCamera(PlayerMovementHandler, FootprintHeight, CameraFollowHeight);
				long ElapsedMillis =
						(System.nanoTime() - StallTraceStageStartNanos) / 1_000_000L;
				if (ElapsedMillis >=
						DebugFileLogger.STALL_TRACE_STAGE_THRESHOLD_MILLIS)
				{
					DebugFileLogger.Append(
							DebugFileLogger.STALL_TRACE_LOG_FILE,
							"[TMA-STALL-TRACE] stage=UpdateAdaptiveCamera ms=" +
									ElapsedMillis);
				}
			}
			else
			{
				UpdateAdaptiveCamera(
						PlayerMovementHandler,
						FootprintHeight,
						CameraFollowHeight);
			}
		}
		else
		{
			SynchronizeAdaptiveCameraToNativeCamera();
		}
	}

	private void SynchronizeAdaptiveCameraToNativeCamera()
	{
		// Keep the normal camera position synchronized while adaptive
		// rendering is paused.
		LastAdaptiveCameraUpdateNanos = 0;
		if (client.getCameraMode() == 0 &&
				client.getLocalPlayer() != null)
		{
			// Y has no scene-local basis. Retaining the last actually presented
			// native focal height gives adaptive rendering a continuous source
			// value after a native-player handoff.
			CurrentCameraPositionY = client.getCameraFocalPointY();
			// Store in sudo world space.
			WorldPoint TrueWorldTile =
					client.getLocalPlayer().getWorldLocation();
			LocalPoint TrueLocalTile = TrueWorldTile == null
					? null
					: LocalPoint.fromWorld(client, TrueWorldTile);
			if (TrueLocalTile != null)
			{
				double CalculationOffsetVectorX =
						TrueLocalTile.getX() -
								TrueWorldTile.getX() * 128;
				double CalculationOffsetVectorY =
						TrueLocalTile.getY() -
								TrueWorldTile.getY() * 128;

				CurrentCameraPositionX = (float)
						(client.getCameraFocalPointX() -
								CalculationOffsetVectorX);
				CurrentCameraPositionZ = (float)
						(client.getCameraFocalPointZ() -
								CalculationOffsetVectorY);
			}
		}
		client.setCameraMode(0);
	}
	private long LastTimeHitSplatApplied = 0;
	@Subscribe
	public void onHitsplatApplied(HitsplatApplied event)
	{
		if (event.getActor() == client.getLocalPlayer())
		{
			LastTimeHitSplatApplied = System.nanoTime();
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
			PublishLocalPlayerRenderState(null, false);
			CurrentCameraPositionX = -1;
			CurrentCameraPositionY = Float.NaN;
			CurrentCameraPositionZ = -1;
			client.setCameraMode(0);
			return;
		}

		// Manage our current hitsplats
        CurrentHitsplats.removeIf(hitsplat -> client.getGameCycle() >= hitsplat.getDisappearsOnGameCycle());

		// Recently been in combat
		if (System.nanoTime() - LastTimeHitSplatApplied < 6e+9) // 6 seconds
		{
			// Show this one
			OverlayRenderer.bShowHPBar = true;
		}
		else
		{
			OverlayRenderer.bShowHPBar = false;
		}
		// [TMA-TELEPORT] Restored original teleport detection. A genuine
		// teleport is identified only by the teleport animation the client
		// publishes on the player. Ordinary fast running/walking never plays
		// these animations, so the movement-continuity path (which treats
		// scene/region transitions as preserved interpolation) remains
		// untouched for normal movement.
		Player LocalPlayer = client.getLocalPlayer();
		if (LocalPlayer != null)
		{
			// [TMA-TELEPORT-CORRECT] Only arm the teleport presentation for
			// animations that are genuinely teleport cast/tablet/arrival
			// sequences. Several original-list entries were spell-casting
			// animations from non-teleport spellbooks (e.g. ZAROS_VERTICAL_
			// CASTING is an Ancient Magick cast, ARCEUUS_NECROMANCY_ANIM
			// is an Arceuus spell). These falsely armed the position-snap
			// teleport-in path during PvP casting, making the model skip
			// tiles on every spell cast while moving.
			int CurrentAnimation = LocalPlayer.getAnimation();
			if (IsGenuineTeleportAnimation(CurrentAnimation))
			{
				OverlayRenderer.LastTimeTeleport = System.nanoTime();
				OverlayRenderer.bShouldPlayTeleportAnimation = true;
				OverlayRenderer.bTeleportInterrupted = false;
			}
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
			PublishLocalPlayerRenderState(null, false);
			return;
		}

		WorldView newWorldView = player.getWorldView();
		if (currentWorldView == null)
		{
			currentWorldView = newWorldView;
		}
		else if (!IsSameWorldView(newWorldView, currentWorldView))
		{
			currentWorldView = newWorldView;
			OverlayRenderer.bEverythingIsStale = true;
			InvalidateScenePresentation();
		}
		else if (newWorldView != currentWorldView)
		{
			// Recreate only the scene-owned objects. The handler will rebase
			// its retained world-space interpolation in the replacement scene.
			currentWorldView = newWorldView;
			InvalidateScenePresentation();
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

	private void DoStartUp()
	{
		bDelayedStartup = false;
		bStartupComplete = true;
		InitializePrayerImages();
		InitializeSkullImages();
		InitializeHitsplatImages();

		renderCallbackManager.register(renderCallback);
		drawManager.registerEveryFrameListener(PostDrawCameraModeHandoff);
		mouseManager.registerMouseListener(MinimapClickListener);
		bForceEarlyOut = false;
		CurrentCameraPositionX = -1;
		CurrentCameraPositionY = Float.NaN;
		CurrentCameraPositionZ = -1;
		LastAdaptiveCameraUpdateNanos = 0;
		bAdaptiveCameraRenderedThisFrame = false;
		PendingPrimaryMousePress = null;
		bSceneLoadVisualHandoffPending = false;
		PreRenderedHandler = null;
		bPreRenderedSceneLoadFrame = false;
		PublishLocalPlayerRenderState(null, false);
	}


	@Override
	protected void startUp() throws Exception
	{
		overlayManager.add(OverlayRenderer);
		if (client.getGameState() == GameState.LOGIN_SCREEN)
		{
			DoStartUp();
		}
		else
		{
			bDelayedStartup = true;
			bStartupComplete = false;
			bForceEarlyOut = true;
		}
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
		// Clear the callback snapshot synchronously before unregistering is
		// queued, so an in-flight upload can only draw the native player.
		PublishLocalPlayerRenderState(null, false);
		bDelayedStartup = false;
		bStartupComplete = false;
		CurrentCameraPositionX = -1;
		CurrentCameraPositionY = Float.NaN;
		CurrentCameraPositionZ = -1;
		LastAdaptiveCameraUpdateNanos = 0;
		bAdaptiveCameraRenderedThisFrame = false;
		PendingPrimaryMousePress = null;
		bSceneLoadVisualHandoffPending = false;
		PreRenderedHandler = null;
		bPreRenderedSceneLoadFrame = false;
		mouseManager.unregisterMouseListener(MinimapClickListener);

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

		// [TMA-STOP-FACING] A yellow Walk click arms the hold. A red world
		// interaction cancels it immediately so NPC/object/player facing keeps
		// using RuneScape's normal orientation changes.
		CustomMovementHandler LocalPlayerHandler = GetLocalPlayerMovementHandler();
		MenuAction Action = event.getMenuAction();
		if (Action == WALK || IsRedWorldInteraction(Action))
		{
			InterruptTeleportPresentationForUserInteraction();
		}
		if (LocalPlayerHandler != null)
		{
			if (Action == WALK || IsRedWorldInteraction(Action))
			{
				LocalPlayerHandler
						.DisarmPohArrivalCoordinateGuardForUserInteraction();
			}

			if (Action == WALK)
			{
				LocalPlayerHandler.ArmWalkStopFacingHold();
			}
			else if (IsRedWorldInteraction(Action))
			{
				LocalPlayerHandler.CancelWalkStopFacingHold();
			}
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

	private CustomMovementHandler GetLocalPlayerMovementHandler()
	{
		Player LocalPlayer = client.getLocalPlayer();
		return LocalPlayer == null
				? null
				: OverlayRenderer.MovementHandlerCache.get(LocalPlayer.getId());
	}

	static boolean IsRedWorldInteraction(MenuAction Action)
	{
		return Action != null && RED_WORLD_INTERACTION_ACTIONS.contains(Action);
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged gameStateChanged)
	{
		GameState NewState = gameStateChanged.getGameState();
		if (NewState == GameState.LOGIN_SCREEN && bDelayedStartup)
		{
			DoStartUp();
		}

		// Scene ownership changes regardless of the GPU support detector. Mark
		// this before its early-out so a coincident support pause cannot leave
		// an old RuneLiteObject or adaptive-camera target alive indefinitely.
		if (NewState == GameState.LOADING ||
				NewState == GameState.CONNECTION_LOST ||
				NewState == GameState.HOPPING)
		{
			InvalidateScenePresentation();
		}

		if (bForceEarlyOut || !bIsPluginSupportedCurrently)
		{
			return;
		}

		if (NewState == GameState.LOGGED_IN)
		{
			Player LocalPlayer = client.getLocalPlayer();
			if (LocalPlayer != null)
			{
				// LOADING already invalidated the scene generation. Adopt the
				// destination wrapper now so onGameTick does not mistake the
				// same transition for a second invalidation.
				currentWorldView = LocalPlayer.getWorldView();
			}
		}
	}

	@Provides
	TrueTileMovementConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(TrueTileMovementConfig.class);
	}

}
