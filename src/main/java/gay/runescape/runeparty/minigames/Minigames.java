package gay.runescape.runeparty.minigames;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Client-side mini-game registry -- keyed the same way the server's own registry is (see
 * app.py/minigames' REGISTRY), so RunePartyPanel can look up the right Minigame's control panel
 * for whatever key MINIGAME_STARTED's payload carried (see RunePartyPlugin#getMinigameKey). Add
 * a new mini-game by dropping a Minigame implementation in this package and registering it here.
 */
public final class Minigames
{
    // Registered 4 times under different keys, matching the server's own REGISTRY (see
    // minigames/__init__.py), purely so the selection wheel/ready-check/etc. have more than one
    // option to actually cycle through while real mini-games are still being built.
    private static final String FALLBACK_KEY = "placeholder-1";

    private static final Map<String, Minigame> REGISTRY = new LinkedHashMap<>();

    static
    {
        register(new PlaceholderMinigame("placeholder-1", "Placeholder Mini-Game #1"));
        register(new PlaceholderMinigame("placeholder-2", "Placeholder Mini-Game #2"));
        register(new PlaceholderMinigame("placeholder-3", "Placeholder Mini-Game #3"));
        register(new PlaceholderMinigame("placeholder-4", "Placeholder Mini-Game #4"));
    }

    private static void register(Minigame minigame)
    {
        REGISTRY.put(minigame.getKey(), minigame);
    }

    /** Falls back to FALLBACK_KEY for an unrecognized or missing key -- e.g. a server running a
     * mini-game this client build was never updated to know about -- rather than leaving the
     * panel with nothing to show at all. */
    public static Minigame get(String key)
    {
        Minigame minigame = key != null ? REGISTRY.get(key) : null;
        return minigame != null ? minigame : REGISTRY.get(FALLBACK_KEY);
    }

    /** Every registered mini-game, in registration order -- stable across calls, so the selection
     * spinner (see AnnouncementOverlay#renderMinigameSpinner) can assign each one a fixed wheel
     * segment index. */
    public static List<Minigame> all()
    {
        return Collections.unmodifiableList(new ArrayList<>(REGISTRY.values()));
    }

    private Minigames()
    {
    }
}
