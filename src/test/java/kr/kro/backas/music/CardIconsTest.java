package kr.kro.backas.music;

import net.dv8tion.jda.api.entities.emoji.Emoji;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CardIconsTest {

    @Test
    void drawsEverySquareIconSmallEnoughForDiscord() throws IOException {
        for (CardIcons.Glyph glyph : CardIcons.Glyph.values()) {
            byte[] png = CardIcons.png(glyph);
            assertTrue(png.length < 256 * 1024, glyph + " is " + png.length + " bytes");
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
            assertEquals(CardIcons.SIZE, image.getWidth());
            assertEquals(CardIcons.SIZE, image.getHeight());
            assertTrue(hasInk(image), glyph + " is empty");
        }
    }

    @Test
    void emojiNamesAreValidForDiscord() {
        for (CardIcons.Glyph glyph : CardIcons.Glyph.values()) {
            assertTrue(glyph.emojiName().matches("[a-z0-9_]{2,32}"), glyph.emojiName());
        }
    }

    @Test
    void fallsBackToStandardEmojisBeforeTheIconsAreUploaded() {
        assertEquals(Emoji.Type.UNICODE, CardIcons.of(CardIcons.Glyph.PAUSE).getType());
    }

    private static boolean hasInk(BufferedImage image) {
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) >>> 24) > 0) return true;
            }
        }
        return false;
    }
}
