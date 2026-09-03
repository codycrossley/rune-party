package gay.runescape.runeparty.minigames;

import gay.runescape.runeparty.KeyedRegistry;

import java.util.List;

/** Client-side mini-game registry, keyed to match the server's own mini-game keys, so
 * RunePartyPanel can look up the right Minigame's control panel for whatever key
 * MINIGAME_STARTED's payload carried. Add a new mini-game by dropping a Minigame implementation in
 * this package and registering it here. */
public final class Minigames
{
    // fallbackKey is "coin-rush" -- a defensive backstop for an unrecognized key (e.g. version
    // skew), not a live concern in practice.
    private static final KeyedRegistry<Minigame> REGISTRY = new KeyedRegistry<>("coin-rush");

    static
    {
        REGISTRY.register(new CoinRushMinigame());
        REGISTRY.register(new TrueOrFalseMinigame());
        REGISTRY.register(new ArenaMinigame());
        REGISTRY.register(new FishingContestMinigame());
        REGISTRY.register(new TurfWarsMinigame());
        REGISTRY.register(new SandwichRushMinigame());
        REGISTRY.register(new WhosYourJaddyMinigame());
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
