package gay.runescape.runeparty.overlays;

import gay.runescape.runeparty.GamePhase;
import gay.runescape.runeparty.RosterReducer;
import gay.runescape.runeparty.RunePartyPlugin;

import net.runelite.api.Client;
import net.runelite.api.Model;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.input.MouseAdapter;
import net.runelite.client.input.MouseManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Shared visual chrome for a one-off NPC "conversation" drawn over the chatbox -- chathead,
 * speaker name, background, wrapped body text, clickable option rows, and the click-capture/
 * reset-on-new-encounter bookkeeping every such dialogue needs. Extracted from
 * WiseOldManDialogueOverlay (the first of these) once ItemShopDialogueOverlay needed the exact
 * same chrome around a different body -- see those two for the concrete subclasses, and add a new
 * one here rather than copy-pasting either the same way ItemShopDialogueOverlay itself once was.
 * <p>
 * Drawn rather than driven through the game's real dialog interface, same reasoning the Follower
 * Buddy plugin's own FollowerDialog gives (https://github.com/MikeSpatol/follower-buddy): the real
 * dialog widgets only exist while the game itself has a conversation open, and no script exists
 * for a plugin to open one on demand. The chathead is genuine even so -- ChatheadRenderer projects
 * the NPC's own real chathead model (adapted from that same plugin's own ChatheadRenderer/
 * GouraudRasterizer/GameColourTable).
 * <p>
 * Colors and the "no drop shadow" choice below also follow that same plugin's own FollowerDialog:
 * a dark red speaker name, black body text (readable against the real chatbox sprite's light
 * backdrop -- CHATBOX_SPRITE_ID below is the exact same sprite, 1017, FollowerDialog's own
 * doc identifies as "the old api SpriteID.CHATBOX"), blue unselected options turning white on
 * hover, no shadow under any of it ("dialog text on parchment has none in the real client, and a
 * shadow visibly fattens the glyphs" -- FollowerDialog's own doc). This replaces an earlier,
 * unauthenticated palette (orange name/white body/lavender options) that read poorly against that
 * same sprite -- white body text over a light parchment backdrop is nearly illegible. Text itself
 * is drawn via RunePartyFonts#DIALOGUE_BITMAP -- the game's own real bitmap dialogue font (a
 * pre-extracted glyph dump, not a TrueType font at all), the exact same primary path Follower
 * Buddy's own FollowerDialog uses, blitting the real client's own glyph pixels rather than
 * approximating them through a system rasterizer -- falling back to RunePartyFonts#DIALOGUE_PLAIN
 * (RuneStar's own "Plain 12" TTF recreation) only if that dump fails to load; see drawCentered's
 * own doc for that fallback shape, and DIALOGUE_BITMAP's own doc for why an earlier version of
 * this file shipped with only the fallback, mistaking it for Follower Buddy's actual default.
 * Every string is horizontally centered within its own column (never left-aligned), same as
 * FollowerDialog's own drawCell -- see drawCentered/drawWrappedText/drawOptionRows' own docs.
 * <p>
 * A subclass supplies: which NPC's chathead/name to show (getNpcId/getSpeakerName -- overriding
 * loadChatheadModel too, if that NPC's own in-world model is recolored by hand, see
 * ItemShopDialogueOverlay's own override), which Presentation-backed encounterRsn/revealAt gate
 * the box open (getEncounterRsn/getRevealAt), and everything drawn below the name (drawBody) --
 * typically ending in a call to drawOptionRows (plain single-line rows), which publishes into the
 * optionBounds/optionCallbacks this class's own click handling reads. */
public abstract class ChatboxDialogueOverlay extends Overlay
{
    protected static final int CHATHEAD_SIZE = 130;
    private static final int CHATBOX_SPRITE_ID = 1017; // the real client's own chatbox background sprite
    // Real widget bounds when available (see computeBounds) -- this fallback only covers the rare
    // frame it isn't (not yet loaded, or hidden). 519x165 is the real chatbox's own fixed-layout
    // size; BUTTON_STRIP (matching Follower Buddy's own FollowerDialog#BUTTON_STRIP) is the strip
    // along its bottom edge -- the filter/report/friend-chat button row -- that both the real
    // dialog interface and this one have to stop short of rather than draw over.
    private static final int FALLBACK_WIDTH = 519;
    private static final int FALLBACK_HEIGHT = 165;
    private static final int BUTTON_STRIP = 23;
    protected static final int TEXT_LEFT = 140; // clears the chathead portrait on the left
    protected static final int TEXT_RIGHT_MARGIN = 20;
    // Tuned against RunePartyFonts#DIALOGUE_BITMAP's own real metrics (the actual game font: 15px
    // ascent, 16px line height, almost no descent) -- these are all BASELINE offsets, not "top of
    // text" ones, since every draw call in this class (drawCentered/drawBaseline) positions a
    // string by its own baseline; a line's own glyphs extend upward from that baseline by roughly
    // a full ascent(g) worth of pixels, which is why NAME_TOP_OFFSET needs real clearance above it
    // (see its own doc) and drawWrappedText's own returned offset has to add ascent(g) before a
    // caller can safely treat it as the next line's own baseline (see that method's own doc).
    // NAME_TOP_OFFSET to BODY_TOP_OFFSET is a touch over one line height; BODY_TOP_OFFSET to
    // OPTIONS_TOP_OFFSET is the same 34px two-line band this class always reserved -- see
    // drawWrappedText's own doc for that band's own role.
    //
    // NAME_TOP_OFFSET specifically needs enough room that NAME_TOP_OFFSET - ascent(g) clears the
    // box's own top border with a visible margin, not just avoids negative territory -- at the
    // font's own real ascent (15px), anything below ~18 here left a capital letter's own top
    // pixels reading as touching/overlapping the border above it.
    private static final int NAME_TOP_OFFSET = 20;
    protected static final int BODY_TOP_OFFSET = 32;
    protected static final int OPTION_ROW_HEIGHT = 18;
    protected static final int OPTIONS_TOP_OFFSET = 66;

    // See this class's own doc for why these match Follower Buddy's own FollowerDialog palette.
    private static final Color PARCHMENT = new Color(0xc8, 0xb8, 0x8f);
    private static final Color BORDER = new Color(0, 0, 0);
    protected static final Color NAME_COLOR = new Color(0x80, 0x00, 0x00);
    protected static final Color BODY_COLOR = Color.BLACK;
    protected static final Color OPTION_COLOR = new Color(0x00, 0x00, 0xff);
    protected static final Color OPTION_HOVER_COLOR = Color.WHITE;

    protected final Client client;
    protected final RunePartyPlugin plugin;
    protected final RosterReducer roster;
    private final MouseManager mouseManager;
    private final SpriteManager spriteManager;

    // All fields below are touched from both the client thread (render(), every frame) and the
    // AWT event thread (clickAdapter, on a real mouse click) -- volatile (or, for the two arrays,
    // a fresh array swapped in atomically each frame) rather than synchronized: a click landing
    // one frame stale just means it's ignored or acts on the previous frame's geometry, never a
    // torn read.
    private volatile boolean open = false;
    // The last encounterRsn actually seen (including null) -- see render()'s own doc for why local
    // UI state resets exactly when this changes, not whenever the box merely isn't open.
    private volatile String lastSeenEncounterRsn = null;
    protected volatile boolean submitted = false; // set the instant a final choice goes out
    protected volatile Rectangle bounds = new Rectangle();
    protected volatile Rectangle[] optionBounds = new Rectangle[0];
    protected volatile Runnable[] optionCallbacks = new Runnable[0];
    // Deferred click action, consumed on the very next render() call (client thread) rather than
    // run directly inside mousePressed (AWT thread) -- same safety idiom FollowerDialog's own
    // pendingAction field follows, since a subclass's own submit call has no business running off
    // the client thread.
    private volatile Runnable pendingClick;

    // This NPC's own chathead portrait, rendered once and cached forever -- its model never
    // changes, unlike a custom composed appearance that can animate its own jaw mid-line (see
    // ChatheadRenderer's own doc for why the plugin this was adapted from can't just do this).
    private BufferedImage chatheadImage;

    private final MouseAdapter clickAdapter = new MouseAdapter()
    {
        @Override
        public MouseEvent mousePressed(MouseEvent event)
        {
            if (!open || !SwingUtilities.isLeftMouseButton(event)) return event;
            if (!bounds.contains(event.getPoint())) return event; // outside the box -- let it pass through untouched

            Rectangle[] rects = optionBounds;
            Runnable[] callbacks = optionCallbacks;
            for (int i = 0; i < rects.length; i++)
            {
                if (rects[i] != null && rects[i].contains(event.getPoint()) && callbacks[i] != null)
                {
                    pendingClick = callbacks[i];
                    break;
                }
            }

            // Consumed either way -- the whole box is a dead zone for the game's own click-to-walk
            // underneath it, same as the real dialog interface is.
            event.consume();
            return event;
        }
    };

    protected ChatboxDialogueOverlay(Client client, RunePartyPlugin plugin, MouseManager mouseManager, SpriteManager spriteManager, RosterReducer roster)
    {
        this.client = client;
        this.plugin = plugin;
        this.mouseManager = mouseManager;
        this.spriteManager = spriteManager;
        this.roster = roster;

        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
    }

    public void register()
    {
        mouseManager.registerMouseListener(clickAdapter);
    }

    public void unregister()
    {
        mouseManager.unregisterMouseListener(clickAdapter);
    }

    /** The rsn currently mid-encounter for this dialogue, or null -- backed by this dialogue's own
     * Presentation class (e.g. WiseOldManPresentation#getEncounterRsn). */
    protected abstract String getEncounterRsn();

    /** When this dialogue is allowed to actually start showing -- backed by this dialogue's own
     * Presentation class (e.g. WiseOldManPresentation#getRevealAt()). */
    protected abstract long getRevealAt();

    /** This NPC's own id, for its chathead portrait here (see the paired *NpcOverlay for the same
     * id's other use, the in-world model). */
    protected abstract int getNpcId();

    /** The speaker name drawn at the top of the box. */
    protected abstract String getSpeakerName();

    /** Draws everything below the name -- the greeting/prompt text and whatever clickable rows
     * this screen needs -- and publishes their hit-boxes into optionBounds/optionCallbacks (see
     * drawOptionRows below, or a subclass's own row-drawing for anything fancier). Called only
     * once shouldBeOpen is confirmed true and background/chathead/name are already drawn. `self`
     * is the local player's own rsn (never null when this is called). */
    protected abstract void drawBody(Graphics2D g, String self);

    /** Local UI state to reset the instant a genuinely new encounter opens (or none at all
     * anymore) -- see render()'s own doc for why this is keyed off getEncounterRsn() itself
     * changing, not whenever the box merely isn't open. Base already resets submitted/
     * optionBounds/optionCallbacks/pendingClick; override to reset any subclass-specific screen/
     * selection state on top, calling super first. */
    protected void resetForNextEncounter()
    {
        submitted = false;
        pendingClick = null;
        optionBounds = new Rectangle[0];
        optionCallbacks = new Runnable[0];
    }

    @Override
    public Dimension render(Graphics2D g)
    {
        String self = plugin.getLocalRsn();
        String encounterRsn = getEncounterRsn();

        // Fresh local UI state exactly when the real encounterRsn itself changes -- a genuinely
        // new encounter opening, or none at all anymore. Deliberately NOT keyed off
        // shouldBeOpen/open below: those also go false the instant a final choice is submitted,
        // well before the server's own confirming dismissal event actually lands and changes
        // encounterRsn -- resetting `submitted` on that transition instead would flip it back to
        // false while encounterRsn is still the local player's own, reopening the box for one or
        // more stray frames until the real dismissal finally arrives.
        if (!Objects.equals(encounterRsn, lastSeenEncounterRsn))
        {
            lastSeenEncounterRsn = encounterRsn;
            resetForNextEncounter();
        }

        boolean shouldBeOpen = plugin.getPhase() == GamePhase.ACTIVE
            && self != null && self.equalsIgnoreCase(encounterRsn)
            && !submitted
            && System.currentTimeMillis() >= getRevealAt();

        open = shouldBeOpen;
        if (!shouldBeOpen)
        {
            return null;
        }

        Runnable click = pendingClick;
        pendingClick = null;
        if (click != null) click.run();
        if (submitted) return null; // the click above just submitted a final choice -- nothing left to draw this frame

        bounds = computeBounds();

        // Antialiasing OFF, not on -- see Follower Buddy's own FollowerDialog#render doc: "The
        // game draws its text with a bitmap font and no antialiasing. Leaving AA on softens and
        // visibly thickens every glyph." RunePartyFonts#DIALOGUE_PLAIN is exactly that kind of
        // font (fonthashint: false; a "pixel-perfect" recreation with no hint instructions of its
        // own, only correct at the crisp, unsmoothed rendering it was authored for) -- forcing AA
        // on it doesn't just look bolder, it visibly warps the letterforms into something that
        // barely reads as the same typeface.
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);

        // Belt-and-suspenders: every subclass's own screen is careful to lay its own content out
        // within the box (see e.g. ItemShopDialogueOverlay/WiseOldManDialogueOverlay's own
        // carousel doc), but clipping here means a future subclass that draws one line too many
        // still can't paint outside the box -- it silently gets cut off at the border instead of
        // spilling over whatever's underneath (the game world, other overlays). Restored
        // unconditionally in a finally so a subclass's own drawBody throwing never leaves the
        // client's graphics context clipped for every other overlay drawn after this one this
        // frame.
        java.awt.Shape previousClip = g.getClip();
        g.setClip(bounds);
        try
        {
            drawBackground(g);
            drawChathead(g);
            drawName(g);
            drawBody(g, self);
        }
        finally
        {
            g.setClip(previousClip);
        }

        return null;
    }

    /** The real chatbox widget's own current on-screen rectangle, minus BUTTON_STRIP off its
     * bottom edge -- adapts automatically to whichever layout (fixed/resizable, classic/modern,
     * with or without the side panels open) the player's actually using, same reasoning
     * FollowerDialog's own doc gives for checking the real widget first rather than always trusting
     * a fixed guess. Falls back to that same fixed guess only on the rare frame the real widget
     * isn't available at all (not loaded yet, or hidden -- e.g. the player's closed the chat
     * panel). */
    private Rectangle computeBounds()
    {
        Widget chat = client.getWidget(InterfaceID.CHATBOX, 0);
        Rectangle area = chat != null && !chat.isHidden()
            ? chat.getBounds()
            : new Rectangle(0, client.getCanvasHeight() - FALLBACK_HEIGHT, FALLBACK_WIDTH, FALLBACK_HEIGHT);
        return new Rectangle(area.x, area.y, area.width, Math.max(0, area.height - BUTTON_STRIP));
    }

    private void drawBackground(Graphics2D g)
    {
        BufferedImage sprite = spriteManager.getSprite(CHATBOX_SPRITE_ID, 0);
        if (sprite != null)
        {
            g.drawImage(sprite, bounds.x, bounds.y, bounds.width, bounds.height, null);
        }
        else
        {
            g.setColor(PARCHMENT);
            g.fillRect(bounds.x, bounds.y, bounds.width, bounds.height);
        }
        g.setColor(BORDER);
        g.drawRect(bounds.x, bounds.y, bounds.width - 1, bounds.height - 1);
    }

    private BufferedImage chathead()
    {
        if (chatheadImage != null) return chatheadImage;
        Model model = loadChatheadModel();
        if (model == null) return null; // not cached yet -- keep retrying every frame until it resolves
        chatheadImage = ChatheadRenderer.render(model, CHATHEAD_SIZE, CHATHEAD_SIZE);
        return chatheadImage;
    }

    /** This NPC's own chathead portrait model, ready to light/render -- default just loads
     * getNpcId()'s own natural-colored chathead resource. Override when the in-world NPC standing
     * on the tile is recolored by hand (see ItemShopDialogueOverlay's own override, and
     * ItemShopNpcOverlay's RECOLOR_FIND/RECOLOR_REPLACE doc for why) -- without this, the portrait
     * and the NPC actually standing there would show two different palettes for what's meant to be
     * the same character. */
    protected Model loadChatheadModel()
    {
        return RunePartyRender.loadNpcChatheadModel(client, getNpcId());
    }

    private void drawChathead(Graphics2D g)
    {
        BufferedImage face = chathead();
        if (face == null) return;
        g.drawImage(face, bounds.x + 4, bounds.y + 2, null);
    }

    private void drawName(Graphics2D g)
    {
        // Same font as everything else in the box, differentiated only by NAME_COLOR -- Follower
        // Buddy's own FollowerDialog draws its speaker name through the exact same drawCell/font
        // path the body text uses, no separate bold weight. Horizontally centered in the column,
        // same as every other string this class draws -- see drawWrappedText's own doc for why.
        int columnWidth = bounds.width - TEXT_LEFT - TEXT_RIGHT_MARGIN;
        drawCentered(g, getSpeakerName(), bounds.x + TEXT_LEFT, columnWidth, bounds.y + NAME_TOP_OFFSET, NAME_COLOR);
    }

    /** Draws one clickable row per (label, callback) pair, top to bottom starting at {@code top},
     * and publishes their hit-boxes/callbacks (as a single atomic array swap each) for the next
     * mouse click to test against -- see clickAdapter's own doc for why this is a fresh array
     * rather than a mutation of the previous frame's. */
    protected void drawOptionRows(Graphics2D g, List<String> labels, List<Runnable> callbacks, int top)
    {
        Rectangle[] rects = new Rectangle[labels.size()];
        net.runelite.api.Point mouse = client.getMouseCanvasPosition();
        int rowWidth = bounds.width - TEXT_LEFT - TEXT_RIGHT_MARGIN;
        int ascent = ascent(g);

        int y = top;
        for (int i = 0; i < labels.size(); i++)
        {
            // The row's own hit-box still spans the FULL column width (unrelated to where its
            // label happens to be drawn) -- clicking anywhere on an option's row selects it, not
            // just the label text itself.
            Rectangle row = new Rectangle(bounds.x + TEXT_LEFT, y - ascent, rowWidth, OPTION_ROW_HEIGHT);
            rects[i] = row;

            boolean hovered = mouse != null && row.contains(mouse.getX(), mouse.getY());
            drawCentered(g, labels.get(i), bounds.x + TEXT_LEFT, rowWidth, y, hovered ? OPTION_HOVER_COLOR : OPTION_COLOR);

            y += OPTION_ROW_HEIGHT;
        }

        optionBounds = rects;
        optionCallbacks = callbacks.toArray(new Runnable[0]);
    }

    /** This box's own current line height, in pixels -- RunePartyFonts#DIALOGUE_BITMAP's own
     * tallest glyph when loaded, else the AWT fallback's own reported line height. Every
     * dialogue's own manual layout math (WiseOldManDialogueOverlay/ItemShopDialogueOverlay's own
     * carousels included) uses this and the two below instead of a raw FontMetrics call, so both
     * rendering paths agree on spacing regardless of which one actually loaded. */
    protected int lineHeight(Graphics2D g)
    {
        GameFont bitmap = RunePartyFonts.DIALOGUE_BITMAP;
        if (bitmap != null) return bitmap.getLineHeight();
        g.setFont(RunePartyFonts.DIALOGUE_PLAIN);
        return g.getFontMetrics().getHeight();
    }

    /** This box's own current ascent, in pixels -- see lineHeight's own doc. */
    protected int ascent(Graphics2D g)
    {
        GameFont bitmap = RunePartyFonts.DIALOGUE_BITMAP;
        if (bitmap != null) return bitmap.getAscent();
        g.setFont(RunePartyFonts.DIALOGUE_PLAIN);
        return g.getFontMetrics().getAscent();
    }

    /** The exact pixel width {@code text} renders at -- see lineHeight's own doc. */
    protected int textWidth(Graphics2D g, String text)
    {
        GameFont bitmap = RunePartyFonts.DIALOGUE_BITMAP;
        if (bitmap != null) return bitmap.stringWidth(text);
        g.setFont(RunePartyFonts.DIALOGUE_PLAIN);
        return g.getFontMetrics().stringWidth(text);
    }

    /** Draws {@code text} with its baseline at {@code baselineY}, horizontally centered within
     * [columnX, columnX+columnWidth) -- RunePartyFonts#DIALOGUE_BITMAP when loaded, blitting the
     * game's own actual glyph pixels exactly as the real client does, else the AWT fallback font.
     * No shadow, either path -- see this class's own doc for why (dialog text on parchment has
     * none in the real client). */
    protected void drawCentered(Graphics2D g, String text, int columnX, int columnWidth, int baselineY, Color color)
    {
        int x = columnX + (columnWidth - textWidth(g, text)) / 2;
        GameFont bitmap = RunePartyFonts.DIALOGUE_BITMAP;
        if (bitmap != null)
        {
            bitmap.drawBaseline(g, text, x, baselineY, color.getRGB() & 0xFFFFFF);
        }
        else
        {
            g.setFont(RunePartyFonts.DIALOGUE_PLAIN);
            g.setColor(color);
            g.drawString(text, x, baselineY);
        }
    }

    // Minimum breathing room, below whichever text a screen's own body actually ends at, before
    // its first option row starts -- applied on top of the returned drawWrappedText offset (see
    // that method's own doc for why a fixed OPTIONS_TOP_OFFSET alone isn't safe: a greeting that
    // wraps to as many lines as the reserved band was sized for leaves as little as ~1px between
    // the last line's own descenders and the first option, before this gap is even added).
    private static final int BODY_OPTIONS_GAP = 6;

    // A carousel screen's own visually distinct "blank line" between sections (header -> entry,
    // entry -> Next/Back) -- see WiseOldManDialogueOverlay#drawTargetCarousel and
    // ItemShopDialogueOverlay#drawItemCarousel, the two callers. Deliberately its own constant
    // rather than reusing lineHeight(g) for this: a carousel adds this ON TOP of a
    // drawWrappedText/headerBottomOffset return value that, since that method started adding
    // ascent(g) of its own (see its doc), already carries a full ascent's worth of clearance plus
    // BODY_OPTIONS_GAP -- stacking a second FULL lineHeight on top of that double-counts the
    // clearance drawWrappedText already guarantees, and in the real chatbox's own fixed-layout
    // (fallback) size pushed a carousel's final "Next" row all the way to the very bottom edge of
    // the box with zero margin left. This value still reads as a clear paragraph break (noticeably
    // more than BODY_OPTIONS_GAP's own tight 6px) without re-adding a whole line's worth of
    // already-covered clearance.
    protected static final int CAROUSEL_BLANK_LINE_GAP = 10;

    /** The greeting/prompt text's own nominal column -- BODY_TOP_OFFSET to OPTIONS_TOP_OFFSET
     * below the box's top edge -- vertically centers its wrapped block within this exact band
     * when it fits, mirroring Follower Buddy's own FollowerDialog#BODY_HEIGHT ("the game genuinely
     * spaces a two-line message wider than a three-line one" -- see that class's own
     * measuredLineHeight doc); we use this font's own natural line height throughout rather than
     * that plugin's own per-line-count measured table, since that table was measured against the
     * real client's own bitmap font metrics, not this bundled TTF's.
     *
     * Returns the offset (below the box's top edge, same units as OPTIONS_TOP_OFFSET -- NOT an
     * absolute screen y) a caller should use as the BASELINE of whatever comes next -- every call
     * site uses {@code Math.max(OPTIONS_TOP_OFFSET, thisReturnValue)} as where its own
     * options/entries actually start, rather than assuming the nominal OPTIONS_TOP_OFFSET always
     * has room. Critically, this is {@code blockTopOffset + blockHeight + ascent(g) +
     * BODY_OPTIONS_GAP}, NOT just {@code blockTopOffset + blockHeight + BODY_OPTIONS_GAP} the way
     * an earlier version of this method computed it -- every caller (drawOptionRows, and each
     * carousel's own manual layout) treats the value it gets back as a BASELINE to draw the next
     * line AT, and a baseline's own glyphs extend UPWARD from it by a full ascent(g) worth of
     * pixels, not downward. Omitting that ascent left the next line's own ink starting inside the
     * body text's own line box instead of below it -- reading as visibly overlapping (a name/
     * option row's own cap-height letters poking up into the line above) even though the two
     * baselines themselves were several pixels apart, exactly the class of bug this method's own
     * BODY_OPTIONS_GAP was originally meant to prevent (see git history -- that gap alone wasn't
     * enough once the real bitmap font's own tall ascent/thin descent ratio replaced the AWT
     * fallback this was first tuned against). */
    protected int drawWrappedText(Graphics2D g, String text, int x, int width)
    {
        List<String> lines = wrap(g, text, width);
        int lineHeight = lineHeight(g);
        int bandHeight = OPTIONS_TOP_OFFSET - BODY_TOP_OFFSET;
        int blockHeight = lines.size() * lineHeight;
        int blockTopOffset = BODY_TOP_OFFSET + Math.max(0, (bandHeight - blockHeight) / 2);

        int y = bounds.y + blockTopOffset + ascent(g);
        for (String line : lines)
        {
            // Horizontally centered in the same column every option/name row centers in --
            // see drawOptionRows/drawName's own doc for why (Follower Buddy's own FollowerDialog
            // centers every string it draws, name/body/options alike, rather than left-aligning
            // any of them).
            drawCentered(g, line, x, width, y, BODY_COLOR);
            y += lineHeight;
        }

        return blockTopOffset + blockHeight + ascent(g) + BODY_OPTIONS_GAP;
    }

    // Memoizes wrap() below, keyed on the exact (text, maxWidth) pair its own result depends on --
    // textWidth's own font (RunePartyFonts.DIALOGUE_BITMAP/DIALOGUE_PLAIN) is a fixed, load-once
    // resource, never varying frame to frame, so wrap()'s output is a pure function of just those
    // two inputs. drawWrappedText calls this on every one of the ~50 frames/sec render() runs a
    // dialogue box stays open, but the body text it wraps is static for that whole screen -- only
    // changing when the encounter/carousel page actually advances -- so this was re-running a full
    // greedy word-wrap (re-measuring every candidate substring via textWidth) dozens of times a
    // second for byte-identical output.
    private String lastWrapText = null;
    private int lastWrapWidth = -1;
    private List<String> lastWrapResult = Collections.emptyList();

    /** Plain greedy word-wrap against {@code maxWidth} -- good enough for the short, fixed lines
     * these dialogues ever show. Measured via textWidth (the real bitmap font's own per-character
     * advances when loaded, so wrap decisions agree with what actually gets drawn -- not just an
     * AWT approximation the way this always measured before the bitmap font was ported in). */
    private List<String> wrap(Graphics2D g, String text, int maxWidth)
    {
        if (maxWidth == lastWrapWidth && Objects.equals(text, lastWrapText)) return lastWrapResult;

        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" "))
        {
            String candidate = line.length() == 0 ? word : line + " " + word;
            if (textWidth(g, candidate) > maxWidth && line.length() > 0)
            {
                lines.add(line.toString());
                line = new StringBuilder(word);
            }
            else
            {
                line = new StringBuilder(candidate);
            }
        }
        if (line.length() > 0) lines.add(line.toString());

        lastWrapText = text;
        lastWrapWidth = maxWidth;
        lastWrapResult = lines;
        return lines;
    }
}
