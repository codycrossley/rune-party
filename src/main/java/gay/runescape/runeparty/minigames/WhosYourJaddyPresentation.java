package gay.runescape.runeparty.minigames;

import gay.runescape.runeparty.net.ApiClient;
import gay.runescape.runeparty.net.Events;
import gay.runescape.runeparty.net.Json;
import gay.runescape.runeparty.RunePartyPlugin;
import gay.runescape.runeparty.TimedBanner;

import java.util.Locale;

/** Who's Your Jaddy?'s own client-side state (server-driven duel resolution; the attack beats
 * themselves have no state of their own, see RunePartyPlugin's own dedicated JADDY_ATTACK_TRIGGERED
 * case). winningColor is real state, applied catch-up or not -- whichever of TEAM_A_COLOR/
 * TEAM_B_COLOR the surviving Jad's own zone was, or null before the duel resolves. Only the
 * celebratory banner is cosmetic-only. */
public final class WhosYourJaddyPresentation implements MinigamePresentationFeature
{
    private final RunePartyPlugin plugin;

    private volatile String winningColor = null;
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

    @Override
    public void onStarted(boolean catchingUp)
    {
        // Same reasoning as every other mini-game's own reset in onStarted -- a fresh Jaddy duel
        // hasn't resolved yet, regardless of catch-up.
        winningColor = null;
    }

    @Override
    public void reset()
    {
        resolvedBanner.reset();
        winningColor = null;
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
