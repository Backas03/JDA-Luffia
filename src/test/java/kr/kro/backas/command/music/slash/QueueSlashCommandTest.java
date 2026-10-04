package kr.kro.backas.command.music.slash;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueueSlashCommandTest {

    private static final int DISCORD_COMPONENT_LIMIT = 40;

    @Test
    void rowShowsNumberLinkAndDetailsOnASmallSecondLine() {
        assertEquals("3. [곡](https://y/1)\n-# 아티스트 · 3:33 · 박카스",
                QueueSlashCommand.rowText(3, "[곡](https://y/1)", List.of("아티스트", "3:33", "박카스")));
    }

    @Test
    void headingSummarisesCountLengthAndSingleRequester() {
        assertEquals("**대기열** · 비어 있음", QueueSlashCommand.heading("대기열", 0, null, null));
        assertEquals("**대기열** · 437곡 · 25시간 12분 · 요청 박카스", QueueSlashCommand.heading("대기열", 437, "25시간 12분", "박카스"));
        assertEquals("**다음 추천** · 2곡 · 7분", QueueSlashCommand.heading("다음 추천", 2, "7분", null));
    }

    @Test
    void overflowOnlyAppearsWhenRowsWereCutOff() {
        assertEquals("", QueueSlashCommand.overflow(5, 5));
        assertEquals("-# 외 7곡", QueueSlashCommand.overflow(12, 5));
    }

    @Test
    void totalLengthReadsNaturally() {
        assertEquals("45초", QueueSlashCommand.totalLength(45_000));
        assertEquals("7분", QueueSlashCommand.totalLength(7 * 60_000 + 20_000));
        assertEquals("1시간", QueueSlashCommand.totalLength(60 * 60_000));
        assertEquals("25시간 12분", QueueSlashCommand.totalLength((25 * 60 + 12) * 60_000L));
    }

    @Test
    void thumbnailRowsStayWithinDiscordsComponentBudget() {
        int container = 1;
        int nowPlayingSection = 3;
        int settings = 1;
        int separators = 3;
        int headings = 2;
        int queueRows = QueueSlashCommand.MAX_THUMBNAIL_ROWS * 3 + 1;
        int autoRows = Math.min(QueueSlashCommand.MAX_AUTO_ROWS, QueueSlashCommand.MAX_THUMBNAIL_ROWS) * 3 + 1;
        int footer = 1;
        assertTrue(container + nowPlayingSection + settings + separators + headings + queueRows + autoRows + footer
                <= DISCORD_COMPONENT_LIMIT);
    }
}
