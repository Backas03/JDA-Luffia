package kr.kro.backas.command.music.slash;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueueSlashCommandTest {

    private static final int DISCORD_COMPONENT_LIMIT = 40;

    @Test
    void rowShowsNumberLinkAndDetailsOnASmallSecondLine() {
        assertEquals("**3.** [곡](https://y/1)\n-# 아티스트 · 3분 33초 · 박카스",
                QueueSlashCommand.rowText(3, "[곡](https://y/1)", List.of("아티스트", "3분 33초", "박카스")));
    }

    @Test
    void overflowOnlyAppearsWhenRowsWereCutOff() {
        assertEquals("", QueueSlashCommand.overflow(5, 5));
        assertEquals("-# ... 외 7곡", QueueSlashCommand.overflow(12, 5));
    }

    @Test
    void thumbnailRowsStayWithinDiscordsComponentBudget() {
        int container = 1;
        int nowPlayingSection = 3;
        int separators = 4;
        int headings = 3;
        int queueRows = QueueSlashCommand.MAX_VIEW_ROWS * 3 + 1;
        int autoRows = QueueSlashCommand.MAX_AUTO_ROWS * 3 + 1;
        int footer = 1;
        assertTrue(container + nowPlayingSection + separators + headings + queueRows + autoRows + footer <= DISCORD_COMPONENT_LIMIT);
    }
}
