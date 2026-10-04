package kr.kro.backas.music;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArtworkColorsTest {

    private static BufferedImage image(int width, int height, Color fill) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) image.setRGB(x, y, fill.getRGB());
        }
        return image;
    }

    @Test
    void picksTheMostCommonVividColorOverGreysAndBlacks() {
        BufferedImage image = image(100, 100, Color.BLACK);
        for (int y = 0; y < 100; y++) {
            for (int x = 0; x < 30; x++) image.setRGB(x, y, new Color(200, 30, 40).getRGB());
            for (int x = 30; x < 45; x++) image.setRGB(x, y, new Color(40, 60, 220).getRGB());
            for (int x = 45; x < 60; x++) image.setRGB(x, y, Color.GRAY.getRGB());
        }
        Color color = ArtworkColors.dominant(image);
        assertTrue(color.getRed() > color.getBlue() && color.getRed() > color.getGreen(), color.toString());
    }

    @Test
    void fallsBackToTheAverageWhenNothingIsVivid() {
        Color color = ArtworkColors.dominant(image(20, 20, new Color(120, 120, 120)));
        float[] hsb = Color.RGBtoHSB(color.getRed(), color.getGreen(), color.getBlue(), null);
        assertTrue(hsb[2] >= 0.45f, color.toString());
    }

    @Test
    void displayableColorsAreNeverTooDarkToSeeOnTheSidebar() {
        Color color = ArtworkColors.displayable(new Color(30, 10, 10));
        float[] hsb = Color.RGBtoHSB(color.getRed(), color.getGreen(), color.getBlue(), null);
        assertTrue(hsb[2] >= 0.45f, color.toString());
        assertEquals(MusicEmbeds.PRIMARY, ArtworkColors.of(null));
    }
}
