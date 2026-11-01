package kr.kro.backas.music;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Icon;
import net.dv8tion.jda.api.entities.emoji.ApplicationEmoji;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class CardIcons {

    public enum Glyph {
        PREVIOUS("⏮️"),
        PLAY("▶️"),
        PAUSE("⏸️"),
        NEXT("⏭️"),
        QUEUE("📜"),
        LYRICS("🎤"),
        ADJUST("🎛️"),
        VOLUME_DOWN("🔉"),
        VOLUME_UP("🔊"),
        DOCUMENT("📄"),
        SHUFFLE("🔀"),
        SPARKLE("✨"),
        REPEAT("🔁"),
        PLUS("➕"),
        CHAT("💬"),
        EXIT("🚪");

        private final String fallback;

        Glyph(String fallback) {
            this.fallback = fallback;
        }

        String emojiName() {
            return "luffia_" + name().toLowerCase() + "_v" + VERSION;
        }
    }

    static final int VERSION = 1;
    static final int SIZE = 128;
    private static final Color INK = new Color(0xF2, 0xF3, 0xF5);
    private static final Logger LOGGER = LoggerFactory.getLogger(CardIcons.class);
    private static final Map<Glyph, Emoji> INSTALLED = new ConcurrentHashMap<>();

    private CardIcons() {
    }

    public static Emoji of(Glyph glyph) {
        Emoji installed = INSTALLED.get(glyph);
        return installed != null ? installed : Emoji.fromUnicode(glyph.fallback);
    }

    public static void install(JDA jda) {
        jda.retrieveApplicationEmojis().queue(existing -> {
            for (Glyph glyph : Glyph.values()) {
                ApplicationEmoji found = existing.stream()
                        .filter(emoji -> emoji.getName().equals(glyph.emojiName()))
                        .findFirst().orElse(null);
                if (found != null) {
                    INSTALLED.put(glyph, found);
                    continue;
                }
                jda.createApplicationEmoji(glyph.emojiName(), Icon.from(png(glyph), Icon.IconType.PNG)).queue(
                        created -> INSTALLED.put(glyph, created),
                        error -> LOGGER.warn("failed to upload the {} button icon, using {} instead", glyph, glyph.fallback, error));
            }
        }, error -> LOGGER.warn("failed to read the bot's emojis, track card buttons keep the standard emojis", error));
    }

    static byte[] png(Glyph glyph) {
        BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.setColor(INK);
            draw(g, glyph);
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "png", out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    private static void draw(Graphics2D g, Glyph glyph) {
        switch (glyph) {
            case PLAY -> fillRounded(g, triangle(44, 26, 44, 102, 104, 64));
            case PAUSE -> {
                g.fill(new RoundRectangle2D.Double(30, 26, 24, 76, 12, 12));
                g.fill(new RoundRectangle2D.Double(74, 26, 24, 76, 12, 12));
            }
            case PREVIOUS -> {
                g.fill(new RoundRectangle2D.Double(24, 28, 16, 72, 10, 10));
                fillRounded(g, triangle(104, 30, 104, 98, 50, 64));
            }
            case NEXT -> {
                g.fill(new RoundRectangle2D.Double(88, 28, 16, 72, 10, 10));
                fillRounded(g, triangle(24, 30, 24, 98, 78, 64));
            }
            case QUEUE -> {
                g.fill(new RoundRectangle2D.Double(20, 28, 88, 14, 14, 14));
                g.fill(new RoundRectangle2D.Double(20, 57, 88, 14, 14, 14));
                g.fill(new RoundRectangle2D.Double(20, 86, 54, 14, 14, 14));
                fillRounded(g, triangle(86, 80, 86, 106, 108, 93));
            }
            case LYRICS -> {
                g.fill(new RoundRectangle2D.Double(44, 12, 40, 66, 40, 40));
                g.setStroke(new BasicStroke(10, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.draw(new Arc2D.Double(28, 28, 72, 66, 180, 180, Arc2D.OPEN));
                g.draw(new Line2D.Double(64, 94, 64, 110));
                g.draw(new Line2D.Double(44, 112, 84, 112));
            }
            case ADJUST -> {
                g.setStroke(new BasicStroke(9, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                for (int y : new int[] {32, 64, 96}) g.draw(new Line2D.Double(18, y, 110, y));
                g.fill(new Ellipse2D.Double(30, 18, 28, 28));
                g.fill(new Ellipse2D.Double(70, 50, 28, 28));
                g.fill(new Ellipse2D.Double(42, 82, 28, 28));
            }
            case VOLUME_DOWN -> {
                fillRounded(g, speaker());
                g.setStroke(new BasicStroke(10, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.draw(new Line2D.Double(82, 64, 112, 64));
            }
            case VOLUME_UP -> {
                fillRounded(g, speaker());
                g.setStroke(new BasicStroke(10, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.draw(new Line2D.Double(82, 64, 112, 64));
                g.draw(new Line2D.Double(97, 49, 97, 79));
            }
            case DOCUMENT -> {
                g.setStroke(new BasicStroke(9, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.draw(new RoundRectangle2D.Double(28, 14, 72, 100, 18, 18));
                g.draw(new Line2D.Double(46, 44, 82, 44));
                g.draw(new Line2D.Double(46, 64, 82, 64));
                g.draw(new Line2D.Double(46, 84, 68, 84));
            }
            case SHUFFLE -> {
                g.setStroke(new BasicStroke(9, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                Path2D.Double down = new Path2D.Double();
                down.moveTo(16, 38);
                down.lineTo(36, 38);
                down.curveTo(60, 38, 60, 90, 84, 90);
                down.lineTo(94, 90);
                g.draw(down);
                Path2D.Double up = new Path2D.Double();
                up.moveTo(16, 90);
                up.lineTo(36, 90);
                up.curveTo(60, 90, 60, 38, 84, 38);
                up.lineTo(94, 38);
                g.draw(up);
                fillRounded(g, triangle(94, 24, 94, 52, 114, 38));
                fillRounded(g, triangle(94, 76, 94, 104, 114, 90));
            }
            case SPARKLE -> {
                g.fill(star(54, 70, 42));
                g.fill(star(100, 26, 18));
            }
            case REPEAT -> {
                g.setStroke(new BasicStroke(10, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.draw(new Arc2D.Double(22, 22, 84, 84, 70, 290, Arc2D.OPEN));
                fillRounded(g, triangle(90, 66, 118, 66, 104, 44));
            }
            case PLUS -> {
                g.setStroke(new BasicStroke(14, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.draw(new Line2D.Double(64, 24, 64, 104));
                g.draw(new Line2D.Double(24, 64, 104, 64));
            }
            case CHAT -> {
                g.setStroke(new BasicStroke(9, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.draw(new RoundRectangle2D.Double(14, 18, 100, 72, 32, 32));
                fillRounded(g, triangle(34, 86, 34, 110, 58, 88));
                g.fill(new Ellipse2D.Double(37, 48, 14, 14));
                g.fill(new Ellipse2D.Double(57, 48, 14, 14));
                g.fill(new Ellipse2D.Double(77, 48, 14, 14));
            }
            case EXIT -> {
                g.setStroke(new BasicStroke(9, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                Path2D.Double door = new Path2D.Double();
                door.moveTo(62, 18);
                door.lineTo(26, 18);
                door.lineTo(26, 110);
                door.lineTo(62, 110);
                g.draw(door);
                g.draw(new Line2D.Double(50, 64, 98, 64));
                fillRounded(g, triangle(94, 48, 94, 80, 116, 64));
            }
        }
    }

    private static Shape speaker() {
        Path2D.Double path = new Path2D.Double();
        path.moveTo(16, 48);
        path.lineTo(36, 48);
        path.lineTo(62, 24);
        path.lineTo(62, 104);
        path.lineTo(36, 80);
        path.lineTo(16, 80);
        path.closePath();
        return path;
    }

    private static Shape star(double cx, double cy, double r) {
        Path2D.Double path = new Path2D.Double();
        path.moveTo(cx, cy - r);
        path.quadTo(cx, cy, cx + r, cy);
        path.quadTo(cx, cy, cx, cy + r);
        path.quadTo(cx, cy, cx - r, cy);
        path.quadTo(cx, cy, cx, cy - r);
        path.closePath();
        return path;
    }

    private static Shape triangle(double x1, double y1, double x2, double y2, double x3, double y3) {
        Path2D.Double path = new Path2D.Double();
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);
        path.lineTo(x3, y3);
        path.closePath();
        return path;
    }

    private static void fillRounded(Graphics2D g, Shape shape) {
        g.fill(shape);
        g.setStroke(new BasicStroke(10, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(shape);
    }
}
