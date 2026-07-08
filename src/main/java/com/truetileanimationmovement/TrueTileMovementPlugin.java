package com.truetileanimationmovement;

import com.google.inject.Provides;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.*;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

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

	private final RenderCallback renderCallback = new RenderCallback()
	{
		@Override
		public boolean drawObject(Scene scene, TileObject object)
		{
			// hide player
			CustomMovementHandler FoundHandler = OverlayRenderer.MovementHandlerCache.get(object.getId());
			if (FoundHandler != null && !FoundHandler.bShouldRenderOwner)
			{
				return false;
			}

			return true;
        }
	};

	public boolean bForceEarlyOut = false; // Debugging purposes

	private WorldView currentWorldView = null;
	//private int LastPrintedAnimation = 0;
	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (bForceEarlyOut)
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
		renderCallbackManager.register(renderCallback);
		overlayManager.add(OverlayRenderer);
	}

	@Override
	protected void shutDown() throws Exception
	{
		OverlayRenderer.Cleanup();
		renderCallbackManager.unregister(renderCallback);
		overlayManager.remove(OverlayRenderer);
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		if (bForceEarlyOut)
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
		if (bForceEarlyOut)
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
