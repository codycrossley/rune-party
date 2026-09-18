package gay.runescape.runeparty.minigames;

import gay.runescape.runeparty.net.ApiClient;
import gay.runescape.runeparty.net.Events;
import gay.runescape.runeparty.net.Json;
import gay.runescape.runeparty.RunePartyPlugin;
import gay.runescape.runeparty.TimedBanner;

import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;

import java.util.Locale;
import java.util.Objects;

/** Who's Your Jaddy?'s own client-side state (server-driven duel resolution; the attack beats
 * themselves have no state of their own, see RunePartyPlugin's own dedicated JADDY_ATTACK_TRIGGERED
 * case). winningColor is real state, applied catch-up or not -- whichever of TEAM_A_COLOR/
 * TEAM_B_COLOR the surviving Jad's own zone was, or null before the duel resolves. Only the
 * celebratory banner is cosmetic-only.
 * <p>
 * {@link #onTick} self-reports the local player's own live zone membership -- deliberately
 * event-driven rather than continuously polled, firing only when it actually changes, replacing
 * what used to be a generic per-tick position heartbeat the server polled at the duel's own
 * unpredictable resolution instant (see minigames/whos_your_jaddy.py's own doc, and DECISIONS.md's
 * project-wide call behind this move). */
public final class WhosYourJaddyPresentation implements MinigamePresentationFeature
{
    private final RunePartyPlugin plugin;

    private volatile String winningColor = null;
    // The zone color hex (TEAM_A_COLOR/TEAM_B_COLOR) actually last sent to the server via
    // report-jaddy-zone, or null for "reported standing in neither zone" -- distinct from a fresh
    // live board lookup (plugin.getLocalJaddyZoneColorHex()), which onTick compares against this on
    // every tick to decide whether anything's actually changed since the last report.
    private volatile String lastReportedZoneColor = null;
    // Payload snapshotted eagerly the instant JADDY_DUEL_RESOLVED lands (see
    // triggerResolvedBanner), not read lazily at fire time the way TurfWarsPresentation's own
    // teamAssignedBanner supplier is -- MINIGAME_ENDED (which clears winningColor via reset())
    // can land close behind this event, and armBanner's own deferred callback would otherwise
    // sometimes read winningColor AFTER that clear already ran, rendering the banner with a null
    // color for no visible reason.
    private final TimedBanner<Resolution> resolvedBanner = new TimedBanner<>();

    public WhosYourJaddyPresentation(RunePartyPlugin plugin)
    {
        this.plugin = plugin;
    }

    @Override
    public void apply(ApiClient.EventOut e, boolean catchingUp)
    {
        String type = e.type.toUpperCase(Locale.ROOT);
        if (!Events.JADDY_DUEL_RESOLVED.equals(type)) return;

        // Real state, applied catch-up or not -- a catching-up client still needs to know which
        // side won even if it missed the attack sequence and death animation (RunePartyPlugin's
        // own dedicated case handles those, skipped during catch-up).
        winningColor = Json.requiredStr(e.payload, type, "winningColor");
        if (!catchingUp)
        {
            triggerResolvedBanner();
        }
    }

    /** Called once per real game tick from RunePartyPlugin#onGameTick while Who's Your Jaddy? is
     * active. Compares a fresh live board lookup (plugin.getLocalJaddyZoneColorHex(), the exact
     * same "which color's ground is the local player standing on" check PlayerOverlay already uses
     * to recolor them) against lastReportedZoneColor, and fires a one-shot report-jaddy-zone request
     * only when the two actually differ -- never on every tick regardless of movement. Unlike
     * TurfWarsPresentation's own claim guard, this doesn't wait for the request to resolve before
     * allowing another: lastReportedZoneColor is updated optimistically the instant the request is
     * fired, and only rolled back (to whatever it was before, so the next tick retries) if that
     * request actually fails -- correct even if the local player changes zones again before the
     * first report's own response comes back, since each report always carries its own fresh
     * snapshot of the color that was true when it was sent. */
    public void onTick(Player selfPlayer)
    {
        WorldPoint pos = selfPlayer != null ? selfPlayer.getWorldLocation() : null;
        String currentColor = pos != null ? plugin.getJaddyZoneColorHex(pos) : null;
        if (Objects.equals(currentColor, lastReportedZoneColor)) return;

        String self = plugin.getLocalRsn();
        final String gid = plugin.gameId;
        final String token = plugin.playerToken;
        if (self == null || gid == null || token == null) return;

        final String previous = lastReportedZoneColor;
        lastReportedZoneColor = currentColor;
        plugin.submitAction("Report Jaddy zone",
            () -> plugin.apiClient.reportJaddyZone(gid, self, token, currentColor),
            e -> lastReportedZoneColor = previous);
    }

    @Override
    public void onStarted(boolean catchingUp)
    {
        // Same reasoning as every other mini-game's own reset in onStarted -- a fresh Jaddy duel
        // hasn't resolved yet, regardless of catch-up.
        winningColor = null;
        lastReportedZoneColor = null;
    }

    @Override
    public void reset()
    {
        resolvedBanner.reset();
        winningColor = null;
        lastReportedZoneColor = null;
    }

    /** Arms AnnouncementOverlay's Who's Your Jaddy? duel-resolved banner -- fired once, right when
     * JADDY_DUEL_RESOLVED lands, chained via armBanner behind whatever's already reserving
     * turnEffectGateUntil so it never stomps on an earlier reveal, same idiom
     * TurfWarsPresentation's own triggerTeamAssignedBanner already uses. Both halves of the payload
     * are captured right here, eagerly, rather than read lazily inside the Supplier the way
     * TurfWarsPresentation's own teamAssignedBanner is -- see resolvedBanner's own field doc for
     * why that matters here specifically -- and the local player's own zone is a live board lookup
     * that only means anything "at this instant", same reasoning minigames/whos_your_jaddy.py's own
     * payout snapshots positions before its own hold rather than after. */
    private void triggerResolvedBanner()
    {
        String winner = winningColor;
        String localZoneColor = plugin.getLocalJaddyZoneColorHex();
        Resolution resolution = new Resolution(winner, localZoneColor);
        plugin.armBanner(resolvedBanner, RunePartyPlugin.JADDY_RESOLVED_BANNER_DURATION_MS, () -> resolution, true);
    }

    public long getResolvedBannerUntil() { return resolvedBanner.until; }
    /** The winning color half of the banner's own eagerly-snapshotted payload -- see
     * resolvedBanner's own field doc for why this isn't just winningColor read directly. */
    public String getResolvedWinningColor() { return resolvedBanner.payload != null ? resolvedBanner.payload.winningColor : null; }
    /** The local player's own zone color hex at the exact instant the duel resolved, or null if
     * they weren't standing in either zone then (including every spectator) -- lets
     * AnnouncementOverlay phrase the reveal as "Your team's Jad won!"/"The other team's Jad won!"
     * for whoever picked a side, falling back to the plain color name for everyone else. */
    public String getResolvedLocalZoneColor() { return resolvedBanner.payload != null ? resolvedBanner.payload.localZoneColor : null; }

    /** resolvedBanner's own payload -- see that field's own doc for why both halves are
     * snapshotted eagerly at trigger time instead of read lazily. Never exposed outside this class
     * directly, only decomposed via getResolvedWinningColor/getResolvedLocalZoneColor, same
     * "private nested payload" shape JadPresentation's own OutcomePayload uses. */
    private static final class Resolution
    {
        final String winningColor;
        final String localZoneColor; // null if the local player wasn't standing in either zone

        Resolution(String winningColor, String localZoneColor)
        {
            this.winningColor = winningColor;
            this.localZoneColor = localZoneColor;
        }
    }
}
