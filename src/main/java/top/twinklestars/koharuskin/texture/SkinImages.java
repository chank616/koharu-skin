package top.twinklestars.koharuskin.texture;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;

public final class SkinImages {
    private SkinImages() {}

    public static BufferedImage preview(BufferedImage skin, String type, int height) {
        if (type.equals("cape")) return resize(part(skin, 1, 1, 10, 16), Math.max(1, height * 10 / 16), height);
        var result = new BufferedImage(16, 32, BufferedImage.TYPE_INT_ARGB);
        var g = result.createGraphics();
        int arm = type.equals("alex") ? 3 : 4;
        boolean modern = skin.getHeight() == skin.getWidth();
        g.drawImage(part(skin, 8, 8, 8, 8), 4, 0, null);
        g.drawImage(part(skin, 20, 20, 8, 12), 4, 8, null);
        g.drawImage(part(skin, 44, 20, arm, 12), 4 - arm, 8, null);
        g.drawImage(part(skin, 4, 20, 4, 12), 4, 20, null);
        if (modern) {
            g.drawImage(part(skin, 36, 52, arm, 12), 12, 8, null);
            g.drawImage(part(skin, 20, 52, 4, 12), 8, 20, null);
            g.drawImage(part(skin, 20, 36, 8, 12), 4, 8, null);
            g.drawImage(part(skin, 44, 36, arm, 12), 4 - arm, 8, null);
            g.drawImage(part(skin, 52, 52, arm, 12), 12, 8, null);
            g.drawImage(part(skin, 4, 36, 4, 12), 4, 20, null);
            g.drawImage(part(skin, 4, 52, 4, 12), 8, 20, null);
        } else {
            g.drawImage(part(skin, 44, 20, arm, 12), 12 + arm, 8, -arm, 12, null);
            g.drawImage(part(skin, 4, 20, 4, 12), 12, 20, -4, 12, null);
        }
        var hat = part(skin, 40, 8, 8, 8);
        if (modern || !opaque(hat)) g.drawImage(hat, 4, 0, null);
        g.dispose();
        return resize(result, Math.max(1, height / 2), height);
    }

    public static BufferedImage avatar(BufferedImage skin, int size, boolean threeDimensional) {
        var face = part(skin, 8, 8, 8, 8);
        var hat = part(skin, 40, 8, 8, 8);
        var front = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
        var fg = front.createGraphics();
        fg.drawImage(face, 0, 0, null);
        boolean outer = skin.getHeight() == skin.getWidth() || !opaque(hat);
        if (outer) fg.drawImage(hat, 0, 0, null);
        fg.dispose();
        if (!threeDimensional) return resize(front, size, size);
        var result = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        var g = result.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        face(g, part(skin, 8, 0, 8, 8), 4, 8, 12, -6, 12, 6);
        face(g, part(skin, 0, 8, 8, 8), 4, 8, 12, 6, 0, 16);
        face(g, front, 16, 14, 12, -6, 0, 16);
        if (outer) {
            face(g, part(skin, 40, 0, 8, 8), 4, 8, 12, -6, 12, 6);
            face(g, part(skin, 32, 8, 8, 8), 4, 8, 12, 6, 0, 16);
        }
        g.dispose();
        return resize(result, size, size);
    }

    private static void face(Graphics2D g, BufferedImage image, int x, int y, int ax, int ay, int bx, int by) {
        g.drawImage(image, new AffineTransform(ax / 8.0, ay / 8.0, bx / 8.0, by / 8.0, x, y), null);
    }

    private static BufferedImage part(BufferedImage skin, int x, int y, int width, int height) {
        int scale = skin.getWidth() / 64;
        return resize(skin.getSubimage(x * scale, y * scale, width * scale, height * scale), width, height);
    }

    private static boolean opaque(BufferedImage image) {
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) {
            if ((image.getRGB(x, y) >>> 24) != 255) return false;
        }
        return true;
    }

    private static BufferedImage resize(BufferedImage source, int width, int height) {
        var result = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        var g = result.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(source, 0, 0, width, height, null);
        g.dispose();
        return result;
    }
}
