package com.truetileanimationmovement;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.google.inject.Provides;
import javax.inject.Inject;
import javax.swing.*;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.*;
import net.runelite.api.widgets.Widget;
import net.runelite.client.RuneLite;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.callback.Hooks;
import net.runelite.client.callback.RenderCallback;
import net.runelite.client.callback.RenderCallbackManager;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.PluginChanged;
import net.runelite.client.input.KeyListener;
import net.runelite.client.input.KeyManager;
import net.runelite.client.input.MouseManager;
import net.runelite.client.input.MouseWheelListener;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import com.google.common.annotations.VisibleForTesting;
import net.runelite.client.ui.overlay.OverlayManager;

import java.awt.*;
import java.awt.event.*;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import net.runelite.api.Perspective;

import static com.sun.jna.platform.linux.Mman.MAP_TYPE;
import static net.runelite.api.MenuAction.*;
import static net.runelite.api.MenuAction.GROUND_ITEM_FIFTH_OPTION;
import static net.runelite.api.MenuAction.GROUND_ITEM_THIRD_OPTION;

@Slf4j
@PluginDescriptor(
	name = "True Tile Movement"
)
public class TrueTileMovementPlugin extends Plugin implements MouseListener, KeyListener, MouseWheelListener
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
	private KeyManager keyManager;

	@Inject
	private MouseManager mouseManager;

	@Inject
	private Gson gson;

	private static final Type MAP_TYPE =
			new TypeToken<Map<String, String>>() {}.getType();

	private final Path saveFile = RuneLite.RUNELITE_DIR.toPath()
			.resolve("TrueTileMovementPlugin")
			.resolve("data.json");

	public boolean bIsPluginSupportedCurrently = true;
	public int TicksSincePluginWasSupport = 0;
	private final RenderCallback renderCallback = new RenderCallback()
	{
		@Override
		public boolean addEntity(Renderable renderable, boolean ui)
		{
			CustomMovementHandler FoundHandler = OverlayRenderer.MovementHandlerCache.get(client.getLocalPlayer().getId());
			if (FoundHandler != null && !FoundHandler.bShouldRenderOwner)
			{

				if (ui && Objects.equals(renderable.toString(), client.getLocalPlayer().toString()))
				{
					return !(renderable instanceof Player);
				}
			}

			return true;
		}

		@Override
		public boolean drawObject(Scene scene, TileObject object)
		{
			// Only supported with GPU plugin
			TicksSincePluginWasSupport = 0;
			bIsPluginSupportedCurrently = true;

			// hide player
			CustomMovementHandler FoundHandler = OverlayRenderer.MovementHandlerCache.get(object.getId());
			if (FoundHandler != null && !FoundHandler.bShouldRenderOwner)
			{
				return false;
			}

			return true;
        }
	};

	public boolean bForceEarlyOut = false;

	public boolean bForceAdaptiveCameraOff = false;
	public boolean bNonAdaptiveCameraActionActive = false;

	private float CurrentCameraPositionX = -1; // Offset in "sudo world space" (see adaptive camera function)
	private float CurrentCameraPositionZ = -1;

	private boolean bIsWalkHereOptionWithExamine = false;
	private long LastInputTime = 0;
	private boolean bIsRecentInput = false;
	private float CurrentPredictedZoomLevel = 0; // (default to halfway) Value between 37 (zoomed out) and 112 (zoomed in)

	// Cache of target name to default action, serialize this so the user can accumulate right click options
	private Map<String, String> MainActionCache = new HashMap<>();
	private void saveMainActionCache() throws IOException
	{
		Files.createDirectories(saveFile.getParent());

		try (Writer writer = Files.newBufferedWriter(saveFile))
		{
			gson.toJson(MainActionCache, MAP_TYPE, writer);
		}
	}

	void loadMainActionCache() throws IOException
	{
		if (!Files.exists(saveFile))
		{
			MainActionCache = new HashMap<>();
			return;
		}

		try (Reader reader = Files.newBufferedReader(saveFile))
		{
			MainActionCache = gson.fromJson(reader, MAP_TYPE);
			if (MainActionCache == null)
			{
				MainActionCache = new HashMap<>();
			}
		}
	}

	private WorldView currentWorldView = null;
	private int LastPrintedAnimation = 0;

	int ConvertFromTypeToPriority(MenuAction Type)
	{
		int ReturnValue = 0;

		if (Type == GROUND_ITEM_FIFTH_OPTION)
		{
			ReturnValue = 1;
		}
		else if (Type == GROUND_ITEM_FOURTH_OPTION)
		{
			ReturnValue = 2;
		}
		else if (Type == GROUND_ITEM_THIRD_OPTION)
		{
			ReturnValue = 3;
		}
		else if (Type == GROUND_ITEM_SECOND_OPTION)
		{
			ReturnValue = 4;
		}
		else if (Type == GROUND_ITEM_FIRST_OPTION)
		{
			ReturnValue = 5;
		}
		else if (Type == NPC_FIFTH_OPTION)
		{
			ReturnValue = 6;
		}
		else if (Type == NPC_FOURTH_OPTION)
		{
			ReturnValue = 7;
		}
		else if (Type == NPC_THIRD_OPTION)
		{
			ReturnValue = 8;
		}
		else if (Type == NPC_SECOND_OPTION)
		{
			ReturnValue = 9;
		}
		else if (Type == NPC_FIRST_OPTION)
		{
			ReturnValue = 10;
		}
		else if (Type == GAME_OBJECT_FIFTH_OPTION)
		{
			ReturnValue = 11;
		}
		else if (Type == GAME_OBJECT_FOURTH_OPTION)
		{
			ReturnValue = 12;
		}
		else if (Type == GAME_OBJECT_THIRD_OPTION)
		{
			ReturnValue = 13;
		}
		else if (Type == GAME_OBJECT_SECOND_OPTION)
		{
			ReturnValue = 14;
		}
		else if (Type == GAME_OBJECT_FIRST_OPTION)
		{
			ReturnValue = 15;
		}


		return ReturnValue;
	}

	MenuEntry[] FindFirstEntry()
	{
		// Find the target
		MenuEntry FirstMenuEntry = null;
		MenuEntry WalkHereMenuEntry = null;
		int HighestPriorityMenuEntry = -1;
		MenuEntry[] entries = client.getMenuEntries();
		for (MenuEntry entry : entries)
		{
			int TypePriority = ConvertFromTypeToPriority(entry.getType());
			if (HighestPriorityMenuEntry < TypePriority && entry.getTarget() != null)
			{
				HighestPriorityMenuEntry = TypePriority;
				FirstMenuEntry = entry;
			}
			else if (entry.getType() == WALK)
			{
				WalkHereMenuEntry = entry;
			}
		}

		// Walk here option
		if (FirstMenuEntry == null)
		{
			FirstMenuEntry = WalkHereMenuEntry;
		}

		MenuEntry[] BundledReturn = new MenuEntry[2];
		BundledReturn[0] = FirstMenuEntry;
		BundledReturn[1] = WalkHereMenuEntry;

		return BundledReturn;
	}

	private boolean IsAdaptiveCameraOn()
	{
		return !bForceAdaptiveCameraOff && config.AdaptiveCameraOn() && !bNonAdaptiveCameraActionActive;
	}

	private double CurrentMinimapZoomLevel = 0;
	@Subscribe
	public void onClientTick(ClientTick event)
	{
		// Update the minimap, it doesn't update in free cam
		if (IsAdaptiveCameraOn())
		{
			client.setCameraMode(0);
		}

		if (client.getWorldView(-1) != client.getLocalPlayer().getWorldView())
		{
			bForceAdaptiveCameraOff = true;
		}
		else
		{
			bForceAdaptiveCameraOff = false;
		}

		// Option has walk here option
		MenuEntry[] entries = client.getMenuEntries();
		if (IsAdaptiveCameraOn() && entries.length > 2)
		{
			MenuEntry[] BundledEntries = FindFirstEntry();
			MenuEntry FirstMenuEntry = BundledEntries[0];
			MenuEntry WalkHereMenuEntry = BundledEntries[1];

			String TargetString = "";
			if (FirstMenuEntry == null)
			{
				FirstMenuEntry = WalkHereMenuEntry;
			}

			TargetString = FirstMenuEntry.getTarget();

			String TargetOption = "Interact";
			if (MainActionCache.containsKey(TargetString))
			{
				TargetOption = MainActionCache.get(TargetString);
			}

			for (int i = 0; i < entries.length; ++i)
			{
				if (entries[i].getType() == WALK)
				{
					if (!client.isMenuOpen() && !TargetString.isEmpty())
					{
						entries[i].setOption(TargetOption);
						entries[i].setTarget(TargetString);
					}
					else if (entries[i] != FirstMenuEntry)
					{
						entries[i].setOption("Walk here");
						entries[i].setTarget("");
					}
				}
			}

			if (WalkHereMenuEntry != null)
			{
				bIsWalkHereOptionWithExamine = true;
			}
			else
			{
				bIsWalkHereOptionWithExamine = false;
			}
		}
		else
		{
			bIsWalkHereOptionWithExamine = false;
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

	private void UpdateAdaptiveCamera(CustomMovementHandler PlayerMovementHandler, int FootprintHeight)
	{
		Player player = client.getLocalPlayer();
		WorldPoint trueWorldTile = player.getWorldLocation();
		LocalPoint trueLocalTile = LocalPoint.fromWorld(client, trueWorldTile);
		if (trueLocalTile == null)
		{
			return;
		}

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

		// Adjust using the current framerate (Tuned to 60FPS)
		Velocity *= (float) (PlayerMovementHandler.CurrentFrameDelta / 16.667);// Speed value centered at 60FPS

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

		// TODO: Probably do to Y too

		client.setCameraMode(1);
		client.setFreeCameraSpeed(0);

		client.setCameraFocalPointX(CurrentCameraPositionX);
		client.setCameraFocalPointY(FootprintHeight - CurrentPredictedZoomLevel);
		client.setCameraFocalPointZ(CurrentCameraPositionZ);

		// Store in sudo-world space to prevent jumps
		CurrentCameraPositionX -= (float) CalculationOffsetVectorX;
		CurrentCameraPositionZ -= (float) CalculationOffsetVectorY;
	}

	@Subscribe
	public void onBeforeRender(BeforeRender beforeRender)
	{
		if (bForceEarlyOut || !bIsPluginSupportedCurrently || client.getLocalPlayer() == null)
		{
			return;
		}

		CustomMovementHandler PlayerMovementHandler = OverlayRenderer.MovementHandlerCache.get(client.getLocalPlayer().getId());
		if (PlayerMovementHandler == null)
		{
			return;
		}

		int FootprintHeight = Perspective.getFootprintTileHeight(client, client.getLocalPlayer().getLocalLocation(), client.getLocalPlayer().getWorldView().getPlane(), client.getLocalPlayer().getFootprintSize());
		if (client.getLocalPlayer().getAnimation() != -1)
		{
			FootprintHeight -= client.getLocalPlayer().getAnimationHeightOffset();
		}
		else
		{
			FootprintHeight -= PlayerMovementHandler.OldAnimationHeight;
		}

		if (client.getCameraMode() == 0 || CurrentPredictedZoomLevel == 0)
		{
			CurrentPredictedZoomLevel = FootprintHeight - client.getCameraFocalPointY();
		}

		if (!client.isMenuOpen() && (System.currentTimeMillis() -LastInputTime > 60))
		{
			bIsRecentInput = false;
		}

		if (IsAdaptiveCameraOn() && !bIsRecentInput && !PlayerMovementHandler.bShouldRenderOwner)
		{
			UpdateAdaptiveCamera(PlayerMovementHandler, FootprintHeight);
		}
		// Cache our options
		else
		{
			if (client.getCameraMode() == 0)
			{
				// Find the target
				MenuEntry[] BundledEntries = FindFirstEntry();
				MenuEntry FirstMenuEntry = BundledEntries[0];
				MenuEntry WalkHereMenuEntry = BundledEntries[1];

				String TargetString = "";
				if (FirstMenuEntry == null)
				{
					FirstMenuEntry = WalkHereMenuEntry;
				}
				TargetString = FirstMenuEntry.getTarget();

				// Update the cache of the true default option
				if (FirstMenuEntry != null && !TargetString.isEmpty())
				{
					MainActionCache.put(TargetString, FirstMenuEntry.getOption());
				}

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
				client.getLocalPlayer().getAnimation() == 3865
		)
		{
			OverlayRenderer.LastTimeTeleport = System.currentTimeMillis();
			OverlayRenderer.bShouldPlayTeleportAnimation = true;
		}

		// Print recent animation for convenience
		//if (LastPrintedAnimation != client.getLocalPlayer().getAnimation())
		//{
		//	LastPrintedAnimation = client.getLocalPlayer().getAnimation();
		//	client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "Current Animation ID " + client.getLocalPlayer().getAnimation(), null);
		//}

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

	@Override
	protected void startUp() throws Exception
	{
		loadMainActionCache();

		client.getCanvas().addMouseListener(this);
		mouseManager.registerMouseWheelListener(this);
		keyManager.registerKeyListener(this);
		renderCallbackManager.register(renderCallback);
		overlayManager.add(OverlayRenderer);
		bForceEarlyOut = false;
		CurrentCameraPositionX = -1;
		CurrentCameraPositionZ = -1;
	}

	@Override
	protected void shutDown() throws Exception
	{
		saveMainActionCache();
		CurrentCameraPositionX = -1;
		CurrentCameraPositionZ = -1;

		clientThread.invoke(() ->
		{
            client.getCanvas().removeMouseListener(this);
			mouseManager.unregisterMouseWheelListener(this);
			keyManager.unregisterKeyListener(this);
			OverlayRenderer.Cleanup();
			renderCallbackManager.unregister(renderCallback);
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

		// These actions disable the adaptive camera
		if (event.getMenuAction() == WIDGET_TARGET && (event.getMenuOption().equals("Use") || event.getMenuOption().equals("Cast") ))
		{
			bNonAdaptiveCameraActionActive = true;
		}
		else
		{
			bNonAdaptiveCameraActionActive = false;
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

		// Cache our plugin data during a world hop or logout
		if (gameStateChanged.getGameState() == GameState.HOPPING ||
		gameStateChanged.getGameState() == GameState.LOGIN_SCREEN)
		{
            try {
                saveMainActionCache();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
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

	@Override
	public void mouseClicked(MouseEvent e)
	{
	}

	@Override
	public void mousePressed(MouseEvent e)
	{
		// If the option is not just "walk here", swap to the old camera system for just a few frames or while the right click menu is open.
		// The plugin's camera is so close to the original camera view that the clickboxes are close enough.
		// The user loses some accuracy, but it allows the feature to be possible.
		if (bIsWalkHereOptionWithExamine && !SwingUtilities.isMiddleMouseButton(e))
		{
			bIsRecentInput = true;
			client.setCameraMode(0);
			LastInputTime = System.currentTimeMillis();
		}
	}

	@Override
	public MouseWheelEvent mouseWheelMoved(MouseWheelEvent event)
	{
		clientThread.invoke(() ->
		{
			// Walk option, we are in the main client for sure
			MenuEntry[] entries = client.getMenuEntries();
			for (MenuEntry entry : entries)
			{
				if (entry.getType() == WALK)
				{
					float rotation = event.getWheelRotation();
					CurrentPredictedZoomLevel -= rotation * 2.5f;
					CurrentPredictedZoomLevel = Math.min(CurrentPredictedZoomLevel, 112);
					CurrentPredictedZoomLevel = Math.max(CurrentPredictedZoomLevel, 37);
					break;
				}
			}
		});

		return event;
	}

	@Override
	public void mouseReleased(MouseEvent e)
	{

	}

	@Override
	public void mouseEntered(MouseEvent e)
	{
	}

	@Override
	public void mouseExited(MouseEvent e)
	{

	}
	private boolean isNonTypingKey(KeyEvent e)
	{
		int code = e.getKeyCode();

		return (code >= KeyEvent.VK_F1 && code <= KeyEvent.VK_F12)
				|| code == KeyEvent.VK_SHIFT
				|| code == KeyEvent.VK_CONTROL
				|| code == KeyEvent.VK_ALT
				|| code == KeyEvent.VK_LEFT
				|| code == KeyEvent.VK_RIGHT
				|| code == KeyEvent.VK_UP
				|| code == KeyEvent.VK_DOWN;
	}

	@Override
	public void keyPressed(KeyEvent e)
	{
		Widget focused = client.getFocusedInputFieldWidget();
		if (focused == null)
		{
			char c = e.getKeyChar();

			if (!Character.isISOControl(c) && !isNonTypingKey(e))
			{
				// This is a printable character that could go into chat, do the same trick as the mouse
				bIsRecentInput = true;
				LastInputTime = System.currentTimeMillis();
				client.setCameraMode(0);
			}
		}
	}

	@Override
	public void keyReleased(KeyEvent e)
	{
	}

	@Override
	public void keyTyped(KeyEvent e)
	{
	}
}
