package gay.runescape.runeparty;

import com.google.gson.JsonObject;
import net.runelite.client.util.Text;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class RosterReducer
{
    private final ConcurrentHashMap<String, RunePartyRole> roleByPlayer = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> displayNameByPlayer = new ConcurrentHashMap<>();
    private final Set<String> rosterPlayers = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, Boolean> actuallyJoined = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> onlineByPlayer = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> numberByPlayer = new ConcurrentHashMap<>(); // turn-order position ("1", "2", ...)
    private final ConcurrentHashMap<String, Integer> coinsByPlayer = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> goldenGnomeCountByPlayer = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Map<String, Integer>> itemsByPlayer = new ConcurrentHashMap<>(); // itemKey -> count held

    public static final class RosterEntry
    {
        public final String rsn;
        public final RunePartyRole role;
        public final boolean online;
        public final String number;
        public final boolean joined;
        public final int coins;
        public final int goldenGnomeCount;
        public final Map<String, Integer> items;

        public RosterEntry(String rsn, RunePartyRole role, boolean online, String number, boolean joined, int coins, int goldenGnomeCount, Map<String, Integer> items)
        {
            this.rsn = rsn;
            this.role = role;
            this.online = online;
            this.number = number;
            this.joined = joined;
            this.coins = coins;
            this.goldenGnomeCount = goldenGnomeCount;
            this.items = items;
        }
    }

    public RunePartyRole getRole(String canonicalRsn)
    {
        if (canonicalRsn == null) return null;
        return roleByPlayer.get(canonicalRsn.toLowerCase(Locale.ROOT));
    }

    public String getNumber(String canonicalRsn)
    {
        if (canonicalRsn == null) return "";
        return numberByPlayer.getOrDefault(canonicalRsn.toLowerCase(Locale.ROOT), "");
    }

    public int getCoins(String canonicalRsn)
    {
        if (canonicalRsn == null) return 0;
        return coinsByPlayer.getOrDefault(canonicalRsn.toLowerCase(Locale.ROOT), 0);
    }

    public int getGoldenGnomeCount(String canonicalRsn)
    {
        if (canonicalRsn == null) return 0;
        return goldenGnomeCountByPlayer.getOrDefault(canonicalRsn.toLowerCase(Locale.ROOT), 0);
    }

    public Map<String, Integer> getItems(String canonicalRsn)
    {
        if (canonicalRsn == null) return Collections.emptyMap();
        return itemsByPlayer.getOrDefault(canonicalRsn.toLowerCase(Locale.ROOT), Collections.emptyMap());
    }

    public boolean isOnline(String canonicalRsn)
    {
        if (canonicalRsn == null) return false;
        return Boolean.TRUE.equals(onlineByPlayer.get(canonicalRsn.toLowerCase(Locale.ROOT)));
    }

    public void syncFromRoster(List<ApiClient.RosterPlayerOut> players)
    {
        if (players == null) return;
        for (ApiClient.RosterPlayerOut p : players)
        {
            if (p == null || p.rsn == null) continue;
            String key = p.rsn.toLowerCase(Locale.ROOT);
            onlineByPlayer.put(key, p.online);
            if (p.number != null) numberByPlayer.put(key, p.number);
            actuallyJoined.put(key, p.joined);
            coinsByPlayer.put(key, p.coins);
            goldenGnomeCountByPlayer.put(key, p.goldenGnomeCount);
            itemsByPlayer.put(key, p.items != null ? new HashMap<>(p.items) : new HashMap<>());
        }
    }

    public boolean hasJoined(String canonicalRsn)
    {
        if (canonicalRsn == null) return false;
        return rosterPlayers.contains(canonicalRsn.toLowerCase(Locale.ROOT));
    }

    public int countRole(RunePartyRole role)
    {
        int n = 0;
        for (String key : rosterPlayers)
        {
            if (roleByPlayer.getOrDefault(key, RunePartyRole.SPECTATOR) == role) n++;
        }
        return n;
    }

    public void reset()
    {
        roleByPlayer.clear();
        displayNameByPlayer.clear();
        rosterPlayers.clear();
        actuallyJoined.clear();
        onlineByPlayer.clear();
        numberByPlayer.clear();
        coinsByPlayer.clear();
        goldenGnomeCountByPlayer.clear();
        itemsByPlayer.clear();
    }

    public void loadSnapshot(List<ApiClient.RosterPlayerOut> players)
    {
        reset();
        if (players == null) return;
        for (ApiClient.RosterPlayerOut p : players)
        {
            if (p == null || p.rsn == null) continue;
            String key = canonicalKey(p.rsn);
            if (key == null) continue;
            displayNameByPlayer.put(key, displayName(p.rsn));
            rosterPlayers.add(key);
            actuallyJoined.put(key, p.joined);
            if (p.number != null) numberByPlayer.put(key, p.number);
            coinsByPlayer.put(key, p.coins);
            goldenGnomeCountByPlayer.put(key, p.goldenGnomeCount);
            itemsByPlayer.put(key, p.items != null ? new HashMap<>(p.items) : new HashMap<>());
            RunePartyRole role = RunePartyRole.SPECTATOR;
            if (p.role != null)
            {
                try { role = RunePartyRole.valueOf(p.role.trim().toUpperCase(Locale.ROOT)); }
                catch (IllegalArgumentException ignored) {}
            }
            roleByPlayer.put(key, role);
        }
    }

    public void apply(ApiClient.EventOut e)
    {
        if (e == null || e.type == null) return;
        final String type = e.type.toUpperCase(Locale.ROOT);

        switch (type)
        {
            case "PLAYER_JOINED":
            {
                String playerRaw = safeStr(e.payload, "player");
                if (playerRaw == null) return;
                String key = canonicalKey(playerRaw);
                if (key == null) return;
                displayNameByPlayer.putIfAbsent(key, displayName(playerRaw));
                rosterPlayers.add(key);
                actuallyJoined.put(key, true);
                roleByPlayer.putIfAbsent(key, RunePartyRole.SPECTATOR);
                coinsByPlayer.putIfAbsent(key, 0);
                goldenGnomeCountByPlayer.putIfAbsent(key, 0);
                itemsByPlayer.putIfAbsent(key, new ConcurrentHashMap<>());
                break;
            }
            case "ROLE_ASSIGNED":
            {
                String playerRaw = safeStr(e.payload, "player");
                String roleRaw = safeStr(e.payload, "role");
                if (playerRaw == null || roleRaw == null) return;
                String key = canonicalKey(playerRaw);
                if (key == null) return;
                displayNameByPlayer.putIfAbsent(key, displayName(playerRaw));
                rosterPlayers.add(key);
                actuallyJoined.putIfAbsent(key, false);
                try
                {
                    roleByPlayer.put(key, RunePartyRole.valueOf(roleRaw.trim().toUpperCase(Locale.ROOT)));
                }
                catch (IllegalArgumentException ignored) {}
                break;
            }
            case "PLAYER_LEFT":
            {
                String playerRaw = safeStr(e.payload, "player");
                if (playerRaw == null) return;
                String key = canonicalKey(playerRaw);
                if (key == null) return;
                roleByPlayer.remove(key);
                displayNameByPlayer.remove(key);
                rosterPlayers.remove(key);
                actuallyJoined.remove(key);
                numberByPlayer.remove(key);
                coinsByPlayer.remove(key);
                goldenGnomeCountByPlayer.remove(key);
                itemsByPlayer.remove(key);
                break;
            }
            case "COINS_CHANGED":
            {
                String playerRaw = safeStr(e.payload, "player");
                if (playerRaw == null) return;
                String key = canonicalKey(playerRaw);
                if (key == null) return;
                Integer coins = safeInt(e.payload, "coins");
                if (coins != null) coinsByPlayer.put(key, coins);
                break;
            }
            case "GOLDEN_GNOME_PURCHASED":
            {
                String playerRaw = safeStr(e.payload, "player");
                if (playerRaw == null) return;
                String key = canonicalKey(playerRaw);
                if (key == null) return;
                Integer count = safeInt(e.payload, "goldenGnomeCount");
                if (count != null) goldenGnomeCountByPlayer.put(key, count);
                break;
            }
            case "ITEM_GRANTED":
            {
                String playerRaw = safeStr(e.payload, "player");
                String itemKey = safeStr(e.payload, "itemKey");
                if (playerRaw == null || itemKey == null) return;
                String key = canonicalKey(playerRaw);
                if (key == null) return;
                Map<String, Integer> inventory = itemsByPlayer.computeIfAbsent(key, k -> new ConcurrentHashMap<>());
                inventory.merge(itemKey, 1, Integer::sum);
                break;
            }
            case "ITEM_USED":
            {
                String playerRaw = safeStr(e.payload, "player");
                String itemKey = safeStr(e.payload, "itemKey");
                if (playerRaw == null || itemKey == null) return;
                String key = canonicalKey(playerRaw);
                if (key == null) return;
                Map<String, Integer> inventory = itemsByPlayer.computeIfAbsent(key, k -> new ConcurrentHashMap<>());
                inventory.compute(itemKey, (k, v) -> (v == null || v <= 1) ? null : v - 1);
                break;
            }
        }
    }

    public List<RosterEntry> snapshot()
    {
        List<RosterEntry> out = new ArrayList<>();
        for (String key : rosterPlayers)
        {
            RunePartyRole role = roleByPlayer.getOrDefault(key, RunePartyRole.SPECTATOR);
            String display = displayNameByPlayer.getOrDefault(key, key);
            boolean online = Boolean.TRUE.equals(onlineByPlayer.get(key));
            String number = numberByPlayer.getOrDefault(key, "");
            boolean joined = Boolean.TRUE.equals(actuallyJoined.get(key));
            int coins = coinsByPlayer.getOrDefault(key, 0);
            int goldenGnomeCount = goldenGnomeCountByPlayer.getOrDefault(key, 0);
            Map<String, Integer> items = new HashMap<>(itemsByPlayer.getOrDefault(key, Collections.emptyMap()));
            out.add(new RosterEntry(display, role, online, number, joined, coins, goldenGnomeCount, items));
        }
        out.sort((a, b) ->
        {
            int oa = roleOrder(a.role);
            int ob = roleOrder(b.role);
            if (oa != ob) return Integer.compare(oa, ob);
            if (!a.number.isEmpty() && !b.number.isEmpty()) return a.number.compareTo(b.number);
            return a.rsn.compareToIgnoreCase(b.rsn);
        });
        return out;
    }

    private static int roleOrder(RunePartyRole role)
    {
        switch (role)
        {
            case PLAYER:    return 0;
            case SPECTATOR: return 1;
            default:        return 2;
        }
    }

    private static String canonicalKey(String raw)
    {
        if (raw == null) return null;
        String s = Text.removeTags(raw)
            .replaceFirst("\\s*\\(level\\s*-?\\s*\\d+\\)\\s*$", "");
        String canon = Text.toJagexName(s);
        if (canon == null || canon.isBlank()) return null;
        return canon.toLowerCase(Locale.ROOT);
    }

    private static String displayName(String raw)
    {
        if (raw == null) return null;
        String s = Text.removeTags(raw)
            .replaceFirst("\\s*\\(level\\s*-?\\s*\\d+\\)\\s*$", "");
        String canon = Text.toJagexName(s);
        return (canon != null && !canon.isBlank()) ? canon : s.trim();
    }

    private static String safeStr(JsonObject o, String key)
    {
        return (o != null && o.has(key) && !o.get(key).isJsonNull())
            ? o.get(key).getAsString()
            : null;
    }

    private static Integer safeInt(JsonObject o, String key)
    {
        try { return (o != null && o.has(key) && !o.get(key).isJsonNull()) ? o.get(key).getAsInt() : null; }
        catch (Exception ignored) { return null; }
    }
}
