package kr.kro.backas.util;

import net.dv8tion.jda.api.entities.Message;

import java.io.PrintWriter;
import java.io.StringWriter;

public class StackTraceUtil {
    public static String convert(Exception e) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        e.printStackTrace(pw);
        return (e.getMessage() + "\n" + sw).substring(0, Math.min(900, sw.toString().length()));
    }

    public static String convertDiscord(Exception e, String lang) {
        return "```" + lang + "\n" + convert(e) + "```";
    }

    public static String convertDiscord(Exception e) {
        return convertDiscord(e, "elm");
    }

    public static void replyError(String message, Message msg, Exception e) {
        msg.reply(
                message + "\n" + StackTraceUtil.convertDiscord(e)
        ).queue();
    }
}
