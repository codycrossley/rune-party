package gay.runescape.runeparty;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class RunePartyPluginTest
{
    public static void main(String[] args) throws Exception
    {
        ExternalPluginManager.loadBuiltin(RunePartyPlugin.class);
        RuneLite.main(args);
    }
}
