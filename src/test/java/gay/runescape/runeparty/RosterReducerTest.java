package gay.runescape.runeparty;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Locks in RosterReducer's PLAYER_LEFT/syncFromRoster behavior against the server's own roster
 * model (see app.py's _apply_roster_event/PLAYER_LEFT and _finalize_roster) -- a departed PLAYER
 * is demoted to SPECTATOR (never deleted outright) with their seat color freed for a new player,
 * and a roster resync can always repair whatever apply() alone left behind. See
 * ARCHITECTURE_REVIEW.md C7 for the original drift this guards against regressing (apply() used to
 * delete the row entirely, and syncFromRoster couldn't resurrect it).
 */
public class RosterReducerTest
{
    private static ApiClient.EventOut event(String type, JsonObject payload)
    {
        ApiClient.EventOut e = new ApiClient.EventOut();
        e.type = type;
        e.payload = payload;
        return e;
    }

    private static JsonObject playerLeftPayload(String rsn)
    {
        JsonObject o = new JsonObject();
        o.addProperty("player", rsn);
        return o;
    }

    private static ApiClient.RosterPlayerOut rosterPlayer(String rsn, String role, boolean joined, String number, String colorNumber)
    {
        ApiClient.RosterPlayerOut p = new ApiClient.RosterPlayerOut();
        p.rsn = rsn;
        p.role = role;
        p.joined = joined;
        p.online = false;
        p.number = number;
        p.colorNumber = colorNumber;
        p.coins = 0;
        p.goldenGnomeCount = 0;
        return p;
    }

    private static RosterReducer.RosterEntry find(RosterReducer reducer, String rsn)
    {
        for (RosterReducer.RosterEntry entry : reducer.snapshot())
        {
            if (entry.rsn.equalsIgnoreCase(rsn)) return entry;
        }
        return null;
    }

    @Test
    public void playerLeftDemotesRatherThanDeletes()
    {
        RosterReducer reducer = new RosterReducer();
        reducer.loadSnapshot(List.of(rosterPlayer("Zezima", "PLAYER", true, "2", "2")));

        reducer.apply(event("PLAYER_LEFT", playerLeftPayload("Zezima")));

        RosterReducer.RosterEntry entry = find(reducer, "Zezima");
        assertTrue("departed player must still appear in the roster, not vanish", entry != null);
        assertEquals(RunePartyRole.SPECTATOR, entry.role);
        assertFalse(entry.joined);
        assertEquals("colorNumber must be freed on removal, not survive it", "", entry.colorNumber);
        assertEquals("stale turn-order number must be cleared", "", entry.number);
    }

    @Test
    public void goldenGnomeLostDecrementsTheRosterCount()
    {
        // Regression test: RosterReducer used to only fold GOLDEN_GNOME_PURCHASED, so a Jad smash
        // penalty (GOLDEN_GNOME_LOST -- same {player, goldenGnomeCount} shape, see events.py's
        // golden_gnome_lost) correctly decremented the server's own count but the roster/stats
        // overlay kept showing the stale pre-loss total forever, since nothing ever re-applied it.
        RosterReducer reducer = new RosterReducer();
        reducer.loadSnapshot(List.of(rosterPlayer("Zezima", "PLAYER", true, "1", "1")));

        JsonObject purchased = new JsonObject();
        purchased.addProperty("player", "Zezima");
        purchased.addProperty("goldenGnomeCount", 2);
        reducer.apply(event("GOLDEN_GNOME_PURCHASED", purchased));
        assertEquals(2, find(reducer, "Zezima").goldenGnomeCount);

        JsonObject lost = new JsonObject();
        lost.addProperty("player", "Zezima");
        lost.addProperty("goldenGnomeCount", 1);
        reducer.apply(event("GOLDEN_GNOME_LOST", lost));
        assertEquals("a Jad smash loss must decrement the roster's own count, not leave it stale", 1, find(reducer, "Zezima").goldenGnomeCount);
    }

    @Test
    public void syncFromRosterResurrectsARowApplyDeletedBefore()
    {
        // Simulates the exact drift C7 flagged: some other client folded a PLAYER_LEFT under the
        // *old* delete-based behavior before this fix landed (or any other path left the row
        // missing) -- a fresh syncFromRoster must still be able to bring it back with the
        // server's authoritative role/colorNumber, not just update fields on a row that already
        // exists.
        RosterReducer reducer = new RosterReducer();

        reducer.syncFromRoster(List.of(rosterPlayer("Zezima", "SPECTATOR", false, "", "2")));

        RosterReducer.RosterEntry entry = find(reducer, "Zezima");
        assertTrue("syncFromRoster must be able to add a player it's never seen before", entry != null);
        assertEquals(RunePartyRole.SPECTATOR, entry.role);
        assertEquals("2", entry.colorNumber);
    }

    /** Mirrors RunePartyPlugin's actual live flow (see handleEvent) rather than apply() in
     * isolation: apply() alone never reconstructs "number" or "colorNumber" from the event stream
     * -- neither PLAYER_JOINED nor ROLE_ASSIGNED's payload carries them, since the server only
     * ever computes them fresh from the whole roster (see _finalize_roster's own doc). The client
     * knows this and always follows a PLAYER_JOINED/ROLE_ASSIGNED/PLAYER_LEFT with a
     * syncRosterSnapshot() call when live, which is what the syncFromRoster call below stands in
     * for -- using this same fixture's expectedRoster as the "server's /roster response", since
     * that's exactly what a real server replaying the same events would return. */
    @Test
    public void fixtureRoundTripMatchesServer() throws IOException
    {
        Fixture fixture = loadFixture();

        RosterReducer reducer = new RosterReducer();
        for (ApiClient.EventOut e : fixture.events)
        {
            reducer.apply(e);
        }
        reducer.syncFromRoster(fixture.expectedRoster);

        for (ApiClient.RosterPlayerOut expected : fixture.expectedRoster)
        {
            RosterReducer.RosterEntry entry = find(reducer, expected.rsn);
            assertNotNull(expected.rsn + " must appear in the roster", entry);
            assertEquals(expected.rsn + ": role", RunePartyRole.valueOf(expected.role), entry.role);
            assertEquals(expected.rsn + ": joined", expected.joined, entry.joined);
            assertEquals(expected.rsn + ": number", expected.number, entry.number);
            assertEquals(expected.rsn + ": colorNumber", expected.colorNumber, entry.colorNumber);
        }
    }

    private static Fixture loadFixture() throws IOException
    {
        try (InputStream in = RosterReducerTest.class.getResourceAsStream("/roster_fixture.json"))
        {
            assertNotNull("roster_fixture.json must be on the test classpath", in);
            return new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), Fixture.class);
        }
    }

    private static final class Fixture
    {
        List<ApiClient.EventOut> events;
        List<ApiClient.RosterPlayerOut> expectedRoster;
    }
}
