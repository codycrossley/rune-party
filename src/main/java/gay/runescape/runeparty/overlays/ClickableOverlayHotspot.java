package gay.runescape.runeparty.overlays;

import net.runelite.client.input.MouseAdapter;
import net.runelite.client.input.MouseManager;

import javax.swing.SwingUtilities;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;

/** Generic click-detection for a single rectangular hotspot on an Overlay's own rendered output --
 * e.g. a mute icon drawn in the corner of a HUD. Any Overlay subclass can own one of these as a
 * plain field (composition, since an Overlay already extends RuneLite's own base class and can't
 * also extend a second one) rather than hand-rolling the mouse-listener plumbing itself every time.
 * Modeled on ChatboxDialogueOverlay's own clickAdapter/bounds/pendingClick fields -- the one other
 * place in this codebase that already does raw click-on-overlay detection -- generalized here to one
 * hotspot + one caller-polled "was I clicked" flag instead of a dialogue's own multiple option rows.
 * <p>
 * Usage: construct once (passing the plugin's already-injected MouseManager), call register()/
 * unregister() from the owning overlay's own lifecycle (same place it's added to/removed from
 * OverlayManager), call setBounds(...) every frame from render() with wherever the hotspot was
 * actually drawn that frame, and call consumeClick() once per frame -- before drawing -- to find out
 * whether it was clicked since the last check. */
public final class ClickableOverlayHotspot
{
    private final MouseManager mouseManager;

    // Touched from both the client thread (render(), every frame, via setBounds/consumeClick) and
    // the AWT event thread (clickAdapter, on a real mouse click) -- volatile rather than
    // synchronized: a click landing one frame stale just means it's checked against the previous
    // frame's bounds, never a torn read.
    private volatile Rectangle bounds = new Rectangle();
    // Deferred -- set on the AWT thread, polled and cleared on the client thread via consumeClick()
    // -- so a caller's own reaction to a click (anything beyond "was I clicked") never runs off the
    // AWT event thread, same safety idiom ChatboxDialogueOverlay's own pendingClick field exists for.
    private volatile boolean clicked = false;

    private final MouseAdapter clickAdapter = new MouseAdapter()
    {
        @Override
        public MouseEvent mousePressed(MouseEvent event)
        {
            if (!SwingUtilities.isLeftMouseButton(event)) return event;
            if (!bounds.contains(event.getPoint())) return event; // outside the hotspot -- let it pass through untouched

            clicked = true;
            // Consumed either way -- the hotspot is a dead zone for the game's own click-to-walk
            // underneath it, same as ChatboxDialogueOverlay's own box is for its own bounds.
            event.consume();
            return event;
        }
    };

    public ClickableOverlayHotspot(MouseManager mouseManager)
    {
        this.mouseManager = mouseManager;
    }

    public void register()
    {
        mouseManager.registerMouseListener(clickAdapter);
    }

    public void unregister()
    {
        mouseManager.unregisterMouseListener(clickAdapter);
    }

    /** Call every frame from render(), with wherever the hotspot was actually drawn THIS frame, in
     * absolute canvas coordinates -- the same coordinate space MouseEvent#getPoint() itself reports
     * in, NOT the overlay's own local drawing origin. For a managed OverlayPosition (anything but
     * DYNAMIC -- see Overlay's own javadoc), that means offsetting by this overlay's own
     * getBounds().getLocation(), which RuneLite's OverlayRenderer populates from the Dimension this
     * same overlay returned last frame: e.g. {@code new Rectangle(getBounds().x + localX,
     * getBounds().y + localY, w, h)}. This is the one real gotcha reusing this class elsewhere needs
     * to get right -- passing local/un-offset coordinates here silently means clicks never land.
     * Pass {@code null} (or an empty rectangle) to temporarily disable the hotspot, e.g. while
     * whatever it's attached to isn't actually showing this frame. */
    public void setBounds(Rectangle bounds)
    {
        this.bounds = bounds != null ? bounds : new Rectangle();
    }

    /** True at most once per actual click -- clears itself on read. Call once per frame from
     * render(), before drawing, so a toggled state's new appearance shows the same frame it
     * changed. */
    public boolean consumeClick()
    {
        if (!clicked) return false;
        clicked = false;
        return true;
    }
}
