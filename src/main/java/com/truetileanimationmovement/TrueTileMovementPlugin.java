package com.truetileanimationmovement;

import com.google.inject.Provides;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.*;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.callback.Hooks;
import net.runelite.client.callback.RenderCallback;
import net.runelite.client.callback.RenderCallbackManager;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.PluginChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import com.google.common.annotations.VisibleForTesting;
import net.runelite.client.ui.overlay.OverlayManager;

import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

@Slf4j
@PluginDescriptor(
	name = "True Tile Movement"
)
public class TrueTileMovementPlugin extends Plugin implements MouseListener
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

	public boolean bIsPluginSupportedCurrently = true;
	public int TicksSincePluginWasSupport = 0;
	private final RenderCallback renderCallback = new RenderCallback()
	{
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

	private float CurrentCameraPositionX = -1;
	private float CurrentCameraPositionZ = -1;

	private int CurrentMenuEntryCount = 0;
	private long LastRightClickTime = 0;
	private boolean bIsRightClick = false;
	private float OldFocalPointY = 0;

	private WorldView currentWorldView = null;
	//private int LastPrintedAnimation = 0;
	@Subscribe
	public void onClientTick(ClientTick event)
	{
		MenuEntry[] entries = client.getMenuEntries();
		CurrentMenuEntryCount = entries.length;

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

		// Update our focal point Y (probably can calculate this somehow)
		if (client.getCameraMode() == 0 || OldFocalPointY == 0)
		{
			OldFocalPointY = client.getCameraFocalPointY();
		}

		CustomMovementHandler PlayerMovementHandler = OverlayRenderer.MovementHandlerCache.get(client.getLocalPlayer().getId());
		if (PlayerMovementHandler != null)
		{
			//client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "True Focal Point X" + client.getCameraFocalPointX(), null);
			//client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "True Focal Point Y" + client.getCameraFocalPointY(), null);
			//client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "True Focal Point Z" + client.getCameraFocalPointZ(), null);

			// Use the old camera system while attempting to make commands. (The old camera is so close to the original, the clickboxes are close enough)
			if (!client.isMenuOpen() && (System.currentTimeMillis() -LastRightClickTime > 60))
			{
				bIsRightClick = false;
			}
			if (config.AdaptiveCameraOn() && !bIsRightClick && !PlayerMovementHandler.bShouldRenderOwner)
			{
				if (CurrentCameraPositionX == -1 || CurrentCameraPositionZ == -1)
				{
					CurrentCameraPositionX = client.getCameraFocalPointX();
					CurrentCameraPositionZ = client.getCameraFocalPointZ();
				}

				LocalPoint lp = PlayerMovementHandler.Model.getLocation();
				int CameraDestinationX = lp.getX();
				int CameraDestinationZ = lp.getY();

				double dx = (CameraDestinationX - CurrentCameraPositionX);
				double dz = (CameraDestinationZ - CurrentCameraPositionZ);

				double VectorDistance = Math.sqrt(dx * dx + dz * dz);

				client.setCameraMode(1);
				if (VectorDistance != 0)
				{
					// Normalize vector
					/*double NormalizedDx = dx / VectorDistance;
					double NormalizedDz = dz / VectorDistance;

					if (dx > 0) {
						CurrentCameraPositionX += (float) Math.min(dx, NormalizedDx * config.FreeCameraMovementSpeed());
					} else {
						CurrentCameraPositionX -= (float) Math.min(-dx, -NormalizedDx * config.FreeCameraMovementSpeed());
					}

					if (dz > 0) {
						CurrentCameraPositionZ += (float) Math.min(dz, NormalizedDz * config.FreeCameraMovementSpeed());
					} else {
						CurrentCameraPositionZ -= (float) Math.min(-dz, -NormalizedDz * config.FreeCameraMovementSpeed());
					}
					*/
					//client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "My Focal Point X" + CameraDestinationX, null);
					//client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "My Focal Point Y" + (model.getModelHeight() - 581), null);
					//client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "My Focal Point Z" + CameraDestinationZ, null);

					// Just snap to position for now
					client.setCameraFocalPointX(CameraDestinationX);
					client.setCameraFocalPointY(OldFocalPointY);
					client.setCameraFocalPointZ(CameraDestinationZ);
				}
			}
			else
			{
				client.setCameraMode(0);
			}
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (bForceEarlyOut || !bIsPluginSupportedCurrently)
		{
			return;
		}

		if (client.getLocalPlayer().getAnimation() == 714) // Teleport
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
		client.getCanvas().addMouseListener(this);
		renderCallbackManager.register(renderCallback);
		overlayManager.add(OverlayRenderer);
		bForceEarlyOut = false;
	}

	@Override
	protected void shutDown() throws Exception
	{
		clientThread.invoke(() ->
		{
			client.getCanvas().removeMouseListener(this);
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
		if (CurrentMenuEntryCount > 2)
		{
			bIsRightClick = true;
			client.setCameraMode(0);
			LastRightClickTime = System.currentTimeMillis();
		}
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
}
