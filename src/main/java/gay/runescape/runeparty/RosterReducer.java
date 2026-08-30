package gay.runescape.runeparty;

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
    private final ConcurrentHashMap<String, String> colorNumberByPlayer = new ConcurrentHashMap<>(); // host-chosen seat color while a PLAYER, cleared on removal -- see RosterEntry#colorNumber
    private final ConcurrentHashMap<String, Integer> coinsByPlayer = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> goldenGnomeCountByPlayer = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Map<String, Integer>> itemsByPlayer = new ConcurrentHashMap<>(); // itemKey -> count held
    // How many turns this player still owes skipping, from Tele Block(s) landed on them -- see
    // Events.TELE_BLOCK_APPLIED/TURN_SKIPPED below. Unlike coinsByPlayer/goldenGnomeCountByPlayer,
    // this has no counterpart in ApiClient.RosterPlayerOut/the roster REST snapshot yet (see
    // syncFromRoster/loadSnapshot) -- a client that resyncs via that snapshot rather than replaying
    // the full event log would briefly show 0 here for an already-stacked player until the next
    // TELE_BLOCK_APPLIED/TURN_SKIPPED arrives. Purely cosmetic gap (the server's own enforcement in
    // _start_next_eligible_turn never reads this client-side copy), left as a known V1 gap rather
    // than plumbing a new roster-endpoint field for it.
    private final ConcurrentHashMap<String, Integer> teleblockedByPlayer = new ConcurrentHashMap<>();
    // Canonical keys of players currently owed a Home Teleport arrival bonus -- see
    // Events.HOME_TELEPORT_ARMED/HOME_TELEPORT_ARRIVED below. Not threaded through RosterEntry/
    // snapshot() the way teleblockedByPlayer is -- nothing needs to *display* this per player, it's
    // purely read by RunePartyPlugin's own onGameTick to decide whether to auto-fire
    // confirmHomeTeleportArrival for the local player (see isHomeTeleportPending, the only reader).
    private final Set<String> homeTeleportPendingByPlayer = ConcurrentHashMap.newKeySet();

    public static final class RosterEntry
    {
        public final String rsn;
        public final RunePartyRole role;
        public final boolean online;
        public final String number;
        /** This player's host-chosen seat color while a PLAYER (see RunePartyColor#forNumber and
         * RunePartyPlugin#addToGameMenuEntry/RunePartyPanel#buildAddToGamePopup, the "Add to Game"
         * submenus that let the host pick it) -- blank once removed, freeing that color for a new
         * player (see app.py's PLAYER_LEFT handling). Unlike {@link #number} (live turn order,
         * always recomputed), this is genuinely per-instance state carried on the ROLE_ASSIGNED
         * event itself, not derived. */
        public final String colorNumber;
        public final boolean joined;
        public final int coins;
        public final int goldenGnomeCount;
        public final Map<String, Integer> items;
        /** How many turns this player still owes skipping from a Tele Block -- see
         * teleblockedByPlayer's own doc. */
        public final int teleblocked;

        public RosterEntry(String rsn, RunePartyRole role, boolean online, String number, String colorNumber, boolean joined, int coins, int goldenGnomeCount, Map<String, Integer> items, int teleblocked)
        {
            this.rsn = rsn;
            this.role = role;
            this.online = online;
            this.number = number;
            this.colorNumber = colorNumber;
            this.joined = joined;
            this.coins = coins;
            this.goldenGnomeCount = goldenGnomeCount;
            this.items = items;
            this.teleblocked = teleblocked;
        }
    }

    public RunePartyRole getRole(String canonicalRsn)
    {
        if (canonicalRsn == null) return null;
        return roleByPlayer.get(canonicalRsn.toLowerCase(Locale.ROOT));
    }

    public String getColorNumber(String canonicalRsn)
    {
        if (canonicalRsn == null) return "";
        return colorNumberByPlayer.getOrDefault(canonicalRsn.toLowerCase(Locale.ROOT), "");
    }

    public Map<String, Integer> getItems(String canonicalRsn)
    {
        if (canonicalRsn == null) return Collections.emptyMap();
        return itemsByPlayer.getOrDefault(canonicalRsn.toLowerCase(Locale.ROOT), Collections.emptyMap());
    }

    public int getTeleblocked(String canonicalRsn)
    {
        if (canonicalRsn == null) return 0;
        return teleblockedByPlayer.getOrDefault(canonicalRsn.toLowerCase(Locale.ROOT), 0);
    }

    public boolean isHomeTeleportPending(String canonicalRsn)
    {
        if (canonicalRsn == null) return false;
        return homeTeleportPendingByPlayer.contains(canonicalRsn.toLowerCase(Locale.ROOT));
    }

    /** Authoritative resync against the server's own roster (unlike apply(), which only folds
     * incremental events) -- upserts every field including role and roster membership, so it can
     * actually repair drift left behind by an incremental apply(), e.g. resurrecting a row
     * PLAYER_LEFT once deleted outright. Never removes a player who's since dropped out of the
     * server's response -- the server's own roster is append-only for the life of a game (see
     * app.py's PLAYER_LEFT handling, which demotes rather than deletes), so there's nothing to
     * prune here. */
    public void syncFromRoster(List<ApiClient.RosterPlayerOut> players)
    {
        if (players == null) return;
        for (ApiClient.RosterPlayerOut p : players)
        {
            if (p == null || p.rsn == null) continue;
            String key = canonicalKey(p.rsn);
            if (key == null) continue;
            displayNameByPlayer.putIfAbsent(key, displayName(p.rsn));
            rosterPlayers.add(key);
            RunePartyRole role = RunePartyRole.SPECTATOR;
            if (p.role != null)
            {
                try { role = RunePartyRole.valueOf(p.role.trim().toUpperCase(Locale.ROOT)); }
                catch (IllegalArgumentException ignored) {}
            }
            roleByPlayer.put(key, role);
            onlineByPlayer.put(key, p.online);
            if (p.number != null) numberByPlayer.put(key, p.number);
            if (p.colorNumber != null) colorNumberByPlayer.put(key, p.colorNumber);
            actuallyJoined.put(key, p.joined);
            coinsByPlayer.put(key, p.coins);
            goldenGnomeCountByPlayer.put(key, p.goldenGnomeCount);
            itemsByPlayer.put(key, p.items != null ? new HashMap<>(p.items) : new HashMap<>());
        }
    }

    /** Every roster entry that's actually seated and playing right now -- role == PLAYER and
     * joined == true -- unsorted (callers each want a different order, see their own .sort()
     * calls). Collapses the identical filter loop over snapshot() repeated at 8 call sites across
     * RunePartyPlugin/AnnouncementOverlay/StatsOverlay/RunePartyMapDialog. */
    public List<RosterEntry> seatedPlayers()
    {
        List<RosterEntry> out = new ArrayList<>();
        for (RosterEntry entry : snapshot())
        {
            if (entry.role == RunePartyRole.PLAYER && entry.joined) out.add(entry);
        }
        return out;
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
        colorNumberByPlayer.clear();
        coinsByPlayer.clear();
        goldenGnomeCountByPlayer.clear();
        itemsByPlayer.clear();
        teleblockedByPlayer.clear();
        homeTeleportPendingByPlayer.clear();
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
            if (p.colorNumber != null) colorNumberByPlayer.put(key, p.colorNumber);
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
            case Events.PLAYER_JOINED:
            {
                String playerRaw = Json.requiredStr(e.payload, type, "player");
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
            case Events.ROLE_ASSIGNED:
            {
                String playerRaw = Json.requiredStr(e.payload, type, "player");
                String roleRaw = Json.requiredStr(e.payload, type, "role");
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
            case Events.PLAYER_LEFT:
            {
                // Demoted to SPECTATOR, not deleted -- matches the server's own PLAYER_LEFT
                // handling (see _apply_roster_event in app.py), which deliberately keeps the
                // player's dict entry so join_order survives a later re-add via assignRole (their
                // turn-order slot stays stable relative to everyone else's, even though the actual
                // "number" is always recomputed fresh). Deleting the row here (the old behavior)
                // meant a departed player could vanish from this client's roster entirely, with
                // nothing to bring them back except a full syncFromRoster -- see that method's own
                // doc. numberByPlayer is cleared since their old turn-order slot is now stale;
                // colorNumberByPlayer is also cleared -- removing a player frees their seat color
                // for a new player (see app.py's own PLAYER_LEFT handling), rather than the old
                // behavior of preserving it for an eventual return.
                String playerRaw = Json.requiredStr(e.payload, type, "player");
                if (playerRaw == null) return;
                String key = canonicalKey(playerRaw);
                if (key == null) return;
                roleByPlayer.put(key, RunePartyRole.SPECTATOR);
                actuallyJoined.put(key, false);
                numberByPlayer.remove(key);
                colorNumberByPlayer.remove(key);
                break;
            }
            case Events.COINS_CHANGED:
            {
                String playerRaw = Json.requiredStr(e.payload, type, "player");
                if (playerRaw == null) return;
                String key = canonicalKey(playerRaw);
                if (key == null) return;
                Integer coins = Json.requiredInt(e.payload, type, "coins");
                if (coins != null) coinsByPlayer.put(key, coins);
                break;
            }
            case Events.GOLDEN_GNOME_PURCHASED:
            case Events.GOLDEN_GNOME_LOST:
            {
                // Both carry the exact same {player, goldenGnomeCount} shape -- a purchase's own
                // +1 and a Jad smash penalty's own -1 (see app.py's golden_gnome_lost) both just
                // overwrite with the new running total, identically. Without this case, a lost
                // Golden Gnome would decrement the real server-side count (see
                // GoldenGnomePresentation's own popup, which *does* handle both) while the
                // roster/stats overlay kept showing the stale pre-loss total forever, since nothing
                // else here ever re-applies GOLDEN_GNOME_LOST.
                String playerRaw = Json.requiredStr(e.payload, type, "player");
                if (playerRaw == null) return;
                String key = canonicalKey(playerRaw);
                if (key == null) return;
                Integer count = Json.requiredInt(e.payload, type, "goldenGnomeCount");
                if (count != null) goldenGnomeCountByPlayer.put(key, count);
                break;
            }
            case Events.ITEM_GRANTED:
            {
                String playerRaw = Json.requiredStr(e.payload, type, "player");
                String itemKey = Json.requiredStr(e.payload, type, "itemKey");
                if (playerRaw == null || itemKey == null) return;
                String key = canonicalKey(playerRaw);
                if (key == null) return;
                Map<String, Integer> inventory = itemsByPlayer.computeIfAbsent(key, k -> new ConcurrentHashMap<>());
                inventory.merge(itemKey, 1, Integer::sum);
                break;
            }
            case Events.ITEM_USED:
            {
                String playerRaw = Json.requiredStr(e.payload, type, "player");
                String itemKey = Json.requiredStr(e.payload, type, "itemKey");
                if (playerRaw == null || itemKey == null) return;
                String key = canonicalKey(playerRaw);
                if (key == null) return;
                Map<String, Integer> inventory = itemsByPlayer.computeIfAbsent(key, k -> new ConcurrentHashMap<>());
                inventory.compute(itemKey, (k, v) -> (v == null || v <= 1) ? null : v - 1);
                break;
            }
            case Events.TELE_BLOCK_APPLIED:
            {
                // `player` is who got blocked (the target), not who cast it -- see events.py's own
                // doc. `stacks` is the new total, not a delta, same "real total, not a delta" shape
                // COINS_CHANGED/GOLDEN_GNOME_PURCHASED already carry.
                String playerRaw = Json.requiredStr(e.payload, type, "player");
                if (playerRaw == null) return;
                String key = canonicalKey(playerRaw);
                if (key == null) return;
                Integer stacks = Json.requiredInt(e.payload, type, "stacks");
                if (stacks != null) teleblockedByPlayer.put(key, stacks);
                break;
            }
            case Events.TURN_SKIPPED:
            {
                String playerRaw = Json.requiredStr(e.payload, type, "player");
                if (playerRaw == null) return;
                String key = canonicalKey(playerRaw);
                if (key == null) return;
                Integer stacksRemaining = Json.requiredInt(e.payload, type, "stacksRemaining");
                if (stacksRemaining != null) teleblockedByPlayer.put(key, stacksRemaining);
                break;
            }
            case Events.HOME_TELEPORT_ARMED:
            {
                String playerRaw = Json.requiredStr(e.payload, type, "player");
                if (playerRaw == null) return;
                String key = canonicalKey(playerRaw);
                if (key == null) return;
                homeTeleportPendingByPlayer.add(key);
                break;
            }
            case Events.HOME_TELEPORT_ARRIVED:
            {
                String playerRaw = Json.requiredStr(e.payload, type, "player");
                if (playerRaw == null) return;
                String key = canonicalKey(playerRaw);
                if (key == null) return;
                homeTeleportPendingByPlayer.remove(key);
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
            String colorNumber = colorNumberByPlayer.getOrDefault(key, "");
            boolean joined = Boolean.TRUE.equals(actuallyJoined.get(key));
            int coins = coinsByPlayer.getOrDefault(key, 0);
            int goldenGnomeCount = goldenGnomeCountByPlayer.getOrDefault(key, 0);
            Map<String, Integer> items = new HashMap<>(itemsByPlayer.getOrDefault(key, Collections.emptyMap()));
            int teleblocked = teleblockedByPlayer.getOrDefault(key, 0);
            out.add(new RosterEntry(display, role, online, number, colorNumber, joined, coins, goldenGnomeCount, items, teleblocked));
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

}
