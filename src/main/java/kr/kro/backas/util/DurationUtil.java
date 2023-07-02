package kr.kro.backas.util;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

public final class DurationUtil {

    public static String formatDuration(int seconds) {
        List<String> timeUnits = Arrays.asList("시간", "분", "초");
        int multiply = 3600;
        int temp = seconds;
        StringBuilder sb = new StringBuilder();
        for (String unit : timeUnits) {
            int value = Math.floorDiv(temp, multiply);
            if (value > 0) {
                sb.append(" ").append(value).append(unit);
                temp -= value * multiply;
            }
            multiply /= 60;
        }
        return sb.toString().replaceFirst("\\s", "");
    }

    public static String formatDurationColon(int seconds) {
        int hours = seconds / 3600;
        int minutes = (seconds / 60) % 60;
        seconds = seconds % 60;
        if (hours != 0) {
            return String.format("%02d", hours) + ":" + String.format("%02d", minutes) + ":" + String.format("%02d", seconds);
        }
        return String.format("%02d", minutes) + ":" + String.format("%02d", seconds);
    }
}
