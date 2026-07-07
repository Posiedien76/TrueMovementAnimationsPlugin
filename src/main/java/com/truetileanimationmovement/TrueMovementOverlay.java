package com.truetileanimationmovement;

import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.kit.KitType;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.ui.overlay.OverlayUtil;
import org.apache.commons.lang3.tuple.Pair;

import javax.inject.Inject;
import javax.swing.*;
import java.awt.*;
import java.util.*;

public class TrueMovementOverlay extends Overlay
{
    // General
    private final Client client;
    private final TrueTileMovementPlugin plugin;
    private final TrueTileMovementConfig config;

    public boolean bEverythingIsStale = false;
    public boolean bRuneliteObjectsStale = false;
    public boolean bRecentlyClickedEvent = false;
    public long LastTimeTeleport = 0;
    public boolean bShouldPlayTeleportAnimation = false;

    public void Cleanup()
    {
        for (Map.Entry<Integer, CustomMovementHandler> entry : MovementHandlerCache.entrySet())
        {
            var value = entry.getValue();
            value.Cleanup();
        }

        MovementHandlerCache.clear();
        bEverythingIsStale = true;
    }

    // Tracking data for all characters we are handling the rendering (including player)
    Map<Integer /* character ID */, CustomMovementHandler> MovementHandlerCache = new HashMap<>();

    @Inject
    private TrueMovementOverlay(Client client, TrueTileMovementPlugin plugin, TrueTileMovementConfig config)
    {
        this.client = client;
        this.plugin = plugin;
        this.config = config;

    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        if (config.DisablePlugin())
        {
            Cleanup();
            return null;
        }

        if (bEverythingIsStale)
        {
            Cleanup();
            bEverythingIsStale = false;
        }

        // Add player to the cache
        if (!MovementHandlerCache.containsKey(client.getLocalPlayer().getId()))
        {
            MovementHandlerCache.put(client.getLocalPlayer().getId(), new CustomMovementHandler(client, plugin, config, this, client.getLocalPlayer()));
        }

        var playerEntry = MovementHandlerCache.get(client.getLocalPlayer().getId());

        // Initialize if needed
        playerEntry.Initialize(bRuneliteObjectsStale);

        // True update
        playerEntry.Update();

        bRuneliteObjectsStale = false;
        return null;
    }
}
