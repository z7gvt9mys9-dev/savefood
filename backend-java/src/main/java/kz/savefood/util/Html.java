package kz.savefood.util;

/** Port of Python's {@code html.escape} — user input must be escaped before being
 * embedded in a Telegram message sent with {@code parse_mode=HTML}. */
public final class Html {
    private Html() {
    }

    public static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#x27;");
    }
}
