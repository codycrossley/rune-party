package gay.runescape.runeparty.overlays;

import gay.runescape.runeparty.GamePhase;
import gay.runescape.runeparty.RunePartyPlugin;

import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.PlayerComposition;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

import java.awt.Dimension;
import java.awt.Graphics2D;
import lombok.extern.slf4j.Slf4j;

/** Renders a real seated {@link Player} as an NPC model, for Brutus Attack (minigames/brutus_attack.py)
 * -- one random seated player transforms into Brutus for the whole round. A genuinely different
 * technique from every other decorative model in this codebase (CrabRaveNpcOverlay/JaddyDuelModel),
 * which all spawn a brand new {@link net.runelite.api.RuneLiteObject} rather than touch how an
 * existing Player renders.
 * <p>
 * The mechanism is {@link PlayerComposition#setTransformedNpcId(int)} -- the same public API
 * field the real game's own engine uses for quest/event transformation scenes -- followed by
 * {@link PlayerComposition#setHash()} to force a rebuild. Confirmed via research (including
 * reading willrobaggins/prop-hunt's source for a comparable multiplayer disguise plugin) that
 * cross-client visibility needs nothing new here: the server already broadcasts which
 * rsn/npcId/animations via PLAYER_TRANSFORMED (see BrutusAttackPresentation), and every seated
 * client independently applies the same mutation locally, same as every other mini-game's own
 * client-rendered state.
 * <p>
 * <b>All eight of {@code Actor}'s own animation slots must be overridden, not just idle/walk</b>
 * (Player extends Actor) -- the first version of this class only set
 * {@code setIdlePoseAnimation}/{@code setWalkAnimation}, leaving {@code runAnimation}/
 * {@code idleRotateLeft}/{@code idleRotateRight}/{@code walkRotateLeft}/{@code walkRotateRight}/
 * {@code walkRotate180} at the player's own original *human* animation ids. The instant the
 * player ran, turned while walking, or rotated in place, the client tried to play a human-skeleton
 * animation on Brutus's own completely different model, which is what the visible jank actually
 * was -- worst when running specifically because that's the single most common of those six
 * states. Brutus's own composition has no distinct run/rotate animations of his own (all -1), so
 * those six slots reuse his own idle or walk animation as the closest sane substitute, rather than
 * being left unset.
 * <p>
 * <b>{@code -1} is not a safe revert value for these fields</b> -- it's only a documented "not
 * transformed" sentinel for {@link PlayerComposition#setTransformedNpcId(int)} specifically, not a
 * general "reset to default" value for {@code Actor}'s own per-player animation fields (a real
 * human's own idle/walk/run animations are genuine equipment-driven ids, never -1). Reverting to
 * -1 there is what caused the transformed player's own rendering to stay broken after the round
 * ended. Fixed by capturing the player's own real values for all eight slots the instant before
 * overwriting them (see {@link #apply}), and restoring those exact captured values on revert (see
 * {@link #revert}) instead of guessing. */
@Slf4j
public final class PlayerTransformOverlay extends Overlay
{
    private final Client client;
    private final RunePartyPlugin plugin;

    // Which rsn (lowercase) this client has actually applied a transform to, and with what -- so
    // render() only calls setHash() once per change instead of every frame, and so clear()/the
    // "no longer active" path knows exactly who (if anyone) still needs reverting. Null means
    // nobody's currently transformed by this overlay.
    private volatile String appliedToRsn = null;
    private volatile boolean loggedApplication = false;

    // The target player's own real animation ids, captured the instant before apply() overwrites
    // them -- see this class's own doc on why -1 isn't a safe revert value for these. Only one
    // transform is ever active at a time (one Brutus per round), so plain fields (not a map
    // keyed by rsn) are enough.
    private volatile int originalIdlePose = -1;
    private volatile int originalIdleRotateLeft = -1;
    private volatile int originalIdleRotateRight = -1;
    private volatile int originalWalk = -1;
    private volatile int originalWalkRotateLeft = -1;
    private volatile int originalWalkRotateRight = -1;
    private volatile int originalWalkRotate180 = -1;
    private volatile int originalRun = -1;

    public PlayerTransformOverlay(Client client, RunePartyPlugin plugin)
    {
        this.client = client;
        this.plugin = plugin;

        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
    }

    @Override
    public Dimension render(Graphics2D g)
    {
        // isMinigameSelectionRevealed() matters here for the same reason JaddyDuelModel's own doc
        // gives: isBrutusAttackActive() alone flips true the instant MINIGAME_STARTED lands, well
        // before the client's own selection wheel has actually spun to a stop, so Brutus's target
        // could otherwise transform into his giant model ahead of the "MINIGAME!" reveal.
        if (plugin.getPhase() != GamePhase.ACTIVE || !plugin.isBrutusAttackActive() || !plugin.isMinigameSelectionRevealed())
        {
            clear();
            return null;
        }

        String targetRsn = plugin.getBrutusAttackBrutusRsn();
        if (targetRsn == null)
        {
            clear();
            return null;
        }

        // The target changed (shouldn't normally happen mid-round, but a reconnect/catch-up could
        // in principle replay a different rsn) -- revert whoever this overlay previously touched
        // before applying the new one.
        if (appliedToRsn != null && !appliedToRsn.equalsIgnoreCase(targetRsn))
        {
            revert(appliedToRsn);
        }

        if (!targetRsn.equalsIgnoreCase(appliedToRsn))
        {
            Player target = findPlayerByName(targetRsn);
            if (target == null) return null; // not in view yet -- retry next frame, same "keep trying" idiom every other spawn here uses

            int npcId = plugin.getBrutusAttackNpcId();
            int idleAnimationId = plugin.getBrutusAttackIdleAnimationId();
            int walkAnimationId = plugin.getBrutusAttackWalkAnimationId();

            apply(target, npcId, idleAnimationId, walkAnimationId);
            appliedToRsn = targetRsn;

            if (!loggedApplication)
            {
                loggedApplication = true;
                log.warn("PlayerTransformOverlay: applied npcId={} (idleAnim={}, walkAnim={}) to player '{}'",
                    npcId, idleAnimationId, walkAnimationId, targetRsn);
            }
        }

        return null;
    }

    private Player findPlayerByName(String rsn)
    {
        for (Player p : client.getPlayers())
        {
            if (p != null && rsn.equalsIgnoreCase(p.getName())) return p;
        }
        return null;
    }

    /** Captures {@code player}'s own real animation ids (see this class's own doc on why revert()
     * needs them) before overriding all eight of Actor's own animation slots -- not just idle/walk
     * -- with Brutus-compatible ones, reusing his own walk animation for the run/rotate slots he
     * has no distinct animation of his own for. */
    private void apply(Player player, int npcId, int idleAnimationId, int walkAnimationId)
    {
        PlayerComposition comp = player.getPlayerComposition();
        if (comp == null) return;

        originalIdlePose = player.getIdlePoseAnimation();
        originalIdleRotateLeft = player.getIdleRotateLeft();
        originalIdleRotateRight = player.getIdleRotateRight();
        originalWalk = player.getWalkAnimation();
        originalWalkRotateLeft = player.getWalkRotateLeft();
        originalWalkRotateRight = player.getWalkRotateRight();
        originalWalkRotate180 = player.getWalkRotate180();
        originalRun = player.getRunAnimation();

        comp.setTransformedNpcId(npcId);
        player.setIdlePoseAnimation(idleAnimationId);
        player.setIdleRotateLeft(idleAnimationId);
        player.setIdleRotateRight(idleAnimationId);
        player.setWalkAnimation(walkAnimationId);
        player.setWalkRotateLeft(walkAnimationId);
        player.setWalkRotateRight(walkAnimationId);
        player.setWalkRotate180(walkAnimationId);
        player.setRunAnimation(walkAnimationId); // Brutus has no distinct run animation -- reuse his walk cycle rather than leaving the player's own human run animation in place
        comp.setHash();
    }

    private void revert(String rsn)
    {
        Player player = findPlayerByName(rsn);
        if (player != null)
        {
            PlayerComposition comp = player.getPlayerComposition();
            if (comp != null)
            {
                comp.setTransformedNpcId(-1); // the one field where -1 really is the documented "not transformed" sentinel
                player.setIdlePoseAnimation(originalIdlePose);
                player.setIdleRotateLeft(originalIdleRotateLeft);
                player.setIdleRotateRight(originalIdleRotateRight);
                player.setWalkAnimation(originalWalk);
                player.setWalkRotateLeft(originalWalkRotateLeft);
                player.setWalkRotateRight(originalWalkRotateRight);
                player.setWalkRotate180(originalWalkRotate180);
                player.setRunAnimation(originalRun);
                comp.setHash();
                log.warn("PlayerTransformOverlay: reverted player '{}' back to their own original animations", rsn);
            }
        }
        appliedToRsn = null;
        loggedApplication = false;
    }

    /** Reverts whoever this overlay last transformed, if still resolvable -- for shutDown() and
     * render()'s own "mini-game no longer active" branch. Safe to call when nobody's transformed. */
    public void clear()
    {
        if (appliedToRsn != null) revert(appliedToRsn);
    }
}
