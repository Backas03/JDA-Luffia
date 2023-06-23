package kr.kro.backas.util;

import java.io.PrintWriter;
import java.io.StringWriter;

public class StackTraceUtil {
    public static String convert(Exception e) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        e.printStackTrace(pw);
        return (e.getMessage() + "\"" + sw + "\"").substring(0, Math.min(1900, sw.toString().length()));
    }
}
