package kr.kro.backas.util;

import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;

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

    public static void replyConvertedError(String message, Message msg, Exception e) {
        msg.reply(
                message + "\n" + convertDiscord(e)
        ).queue();
    }

    public static void replyConvertedError(String message, SlashCommandInteractionEvent event, Exception e) {
        event.reply(
                message + "\n" + convertDiscord(e)
        ).queue();
    }

    public static String convertDiscord(Exception e) {
        return convertDiscord(e, "elm");
    }

    public static void replyError(String message, IReplyCallback callback, Exception e) {
        callback.reply(
                message + "\n" + StackTraceUtil.convertDiscord(e)
        ).queue();
    }

    public static void replyError(String message, Message msg, Exception e) {
        msg.reply(
                message + "\n" + StackTraceUtil.convertDiscord(e)
        ).queue();
    }
}
