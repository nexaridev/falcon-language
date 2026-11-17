package falcon;

import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import javax.imageio.ImageIO;

/** Buffered images for the optional libraries/pillow.fc module; no desktop required. */
final class ImageRuntime implements AutoCloseable {
    private final Map<Long, BufferedImage> images = new HashMap<>();
    private long next = 1, pixels;
    Object invoke(String op, List<Object> a) throws IOException {
        if (op.equals("pillow.create")) {
            int w = integer(a, 0), h = integer(a, 1); Color color = color((String) a.get(2));
            BufferedImage image = allocate(w, h); Graphics2D g = graphics(image);
            try { g.setColor(color); g.fillRect(0, 0, w, h); } finally { g.dispose(); }
            return retain(image);
        }
        if (op.equals("pillow.open")) {
            try (var stream = ImageIO.createImageInputStream(Path.of((String) a.get(0)).toFile())) {
                if (stream == null) throw new IOException("Cannot open image.");
                var readers = ImageIO.getImageReaders(stream);
                if (!readers.hasNext()) throw new IllegalArgumentException("Unsupported or invalid image file.");
                var reader = readers.next();
                try {
                    reader.setInput(stream, true, true);
                    budget(reader.getWidth(0), reader.getHeight(0));
                    BufferedImage decoded = reader.read(0);
                    BufferedImage image = allocate(decoded.getWidth(), decoded.getHeight());
                    Graphics2D g = graphics(image);
                    try { g.drawImage(decoded, 0, 0, null); } finally { g.dispose(); decoded.flush(); }
                    return retain(image);
                } finally { reader.dispose(); }
            }
        }
        long id = (long) a.get(0); BufferedImage image = images.get(id);
        if (image == null) throw new IllegalArgumentException("Unknown or closed image handle.");
        int w = image.getWidth(), h = image.getHeight();
        return switch (op) {
            case "pillow.width" -> (long) w;
            case "pillow.height" -> (long) h;
            case "pillow.close" -> { images.remove(id); pixels -= (long) w * h; image.flush(); yield null; }
            case "pillow.save" -> {
                String format = ((String) a.get(2)).toLowerCase(Locale.ROOT);
                if (format.equals("jpg")) format = "jpeg";
                if (!Set.of("png", "jpeg").contains(format)) throw new IllegalArgumentException("Save format must be png or jpeg.");
                BufferedImage saved = image;
                if (format.equals("jpeg")) {
                    saved = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB); Graphics2D g = graphics(saved);
                    try { g.setColor(Color.WHITE); g.fillRect(0, 0, w, h); g.drawImage(image, 0, 0, null); } finally { g.dispose(); }
                }
                try { if (!ImageIO.write(saved, format, Path.of((String) a.get(1)).toFile())) throw new IOException("No writer for format."); }
                finally { if (saved != image) saved.flush(); }
                yield null;
            }
            case "pillow.copy", "pillow.resize", "pillow.crop", "pillow.flip", "pillow.rotate" -> {
                int ow = w, oh = h, x = 0, y = 0; AffineTransform transform = new AffineTransform();
                if (op.equals("pillow.resize")) { ow = integer(a, 1); oh = integer(a, 2); transform.scale((double) ow / w, (double) oh / h); }
                if (op.equals("pillow.crop")) {
                    x = integer(a, 1); y = integer(a, 2); int right = integer(a, 3), bottom = integer(a, 4);
                    if (x < 0 || y < 0 || right > w || bottom > h || right <= x || bottom <= y) throw new IllegalArgumentException("Crop requires 0 <= left < right <= width and 0 <= top < bottom <= height.");
                    ow = right - x; oh = bottom - y; transform.translate(-x, -y);
                }
                if (op.equals("pillow.flip")) {
                    String axis = (String) a.get(1);
                    if (axis.equals("horizontal")) { transform.translate(w, 0); transform.scale(-1, 1); }
                    else if (axis.equals("vertical")) { transform.translate(0, h); transform.scale(1, -1); }
                    else throw new IllegalArgumentException("Flip axis must be horizontal or vertical.");
                }
                if (op.equals("pillow.rotate")) {
                    long degrees = (long) a.get(1);
                    if (degrees % 90 != 0) throw new IllegalArgumentException("Rotation must be a multiple of 90 degrees (counterclockwise).");
                    int turns = (int) Math.floorMod(degrees / 90, 4);
                    if (turns % 2 != 0) { ow = h; oh = w; }
                    if (turns == 1) { transform.translate(0, w); transform.rotate(-Math.PI / 2); }
                    if (turns == 2) { transform.translate(w, h); transform.rotate(Math.PI); }
                    if (turns == 3) { transform.translate(h, 0); transform.rotate(Math.PI / 2); }
                }
                BufferedImage changed = allocate(ow, oh); Graphics2D g = graphics(changed);
                try { g.drawImage(image, transform, null); } finally { g.dispose(); }
                yield retain(changed);
            }
            case "pillow.grayscale", "pillow.brightness", "pillow.blur" -> {
                double factor = op.equals("pillow.brightness") ? ((Number) a.get(1)).doubleValue() : 1;
                if (!Double.isFinite(factor) || factor < 0 || factor > 10) throw new IllegalArgumentException("Brightness factor must be 0–10.");
                BufferedImage changed = allocate(w, h);
                for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                    Color c = new Color(image.getRGB(x, y), true);
                    int r = c.getRed(), green = c.getGreen(), b = c.getBlue(), alpha = c.getAlpha();
                    if (op.equals("pillow.grayscale")) r = green = b = (int) Math.round(.299 * r + .587 * green + .114 * b);
                    else if (op.equals("pillow.brightness")) { r = channel(r * factor); green = channel(green * factor); b = channel(b * factor); }
                    else {
                        int totalA = 0, totalR = 0, totalG = 0, totalB = 0;
                        for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                            Color sample = new Color(image.getRGB(Math.max(0, Math.min(w - 1, x + dx)), Math.max(0, Math.min(h - 1, y + dy))), true);
                            int sa = sample.getAlpha(); totalA += sa; totalR += sample.getRed() * sa; totalG += sample.getGreen() * sa; totalB += sample.getBlue() * sa;
                        }
                        alpha = totalA / 9; r = totalA == 0 ? 0 : totalR / totalA; green = totalA == 0 ? 0 : totalG / totalA; b = totalA == 0 ? 0 : totalB / totalA;
                    }
                    changed.setRGB(x, y, new Color(r, green, b, alpha).getRGB());
                }
                yield retain(changed);
            }
            case "pillow.getPixel", "pillow.putPixel" -> {
                int x = integer(a, 1), y = integer(a, 2);
                if (x < 0 || x >= w || y < 0 || y >= h) throw new IllegalArgumentException("Pixel coordinates are outside the image.");
                if (op.equals("pillow.putPixel")) { image.setRGB(x, y, color((String) a.get(3)).getRGB()); yield null; }
                Color c = new Color(image.getRGB(x, y), true);
                yield String.format("#%02X%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue(), c.getAlpha());
            }
            case "pillow.paste" -> {
                BufferedImage source = images.get((long) a.get(1));
                if (source == null || source == image) throw new IllegalArgumentException("Paste requires a different, open source image.");
                int x = integer(a, 2), y = integer(a, 3); Graphics2D g = graphics(image);
                try { g.drawImage(source, x, y, null); } finally { g.dispose(); }
                yield null;
            }
            case "pillow.drawText", "pillow.rectangle", "pillow.ellipse", "pillow.line" -> {
                Graphics2D g = graphics(image);
                try {
                    int x = integer(a, 1), y = integer(a, 2);
                    if (op.equals("pillow.drawText")) {
                        int size = integer(a, 4);
                        if (size < 1 || size > 512) throw new IllegalArgumentException("Font size must be 1–512.");
                        g.setFont(new Font("SansSerif", Font.PLAIN, size)); g.setColor(color((String) a.get(5)));
                        g.drawString((String) a.get(3), x, y + g.getFontMetrics().getAscent());
                    } else {
                        int right = integer(a, 3), bottom = integer(a, 4); g.setColor(color((String) a.get(5)));
                        if (op.equals("pillow.line")) g.drawLine(x, y, right, bottom);
                        else {
                            if (right <= x || bottom <= y || (long) right - x > Integer.MAX_VALUE || (long) bottom - y > Integer.MAX_VALUE) throw new IllegalArgumentException("Shape bounds must have positive width and height.");
                            if (op.equals("pillow.rectangle")) g.fillRect(x, y, right - x, bottom - y);
                            else g.fillOval(x, y, right - x, bottom - y);
                        }
                    }
                } finally { g.dispose(); }
                yield null;
            }
            default -> throw new IllegalArgumentException("Unknown image operation.");
        };
    }
    private static int channel(double n) { return Math.max(0, Math.min(255, (int) Math.round(n))); }
    private static int integer(List<Object> a, int index) { return Math.toIntExact((long) a.get(index)); }
    private static Color color(String s) {
        if (!s.matches("#[0-9A-Fa-f]{6}([0-9A-Fa-f]{2})?")) throw new IllegalArgumentException("Use a color like #RRGGBB or #RRGGBBAA.");
        long n = Long.parseLong(s.substring(1), 16);
        return s.length() == 7 ? new Color((int) n) : new Color((int) (n >> 8) | (int) (n & 255) << 24, true);
    }
    private void budget(int w, int h) {
        long count = (long) w * h;
        if (w < 1 || h < 1 || count > 16_000_000 || pixels + count > 64_000_000)
            throw new IllegalArgumentException("Image limit: 16 million pixels each, 64 million retained; close unused images.");
    }
    private BufferedImage allocate(int w, int h) { budget(w, h); return new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB); }
    private long retain(BufferedImage image) { long id = next++; images.put(id, image); pixels += (long) image.getWidth() * image.getHeight(); return id; }
    private static Graphics2D graphics(BufferedImage image) {
        Graphics2D g = image.createGraphics(); g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR); return g;
    }
    @Override public void close() { images.values().forEach(BufferedImage::flush); images.clear(); pixels = 0; }
}
