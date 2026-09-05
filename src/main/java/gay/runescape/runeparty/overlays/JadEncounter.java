package gay.runescape.runeparty.overlays;

import gay.runescape.runeparty.GamePhase;
import gay.runescape.runeparty.RunePartyPlugin;
import gay.runescape.runeparty.SceneObjectSet;

import gay.runescape.runeparty.GamePhase;
import gay.runescape.runeparty.RunePartyPlugin;
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

/** Spawns a real 3D model of TzTok-Jad (NPC {@link #JAD_NPC_ID}) -- not a 2D overlay -- for the
 * full Jad Tile encounter (awaken -> idle loop while awaiting a response -> either a bow
 * acknowledgement or a smash penalty -> despawn), facing back at whoever landed there. Spawned by
 * TILE_EFFECT; the encounter itself is server-driven, so every despawn here reacts to something
 * the server has already decided, never guessing at it.
 * <p>
 * Same "RuneLiteObject registered directly with the client's scene graph" approach the models/
 * package's own GoldenGnomeModel/CoinTrapModel/CoinRushModel use for their own tile decorations.
 * Unlike those three, this isn't a diff against a live, ongoing set (see SceneObjectSet) -- there's
 * only ever at most one Jad active at a time, so it's simpler to just track that one instance
 * directly; this is also why this class, alone in models/, needs RunePartyPlugin#scheduleDelayed
 * for its own animation-hold timers rather than a plain per-frame diff. */
public class JadEncounter extends Overlay
{
    private static final int JAD_NPC_ID = 3127; // TzTok-Jad

    // Fallback if NPCComposition.getSize() isn't available yet the first frame render() runs.
    // RuneLiteObject's own default radius (60) works well for models the size of a single tile,
    // but Jad is much bigger, so this scales by the model's actual size once it's known.
    private static final int DEFAULT_RADIUS = 320;

    private final Client client;
    private final ClientThread clientThread;
    private final RunePartyPlugin plugin;

    private RuneLiteObject jadObject;
    private Model jadModel;

    // volatile: spawn()/playSmash()/playBowThenClear()/clear() are all called from RunePartyPlugin's
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

    // When the object is actually allowed to start showing -- the later of "now" or
    // plugin.getTurnEffectGateUntil(), computed once inside spawn(), not re-checked every frame.
    // See JadPresentation#revealAt's own doc for why this must be a fixed timestamp decided once.
    private volatile long revealAt;

    // Set by playSmash() (see JAD_SMASH_TRIGGERED handling), consumed the next frame render() finds
    // both a live jadObject and the animation resource actually loaded -- same "retry every frame
    // until it's ready, then fire once" idiom CoinTrapModel's own update() uses for
    // COIN_TRAP_SPRING_ANIMATION_ID.
    private volatile boolean smashPending;

    // Set by playBowThenClear() (see JAD_DISMISSED's "bowed" branch in RunePartyPlugin), consumed
    // the same "retry every frame until the animation resource is ready, then fire once" way
    // smashPending is. Once applied, render() schedules the return to idle and the actual despawn
    // itself, purely client-timed -- there's no server event marking either step as "done".
    private volatile boolean bowAcknowledgePending;

    // Whether the idle loop (JAD_IDLE_ANIMATION_ID) has already been set on the current jadObject --
    // an object flag rather than "is jadObject.getAnimationController() non-null", since checking
    // that would also be true once the smash animation takes over, and this needs to distinguish
    // "already looping idle" from "never got the chance to." Reset on clear() so the next encounter
    // starts idling fresh.
    private volatile boolean idleApplied;

    public JadEncounter(Client client, ClientThread clientThread, RunePartyPlugin plugin)
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

                // Estimated duration of the smash animation itself, after which Jad returns to its
                // ordinary idle loop rather than staying frozen on the smash's last frame until
                // JAD_DISMISSED despawns it. Unlike playBowThenClear's own tail, no separate "and
                // then clear" follow-up is scheduled here -- JAD_DISMISSED already despawns this on
                // its own server-timed schedule.
                plugin.scheduleDelayed(() -> idleApplied = false, RunePartyPlugin.JAD_SMASH_ANIMATION_HOLD_MS);
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
                // next frame, so no separate pending flag is needed for the return trip. clear()
                // hops to the client thread itself for the actual RuneLiteObject mutation, so it's
                // safe to call from this executor thread directly.
                plugin.scheduleDelayed(() -> idleApplied = false, RunePartyPlugin.JAD_BOW_ACKNOWLEDGE_ANIMATION_HOLD_MS);
                plugin.scheduleDelayed(this::clear,
                    RunePartyPlugin.JAD_BOW_ACKNOWLEDGE_ANIMATION_HOLD_MS + RunePartyPlugin.JAD_BOW_ACKNOWLEDGE_IDLE_HOLD_MS);
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

    /** Spawns (or relocates, if one's already up) Jad at {@code center}, facing {@code facing}.
     * The actual RuneLiteObject is created/positioned lazily inside render(), same "retry every
     * frame until the model's loaded" idiom GoldenGnomeModel's own update() uses -- held back
     * until revealAt (see that field's own doc) even once the model's ready, so the object doesn't
     * pop into the world while some earlier effect (e.g. a Golden Gnome outcome banner from the
     * same roll) is still showing. */
    public void spawn(WorldPoint center, WorldPoint facing)
    {
        this.jadCenter = center;
        this.facing = facing;
        long now = System.currentTimeMillis();
        this.revealAt = Math.max(now, plugin.getTurnEffectGateUntil());
    }

    /** Plays the smash animation (lordmagmus_smash, RunePartyPlugin.JAD_SMASH_ANIMATION_ID) once,
     * fired by JAD_SMASH_TRIGGERED -- the bow window closed without a bow. Actually applied lazily
     * inside render() (see smashPending), same "retry until the resource's loaded" reasoning every
     * other lazy animation/model load in this codebase already follows. */
    public void playSmash()
    {
        smashPending = true;
    }

    /** Plays the bow-acknowledge animation (RunePartyPlugin.JAD_BOW_ACKNOWLEDGE_ANIMATION_ID) once,
     * fired by JAD_DISMISSED's "bowed" branch -- the player bowed in time. Actually applied lazily
     * inside render() (see bowAcknowledgePending), same idiom playSmash() itself follows; once
     * applied, render() itself schedules the return to JAD_IDLE_ANIMATION_ID and the real despawn
     * after it (JAD_BOW_ACKNOWLEDGE_ANIMATION_HOLD_MS then JAD_BOW_ACKNOWLEDGE_IDLE_HOLD_MS later). */
    public void playBowThenClear()
    {
        bowAcknowledgePending = true;
    }

    /** Despawns Jad immediately, if one's currently up -- for shutDown(), RunePartyPlugin's own
     * JAD_DISMISSED handling, and render()'s own phase-based safety net. Safe to call when nothing's
     * spawned.
     * <p>
     * Called from more than one thread (RunePartyPlugin#handleEvent's WebSocket callback thread,
     * plugin.scheduleDelayed's scheduled thread, and render() on the client thread) --
     * RuneLiteObject#setActive() asserts client-thread ownership, so this dispatches there itself
     * rather than trust the caller's thread. */
    public void clear()
    {
        jadCenter = null;
        facing = null;
        smashPending = false;
        bowAcknowledgePending = false;
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
