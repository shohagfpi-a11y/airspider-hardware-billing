package com.hardwarebilling.config;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.AffineTransform;
import java.awt.geom.CubicCurve2D;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Random;

/**
 * DEVELOPMENT ONLY: draws a realistic-looking round company stamp and a handwritten-style signature
 * (transparent PNGs) for the demo company. Real installations upload their own images in Company settings.
 */
public final class DemoImages {
    private DemoImages() {}

    private static final Color INK = new Color(28, 58, 160, 215);

    public static byte[] stamp(String topText, String bottomText, String centerTop, String centerBottom) {
        int size = 520;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        double cx = size / 2.0, cy = size / 2.0;
        g.rotate(Math.toRadians(-14), cx, cy);                 // stamps are never perfectly straight
        g.setColor(INK);

        g.setStroke(new BasicStroke(10f));
        g.draw(new Ellipse2D.Double(cx - 240, cy - 240, 480, 480));
        g.setStroke(new BasicStroke(3f));
        g.draw(new Ellipse2D.Double(cx - 222, cy - 222, 444, 444));
        g.draw(new Ellipse2D.Double(cx - 150, cy - 150, 300, 300));

        // ring texts: shrink until the top text fits in 160 degrees, then put the stars in the gaps
        double radius = 186;
        Font top = fit(g, topText, radius, Math.toRadians(160), 30);
        Font bottom = fit(g, bottomText, radius, Math.toRadians(110), 28);
        double topSpan = arcText(g, topText, top, cx, cy, radius, true);
        double bottomSpan = arcText(g, bottomText, bottom, cx, cy, radius, false);
        double topEnd = -Math.PI / 2 + topSpan / 2, bottomStart = Math.PI / 2 - bottomSpan / 2;
        double gapMid = (topEnd + bottomStart) / 2;           // right-hand gap; the left one is its mirror
        star(g, cx + radius * Math.cos(gapMid), cy + radius * Math.sin(gapMid) + 2, 11);
        star(g, cx - radius * Math.cos(gapMid), cy + radius * Math.sin(gapMid) + 2, 11);

        // centre
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 26));
        centered(g, centerTop, cx, cy - 40);
        g.setStroke(new BasicStroke(3f));
        g.drawLine((int) cx - 110, (int) cy - 20, (int) cx + 110, (int) cy - 20);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 30));
        centered(g, "U.A.E.", cx, cy + 22);
        g.drawLine((int) cx - 110, (int) cy + 38, (int) cx + 110, (int) cy + 38);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 20));
        centered(g, centerBottom, cx, cy + 76);
        g.dispose();

        // ink texture: knock out random specks so it looks pressed by hand
        Random r = new Random(7);
        for (int i = 0; i < 9000; i++) {
            int x = r.nextInt(size), y = r.nextInt(size);
            int argb = img.getRGB(x, y);
            int a = argb >>> 24;
            if (a > 0) img.setRGB(x, y, ((int) (a * (0.25 + r.nextDouble() * 0.5)) << 24) | (argb & 0xFFFFFF));
        }
        return png(img);
    }

    /**
     * Company logo: rounded mark (water drop + lightning bolt for "sanitary & electricity") and a two-line wordmark.
     */
    public static byte[] logo(String line1, String line2) {
        Font f1 = new Font(Font.SANS_SERIF, Font.BOLD, 66);
        Font f2 = new Font(Font.SANS_SERIF, Font.BOLD, 30);
        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D pg = probe.createGraphics();
        int w1 = pg.getFontMetrics(f1).stringWidth(line1);
        int w2 = pg.getFontMetrics(f2).stringWidth(line2) + line2.length() * 6;   // letter spacing
        pg.dispose();
        int width = 250 + Math.max(w1, w2) + 20, height = 240;
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

        // mark
        g.setPaint(new GradientPaint(10, 20, new Color(29, 78, 216), 210, 220, new Color(14, 165, 233)));
        g.fill(new java.awt.geom.RoundRectangle2D.Double(10, 20, 200, 200, 52, 52));
        java.awt.geom.Path2D drop = new java.awt.geom.Path2D.Double();
        drop.moveTo(110, 46);
        drop.curveTo(140, 92, 168, 124, 168, 152);
        drop.curveTo(168, 186, 142, 206, 110, 206);
        drop.curveTo(78, 206, 52, 186, 52, 152);
        drop.curveTo(52, 124, 80, 92, 110, 46);
        drop.closePath();
        g.setColor(Color.WHITE);
        g.fill(drop);
        Polygon bolt = new Polygon(new int[]{120, 86, 108, 96, 136, 114, 126}, new int[]{92, 152, 152, 194, 128, 128, 92}, 7);
        g.setColor(new Color(245, 158, 11));
        g.fill(bolt);

        // wordmark
        g.setFont(f1);
        g.setColor(new Color(15, 42, 107));
        g.drawString(line1, 245, 122);
        g.setFont(f2);
        g.setColor(new Color(2, 132, 199));
        int x = 248;
        for (char ch : line2.toCharArray()) {
            g.drawString(String.valueOf(ch), x, 178);
            x += g.getFontMetrics().charWidth(ch) + 6;
        }
        g.dispose();
        return png(img);
    }

    public static byte[] signature() {
        BufferedImage img = new BufferedImage(520, 180, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setColor(new Color(16, 32, 96, 235));
        g.setStroke(new BasicStroke(4.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        // a flowing autograph built from cubic curves
        double[][] c = {
                {30, 120, 40, 40, 90, 30, 95, 95}, {95, 95, 100, 150, 60, 150, 70, 100}, {70, 100, 85, 40, 140, 60, 130, 110},
                {130, 110, 125, 140, 160, 120, 175, 85}, {175, 85, 185, 60, 200, 130, 215, 100}, {215, 100, 230, 70, 240, 120, 260, 95},
                {260, 95, 280, 60, 290, 140, 310, 90}, {310, 90, 330, 40, 345, 130, 360, 100}, {360, 100, 380, 70, 400, 110, 430, 80},
                {430, 80, 450, 60, 470, 90, 490, 70}};
        for (double[] p : c) g.draw(new CubicCurve2D.Double(p[0], p[1], p[2], p[3], p[4], p[5], p[6], p[7]));
        g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new CubicCurve2D.Double(60, 140, 200, 120, 330, 150, 480, 118));   // underline flourish
        g.dispose();
        return png(img);
    }

    private static Font fit(Graphics2D g, String text, double radius, double maxSpan, int startSize) {
        for (int size = startSize; size > 12; size--) {
            Font f = new Font(Font.SANS_SERIF, Font.BOLD, size);
            if (f.getStringBounds(text, g.getFontRenderContext()).getWidth() / radius <= maxSpan) return f;
        }
        return new Font(Font.SANS_SERIF, Font.BOLD, 12);
    }

    /** Draws text along the circle and returns the angle it covers. */
    private static double arcText(Graphics2D g, String text, Font font, double cx, double cy, double radius, boolean top) {
        FontRenderContext frc = g.getFontRenderContext();
        GlyphVector gv = font.createGlyphVector(frc, text);
        double total = gv.getLogicalBounds().getWidth();
        double angleSpan = total / radius;
        double start = top ? -Math.PI / 2 - angleSpan / 2 : Math.PI / 2 + angleSpan / 2;
        double x = 0;
        for (int i = 0; i < text.length(); i++) {
            String ch = String.valueOf(text.charAt(i));
            double w = font.getStringBounds(ch, frc).getWidth();
            double mid = x + w / 2;
            double ang = top ? start + mid / radius : start - mid / radius;
            AffineTransform old = g.getTransform();
            g.translate(cx + radius * Math.cos(ang), cy + radius * Math.sin(ang));
            g.rotate(top ? ang + Math.PI / 2 : ang - Math.PI / 2);
            g.setFont(font);
            int ascentShift = top ? 10 : 10;
            g.drawString(ch, (float) (-w / 2), ascentShift);
            g.setTransform(old);
            x += w;
        }
        return angleSpan;
    }

    private static void star(Graphics2D g, double x, double y, double r) {
        Polygon p = new Polygon();
        for (int i = 0; i < 10; i++) {
            double rr = i % 2 == 0 ? r : r * 0.45;
            double a = -Math.PI / 2 + i * Math.PI / 5;
            p.addPoint((int) Math.round(x + rr * Math.cos(a)), (int) Math.round(y + rr * Math.sin(a)));
        }
        g.fill(p);
    }

    private static void centered(Graphics2D g, String s, double cx, double baseline) {
        int w = g.getFontMetrics().stringWidth(s);
        g.drawString(s, (float) (cx - w / 2.0), (float) baseline);
    }

    private static byte[] png(BufferedImage img) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
