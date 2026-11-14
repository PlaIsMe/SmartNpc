package com.pla.smart_npc.ai;

import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.BitSet;
import java.util.Locale;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Bounded, session-only wording memory, owned exclusively by the server thread. */
final class AiSpeechVariety {
    static final String INSTRUCTION = "\nAnswer the current speaker.message or event. rejected_wording is an unsuccessful "
            + "answer, not a new topic or fact. Rephrase briefly while keeping the same supplied facts and allowed actions.";
    private final ArrayDeque<String> spoken = new ArrayDeque<>();
    // Fixed 2 MiB session membership filter: no old sentences are evicted. A rare false positive
    // only requests different wording; it cannot let a previously recorded sentence through.
    private static final int FILTER_BITS = 1 << 24;
    private static final Set<String> ACKNOWLEDGEMENTS = Set.of("hi", "hey", "hello", "ok", "okay", "yes", "no", "yeah", "lol", "haha", "你好", "嗨", "好", "嗯", "是", "不", "chào", "ừ", "vâng");
    private final BitSet session = new BitSet(FILTER_BITS);
    private final MessageDigest digest;

    AiSpeechVariety() {
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    void remember(String text) {
        spoken.addLast(text);
        while (spoken.size() > 10) spoken.removeFirst();
        for (int index : indices(normalize(text))) session.set(index);
    }

    boolean repeats(String text) {
        String candidate = normalize(text);
        // Brief acknowledgements and pure emotes remain ordinary conversation in every locale.
        if (candidate.codePointCount(0, candidate.length()) < 2 || ACKNOWLEDGEMENTS.contains(candidate)) return false;
        boolean seen = true;
        for (int index : indices(candidate)) seen &= session.get(index);
        if (seen) return true;
        for (String previous : spoken) {
            String prior = normalize(previous);
            if (candidate.equals(prior)) return true;
            String left = body(text);
            String right = body(previous);
            if (left.length() >= 24 && right.length() >= 24 && samePolarity(left, right)
                    && distance(left, right) <= Math.min(left.length(), right.length()) / 10) return true;
        }
        return false;
    }

    private int[] indices(String text) {
        byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
        int[] indices = new int[4];
        for (int i = 0; i < indices.length; i++) {
            int offset = i * 3;
            indices[i] = ((hash[offset] & 255) << 16) | ((hash[offset + 1] & 255) << 8) | (hash[offset + 2] & 255);
        }
        return indices;
    }

    private static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private static String body(String text) {
        return normalize(text.toLowerCase(Locale.ROOT).replaceFirst("^(?:fine|alright|okay|ok|well|yeah|bro)[,!.: ]+", ""));
    }

    private static boolean samePolarity(String left, String right) {
        // Do not classify a refusal as a spelling variation of an acceptance.
        for (String marker : new String[]{"not", "dont", "cant", "wont", "never", "不", "没", "không", "chưa"}) {
            if (left.contains(marker) != right.contains(marker)) return false;
        }
        return true;
    }

    private static int distance(String left, String right) {
        int[] row = new int[right.length() + 1];
        for (int j = 0; j < row.length; j++) row[j] = j;
        for (int i = 1; i <= left.length(); i++) {
            int diagonal = row[0];
            row[0] = i;
            for (int j = 1; j <= right.length(); j++) {
                int above = row[j];
                row[j] = Math.min(Math.min(row[j] + 1, row[j - 1] + 1), diagonal + (left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1));
                diagonal = above;
            }
        }
        return row[right.length()];
    }
}
