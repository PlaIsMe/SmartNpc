package com.pla.smart_npc.ai;

import java.util.Locale;
import java.util.regex.Pattern;

/** Conservative chat intent hints. These never bypass world/target validation. */
public final class AiDialogueIntent {
    private static final Pattern INSULT = Pattern.compile("(?i)\\b(?:fuck (?:you|off)|you (?:suck|idiot|loser)|shut up|stupid (?:npc|idiot))\\b|傻逼|傻子|废物|滚开|去死|去你的|闭嘴");
    private static final Pattern PEACE = Pattern.compile("(?i)\\b(?:stop|truce|sorry|forgive|don't fight|do not fight|leave me alone)\\b|别打|停手|对不起|饶了|和好");
    private static final Pattern ATTACK = Pattern.compile("(?i)(?:^|[,!。！]\\s*|\\b(?:let's|lets|let us|go|please|can you|could you|help me)\\s+)(?:attack|kill|fight|hit)\\b|^(?:嘿[，, ]*)?(?:一起|帮我|请你|快去|去)?(?:攻击|打他|打它|杀了|揍)|一起(?:攻击|打|杀|揍)");
    private static final Pattern PROFANITY = Pattern.compile("(?i)\\b(?:fuck\\w*|shit\\w*|bastard\\w*)\\b|他妈|傻逼|操你|去你的|滚你的");
    private AiDialogueIntent() {}
    private static final Pattern VI_INSULT = Pattern.compile("(?iu)(?<![\\p{L}\\p{N}_])(?:đmm|đcmm|đcm mày|đcm m|địt mẹ mày|câm mồm|mày ngu|m ngu)(?![\\p{L}\\p{N}_])");
    private static final Pattern VI_PEACE = Pattern.compile("(?iu)(?<![\\p{L}\\p{N}_])(?:xin lỗi|tha tao|tha t|tha cho|đừng đánh|đừng đấm|ngừng đánh|ngừng tay|dừng tay|thôi đánh|nghỉ đánh|hòa nhé|hoà nhé|làm hòa|làm hoà)(?![\\p{L}\\p{N}_])");
    private static final Pattern VI_ATTACK = Pattern.compile("(?iu)(?:^|[,!?]\\s*|\\b(?:cùng|đi|hãy|giúp tao|giúp t|đi cùng tao|cùng tao|cùng t)\\s+)(?:đánh|giết|đấm|tấn công|xử)\\s");
    public static boolean peace(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("don't stop") || lower.contains("do not stop") || lower.contains("keep fighting") || lower.contains("继续打") || lower.contains("别停手") || lower.contains("đừng dừng") || lower.contains("cứ đánh") || lower.contains("đánh tiếp")) return false;
        return PEACE.matcher(text).find() || VI_PEACE.matcher(text).find();
    }
    public static boolean insult(String text) { return !peace(text) && (INSULT.matcher(text).find() || VI_INSULT.matcher(text).find()); }
    public static boolean insult(String text, String npcName) {
        if (insult(text)) return true;
        return !peace(text) && Pattern.compile("(?iu)(?<![\\p{L}\\p{N}_])(?:đcm|đmm|đcmm)\\s+" + Pattern.quote(npcName) + "(?![\\p{L}\\p{N}_])").matcher(text).find();
    }
    public static boolean attack(String text) { return !peace(text) && (ATTACK.matcher(text.strip()).find() || VI_ATTACK.matcher(text.strip()).find()); }
    public static boolean profane(String text) { return PROFANITY.matcher(text).find(); }
    public static boolean gift(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.matches(".*\\b(?:give|spare|share|hand|pass|have|got|about)\\b.*") || lower.contains("给我") || lower.contains("分我") || lower.contains("借我") || lower.matches(".*(?:cho tao|cho t |cho tôi|cho tớ|xin |chia |có dư|có thừa|còn dư|đưa tao|đưa t ).*");
    }
    public static boolean gather(String text) {
        return text.toLowerCase(Locale.ROOT).matches(".*\\b(?:gather|collect|chop|mine|dig|find logs)\\b.*") || text.contains("采集") || text.contains("砍") || text.contains("挖") || text.contains("收集") || text.matches("(?iu).*(?:đào |đốn |chặt |thu thập|kiếm gỗ|tìm gỗ).*");
    }
    public static boolean smallTalk(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return information(lower) || lower.matches(".*\\b(?:weather|raining|rainy|snow|snowing|snowy|sunny|daytime|nighttime)\\b.*")
                || lower.matches(".*\\b(?:is it|is this) (?:day|night)\\b.*")
                || lower.matches("(?:hi|hey|hello|yo|sup|thanks|thank you|lol|haha)[!?. ,]*")
                || lower.contains("天气") || lower.contains("下雨") || lower.contains("下雪") || lower.contains("晴天") || lower.contains("白天") || lower.contains("晚上")
                || lower.matches(".*(?:thời tiết|trời mưa|đang mưa|mưa không|tuyết rơi|dưới tuyết|có tuyết|tuyết không|trời nắng|ban ngày|ban đêm).*")
                || lower.matches("(?:chào|hello|hế lô|ê|cảm ơn|cám ơn|haha|hehe|ty|thx|ok|okay|kk|gg|wp|gg wp|ggwp|nice|bye|brb|afk)(?:[!?. ,]+|bro|ní|nha|nhé|tí)*");
    }
    public static boolean information(String text) {
        return text.matches("(?iu).*\\b(?:coords?|coordinates?|where are you|where is it|what weapon|which weapon|your weapon|your model|my model|your emote|my emote)\\b.*")
                || text.matches(".*(?:坐标|你在哪|你在哪里|什么武器|啥武器|你的模型|我的模型|你的表情|我的表情).*")
                || text.matches("(?iu).*(?:tọa độ|toạ độ|ở đâu|vũ khí gì|cầm vũ khí|model của|emote của).*");
    }
}
