package gay.runescape.runeparty;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("runeparty")
public interface RunePartyConfig extends Config
{
    @ConfigItem(
        keyName = "showStatsOverlay",
        name = "Show stats overlay",
        description = "Show each player's coin count and Golden Gnome count",
        position = 0
    )
    default boolean showStatsOverlay()
    {
        return true;
    }

    @ConfigItem(
        keyName = "playMinigameMusic",
        name = "Play minigame music",
        description = "Play audio clips during certain minigames (currently just Crab Rave)",
        position = 1
    )
    default boolean playMinigameMusic()
    {
        return true;
    }

    @ConfigItem(
        keyName = "devMode",
        name = "Dev Mode",
        description = "Enable local, server-free course-authoring tools in Host Controls",
        position = 2
    )
    default boolean devMode()
    {
        return false;
    }
}
