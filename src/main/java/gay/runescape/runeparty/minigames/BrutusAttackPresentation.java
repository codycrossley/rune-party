package gay.runescape.runeparty.minigames;

import gay.runescape.runeparty.net.ApiClient;
import gay.runescape.runeparty.net.Events;
import gay.runescape.runeparty.net.Json;
import gay.runescape.runeparty.RunePartyPlugin;

import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Brutus Attack's own client-side state. brutusRsn/npcId/idleAnimationId/walkAnimationId are
 * folded from PLAYER_TRANSFORMED (see that event's own doc), real state applied catch-up or not --
 * a catching-up client must know who's currently transformed just as much as one that was already
 * connected. roundNumber/roundStartAt are folded from BRUTUS_ROUND_STARTED, the same
 * single-stamp-off-a-round's-own-event shape RepeatAfterMePresentation's own roundStartAt uses --
 * anchors this round's own local DASH_DURATION_MS countdown. eliminatedRsns is real state too,
 * applied catch-up or not, same shape HotPotatoPresentation's own eliminatedRsns uses; see
 * PlayerOverlay's own drawBrutusAttackTargetToken, which reverts a still-listed rsn's own bullseye
 * back to a plain token the instant it's added here.
 * <p>
 * <b>No continuous position pinging, and no target ever reports its own position at all</b> --
 * see onTick, called once per game tick while this mini-game is active (RunePartyPlugin#
 * onGameTick). Two one-shot, position-free reports: confirmBrutusArrival, fired once by Brutus and
 * once by each target the instant its own client locally detects standing on its own required
 * zone's colored tile this round (a plain readiness signal -- nobody's actual position is ever
 * needed for arrival at all). confirmBrutusDash, fired once, only by Brutus, the instant his own
 * client detects landing on the target-zone tile -- carrying his own real position this time,
 * since that IS the moment that matters.
 * <p>
 * The elimination decision itself lives entirely client-side, on each TARGET's own machine, not on
 * the server and not via anyone's own recorded arrival spot (an earlier iteration tried both of
 * those and hit real problems -- a frozen arrival position going stale the instant a target moved,
 * then a move-triggered re-report that worked but still needed a target to keep talking to the
 * server at all). Brutus's own confirmBrutusDash report is broadcast back out to every other seated
 * client same as any other event (BRUTUS_DASH_REPORTED, folded below) -- each one independently
 * checks its OWN real position against it the instant it arrives (RunePartyPlugin#
 * isLocalPlayerHitByBrutusDash, same BRUTUS_HITBOX_RADIUS_TILES-wide match the server itself used
 * to do), and calls confirmBrutusElimination only if it actually finds a match. A target that
 * never gets hit and never leaves its own zone sends nothing at all past its own one arrival
 * report for the round -- one that walks back out of its own zone once the round's actually active
 * sends exactly one more, confirmLeftZone (see onTick's own doc) -- a deliberately silent
 * self-elimination, not a "HIT!".
 * confirmedArrivalThisRound/dashReportedThisRound guard Brutus's own two one-shot reports;
 * targetConfirmedArrivalThisRound guards a target's own single arrival report the same way. All
 * three reset on BRUTUS_ARRIVAL_PENDING, which the server fires right before each of the 3 rounds'
 * own gathering begins, so everyone reports fresh every round even if nobody physically moved. See
 * minigames/brutus_attack.py's own doc for the full reasoning on why none of this bothers verifying
 * anything server-side either (the stakes here -- a flat coin reward -- don't warrant guarding
 * against a spoofed report).
 * <p>
 * dashResultBannerUntil/dashResultWasHit drive AnnouncementOverlay's own "HIT!"/"MISS!" flash --
 * armed the instant either BRUTUS_PLAYER_ELIMINATED (a hit) or BRUTUS_DASH_MISSED (a miss) lands,
 * live only (never for a catching-up client replaying history), same gating RunePartyPlugin's own
 * dedicated cases elsewhere in this codebase already give a one-shot cosmetic reveal. */
public final class BrutusAttackPresentation implements MinigamePresentationFeature
{
    // Matches the server's own DASH_DURATION_SECONDS (minigames/brutus_attack.py) -- both sides
    // need to agree since this is what the client's own dash countdown banner counts down from.
    private static final long DASH_DURATION_MS = 10_000;

    // How long the "HIT!"/"MISS!" flash stays up (including its own fade-out) -- see
    // AnnouncementOverlay#renderBrutusAttackDashResult, the only reader.
    private static final long DASH_RESULT_BANNER_DURATION_MS = 1800;

    private final RunePartyPlugin plugin;

    private volatile String brutusRsn = null;
    private volatile int npcId = -1;
    private volatile int idleAnimationId = -1;
    private volatile int walkAnimationId = -1;
    private volatile int roundNumber = 0;
    private volatile long roundStartAt = 0;
    private final Set<String> eliminatedRsns = ConcurrentHashMap.newKeySet(); // lowercase rsn
    private volatile long dashResultBannerUntil = 0;
    private volatile boolean dashResultWasHit = false;

    // One-shot guards for onTick's own arrival/dash reports -- see this class's own doc. Reset on
    // BRUTUS_ARRIVAL_PENDING (every round, including round 1) and on onStarted/reset.
    private volatile boolean confirmedArrivalThisRound = false;
    private volatile boolean dashReportedThisRound = false;
    private volatile boolean targetConfirmedArrivalThisRound = false;
    // One-shot guard for a target's own "I stepped out of my zone" self-elimination report -- see
    // onTick's own doc. Reset alongside the other one-shots on BRUTUS_ARRIVAL_PENDING/onStarted/
    // reset, same as targetConfirmedArrivalThisRound.
    private volatile boolean targetLeftZoneReportedThisRound = false;
    // One-shot guard for Brutus's own "I stepped off the whole arena" self-report -- see onTick's
    // own doc. Reset alongside the other one-shots on BRUTUS_ARRIVAL_PENDING/onStarted/reset.
    private volatile boolean brutusOutOfBoundsReportedThisRound = false;
    // Whether the CURRENT round's own dash window has actually opened yet (BRUTUS_ROUND_STARTED
    // has landed since the last BRUTUS_ARRIVAL_PENDING reset) -- gates onTick's own Brutus-dash
    // check. Without this, Brutus's own dashReportedThisRound reset (on BRUTUS_ARRIVAL_PENDING,
    // which fires the instant the NEXT round's gathering begins, before either player has
    // necessarily moved at all) could immediately re-fire confirmBrutusDash on the very next tick
    // if Brutus simply never left the target zone after the previous round's own dash -- reporting
    // his stale leftover position as though it were a fresh live entry, well before the round (or
    // even its own arrival gate) had actually started. That stale report then "uses up" his
    // one-shot dash trigger for the round, so a genuine dash later in that same round's real
    // window never gets reported at all -- exactly the bug a real game surfaced (three rounds in a
    // row logged an identical BRUTUS_DASH_REPORTED landing before that round's own
    // BRUTUS_ROUND_STARTED had even fired).
    private volatile boolean dashWindowOpenThisRound = false;
    // Whether the CURRENT round's own outcome (hit or miss) has already landed -- set on either
    // BRUTUS_PLAYER_ELIMINATED or BRUTUS_DASH_MISSED, unconditionally (not gated on !catchingUp:
    // a reconnecting client needs this to reflect the round's own true current state, not just
    // whichever cosmetic reveals it personally watched happen). Reset on BRUTUS_ARRIVAL_PENDING.
    // Drives AnnouncementOverlay's own dash countdown -- see getDashEndsAt's own doc for why this
    // has to be a dedicated flag rather than reusing dashResultBannerUntil's own timing: a hit or
    // a miss-by-grace-period can resolve well before the fixed 10-second window would naturally
    // reach zero, and the countdown numeral needs to disappear the instant that happens, not keep
    // ticking down alongside (or past) the "HIT!"/"MISS!" flash.
    private volatile boolean dashResolvedThisRound = false;
    // Whether Brutus's own dash has landed for the CURRENT round -- set the instant
    // BRUTUS_DASH_REPORTED arrives, well before the round's own outcome (dashResolvedThisRound)
    // is actually known. The server itself still waits out a short grace period after the dash
    // lands for a target's own independent elimination self-report to arrive (see
    // minigames/brutus_attack.py's own BRUTUS_ELIMINATION_GRACE_SECONDS) before concluding a miss
    // -- without this flag, the countdown would keep ticking down against the fixed 10-second
    // deadline through that whole gap, well after the moment that actually mattered (Brutus
    // physically entering the zone) has already passed. Reset on BRUTUS_ARRIVAL_PENDING.
    private volatile boolean dashLandedThisRound = false;
    // Brutus's own reported landing tile for the CURRENT round -- only meaningful while
    // dashLandedThisRound is true. Lets AnnouncementOverlay/TileOverlay highlight exactly which
    // tiles counted as the "crash zone" for this dash (see RunePartyPlugin#
    // getBrutusAttackCrashZoneTiles, the only reader), the same BRUTUS_HITBOX_RADIUS_TILES-wide
    // span isLocalPlayerHitByBrutusDash itself checks against.
    private volatile WorldPoint dashLandedPosition = null;

    public BrutusAttackPresentation(RunePartyPlugin plugin)
    {
        this.plugin = plugin;
    }

    @Override
    public void apply(ApiClient.EventOut e, boolean catchingUp)
    {
        String type = e.type.toUpperCase(Locale.ROOT);
        switch (type)
        {
            case Events.PLAYER_TRANSFORMED:
            {
                String rsn = Json.requiredStr(e.payload, type, "player");
                Integer npc = Json.requiredInt(e.payload, type, "npcId");
                Integer idleAnim = Json.requiredInt(e.payload, type, "idleAnimationId");
                Integer walkAnim = Json.requiredInt(e.payload, type, "walkAnimationId");
                if (rsn == null || npc == null || idleAnim == null || walkAnim == null) return;

                brutusRsn = rsn;
                npcId = npc;
                idleAnimationId = idleAnim;
                walkAnimationId = walkAnim;
                break;
            }

            case Events.BRUTUS_ARRIVAL_PENDING:
            {
                // roundStartAt must reset here too, not just the guard flags below -- a round that
                // resolves early (a hit, or a miss caught by the grace period well before the full
                // 10-second deadline, the common case) leaves the PREVIOUS round's own
                // roundStartAt still in the future relative to now. Without this, getDashEndsAt()
                // keeps computing off that stale value until the next BRUTUS_ROUND_STARTED
                // overwrites it, so the moment the guard flags below clear, the countdown
                // reappears and counts down the previous round's leftover time -- right alongside
                // or after the HIT!/MISS! banner for the round that just ended.
                roundStartAt = 0;
                confirmedArrivalThisRound = false;
                dashReportedThisRound = false;
                targetConfirmedArrivalThisRound = false;
                targetLeftZoneReportedThisRound = false;
                brutusOutOfBoundsReportedThisRound = false;
                dashWindowOpenThisRound = false;
                dashResolvedThisRound = false;
                dashLandedThisRound = false;
                dashLandedPosition = null;
                break;
            }

            case Events.BRUTUS_ROUND_STARTED:
            {
                Integer round = Json.requiredInt(e.payload, type, "roundNumber");
                if (round == null) return;
                roundNumber = round;
                roundStartAt = System.currentTimeMillis();
                dashWindowOpenThisRound = true;
                break;
            }

            case Events.BRUTUS_DASH_REPORTED:
            {
                // Unconditional, unlike the elimination self-check below -- durable round state
                // (Brutus's own dash has landed for this round, and where), needed even for a
                // catching-up client so the countdown/crash-zone highlight don't render at all for
                // a round that's already past this point. See dashLandedThisRound's own doc for
                // why the countdown needs this signal specifically, separately from
                // dashResolvedThisRound.
                Integer x = Json.requiredInt(e.payload, type, "x");
                Integer y = Json.requiredInt(e.payload, type, "y");
                Integer plane = Json.requiredInt(e.payload, type, "plane");
                String dasher = Json.requiredStr(e.payload, type, "player");
                if (x == null || y == null || plane == null || dasher == null) return;

                dashLandedThisRound = true;
                dashLandedPosition = new WorldPoint(x, y, plane);

                // The elimination self-check itself, by contrast, is NOT applied catch-up -- a
                // reconnecting client has no business re-evaluating an already-resolved dash from
                // before it even joined, and firing a stray confirmBrutusElimination for ancient
                // history would just confuse the round it's now catching up into.
                if (catchingUp) return;

                String self = plugin.getLocalRsn();
                if (self == null || self.equalsIgnoreCase(dasher)) return; // Brutus doesn't check his own dash against himself
                if (eliminatedRsns.contains(self.toLowerCase(Locale.ROOT))) return; // already out for the rest of the round

                if (plugin.isLocalPlayerHitByBrutusDash(x, y, plane))
                {
                    confirmElimination(self);
                }
                break;
            }

            case Events.BRUTUS_PLAYER_ELIMINATED:
            {
                // Real state, applied catch-up or not -- a catching-up client must know who's
                // already eliminated so PlayerOverlay's own bullseye token reverts immediately. The
                // "HIT!" flash below is a separate, one-shot reveal, gated on !catchingUp same as
                // every other cosmetic reveal elsewhere in this codebase.
                String rsn = Json.requiredStr(e.payload, type, "player");
                if (rsn != null) eliminatedRsns.add(rsn.toLowerCase(Locale.ROOT));
                dashResolvedThisRound = true;
                if (!catchingUp)
                {
                    dashResultWasHit = true;
                    dashResultBannerUntil = System.currentTimeMillis() + DASH_RESULT_BANNER_DURATION_MS;
                }
                break;
            }

            case Events.BRUTUS_TARGET_LEFT_ZONE:
            {
                // Same eliminatedRsns fold BRUTUS_PLAYER_ELIMINATED does (real state, applied
                // catch-up or not) -- but deliberately NO "HIT!" flash and no dashResolvedThisRound
                // stamp: Brutus didn't actually do anything, so this is silent and the round simply
                // carries on. If this was the last target standing, the server ends the round (and,
                // via MINIGAME_ENDED, the whole mini-game) on its own -- see the server's own
                // brutus_target_left_zone doc -- so there's nothing extra to drive from here even
                // in that case.
                String rsn = Json.requiredStr(e.payload, type, "player");
                if (rsn != null) eliminatedRsns.add(rsn.toLowerCase(Locale.ROOT));
                break;
            }

            case Events.BRUTUS_DASH_MISSED:
            {
                dashResolvedThisRound = true;
                if (!catchingUp)
                {
                    dashResultWasHit = false;
                    dashResultBannerUntil = System.currentTimeMillis() + DASH_RESULT_BANNER_DURATION_MS;
                }
                break;
            }

            default:
                break;
        }
    }

    /** Called once per real game tick from RunePartyPlugin#onGameTick while this mini-game is
     * active. Fires at most one confirmBrutusArrival per role per round, plus (only for the
     * locally-assigned Brutus, and only once dashWindowOpenThisRound -- see that field's own doc
     * for why this can't just check dashReportedThisRound alone) one confirmBrutusDash, or --
     * failing that, if he steps off the arena entirely before ever reaching the target zone -- one
     * confirmBrutusOutOfBounds (an instant "MISS!", same as a natural timeout). A target that gets
     * hit by Brutus's own dash is checked separately, reactively, in apply's own
     * BRUTUS_DASH_REPORTED case, not here -- but a target that simply WALKS OUT of its own zone
     * once the round is actually active is checked right here, every tick: only once
     * dashWindowOpenThisRound (the round has genuinely started, not just the pre-round gather/
     * setup phase, where wandering off and back costs nothing), standing anywhere off the target
     * zone's own tiles before the round resolves is an immediate self-elimination via
     * confirmLeftZone -- deliberately a SEPARATE one-shot report from confirmElimination, not a
     * reuse of it: this silently eliminates the target (no "HIT!" flash, Brutus didn't do anything)
     * and lets the mini-game carry on, only short-circuiting the round if it happens to empty out
     * every remaining target (see the server's own brutus_target_left_zone doc). Stops a target
     * from just retreating to safety the instant the danger starts, without misreporting it as a
     * catch. */
    public void onTick(Player selfPlayer)
    {
        if (selfPlayer == null) return;
        WorldPoint pos = selfPlayer.getWorldLocation();
        if (pos == null) return;
        String self = plugin.getLocalRsn();
        if (self == null || brutusRsn == null) return;

        if (self.equalsIgnoreCase(brutusRsn))
        {
            if (!confirmedArrivalThisRound)
            {
                List<WorldPoint> ownZoneTiles = plugin.findBrutusZoneTiles();
                if (ownZoneTiles.contains(pos))
                {
                    confirmedArrivalThisRound = true;
                    confirmArrival(self);
                }
            }

            if (dashWindowOpenThisRound && !dashReportedThisRound)
            {
                List<WorldPoint> targetZoneTiles = plugin.findBrutusTargetZoneTiles();
                if (targetZoneTiles.contains(pos))
                {
                    dashReportedThisRound = true;
                    confirmDash(self, pos);
                }
                else if (!brutusOutOfBoundsReportedThisRound && !plugin.findBrutusAttackArenaTiles().contains(pos))
                {
                    brutusOutOfBoundsReportedThisRound = true;
                    confirmOutOfBounds(self);
                }
            }
        }
        else
        {
            List<WorldPoint> ownZoneTiles = plugin.findBrutusTargetZoneTiles();
            boolean inZone = ownZoneTiles.contains(pos);

            if (!targetConfirmedArrivalThisRound)
            {
                if (inZone)
                {
                    targetConfirmedArrivalThisRound = true;
                    confirmArrival(self);
                }
            }
            else if (dashWindowOpenThisRound && !inZone && !targetLeftZoneReportedThisRound && !dashResolvedThisRound
                && !eliminatedRsns.contains(self.toLowerCase(Locale.ROOT)))
            {
                targetLeftZoneReportedThisRound = true;
                confirmLeftZone(self);
            }
        }
    }

    private void confirmArrival(String self)
    {
        final String gid = plugin.gameId;
        final String token = plugin.playerToken;
        if (gid == null || token == null) return;

        plugin.submitAction("Confirm Brutus arrival",
            () -> plugin.apiClient.confirmBrutusArrival(gid, self, token),
            e -> plugin.addChatMessage("Failed to confirm your Brutus Bullet arrival: " + e.getMessage()));
    }

    private void confirmDash(String self, WorldPoint pos)
    {
        final String gid = plugin.gameId;
        final String token = plugin.playerToken;
        if (gid == null || token == null) return;

        plugin.submitAction("Confirm Brutus dash",
            () -> plugin.apiClient.confirmBrutusDash(gid, self, token, pos.getX(), pos.getY(), pos.getPlane()),
            e -> plugin.addChatMessage("Failed to confirm your Brutus Bullet dash: " + e.getMessage()));
    }

    private void confirmElimination(String self)
    {
        final String gid = plugin.gameId;
        final String token = plugin.playerToken;
        if (gid == null || token == null) return;

        plugin.submitAction("Confirm Brutus elimination",
            () -> plugin.apiClient.confirmBrutusElimination(gid, self, token),
            e -> plugin.addChatMessage("Failed to confirm your Brutus Bullet elimination: " + e.getMessage()));
    }

    private void confirmLeftZone(String self)
    {
        final String gid = plugin.gameId;
        final String token = plugin.playerToken;
        if (gid == null || token == null) return;

        plugin.submitAction("Confirm Brutus target left zone",
            () -> plugin.apiClient.confirmBrutusTargetLeftZone(gid, self, token),
            e -> plugin.addChatMessage("Failed to confirm you left your Brutus Bullet zone: " + e.getMessage()));
    }

    private void confirmOutOfBounds(String self)
    {
        final String gid = plugin.gameId;
        final String token = plugin.playerToken;
        if (gid == null || token == null) return;

        plugin.submitAction("Confirm Brutus out of bounds",
            () -> plugin.apiClient.confirmBrutusOutOfBounds(gid, self, token),
            e -> plugin.addChatMessage("Failed to confirm your Brutus Bullet out-of-bounds: " + e.getMessage()));
    }

    @Override
    public void onStarted(boolean catchingUp)
    {
        // Identical to reset()'s own body, so just delegates.
        reset();
    }

    @Override
    public void reset()
    {
        brutusRsn = null;
        npcId = -1;
        idleAnimationId = -1;
        walkAnimationId = -1;
        roundNumber = 0;
        roundStartAt = 0;
        eliminatedRsns.clear();
        confirmedArrivalThisRound = false;
        dashReportedThisRound = false;
        targetConfirmedArrivalThisRound = false;
        targetLeftZoneReportedThisRound = false;
        brutusOutOfBoundsReportedThisRound = false;
        dashWindowOpenThisRound = false;
        dashResolvedThisRound = false;
        dashLandedThisRound = false;
        dashLandedPosition = null;
        dashResultBannerUntil = 0;
        dashResultWasHit = false;
    }

    /** The rsn currently transformed into Brutus -- null before PLAYER_TRANSFORMED lands. */
    public String getBrutusRsn() { return brutusRsn; }
    public int getNpcId() { return npcId; }
    public int getIdleAnimationId() { return idleAnimationId; }
    public int getWalkAnimationId() { return walkAnimationId; }
    public int getRoundNumber() { return roundNumber; }
    /** Lowercase rsns eliminated so far this mini-game -- see PlayerOverlay's own reuse of Hot
     * Potato's skull indicator, the only reader. Only affects who Brutus still has left to catch,
     * never the end-of-round reward (see brutus_attack.py's own doc). */
    public Set<String> getEliminatedRsns() { return eliminatedRsns; }

    /** When the current round's own dash window closes -- 0 if no round is active yet. A hit or a
     * miss-by-grace-period can resolve well before this deadline is actually reached -- see
     * isDashResolvedThisRound, which AnnouncementOverlay's own countdown also checks so the
     * numeral disappears the instant the round resolves, rather than counting down regardless. */
    public long getDashEndsAt() { return roundStartAt != 0 ? roundStartAt + DASH_DURATION_MS : 0; }
    /** Whether the CURRENT round's own outcome has already landed (a hit or a miss) -- see that
     * field's own doc. */
    public boolean isDashResolvedThisRound() { return dashResolvedThisRound; }
    /** Whether Brutus's own dash has landed for the CURRENT round, whether or not its outcome is
     * known yet -- see that field's own doc for why this fires earlier than isDashResolvedThisRound. */
    public boolean isDashLandedThisRound() { return dashLandedThisRound; }
    /** Brutus's own reported landing tile for the current round -- null unless
     * isDashLandedThisRound() is true. See RunePartyPlugin#getBrutusAttackCrashZoneTiles, the only
     * reader. */
    public WorldPoint getDashLandedPosition() { return dashLandedPosition; }

    /** When the current "HIT!"/"MISS!" flash should disappear -- 0 if none is armed. See
     * AnnouncementOverlay#renderBrutusAttackDashResult, the only reader. */
    public long getDashResultBannerUntil() { return dashResultBannerUntil; }
    /** Whether the most recent dash result was a hit ("HIT!") or a miss ("MISS!") -- only
     * meaningful while getDashResultBannerUntil() hasn't passed yet. */
    public boolean isDashResultHit() { return dashResultWasHit; }
}
