package com.truetileanimationmovement;

import net.runelite.api.*;
import net.runelite.api.Point;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.kit.KitType;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.util.ColorUtil;
import net.runelite.client.util.Text;
import org.apache.commons.lang3.tuple.Pair;

import javax.inject.Inject;
import javax.swing.*;
import java.awt.*;
import java.awt.font.TextAttribute;
import java.awt.image.BufferedImage;
import java.text.AttributedString;
import java.util.*;
import java.util.List;
import java.util.function.ToIntFunction;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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
    public boolean bTeleportInterrupted = false;

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

        // Do not clear the cache here to avoid rapid runelite object recreation (cleaned up at a later point)
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

    public void RenderHPBar(Graphics2D graphics, Point HPBarPoint)
    {
        final float ratio = (float) client.getBoostedSkillLevel(Skill.HITPOINTS) / client.getRealSkillLevel(Skill.HITPOINTS);

        // Draw bar
        final int barX = HPBarPoint.getX() - 15;
        final int barY = HPBarPoint.getY();
        final int barWidth = HP_BAR_SIZE.width;
        final int barHeight = HP_BAR_SIZE.height;

        // Restricted by the width to prevent the bar from being too long while you are boosted above your real HP level.
        final int progressFill = (int) Math.ceil(Math.min((barWidth * ratio), barWidth));

        graphics.setColor(BAR_BG_COLOR);
        graphics.fillRect(barX, barY, barWidth, barHeight);
        graphics.setColor(BAR_FILL_COLOR);
        graphics.fillRect(barX, barY, progressFill, barHeight);

    }
    @Inject
    private FontManager fontManager;

    public List<Hitsplat> getTop4Hitsplats(
            List<Hitsplat> hitsplats,
            ToIntFunction<Hitsplat> priorityFunction)
    {
        return hitsplats.stream()
                .sorted(Comparator.comparingInt(priorityFunction).reversed())
                .limit(4)
                .collect(Collectors.toList());
    }

    public void RenderHitsplats(Graphics2D graphics)
    {
        if (!config.CustomOverheadRendering())
        {
            return;
        }

        graphics.setFont(FontManager.getRunescapeSmallFont());

        Player player = client.getLocalPlayer();
        var playerEntry = MovementHandlerCache.get(player.getId());
        if (playerEntry == null)
        {
            return;
        }

        Point[] HitsplatPointOffsets = {
                new Point(0, 0),
                new Point(0, -config.MultipleHitsplatOffset() + 5),
                new Point(-config.MultipleHitsplatOffset() / 2 - 3, -config.MultipleHitsplatOffset() / 2),
                new Point(config.MultipleHitsplatOffset() / 2 + 3, -config.MultipleHitsplatOffset() / 2)
        };

        // Render up to 4 hitsplats
        List<Hitsplat> Top4Hitsplats = getTop4Hitsplats(
                plugin.CurrentHitsplats,
                hs ->
                {
                    int priority = 0;
                    priority += 100 * (hs.getDisappearsOnGameCycle() - client.getGameCycle()); // Main priority is based on decay
                    priority += hs.getAmount(); // Secondary priority based on amount

                    return priority;
                }
        );


        // Reverse the array to draw most important last
        int i = 0;
        for (int j = Top4Hitsplats.size() - 1; j >= 0; --j)
        {
            Hitsplat hitsplat = Top4Hitsplats.get(j);
            if (hitsplat == null)
            {
                continue;
            }

            String text = String.valueOf(hitsplat.getAmount());

            Point point = Perspective.getCanvasTextLocation(
                    client,
                    graphics,
                    playerEntry.Model.getLocation(),
                    text,
                    player.getLogicalHeight() / 2
            );

            if (point != null)
            {
                BufferedImage HitsplatImage = plugin.hitsplatImages.get(hitsplat.getHitsplatType());
                int x = point.getX() + HitsplatPointOffsets[i].getX();
                int y = point.getY() + 8 + HitsplatPointOffsets[i].getY();

                FontMetrics metrics = graphics.getFontMetrics();
                int textWidth = metrics.stringWidth(text);
                int textHeight = metrics.getHeight();

                int textX = x;
                int textY = y;

                if (HitsplatImage != null)
                {
                    int imageX = textX + textWidth / 2 - HitsplatImage.getWidth() / 2;
                    int imageY = textY - textHeight + (textHeight - HitsplatImage.getHeight()) / 2;

                    graphics.drawImage(
                            HitsplatImage,
                            imageX,
                            imageY,
                            null
                    );

                }


                // Shadow
                graphics.setColor(Color.BLACK);
                graphics.drawString(text,
                        textX + 1,
                        textY + 1);

                graphics.setColor(Color.WHITE);
                graphics.drawString(
                        text,
                        textX,
                        textY
                );
            }

            ++i;
        }
    }

    public void RenderOverheadObjects(Graphics2D graphics)
    {
        if (!config.CustomOverheadRendering())
        {
            return;
        }

        Player player = client.getLocalPlayer();
        var playerEntry = MovementHandlerCache.get(player.getId());

        HeadIcon headIcon = player.getOverheadIcon();
        int skullIcon = client.getLocalPlayer().getSkullIcon();
        String OverheadText = player.getOverheadText();
        boolean bIsOverheadTextActive = OverheadText != null;

        if ((!bShowHPBar && headIcon == null && skullIcon == -1 && !bIsOverheadTextActive) || playerEntry == null)
        {
            return;
        }

        final LocalPoint localLocation = playerEntry.Model.getLocation();

        int groundHeight = Perspective.getFootprintTileHeight(
                client,
                localLocation,
                client.getLocalPlayer().getWorldView().getPlane(),
                player.getFootprintSize()
        );
        // Adjust height in 3D space
        int zOffset = player.getLogicalHeight() + config.OverheadObjectOffset();

        Point point = Perspective.localToCanvas(
                client,
                localLocation.getX(),
                localLocation.getY(),
                groundHeight - zOffset
        );

        if (point == null)
        {
            return;
        }
        int yOffset = 0;

        // Chat text changes the overhead offset.
        if (bIsOverheadTextActive)
        {
            graphics.setFont(FontManager.getRunescapeBoldFont());

            FontMetrics metrics = graphics.getFontMetrics();
            int drawX = point.getX() - metrics.stringWidth(OverheadText) / 2;
            int drawY = point.getY() + yOffset + config.OverheadTextOffset();

            // Shadow
            graphics.setColor(Color.BLACK);
            graphics.drawString(OverheadText, drawX + 1, drawY + 1);

            // Foreground
            graphics.setColor(Color.YELLOW); // Just support yellow for now
            graphics.drawString(OverheadText, drawX, drawY);

            yOffset = yOffset - 5;
        }

        if (bShowHPBar)
        {
            // Render HP bar
            Point HPBarPoint = new Point(point.getX(), point.getY() - config.OverheadHPBarOffset() + yOffset);
            RenderHPBar(graphics, HPBarPoint);

            yOffset = yOffset - 4;
        }

        if (skullIcon != -1)
        {
            BufferedImage SkullImage = plugin.GetSkullIcon(skullIcon);
            graphics.drawImage(
                    SkullImage,
                    point.getX() - SkullImage.getWidth() / 2,
                    point.getY() - 30 - 2 + yOffset,
                    null
            );

            yOffset = yOffset - 28;
        }

        if (headIcon != null)
        {

            BufferedImage PrayerImage = plugin.GetPrayerIcon(headIcon);
            graphics.drawImage(
                    PrayerImage,
                    point.getX() - PrayerImage.getWidth() / 2,
                    point.getY() - 30 - 2 + yOffset,
                    null
            );

        }
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

        // Overheads
        RenderOverheadObjects(graphics);

        // Hitsplats
        RenderHitsplats(graphics);

        return null;
    }
}
