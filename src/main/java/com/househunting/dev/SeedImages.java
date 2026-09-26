package com.househunting.dev;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Random;

/**
 * Draws plausible-looking flat photographs for the dev seed.
 *
 * <p><b>Why draw them rather than ship files.</b> Three options were on the
 * table. Downloading stock photos at seed time makes a local database depend on
 * a network and on somebody else's terms of use. Committing a folder of JPEGs
 * puts megabytes of binary in the repository forever and raises a licence
 * question for every one of them. Drawing them costs about fifteen lines per
 * scene, weighs nothing, and — the part that actually matters — is
 * <em>deterministic</em>: listing 47 gets the same five images on your machine
 * and mine, so "the third photo on the Powai 2BHK is stretched" is a thing two
 * people can look at together.
 *
 * <p>They are obviously not photographs. That is deliberate too: nobody should
 * ever mistake seeded data for real data, and a drawing that reads as "a room"
 * at card size is enough to test layout, aspect ratio, lazy loading, the
 * gallery and the cover-photo rules — which is the entire job.
 *
 * <p>Everything here is package-private to {@code dev}. It is reachable only
 * from {@link DevDataSeeder}, which is {@code @Profile("dev")}, so none of it
 * can run in production.
 */
final class SeedImages {

    /** 3:2, the aspect ratio the card and the gallery are laid out for. */
    private static final int WIDTH = 1000;
    private static final int HEIGHT = 667;

    /**
     * The kinds of photograph a real listing has, in the order people shoot
     * them. The seeder takes a prefix of this list, so a listing with three
     * photos gets a living room, a bedroom and a kitchen rather than three
     * bathrooms.
     */
    enum Scene { LIVING_ROOM, BEDROOM, KITCHEN, BALCONY, BATHROOM, EXTERIOR }

    private SeedImages() {
    }

    /**
     * One JPEG, as bytes.
     *
     * @param seed anything stable for this listing+position; the same seed
     *             always produces byte-identical output
     */
    static byte[] jpeg(Scene scene, long seed) {
        Random rng = new Random(seed);
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

        // One hue per image drives every colour in it, so the five photos of a
        // listing look like five photos of one flat rather than five clip-art
        // scenes that happen to be adjacent.
        float hue = rng.nextFloat();

        switch (scene) {
            case LIVING_ROOM -> livingRoom(g, rng, hue);
            case BEDROOM -> bedroom(g, rng, hue);
            case KITCHEN -> kitchen(g, rng, hue);
            case BATHROOM -> bathroom(g, rng, hue);
            case BALCONY -> balcony(g, rng, hue);
            case EXTERIOR -> exterior(g, rng, hue);
        }

        softLight(g, rng);
        g.dispose();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "jpeg", out);
        } catch (IOException e) {
            // Writing to a byte array cannot fail for I/O reasons; if it does,
            // something is wrong with the JPEG writer itself and there is no
            // sensible recovery inside a seeder.
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    // ---- Scenes ------------------------------------------------------------

    private static void livingRoom(Graphics2D g, Random rng, float hue) {
        int floorTop = (int) (HEIGHT * 0.62);
        wall(g, hue, floorTop);
        woodFloor(g, rng, floorTop);

        window(g, (int) (WIDTH * 0.06), (int) (HEIGHT * 0.12),
                (int) (WIDTH * 0.30), (int) (HEIGHT * 0.42));

        // Rug, then sofa on top of it: painter's order is what gives a flat
        // drawing any sense of depth at all.
        Color rug = hsb(hue, 0.18f, 0.72f);
        g.setColor(rug);
        g.fillRoundRect((int) (WIDTH * 0.30), (int) (HEIGHT * 0.72),
                (int) (WIDTH * 0.60), (int) (HEIGHT * 0.20), 24, 24);

        Color sofa = hsb(hue, 0.30f, 0.58f);
        int sx = (int) (WIDTH * 0.36);
        int sy = (int) (HEIGHT * 0.52);
        int sw = (int) (WIDTH * 0.46);
        int sh = (int) (HEIGHT * 0.26);
        g.setColor(sofa.darker());
        g.fillRoundRect(sx, sy, sw, (int) (sh * 0.62), 18, 18);          // back
        g.setColor(sofa);
        g.fillRoundRect(sx - 12, sy + (int) (sh * 0.45), sw + 24, (int) (sh * 0.55), 20, 20);

        for (int i = 0; i < 3; i++) {
            g.setColor(hsb(hue + 0.08f * i, 0.42f, 0.78f));
            g.fillRoundRect(sx + 18 + i * (sw / 3), sy + 10, sw / 4, (int) (sh * 0.36), 14, 14);
        }

        // Coffee table
        g.setColor(new Color(0x6B4E2E));
        g.fillRoundRect((int) (WIDTH * 0.44), (int) (HEIGHT * 0.80),
                (int) (WIDTH * 0.28), 16, 8, 8);

        framedPicture(g, rng, hue, (int) (WIDTH * 0.56), (int) (HEIGHT * 0.12));
        plant(g, (int) (WIDTH * 0.88), floorTop);
    }

    private static void bedroom(Graphics2D g, Random rng, float hue) {
        int floorTop = (int) (HEIGHT * 0.66);
        wall(g, hue, floorTop);
        woodFloor(g, rng, floorTop);

        window(g, (int) (WIDTH * 0.68), (int) (HEIGHT * 0.14),
                (int) (WIDTH * 0.26), (int) (HEIGHT * 0.38));

        int bx = (int) (WIDTH * 0.10);
        int by = (int) (HEIGHT * 0.34);
        int bw = (int) (WIDTH * 0.52);

        // Headboard
        g.setColor(hsb(hue, 0.28f, 0.46f));
        g.fillRoundRect(bx, by, bw, (int) (HEIGHT * 0.22), 16, 16);

        // Mattress and duvet
        g.setColor(new Color(0xF4F1EA));
        g.fillRoundRect(bx - 10, by + (int) (HEIGHT * 0.20), bw + 20, (int) (HEIGHT * 0.30), 18, 18);
        g.setColor(hsb(hue, 0.22f, 0.80f));
        g.fillRoundRect(bx - 10, by + (int) (HEIGHT * 0.30), bw + 20, (int) (HEIGHT * 0.20), 18, 18);

        // Pillows
        g.setColor(Color.WHITE);
        g.fillRoundRect(bx + 24, by + (int) (HEIGHT * 0.17), bw / 3, (int) (HEIGHT * 0.10), 20, 20);
        g.fillRoundRect(bx + 40 + bw / 3, by + (int) (HEIGHT * 0.17), bw / 3, (int) (HEIGHT * 0.10), 20, 20);

        // Bedside table and lamp
        int tx = bx + bw + 30;
        g.setColor(new Color(0x7A5B38));
        g.fillRoundRect(tx, (int) (HEIGHT * 0.60), (int) (WIDTH * 0.12), (int) (HEIGHT * 0.16), 8, 8);
        g.setColor(hsb(hue + 0.4f, 0.35f, 0.88f));
        g.fillRoundRect(tx + 16, (int) (HEIGHT * 0.48), (int) (WIDTH * 0.08), (int) (HEIGHT * 0.12), 10, 10);

        framedPicture(g, rng, hue, (int) (WIDTH * 0.18), (int) (HEIGHT * 0.10));
    }

    private static void kitchen(Graphics2D g, Random rng, float hue) {
        int counterTop = (int) (HEIGHT * 0.56);
        g.setColor(hsb(hue, 0.08f, 0.93f));
        g.fillRect(0, 0, WIDTH, HEIGHT);

        // Tiled splashback between the cabinets and the counter
        int tile = 46;
        for (int y = (int) (HEIGHT * 0.30); y < counterTop; y += tile) {
            for (int x = 0; x < WIDTH; x += tile) {
                g.setColor(hsb(hue, 0.10f, 0.96f - ((x / tile + y / tile) % 2) * 0.05f));
                g.fillRect(x, y, tile - 2, tile - 2);
            }
        }

        // Upper cabinets
        g.setColor(hsb(hue, 0.20f, 0.66f));
        g.fillRect(0, 0, WIDTH, (int) (HEIGHT * 0.28));
        g.setColor(hsb(hue, 0.20f, 0.58f));
        for (int x = 0; x < WIDTH; x += WIDTH / 5) {
            g.drawRect(x + 6, 8, WIDTH / 5 - 12, (int) (HEIGHT * 0.28) - 18);
        }

        // Worktop
        g.setColor(new Color(0x3B3B3B));
        g.fillRect(0, counterTop, WIDTH, 22);

        // Base units
        g.setColor(hsb(hue, 0.22f, 0.52f));
        g.fillRect(0, counterTop + 22, WIDTH, HEIGHT - counterTop - 22);
        g.setColor(hsb(hue, 0.22f, 0.44f));
        for (int x = 0; x < WIDTH; x += WIDTH / 4) {
            g.drawRect(x + 8, counterTop + 32, WIDTH / 4 - 16, HEIGHT - counterTop - 48);
        }

        // Sink and tap
        g.setColor(new Color(0xC9CDD2));
        g.fillRoundRect((int) (WIDTH * 0.10), counterTop - 4, (int) (WIDTH * 0.22), 26, 8, 8);
        g.setStroke(new BasicStroke(7));
        g.drawArc((int) (WIDTH * 0.16), counterTop - 60, 60, 60, 0, 180);

        // Hob
        g.setColor(new Color(0x2A2A2A));
        g.fillRoundRect((int) (WIDTH * 0.62), counterTop - 2, (int) (WIDTH * 0.24), 24, 6, 6);
        g.setColor(new Color(0x555555));
        for (int i = 0; i < 2; i++) {
            g.fill(new Ellipse2D.Double(WIDTH * 0.65 + i * WIDTH * 0.11, counterTop + 2, 44, 18));
        }

        plant(g, (int) (WIDTH * 0.46), counterTop);
    }

    private static void bathroom(Graphics2D g, Random rng, float hue) {
        int tile = 58;
        for (int y = 0; y < HEIGHT; y += tile) {
            for (int x = 0; x < WIDTH; x += tile) {
                float v = 0.90f - ((x / tile + y / tile) % 2) * 0.06f;
                g.setColor(hsb(hue, 0.12f, v));
                g.fillRect(x, y, tile - 3, tile - 3);
            }
        }

        // Counter and basin
        int counterTop = (int) (HEIGHT * 0.58);
        g.setColor(new Color(0x4A4A4A));
        g.fillRect((int) (WIDTH * 0.12), counterTop, (int) (WIDTH * 0.52), 20);
        g.setColor(Color.WHITE);
        g.fill(new Ellipse2D.Double(WIDTH * 0.24, counterTop - 26, WIDTH * 0.24, 54));
        g.setColor(new Color(0xB8BDC4));
        g.setStroke(new BasicStroke(8));
        g.drawArc((int) (WIDTH * 0.32), counterTop - 84, 54, 60, 0, 180);

        // Mirror
        g.setColor(hsb(hue, 0.16f, 0.80f));
        g.fill(new RoundRectangle2D.Double(WIDTH * 0.20, HEIGHT * 0.12,
                WIDTH * 0.32, HEIGHT * 0.34, 18, 18));
        g.setColor(new Color(255, 255, 255, 60));
        g.fillPolygon(
                new int[]{(int) (WIDTH * 0.22), (int) (WIDTH * 0.36), (int) (WIDTH * 0.30), (int) (WIDTH * 0.20)},
                new int[]{(int) (HEIGHT * 0.44), (int) (HEIGHT * 0.14), (int) (HEIGHT * 0.14), (int) (HEIGHT * 0.30)},
                4);

        // Towel rail
        g.setColor(new Color(0xB8BDC4));
        g.fillRect((int) (WIDTH * 0.72), (int) (HEIGHT * 0.32), (int) (WIDTH * 0.20), 8);
        g.setColor(hsb(hue + 0.5f, 0.30f, 0.88f));
        g.fillRoundRect((int) (WIDTH * 0.74), (int) (HEIGHT * 0.34), (int) (WIDTH * 0.07),
                (int) (HEIGHT * 0.30), 8, 8);
        g.setColor(hsb(hue + 0.5f, 0.20f, 0.95f));
        g.fillRoundRect((int) (WIDTH * 0.82), (int) (HEIGHT * 0.34), (int) (WIDTH * 0.07),
                (int) (HEIGHT * 0.26), 8, 8);
    }

    private static void balcony(Graphics2D g, Random rng, float hue) {
        sky(g, rng);
        skyline(g, rng, (int) (HEIGHT * 0.62));

        // Floor
        int floorTop = (int) (HEIGHT * 0.70);
        g.setColor(new Color(0x9C8E7C));
        g.fillRect(0, floorTop, WIDTH, HEIGHT - floorTop);
        g.setColor(new Color(0x8A7C6B));
        for (int x = -HEIGHT; x < WIDTH + HEIGHT; x += 70) {
            g.drawLine(x, HEIGHT, x + 120, floorTop);
        }

        // Railing
        g.setColor(new Color(0x40464C));
        g.fillRect(0, (int) (HEIGHT * 0.44), WIDTH, 12);
        g.fillRect(0, floorTop - 10, WIDTH, 10);
        for (int x = 20; x < WIDTH; x += 52) {
            g.fillRect(x, (int) (HEIGHT * 0.44), 8, floorTop - (int) (HEIGHT * 0.44));
        }

        plant(g, (int) (WIDTH * 0.12), HEIGHT - 20);
        plant(g, (int) (WIDTH * 0.86), HEIGHT - 40);
    }

    private static void exterior(Graphics2D g, Random rng, float hue) {
        sky(g, rng);
        skyline(g, rng, (int) (HEIGHT * 0.86));

        // The listed building, front and centre and lighter than the skyline
        // behind it, so the eye lands on it first.
        int bw = (int) (WIDTH * 0.42);
        int bx = (WIDTH - bw) / 2;
        int by = (int) (HEIGHT * 0.18);
        int bh = (int) (HEIGHT * 0.68);

        g.setColor(hsb(hue, 0.10f, 0.88f));
        g.fillRect(bx, by, bw, bh);
        g.setColor(hsb(hue, 0.12f, 0.76f));
        g.fillRect(bx + bw - 40, by, 40, bh);

        g.setColor(hsb(hue, 0.30f, 0.52f));
        for (int y = by + 24; y < by + bh - 40; y += 54) {
            for (int x = bx + 22; x < bx + bw - 60; x += 62) {
                g.fillRect(x, y, 40, 32);
            }
        }

        // Grass and trees at the base
        g.setColor(new Color(0x5E7A4B));
        g.fillRect(0, (int) (HEIGHT * 0.86), WIDTH, HEIGHT);
        for (int i = 0; i < 6; i++) {
            int tx = 40 + rng.nextInt(WIDTH - 80);
            g.setColor(new Color(0x6B4E2E));
            g.fillRect(tx, (int) (HEIGHT * 0.80), 12, (int) (HEIGHT * 0.09));
            g.setColor(new Color(0x3F6B3A));
            g.fill(new Ellipse2D.Double(tx - 34, HEIGHT * 0.70, 80, 90));
        }
    }

    // ---- Shared pieces -----------------------------------------------------

    private static void wall(Graphics2D g, float hue, int floorTop) {
        g.setPaint(new GradientPaint(
                0, 0, hsb(hue, 0.07f, 0.97f),
                0, floorTop, hsb(hue, 0.12f, 0.86f)));
        g.fillRect(0, 0, WIDTH, floorTop);
        g.setPaint(null);
        // Skirting board: a thin dark line is what stops a wall and a floor
        // reading as one flat rectangle.
        g.setColor(new Color(0xE0DACF));
        g.fillRect(0, floorTop - 14, WIDTH, 14);
    }

    private static void woodFloor(Graphics2D g, Random rng, int floorTop) {
        g.setColor(new Color(0xB08858));
        g.fillRect(0, floorTop, WIDTH, HEIGHT - floorTop);
        g.setColor(new Color(0x9E7748));
        g.setStroke(new BasicStroke(2));
        // Converging plank lines: cheap perspective, and enough of it that the
        // floor does not look like a beige bar.
        for (int x = -WIDTH; x < WIDTH * 2; x += 64) {
            g.drawLine(x, HEIGHT, (x + WIDTH / 2) / 2 + WIDTH / 4, floorTop);
        }
        for (int y = floorTop; y < HEIGHT; y += 46) {
            g.drawLine(0, y, WIDTH, y);
        }
    }

    private static void window(Graphics2D g, int x, int y, int w, int h) {
        g.setColor(new Color(0xF7F5F1));
        g.fillRect(x - 10, y - 10, w + 20, h + 20);

        g.setPaint(new GradientPaint(0, y, new Color(0x7FB6E8), 0, y + h, new Color(0xD9EAF7)));
        g.fillRect(x, y, w, h);
        g.setPaint(null);

        g.setColor(new Color(0xFFFFFF));
        g.setStroke(new BasicStroke(8));
        g.drawLine(x + w / 2, y, x + w / 2, y + h);
        g.drawLine(x, y + h / 2, x + w, y + h / 2);
        g.setStroke(new BasicStroke(10));
        g.drawRect(x, y, w, h);
    }

    private static void framedPicture(Graphics2D g, Random rng, float hue, int x, int y) {
        int w = 150;
        int h = 110;
        g.setColor(new Color(0x2F2A24));
        g.fillRect(x - 6, y - 6, w + 12, h + 12);
        g.setColor(hsb(hue + 0.3f, 0.25f, 0.9f));
        g.fillRect(x, y, w, h);
        g.setColor(hsb(hue + 0.3f, 0.45f, 0.65f));
        g.fillPolygon(
                new int[]{x, x + w / 2, x + w},
                new int[]{y + h, y + h / 3, y + h},
                3);
    }

    private static void plant(Graphics2D g, int x, int baseY) {
        g.setColor(new Color(0xB4643C));
        g.fillRoundRect(x - 26, baseY - 46, 52, 50, 8, 8);
        g.setColor(new Color(0x3F6B3A));
        for (int i = -2; i <= 2; i++) {
            g.fill(new Ellipse2D.Double(x - 12 + i * 15, baseY - 130 + Math.abs(i) * 18, 26, 84));
        }
    }

    private static void sky(Graphics2D g, Random rng) {
        g.setPaint(new GradientPaint(0, 0, new Color(0x5A9BD4), 0, HEIGHT, new Color(0xE8F1F8)));
        g.fillRect(0, 0, WIDTH, HEIGHT);
        g.setPaint(null);
        g.setColor(new Color(255, 255, 255, 150));
        for (int i = 0; i < 5; i++) {
            int cx = rng.nextInt(WIDTH);
            int cy = rng.nextInt((int) (HEIGHT * 0.4));
            g.fill(new Ellipse2D.Double(cx, cy, 130 + rng.nextInt(90), 44));
        }
    }

    private static void skyline(Graphics2D g, Random rng, int baseline) {
        // Two passes, the far one paler: atmospheric perspective, and the only
        // reason a row of grey rectangles reads as distance.
        for (int pass = 0; pass < 2; pass++) {
            int alpha = pass == 0 ? 90 : 170;
            int offset = pass == 0 ? 40 : 0;
            for (int x = -40; x < WIDTH + 40; x += 60 + rng.nextInt(40)) {
                int h = (int) (HEIGHT * (0.12 + rng.nextDouble() * 0.28));
                g.setColor(new Color(60, 70, 85, alpha));
                g.fillRect(x, baseline - h - offset, 50 + rng.nextInt(40), h + offset);
            }
        }
    }

    /**
     * A corner darkening pass.
     *
     * <p>Flat fills are the single strongest tell that an image was drawn
     * rather than taken. A vignette costs four gradient rectangles and does
     * more for the illusion than any amount of extra furniture.
     */
    private static void softLight(Graphics2D g, Random rng) {
        int band = 90;
        g.setPaint(new GradientPaint(0, 0, new Color(0, 0, 0, 40), band, 0, new Color(0, 0, 0, 0)));
        g.fillRect(0, 0, band, HEIGHT);
        g.setPaint(new GradientPaint(WIDTH, 0, new Color(0, 0, 0, 40), WIDTH - band, 0, new Color(0, 0, 0, 0)));
        g.fillRect(WIDTH - band, 0, band, HEIGHT);
        g.setPaint(new GradientPaint(0, HEIGHT, new Color(0, 0, 0, 45), 0, HEIGHT - band, new Color(0, 0, 0, 0)));
        g.fillRect(0, HEIGHT - band, WIDTH, band);
        g.setPaint(null);
    }

    /** Hue wraps, so callers can add offsets freely without a modulo. */
    private static Color hsb(float hue, float saturation, float brightness) {
        return Color.getHSBColor((hue % 1f + 1f) % 1f, saturation, brightness);
    }
}
