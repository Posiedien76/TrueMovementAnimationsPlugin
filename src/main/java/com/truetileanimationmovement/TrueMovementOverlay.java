package com.truetileanimationmovement;

import net.runelite.api.*;
import net.runelite.api.Point;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.kit.KitType;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.ui.overlay.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.ui.overlay.components.LineComponent;
import org.apache.commons.lang3.tuple.Pair;

import javax.inject.Inject;
import javax.swing.*;
import java.awt.*;
import java.util.*;

public class TrueMovementOverlay extends OverlayPanel
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

    // HP Bar
    public boolean bShowHPBar = true;
    private static final Color BAR_FILL_COLOR = Color.green;
    private static final Color BAR_BG_COLOR = Color.red;
    private static final Dimension HP_BAR_SIZE = new Dimension(30, 5);

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
    public Map<Integer /* character ID */, CustomMovementHandler> MovementHandlerCache = new HashMap<>();

    @Inject
    private TrueMovementOverlay(Client client, TrueTileMovementPlugin plugin, TrueTileMovementConfig config)
    {
        this.client = client;
        this.plugin = plugin;
        this.config = config;

        setPosition(OverlayPosition.DYNAMIC);
        setPriority(PRIORITY_HIGH);
        setLayer(OverlayLayer.ABOVE_SCENE);
    }

    public void RenderHPBar(Graphics2D graphics)
    {
        var playerEntry = MovementHandlerCache.get(client.getLocalPlayer().getId());

        if (!bShowHPBar || playerEntry == null)
        {
            return;
        }

        final int height = client.getLocalPlayer().getLogicalHeight() + 28;

        final LocalPoint localLocation = playerEntry.Model.getLocation();
        final Point canvasPoint = Perspective.localToCanvas(client, localLocation, client.getPlane(), height);

        final float ratio = (float) client.getBoostedSkillLevel(Skill.HITPOINTS) / client.getRealSkillLevel(Skill.HITPOINTS);

        // Draw bar
        final int barX = canvasPoint.getX() - 15;
        final int barY = canvasPoint.getY();
        final int barWidth = HP_BAR_SIZE.width;
        final int barHeight = HP_BAR_SIZE.height;

        // Restricted by the width to prevent the bar from being too long while you are boosted above your real HP level.
        final int progressFill = (int) Math.ceil(Math.min((barWidth * ratio), barWidth));

        graphics.setColor(BAR_BG_COLOR);
        graphics.fillRect(barX, barY, barWidth, barHeight);
        graphics.setColor(BAR_FILL_COLOR);
        graphics.fillRect(barX, barY, progressFill, barHeight);

    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        if (plugin.bForceEarlyOut || !plugin.bIsPluginSupportedCurrently)
        {
            Cleanup();

            // On screen message for requiring GPU plugin
            if (!plugin.bIsPluginSupportedCurrently)
            {
                panelComponent.getChildren().clear();
                panelComponent.getChildren().add(
                        LineComponent.builder()
                                .left("True Tile Animation Movement Plugin: DISABLED (GPU Plugin is required)")
                                .build()
                );

                return super.render(graphics);
            }
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
            MovementHandlerCache.put(client.getLocalPlayer().getId(), new CustomMovementHandler(client, plugin, config,this, client.getLocalPlayer()));
        }

        var playerEntry = MovementHandlerCache.get(client.getLocalPlayer().getId());

        // Initialize if needed
        playerEntry.Initialize(bRuneliteObjectsStale);

        // True update
        playerEntry.Update();

        bRuneliteObjectsStale = false;

        // Render HP bar
        RenderHPBar(graphics);

        return null;
    }
}
