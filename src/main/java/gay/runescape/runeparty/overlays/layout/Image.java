package gay.runescape.runeparty.overlays.layout;

import java.awt.AlphaComposite;
import java.awt.Composite;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/** A fixed-size image leaf -- the same {@code AlphaComposite} draw idiom already used for wheel-
 * icon/item-icon rendering elsewhere in this codebase (e.g. CoinRushMinigame, BalloonPopMinigame),
 * wrapped as a node so it can sit alongside Text/Box in a declarative tree. Not exercised by the
 * first AnnouncementOverlay migration batch (no tier-1 banner uses an icon) -- built now so the
 * chance-space tableau's two player-color tokens can eventually move into this engine too. */
public final class Image extends Node
{
    private final BufferedImage image;
    private final int width;
    private final int height;

    private Image(BufferedImage image, int width, int height)
    {
        this.image = image;
        this.width = width;
        this.height = height;
    }

    public static Image of(BufferedImage image, int size)
    {
        return new Image(image, size, size);
    }

    public static Image of(BufferedImage image, int width, int height)
    {
        return new Image(image, width, height);
    }

    @Override
    public Dimension measure(Graphics2D g, int maxWidth)
    {
        return new Dimension(width, height);
    }

    @Override
    public void paint(Graphics2D g, int x, int y, int maxWidth, float alpha)
    {
        if (image == null) return;
        float a = Math.max(0f, Math.min(1f, alpha * ownOpacity()));
        Composite original = g.getComposite();
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, a));
        g.drawImage(image, x, y, width, height, null);
        g.setComposite(original);
    }
}
