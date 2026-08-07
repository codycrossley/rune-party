package gay.runescape.runeparty;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("runeparty")
public interface RunePartyConfig extends Config
{
    @ConfigItem(
        keyName = "showOverlay",
        name = "Show player overlays",
        description = "Show player overlays above player heads",
        position = 0
    )
    default boolean showOverlay()
    {
        return true;
    }

    @ConfigItem(
        keyName = "showTileOverlay",
        name = "Show board overlay",
        description = "Highlight the shared course/board tiles on the ground",
        position = 1
    )
    default boolean showTileOverlay()
    {
        return true;
    }

    @ConfigItem(
        keyName = "showDiceOverlay",
        name = "Show dice roll overlay",
        description = "Show whose turn it is and the rolled number",
        position = 2
    )
    default boolean showDiceOverlay()
    {
        return true;
    }

    @ConfigItem(
        keyName = "showStatsOverlay",
        name = "Show stats overlay",
        description = "Show each player's coin count and gilded gnomeball count",
        position = 3
    )
    default boolean showStatsOverlay()
    {
        return true;
    }
}
