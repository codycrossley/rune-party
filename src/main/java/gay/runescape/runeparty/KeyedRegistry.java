package gay.runescape.runeparty;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Shared "keyed collection -- look one back up by key, falling back gracefully for an
 * unrecognized one, list them all in registration order" mechanism behind Minigames/Items' own
 * client-side registries (see ARCHITECTURE_REVIEW.md's S4). The *entities* those hold (Minigame,
 * Item) genuinely differ in shape and stay separate concrete types -- forcing them into one
 * shared interface would buy nothing and cost clarity -- but this lookup mechanism underneath
 * both was previously copy-pasted between Minigames.java and Items.java. */
public final class KeyedRegistry<T extends WheelEntry>
{
    private final Map<String, T> byKey = new LinkedHashMap<>();
    private final String fallbackKey;

    /** fallbackKey: which registered entry {@link #get} falls back to for an unrecognized or
     * missing key -- e.g. a server running something this client build was never updated to know
     * about -- rather than leaving the caller with nothing to show at all. Must itself be
     * registered via {@link #register} before any {@link #get} call. */
    public KeyedRegistry(String fallbackKey)
    {
        this.fallbackKey = fallbackKey;
    }

    public void register(T entry)
    {
        byKey.put(entry.getKey(), entry);
    }

    public T get(String key)
    {
        T entry = key != null ? byKey.get(key) : null;
        return entry != null ? entry : byKey.get(fallbackKey);
    }

    /** Every registered entry, in registration order -- stable across calls, so a selection wheel
     * (see AnnouncementOverlay#drawWheel) can assign each one a fixed segment index. */
    public List<T> all()
    {
        return Collections.unmodifiableList(new ArrayList<>(byKey.values()));
    }
}
