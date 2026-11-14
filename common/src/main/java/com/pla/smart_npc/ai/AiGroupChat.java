package com.pla.smart_npc.ai;

import java.util.Locale;
import java.util.regex.Pattern;

/** Public-address intent; ordinary questions about one person are not an open invitation. */
public final class AiGroupChat {
    private static final Pattern ADDRESS = Pattern.compile("(?<![\\p{L}\\p{N}_])(?:you (?:all|guys)|all (?:of )?you|all guys|everyone|everybody|guys|folks|y'all|yall|mọi người|các bạn)(?![\\p{L}\\p{N}_])|大家|各位|你们|全服|兄弟们|大伙|伙计们|小伙伴们");
    private static final Pattern OPEN_QUESTION = Pattern.compile("(?<![\\p{L}\\p{N}_])(?:anyone|anybody|any1)(?![\\p{L}\\p{N}_])|\\bwho(?:\\s+wants?(?:\\s+to)?|\\s+would\\s+like|\\s+needs?|\\s+has|\\s+got|\\s+can|\\s+is\\s+(?:up\\s+for|down\\s+for|interested)|['’]s\\s+(?:up\\s+for|down\\s+for|interested))\\b|\\b(?:can|could|would) (?:someone|somebody)\\b|\\b(?:any|looking for) volunteers?\\b|\\bany takers\\b|有人(?:吗|么|在|想|要|有|能|愿意|愿不愿意|可以|一起)|有没有人|谁(?:想|要|有|能|愿意|愿不愿意|可以|来|一起)|有谁|有愿意");
    private static final Pattern ALL_END = Pattern.compile("(?:^|[\\s,])all[\\s!?.,]*$");
    private static final Pattern PERSONAL_REPORT = Pattern.compile("^(?:i|we) (?:don['’]t|do not|didn['’]t|did not|haven['’]t|have not|never|can['’]t|cannot)\\b|^(?:我|我们)(?:不|没|找不到|看不到)");

    private AiGroupChat() {}

    /** Only equivalent greetings coalesce; questions and corrections remain new turns. */
    public static boolean sameGreeting(String first, String second) {
        return greeting(first) && greeting(second);
    }

    private static boolean greeting(String message) {
        if (message == null) return false;
        String text = message.strip().toLowerCase(Locale.ROOT).replaceAll("[!?. ,]+", " ").strip();
        return text.matches("(?:hi|hey|hello|yo|sup)(?: (?:guys|everyone|everybody|all|folks|bro))?")
                || text.matches("(?:大家|各位|你们)?(?:你好|好|嗨|哈喽)")
                || text.matches("(?:chào|hello|hế lô)(?: (?:mọi người|các bạn|anh em|ông))?");
    }

    public static boolean isAddressedToGroup(String message) {
        if (message == null || message.isBlank()) return false;
        String text = message.strip().toLowerCase(Locale.ROOT);
        if (ADDRESS.matcher(text).find() || ALL_END.matcher(text).find() || text.matches(".*(?:mấy ní|các vợ|ê bây|cả server|cả làng|ai có |ai muốn |ai cần |ai cho |ai cứu |ai giúp |ai đi |ai rảnh|ai chơi|có ai |ê tao cần |ê t cần ).*")) return true;
        // "I didn't see anyone" is a report, whereas "Has anyone seen a spider?" is public chat.
        return !PERSONAL_REPORT.matcher(text).find() && OPEN_QUESTION.matcher(text).find();
    }
}
