package gay.runescape.runeparty.minigames;

import gay.runescape.runeparty.KeyedRegistry;

import java.util.List;

/** Client-side mini-game registry -- keyed the same way the server's own registry is (see
 * app.py/minigames' REGISTRY), so RunePartyPanel can look up the right Minigame's control panel
 * for whatever key MINIGAME_STARTED's payload carried (see RunePartyPlugin#getMinigameKey). Add
 * a new mini-game by dropping a Minigame implementation in this package and registering it here.
 */
public final class Minigames
{
    // Every real mini-game, matching the server's own REGISTRY (see minigames/__init__.py) key
    // for key. Dropping any of these would silently break that server's actual rounds by falling
    // back to a mini-game with no matching UI for what the server thinks is happening.
    //
    // fallbackKey is "coin-rush" -- see KeyedRegistry#get's own doc on what this is for (a server
    // running a key this client build was never updated to know about, e.g. version skew). Was
    // "placeholder-1" back when PlaceholderMinigame's neutral "just submit a score" UI existed
    // specifically to be an honest, harmless fallback for that case; now that it's gone, this
    // falls back to showing Coin Rush's own control panel instead, which is a less obviously-a-
    // fallback degradation but still functional -- no client build has actually shipped without
    // Turf Wars (or a future minigame) registered, so this path is a defensive backstop, not a
    // live concern in practice.
    private static final KeyedRegistry<Minigame> REGISTRY = new KeyedRegistry<>("coin-rush");

    static
    {
        REGISTRY.register(new CoinRushMinigame());
        REGISTRY.register(new TrueOrFalseMinigame());
        REGISTRY.register(new ArenaMinigame());
        REGISTRY.register(new FishingContestMinigame());
        REGISTRY.register(new TurfWarsMinigame());
        REGISTRY.register(new SandwichRushMinigame());
    }

    public static Minigame get(String key)
    {
        return REGISTRY.get(key);
    }

    public static List<Minigame> all()
    {
        return REGISTRY.all();
    }

    private Minigames()
    {
    }
}
