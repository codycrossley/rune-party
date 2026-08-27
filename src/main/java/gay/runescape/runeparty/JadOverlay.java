package gay.runescape.runeparty;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.concurrent.TimeUnit;
import net.runelite.api.Animation;
import net.runelite.api.Client;
import net.runelite.api.Model;
import net.runelite.api.NPCComposition;
import net.runelite.api.Perspective;
import net.runelite.api.RuneLiteObject;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/** Spawns a real 3D model of TzTok-Jad (NPC {@link #JAD_NPC_ID}) -- not a 2D overlay -- when a
 * player's turn resolves onto a Jad Tile (see RunePartyPlugin's TILE_EFFECT handling, the only
 * caller of {@link #spawn}), facing back at whoever landed there. First real Jad Tile behavior;
 * more (an animation trigger, presumably some kind of encounter) is coming later.
 * <p>
 * Same "RuneLiteObject registered directly with the client's scene graph" approach TileOverlay's
 * Golden Gnome/Coin Trap/Coin Rush models already use, and same reasoning for re-asserting
 * LocalPoint every frame rather than caching it (a RuneLiteObject's LocalPoint goes stale across
 * region/scene reloads). Unlike those three, this isn't a diff against a live, ongoing set (see
 * SceneObjectSet) -- there's only ever at most one Jad active at a time, spawned by one specific
 * event and cleared by two others (see RunePartyPlugin's TURN_STARTED/MINIGAME_STARTED handling),
 * so it's simpler to just track that one instance directly.
 * <p>
 * {@link #requestClear} (what those two events call) doesn't despawn immediately -- a solo game
 * (or the last-to-act player in any game) advances straight to the next TURN_STARTED/
 * MINIGAME_STARTED in the very same request the landing itself resolved in, so an immediate clear
 * would make Jad despawn before it was ever actually drawn a single frame. render() instead holds
 * it up for at least {@link #MIN_VISIBLE_MS} from its own spawn() time before honoring a pending
 * clear request -- see spawnedAt/clearRequested. {@link #clear} (the immediate, non-deferred form)
 * stays for shutDown()/the phase-based safety net below, where waiting doesn't make sense (nothing
 * will call render() again to enforce it once the plugin's actually stopped). */
class JadOverlay extends Overlay
{
    private static final int JAD_NPC_ID = 3127; // TzTok-Jad, per the RuneMonk entity-viewer link this was asked from

    // How long Jad stays up once spawned, regardless of how quickly the game logic that triggered
    // it (TURN_STARTED/MINIGAME_STARTED) wants to move on -- see the class doc above.
    private static final long MIN_VISIBLE_MS = 5000;

    // Fallback if NPCComposition.getSize() isn't available yet the first frame render() runs --
    // same 5-tile-wide assumption resolveRadius() computes properly once the composition loads.
    // See RuneLiteObjectController#radius's own doc: the default (60) "works well for models the
    // size of a single tile" -- nothing in either this repo or Gnomeball has ever spawned an
    // object this large before, so this scaling (size * half a tile) is new ground, not a copied
    // pattern, and may need visual tuning once actually seen in-client.
    private static final int DEFAULT_RADIUS = 320;

    private final Client client;
    private final ClientThread clientThread;
    private final RunePartyPlugin plugin;

    private RuneLiteObject jadObject;
    private Model jadModel;

    // volatile: spawn()/requestClear()/playSmash()/clear() are all called from RunePartyPlugin's
    // handleEvent, which runs on EventSocket's own OkHttp WebSocket callback thread (see
    // EventSocket#onMessage -- there's no clientThread.invoke anywhere in that class), not
    // RuneLite's client thread -- while render() reads every one of these fields from the client
    // thread. Plain (non-volatile) fields would have no cross-thread visibility guarantee at all;
    // a write from handleEvent could take an arbitrarily long time (or never) to become visible to
    // render() on the other thread.

    // Null when no Jad is currently spawned. jadCenter is where the model itself stands (see
    // RunePartyPlugin's spawn call -- 3 tiles north of the landed tile); facing is who/where it
    // should face (the landed tile itself), recomputed into a fresh orientation every frame rather
    // than only once at spawn time, so a later spawn() call reusing the same jadObject can't leave
    // it facing a stale point.
    private volatile WorldPoint jadCenter;
    private volatile WorldPoint facing;

    // See the class doc's note on requestClear -- spawnedAt is stamped fresh by every spawn() call
    // (so a second Jad landing before the first one's own window elapsed gets its own full
    // MIN_VISIBLE_MS), and clearRequested just records that TURN_STARTED/MINIGAME_STARTED already
    // asked to despawn, for render() to actually honor once that window's up.
    private volatile long spawnedAt;
    private volatile boolean clearRequested;

    // When the object is actually allowed to start showing -- the later of "now" or
    // plugin.getTurnEffectGateUntil(), computed ONCE inside spawn(), not re-checked every frame.
    // See JadPresentation#revealAt's own doc for why this must be a fixed timestamp decided once:
    // an earlier version re-read the live gate every frame here, which self-inflicted a flashing
    // loop once anything (this render() included) started extending that same gate while showing.
    private volatile long revealAt;

    // Set by playSmash() (see JAD_SMASH_TRIGGERED handling), consumed the next frame render() finds
    // both a live jadObject and the animation resource actually loaded -- same "retry every frame
    // until it's ready, then fire once" idiom TileOverlay's own updateCoinTrapModels uses for
    // COIN_TRAP_SPRING_ANIMATION_ID.
    private volatile boolean smashPending;

    // Set by playBowThenClear() (see JAD_DISMISSED's "bowed" branch in RunePartyPlugin), consumed
    // the same "retry every frame until the animation resource is ready, then fire once" way
    // smashPending is. Once applied, render() schedules the return to idle and the actual despawn
    // itself (see bowSequenceActive below) rather than waiting on requestClear()/MIN_VISIBLE_MS --
    // this is a purely client-timed sequence with no server event marking either step as "done".
    private volatile boolean bowAcknowledgePending;

    // True from the moment playBowThenClear() is called until the scheduled clear() it arms
    // actually runs -- suppresses render()'s own clearRequested/MIN_VISIBLE_MS auto-despawn for
    // that whole window. Without this, TURN_STARTED/MINIGAME_STARTED (which always follows
    // JAD_DISMISSED in the very same event batch on the bowed path -- jad_bow's own
    // _advance_turn_or_start_minigame call) would call requestClear() before the acknowledge
    // animation ever gets a chance to play: spawnedAt is from whenever Jad originally appeared,
    // already well past MIN_VISIBLE_MS by the time bowing happens, so the very next render() frame
    // would despawn Jad immediately via the ordinary clearRequested path instead of playing the
    // animation at all.
    private volatile boolean bowSequenceActive;

    // Whether the idle loop (JAD_IDLE_ANIMATION_ID) has already been set on the current jadObject --
    // an object flag rather than "is jadObject.getAnimationController() non-null", since checking
    // that would also be true once the smash animation takes over, and this needs to distinguish
    // "already looping idle" from "never got the chance to." Reset on clear() so the next encounter
    // starts idling fresh.
    private volatile boolean idleApplied;

    JadOverlay(Client client, ClientThread clientThread, RunePartyPlugin plugin)
    {
        this.client = client;
        this.clientThread = clientThread;
        this.plugin = plugin;

        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
    }

    @Override
    public Dimension render(Graphics2D g)
    {
        if (plugin.getPhase() != GamePhase.ACTIVE)
        {
            clear();
            return null;
        }
        if (jadCenter == null) return null;
        if (System.currentTimeMillis() < revealAt) return null;

        // bowSequenceActive suppresses this while a bow-acknowledge sequence is in flight -- see
        // that field's own doc. Without it, TURN_STARTED/MINIGAME_STARTED's own requestClear()
        // (which always follows a bowed JAD_DISMISSED in the same event batch) would despawn Jad
        // here before the acknowledge animation ever got a chance to play.
        if (clearRequested && !bowSequenceActive && System.currentTimeMillis() - spawnedAt >= MIN_VISIBLE_MS)
        {
            clear();
            return null;
        }

        if (jadModel == null)
        {
            jadModel = RunePartyRender.loadNpcModel(client, JAD_NPC_ID);
            if (jadModel == null) return null; // not cached yet -- keep retrying every frame
        }

        if (jadObject == null)
        {
            jadObject = client.createRuneLiteObject();
            jadObject.setModel(jadModel);
            jadObject.setRadius(resolveRadius());
        }

        jadObject.setOrientation(RunePartyRender.orientationFacing(jadCenter, facing));

        if (smashPending)
        {
            Animation anim = client.loadAnimation(RunePartyPlugin.JAD_SMASH_ANIMATION_ID);
            if (anim != null)
            {
                jadObject.setShouldLoop(false);
                jadObject.setAnimation(anim);
                smashPending = false;
                idleApplied = true; // belt-and-suspenders: never let a late-loading idle animation clobber the smash afterward

                // Estimated duration of the smash animation itself (not measured, same caveat
                // every other animation-hold estimate here carries) -- after which Jad returns to
                // its ordinary idle loop for whatever's left of the server's own real
                // JAD_SMASH_ANIMATION_SECONDS window, rather than staying frozen on the smash's
                // last frame until JAD_DISMISSED despawns it. Unlike playBowThenClear's own tail,
                // no separate "and then clear" follow-up is scheduled here -- JAD_DISMISSED already
                // despawns this immediately on its own server-timed schedule (see RunePartyPlugin's
                // own JAD_DISMISSED handling), so there's nothing for bowSequenceActive's own
                // suppression trick to guard against on this path.
                plugin.uiTimerExec.schedule(() -> idleApplied = false, RunePartyPlugin.JAD_SMASH_ANIMATION_HOLD_MS, TimeUnit.MILLISECONDS);
            }
        }
        else if (bowAcknowledgePending)
        {
            Animation anim = client.loadAnimation(RunePartyPlugin.JAD_BOW_ACKNOWLEDGE_ANIMATION_ID);
            if (anim != null)
            {
                jadObject.setShouldLoop(false);
                jadObject.setAnimation(anim);
                bowAcknowledgePending = false;
                idleApplied = true; // belt-and-suspenders, same reasoning as the smash branch above

                // Neither step below has a server event marking it "done" -- unlike the
                // smash/penalty sequence, this whole tail is purely client-timed. Flipping
                // idleApplied back to false after the animation's own estimated duration is enough
                // to make the !idleApplied branch below reapply JAD_IDLE_ANIMATION_ID on its own
                // next frame -- same lazy "retry until loaded" idiom every animation swap here
                // already uses, so no separate pending flag is needed for the return trip. clear()
                // (and the idleApplied write) are safe to run from this executor thread directly --
                // clear() hops to the client thread itself for the actual RuneLiteObject mutation,
                // see its own doc, and idleApplied is just a volatile field write.
                plugin.uiTimerExec.schedule(() -> idleApplied = false,
                    RunePartyPlugin.JAD_BOW_ACKNOWLEDGE_ANIMATION_HOLD_MS, TimeUnit.MILLISECONDS);
                plugin.uiTimerExec.schedule(this::clear,
                    RunePartyPlugin.JAD_BOW_ACKNOWLEDGE_ANIMATION_HOLD_MS + RunePartyPlugin.JAD_BOW_ACKNOWLEDGE_IDLE_HOLD_MS,
                    TimeUnit.MILLISECONDS);
            }
        }
        else if (!idleApplied)
        {
            // Set once, not every frame -- setAnimation would otherwise restart the loop from
            // frame 0 every single tick instead of actually looping. Retried every frame until the
            // resource loads, same idiom as jadModel/the smash animation above; stops being
            // re-attempted the moment it succeeds, or the moment playSmash() takes over instead.
            Animation anim = client.loadAnimation(RunePartyPlugin.JAD_IDLE_ANIMATION_ID);
            if (anim != null)
            {
                jadObject.setShouldLoop(true);
                jadObject.setAnimation(anim);
                idleApplied = true;
            }
        }

        LocalPoint lp = LocalPoint.fromWorld(client.getTopLevelWorldView(), jadCenter);
        if (lp == null)
        {
            jadObject.setActive(false);
            return null;
        }

        jadObject.setLocation(lp, jadCenter.getPlane());
        if (!jadObject.isActive()) jadObject.setActive(true);

        return null;
    }

    /** Spawns (or relocates, if one's already up) Jad at {@code center}, facing {@code facing},
     * guaranteed to stay up for at least MIN_VISIBLE_MS regardless of any requestClear() that
     * follows (see the class doc). The actual RuneLiteObject is created/positioned lazily inside
     * render(), same "retry every frame until the model's loaded" idiom TileOverlay's own
     * updateGoldenGnomeModels uses -- held back until revealAt (see that field's own doc) even once
     * the model's ready, so the object doesn't pop into the world while some earlier effect (e.g. a
     * Golden Gnome outcome banner from the same roll) is still showing. */
    void spawn(WorldPoint center, WorldPoint facing)
    {
        this.jadCenter = center;
        this.facing = facing;
        long now = System.currentTimeMillis();
        this.spawnedAt = now;
        this.revealAt = Math.max(now, plugin.getTurnEffectGateUntil());
        this.clearRequested = false;
    }

    /** Plays the smash animation (lordmagmus_smash, RunePartyPlugin.JAD_SMASH_ANIMATION_ID) once,
     * fired by JAD_SMASH_TRIGGERED -- the bow window closed without a bow. Actually applied lazily
     * inside render() (see smashPending), same "retry until the resource's loaded" reasoning every
     * other lazy animation/model load in this codebase already follows. */
    void playSmash()
    {
        smashPending = true;
    }

    /** Plays the bow-acknowledge animation (RunePartyPlugin.JAD_BOW_ACKNOWLEDGE_ANIMATION_ID) once,
     * fired by JAD_DISMISSED's "bowed" branch -- the player bowed in time. Actually applied lazily
     * inside render() (see bowAcknowledgePending), same idiom playSmash() itself follows; once
     * applied, render() itself schedules the return to JAD_IDLE_ANIMATION_ID and the real despawn
     * after it (JAD_BOW_ACKNOWLEDGE_ANIMATION_HOLD_MS then JAD_BOW_ACKNOWLEDGE_IDLE_HOLD_MS later --
     * see bowSequenceActive's own doc for why the ordinary requestClear()/MIN_VISIBLE_MS despawn
     * path is suppressed for that whole window instead). */
    void playBowThenClear()
    {
        bowAcknowledgePending = true;
        bowSequenceActive = true;
    }

    /** Asks Jad to despawn once it's been up for at least MIN_VISIBLE_MS -- called on the next
     * TURN_STARTED or MINIGAME_STARTED (whichever fires first) by RunePartyPlugin. Safe to call
     * when nothing's spawned (render() never looks at clearRequested in that case). Not immediate
     * -- see the class doc; for an immediate despawn use {@link #clear}. */
    void requestClear()
    {
        clearRequested = true;
    }

    /** Despawns Jad immediately, if one's currently up, with no regard for MIN_VISIBLE_MS -- for
     * shutDown(), RunePartyPlugin's own JAD_DISMISSED handling, and render()'s own phase-based
     * safety net (leaving/disconnecting shouldn't strand this the way TileOverlay's own models
     * can't either), where nothing will call render() again later to honor a deferred clear anyway.
     * Safe to call when nothing's spawned.
     * <p>
     * Callers include RunePartyPlugin#handleEvent (EventSocket's own WebSocket callback thread --
     * see the field docs above), plugin.uiTimerExec's own scheduled thread (see playBowThenClear),
     * as well as render() itself (already on the client thread) -- RuneLiteObject#setActive()
     * asserts client-thread ownership the same way camera setters do (see
     * RunePartyPlugin#resetState's identical reasoning), so this dispatches there itself rather
     * than trust the caller's thread, same as Gnomeball's own CheerleaderRenderer#clear. */
    void clear()
    {
        jadCenter = null;
        facing = null;
        clearRequested = false;
        smashPending = false;
        bowAcknowledgePending = false;
        bowSequenceActive = false;
        idleApplied = false;
        revealAt = 0;
        RuneLiteObject obj = jadObject;
        if (obj != null) clientThread.invoke(() -> obj.setActive(false));
    }

    private int resolveRadius()
    {
        NPCComposition comp = client.getNpcDefinition(JAD_NPC_ID);
        return comp != null ? comp.getSize() * Perspective.LOCAL_TILE_SIZE / 2 : DEFAULT_RADIUS;
    }
}
