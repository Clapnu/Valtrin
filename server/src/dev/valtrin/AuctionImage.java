// Copyright (c) 2026 Valtrin. All rights reserved. See LICENSE.
package dev.valtrin;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Draws one page of a player's auction house as a PNG (Java2D only: no extra libraries, no network). */
final class AuctionImage {
    private AuctionImage() {}

    static final int W = 920, PAD = 20, COLS = 5, GAP = 10, CARD_H = 172, HEAD_H = 92;
    static final int SCALE = 2;

    /** One card. state: 0 listed, 1 sold. */
    record Card(String name, String tier, int stars, int master, long price, int count, String line, int urgency, boolean sold) {}

    static Color rarity(String tier) {
        if (tier == null) return new Color(0xffffff);
        switch (tier.toUpperCase(Locale.ROOT)) {
            case "UNCOMMON": return new Color(0x55ff55);
            case "RARE": return new Color(0x5b7bff);
            case "EPIC": return new Color(0xc056e0);
            case "LEGENDARY": return new Color(0xffaa00);
            case "MYTHIC": return new Color(0xff6ee0);
            case "DIVINE": return new Color(0x55ffff);
            case "SPECIAL": case "VERY_SPECIAL": return new Color(0xff5555);
            case "ULTIMATE": case "SUPREME": return new Color(0xdd3333);
            default: return new Color(0xffffff);
        }
    }

    /** True when this machine can draw text at all (a server without fonts cannot). */
    static boolean available() {
        try {
            byte[] b = render("Test", "page 1 / 1", 1, 0, 0,
                    List.of(new Card("Test Item", "RARE", 0, 0, 1_000_000, 1, "1d left", 0, false)));
            return b.length > 500;
        } catch (Throwable t) {
            return false;
        }
    }

    static byte[] render(String player, String foot, int listed, int soldN, long soldTotal, List<Card> cards) {
        System.setProperty("java.awt.headless", "true");
        int rows = Math.max(1, (cards.size() + COLS - 1) / COLS);
        int h = HEAD_H + rows * (CARD_H + GAP) + 34;
        BufferedImage img = new BufferedImage(W * SCALE, h * SCALE, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.scale(SCALE, SCALE);
            g.setColor(new Color(0x15161a));
            g.fillRect(0, 0, W, h);

            // header
            g.setColor(new Color(0xe8e8ee));
            g.setFont(font(Font.BOLD, 24));
            g.drawString(clip(g, player, 330), PAD, 48);
            int px = W - PAD;
            String[][] pills = {{"Sold recently", Format.compact(soldTotal)}, {"Sold", Integer.toString(soldN)}, {"Listed", Integer.toString(listed)}};
            for (int i = 0; i < pills.length; i++) {
                g.setFont(font(Font.BOLD, 17));
                int w = Math.max(g.getFontMetrics().stringWidth(pills[i][1]), labelWidth(g, pills[i][0])) + 24;
                px -= w;
                g.setColor(new Color(0x1f2128));
                g.fill(new RoundRectangle2D.Float(px, 24, w, 50, 10, 10));
                g.setColor(new Color(0x2c2f39));
                g.setStroke(new BasicStroke(1f));
                g.draw(new RoundRectangle2D.Float(px + .5f, 24.5f, w - 1, 49, 10, 10));
                g.setFont(font(Font.PLAIN, 12));
                g.setColor(new Color(0x9aa0b3));
                g.drawString(pills[i][0], px + 12, 41);
                g.setFont(font(Font.BOLD, 17));
                g.setColor(i < 2 ? new Color(0x55ff7f) : Color.WHITE);
                if (i == 2) g.setColor(Color.WHITE);
                g.drawString(pills[i][1], px + 12, 64);
                px -= GAP;
            }

            int cw = (W - 2 * PAD - (COLS - 1) * GAP) / COLS;
            for (int i = 0; i < cards.size(); i++) {
                int x = PAD + (i % COLS) * (cw + GAP), y = HEAD_H + (i / COLS) * (CARD_H + GAP);
                card(g, cards.get(i), x, y, cw);
            }
            g.setFont(font(Font.PLAIN, 11));
            g.setColor(new Color(0x6d7186));
            g.drawString("Valtrin · valtrin.app · /notify for sale DMs", PAD, h - 14);
            String right = foot;
            g.drawString(right, W - PAD - g.getFontMetrics().stringWidth(right), h - 14);
        } finally {
            g.dispose();
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(90_000);
            if (!javax.imageio.ImageIO.write(img, "png", out)) throw new IllegalStateException("no png writer");
            return out.toByteArray();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void card(Graphics2D g, Card c, int x, int y, int cw) {
        Color r = rarity(c.tier());
        Shape box = new RoundRectangle2D.Float(x, y, cw, CARD_H, 10, 10);
        g.setColor(c.sold() ? new Color(0x16231c) : new Color(0x1c1e25));
        g.fill(box);
        g.setColor(c.sold() ? new Color(0x2f6a45) : new Color(0x2b2e38));
        g.setStroke(new BasicStroke(1f));
        g.draw(new RoundRectangle2D.Float(x + .5f, y + .5f, cw - 1, CARD_H - 1, 10, 10));
        Shape oldClip = g.getClip();
        g.clip(box);
        g.setColor(r);
        g.fillRect(x, y, cw, 4);
        g.setClip(oldClip);

        // icon tile
        int ix = x + 10, iy = y + 14;
        g.setColor(new Color(0x12131a));
        g.fill(new RoundRectangle2D.Float(ix, iy, 44, 44, 8, 8));
        g.setColor(r);
        g.setStroke(new BasicStroke(2f));
        g.draw(new RoundRectangle2D.Float(ix + 1, iy + 1, 42, 42, 8, 8));
        icon(g, category(c.name()), ix + 7, iy + 7, 30, c.sold() ? dim(r) : r);

        // count badge
        if (c.count() > 1) {
            String t = "×" + c.count();
            g.setFont(font(Font.BOLD, 12));
            int w = g.getFontMetrics().stringWidth(t) + 12;
            g.setColor(new Color(0xffaa00));
            g.fill(new RoundRectangle2D.Float(x + cw - w - 8, y + 16, w, 18, 6, 6));
            g.setColor(new Color(0x201400));
            g.drawString(t, x + cw - w - 2, y + 29);
        }

        // name (two lines)
        g.setFont(font(Font.BOLD, 13));
        g.setColor(c.sold() ? dim2(r) : r);
        List<String> lines = wrap(g, c.name(), cw - 20, 2);
        for (int i = 0; i < lines.size(); i++) g.drawString(lines.get(i), x + 10, y + 78 + i * 16);

        // stars
        if (c.stars() > 0 || c.master() > 0) stars(g, c.stars(), c.master(), x + 10, y + 112);

        // price
        g.setFont(font(Font.BOLD, 19));
        g.setColor(Color.WHITE);
        String price = Format.compact(c.price());
        g.drawString(price, x + 10, y + 136);
        if (c.count() > 1) {
            g.setFont(font(Font.PLAIN, 11));
            g.setColor(new Color(0x8b8fa0));
            g.drawString("each", x + 14 + g.getFontMetrics(font(Font.BOLD, 19)).stringWidth(price), y + 136);
        }

        // bottom line
        g.setFont(font(c.urgency() > 0 || c.sold() ? Font.BOLD : Font.PLAIN, 12));
        g.setColor(c.sold() ? new Color(0x55ff7f) : c.urgency() == 2 ? new Color(0xff5555) : c.urgency() == 1 ? new Color(0xffaa00) : new Color(0x9aa0b3));
        g.drawString(clip(g, c.line(), cw - 20), x + 10, y + 156);

        if (c.sold()) {
            AffineTransform at = g.getTransform();
            Composite(g, .82f);
            g.rotate(Math.toRadians(-16), x + cw / 2.0, y + 52);
            g.setFont(font(Font.BOLD, 24));
            FontMetrics fm = g.getFontMetrics();
            String s = "SOLD";
            float tw = fm.stringWidth(s) + 3 * (s.length() - 1);
            float sx = x + cw / 2f - tw / 2f, sy = y + 60;
            g.setColor(new Color(0xff4d4d));
            g.setStroke(new BasicStroke(3f));
            g.draw(new RoundRectangle2D.Float(sx - 10, sy - 26, tw + 20, 34, 6, 6));
            float cx = sx;
            for (char ch : s.toCharArray()) {
                g.drawString(String.valueOf(ch), cx, sy);
                cx += fm.charWidth(ch) + 3;
            }
            g.setTransform(at);
            Composite(g, 1f);
        }
    }

    private static void Composite(Graphics2D g, float a) { g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, a)); }

    private static void stars(Graphics2D g, int stars, int master, int x, int y) {
        Font f = font(Font.PLAIN, 13);
        g.setFont(f);
        boolean glyphs = f.canDisplay('✪');
        float cx = x;
        g.setColor(new Color(0xffaa00));
        for (int i = 0; i < stars; i++) {
            if (glyphs) { g.drawString("✪", cx, y); cx += g.getFontMetrics().charWidth('✪') + 1; }
            else { g.fill(new Ellipse2D.Float(cx, y - 9, 9, 9)); cx += 11; }
        }
        if (master > 0) {
            g.setColor(new Color(0xff5555));
            if (f.canDisplay('➊')) { g.drawString(String.valueOf((char) ('➉' + Math.min(5, master))), cx + 1, y); }
            else { g.drawString(Integer.toString(master), cx + 2, y); }
        }
    }

    // ------------------------------------------------------------------ icons: simple original shapes by item type

    static String category(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.startsWith("[lvl")) return "pet";
        if (n.contains("chestplate") || n.contains("tunic") || n.contains("jacket")) return "chest";
        if (n.contains("leggings") || n.contains("pants")) return "legs";
        if (n.contains("helmet") || n.contains("hat") || n.contains("crown") || n.contains("mask")) return "helm";
        if (n.contains("boots") || n.contains("shoes") || n.contains("sandals")) return "boots";
        if (n.contains("cloak") || n.contains("cape")) return "cloak";
        if (n.contains("talisman") || n.contains("artifact") || n.contains("ring") || n.contains("relic") || n.contains("charm")) return "tali";
        if (n.contains("wand") || n.contains("sceptre") || n.contains("staff") || n.contains("rod")) return "wand";
        if (n.contains("bow")) return "bow";
        if (n.contains("pickaxe") || n.contains("drill") || n.contains("axe") || n.contains("hoe") || n.contains("shovel")) return "tool";
        if (n.contains("sword") || n.contains("blade") || n.contains("katana") || n.contains("dagger") || n.contains("scythe")
                || n.contains("hyperion") || n.contains("fury") || n.contains("claymore")) return "sword";
        return "gem";
    }

    private static void icon(Graphics2D g, String cat, int x, int y, int s, Color c) {
        g.setColor(c);
        float u = s / 32f;
        Path2D.Float p = new Path2D.Float();
        switch (cat) {
            case "chest": poly(p, u, x, y, 6, 6, 12, 6, 16, 10, 20, 6, 26, 6, 28, 14, 24, 16, 24, 28, 8, 28, 8, 16, 4, 14); break;
            case "legs": poly(p, u, x, y, 8, 4, 24, 4, 24, 14, 21, 28, 17, 28, 16, 16, 15, 28, 11, 28, 8, 14); break;
            case "helm": poly(p, u, x, y, 6, 26, 6, 12, 10, 6, 22, 6, 26, 12, 26, 26, 21, 26, 21, 18, 11, 18, 11, 26); break;
            case "boots": poly(p, u, x, y, 9, 4, 19, 4, 19, 18, 28, 22, 28, 28, 6, 28, 9, 18); break;
            case "cloak": poly(p, u, x, y, 10, 4, 22, 4, 28, 28, 4, 28); break;
            case "tali": poly(p, u, x, y, 16, 3, 27, 11, 23, 27, 9, 27, 5, 11); break;
            case "wand": poly(p, u, x, y, 24, 3, 29, 8, 9, 28, 4, 23); g.fill(p); g.fill(new Ellipse2D.Float(x + 20 * u, y + 1 * u, 10 * u, 10 * u)); return;
            case "bow": poly(p, u, x, y, 9, 3, 13, 3, 25, 16, 13, 29, 9, 29, 19, 16); break;
            case "tool": poly(p, u, x, y, 5, 8, 16, 3, 27, 8, 25, 12, 17, 10, 17, 29, 14, 29, 14, 10, 7, 12); break;
            case "sword": poly(p, u, x, y, 16, 2, 20, 6, 20, 22, 12, 22, 12, 6); g.fill(p); g.fillRect(Math.round(x + 8 * u), Math.round(y + 22 * u), Math.round(16 * u), Math.round(3 * u));
                g.fillRect(Math.round(x + 14 * u), Math.round(y + 25 * u), Math.round(4 * u), Math.round(5 * u)); return;
            case "pet": {
                g.fill(new Ellipse2D.Float(x + 8 * u, y + 14 * u, 16 * u, 14 * u));
                float[][] t = {{4, 8}, {10, 3}, {18, 3}, {24, 8}};
                for (float[] q : t) g.fill(new Ellipse2D.Float(x + q[0] * u, y + q[1] * u, 6 * u, 6 * u));
                return;
            }
            default: poly(p, u, x, y, 16, 3, 28, 12, 16, 29, 4, 12); break;
        }
        g.fill(p);
    }

    private static void poly(Path2D.Float p, float u, int x, int y, float... pts) {
        p.moveTo(x + pts[0] * u, y + pts[1] * u);
        for (int i = 2; i + 1 < pts.length; i += 2) p.lineTo(x + pts[i] * u, y + pts[i + 1] * u);
        p.closePath();
    }

    // ------------------------------------------------------------------ text helpers

    static Font font(int style, int size) {
        Font f = new Font("DejaVu Sans", style, size);
        return f.getFamily().equals("DejaVu Sans") ? f : new Font(Font.SANS_SERIF, style, size);
    }

    private static int labelWidth(Graphics2D g, String s) {
        Font old = g.getFont();
        g.setFont(font(Font.PLAIN, 12));
        int w = g.getFontMetrics().stringWidth(s);
        g.setFont(old);
        return w;
    }

    private static Color dim(Color c) { return new Color(c.getRed() * 6 / 10, c.getGreen() * 6 / 10, c.getBlue() * 6 / 10); }
    private static Color dim2(Color c) { return new Color(c.getRed() * 9 / 10, c.getGreen() * 9 / 10, c.getBlue() * 9 / 10); }

    private static String clip(Graphics2D g, String s, int max) {
        FontMetrics fm = g.getFontMetrics();
        if (fm.stringWidth(s) <= max) return s;
        while (s.length() > 1 && fm.stringWidth(s + "…") > max) s = s.substring(0, s.length() - 1);
        return s + "…";
    }

    private static List<String> wrap(Graphics2D g, String text, int max, int maxLines) {
        FontMetrics fm = g.getFontMetrics();
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String w : text.split(" ")) {
            String t = cur.length() == 0 ? w : cur + " " + w;
            if (fm.stringWidth(t) <= max) { cur = new StringBuilder(t); continue; }
            if (cur.length() > 0) { out.add(cur.toString()); cur = new StringBuilder(w); } else { cur = new StringBuilder(w); }
            if (out.size() == maxLines - 1) break;
        }
        // whatever is left goes on the last line, clipped
        if (out.size() < maxLines) {
            String rest = cur.toString();
            if (out.size() == maxLines - 1 && text.length() > String.join(" ", out).length() + rest.length() + out.size()) {
                int from = String.join(" ", out).length() + (out.isEmpty() ? 0 : 1);
                rest = text.substring(Math.min(from, text.length()));
            }
            if (!rest.isEmpty()) out.add(clip(g, rest, max));
        }
        return out;
    }
}
