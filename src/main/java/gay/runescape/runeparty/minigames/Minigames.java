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
    // PlaceholderMinigame registered 4 times under different keys, matching the server's own
    // REGISTRY (see minigames/__init__.py), alongside the real mini-games (CoinRushMinigame,
    // TrueOrFalseMinigame, ArenaMinigame) so the selection wheel has more than a few options to
    // actually cycle through. Dropping any of them would silently break that server's actual
    // rounds by falling back to a mini-game with no matching UI for what the server thinks is
    // happening.
    private static final KeyedRegistry<Minigame> REGISTRY = new KeyedRegistry<>("placeholder-1");

    static
    {
        REGISTRY.register(new PlaceholderMinigame("placeholder-1", "Placeholder Mini-Game #1"));
        REGISTRY.register(new PlaceholderMinigame("placeholder-2", "Placeholder Mini-Game #2"));
        REGISTRY.register(new PlaceholderMinigame("placeholder-3", "Placeholder Mini-Game #3"));
        REGISTRY.register(new PlaceholderMinigame("placeholder-4", "Placeholder Mini-Game #4"));
        REGISTRY.register(new CoinRushMinigame());
        REGISTRY.register(new TrueOrFalseMinigame());
        REGISTRY.register(new ArenaMinigame());
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
