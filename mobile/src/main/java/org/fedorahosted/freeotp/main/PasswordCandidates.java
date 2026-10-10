package org.fedorahosted.freeotp.main;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

// 由用户输入的密码生成常见变体候选，供还原时逐个校验，顺序=大概率的变体在前。
// 不按 PasswordActivity 的复杂度规则过滤候选：该规则是后加的，旧备份的密码可能不满足它。
public final class PasswordCandidates {
    private static final int MAX_SINGLE_SUBSTITUTION_VARIANTS = 20;

    private static final char[] LEET_FROM = { 'a', 'e', 'i', 'o', 's', 't' };
    private static final char[] LEET_SYMBOL = { '@', '3', '1', '0', '5', '7' };
    private static final char[] LEET_DIGIT = { '4', '3', '1', '0', '5', '7' };

    private static final String[] SUFFIX_DIGITS = {
            "1", "0", "01", "12", "123", "1234", "99", "88", "66", "520", "666", "888" };
    private static final String[] SUFFIX_SYMBOLS = { "!", "!!", "@", "#", "$", ".", "_", "-", "*" };
    private static final String[] PREFIXES = { "1", "0", "!", "@", "#", "$" };
    private static final int FIRST_YEAR = 2010;
    private static final int LAST_YEAR = 2026;

    private PasswordCandidates() {
    }

    public static List<String> generate(String base) {
        if (base == null || base.isEmpty()) {
            return Collections.emptyList();
        }

        Set<String> candidates = new LinkedHashSet<>();
        candidates.add(base);
        addCaseVariants(candidates, base);
        addWholeSubstitution(candidates, base, LEET_SYMBOL);
        addWholeSubstitution(candidates, base, LEET_DIGIT);
        addSingleSubstitutions(candidates, base);
        addSuffixed(candidates, base);
        addPrefixed(candidates, base);
        addWhitespaceVariants(candidates, base);
        addTailVariants(candidates, base);
        return new ArrayList<>(candidates);
    }

    private static void addCaseVariants(Set<String> out, String base) {
        out.add(Character.toUpperCase(base.charAt(0)) + base.substring(1));
        out.add(base.toUpperCase(Locale.ROOT));
        out.add(base.toLowerCase(Locale.ROOT));
        out.add(swapCase(base));
    }

    private static String swapCase(String base) {
        StringBuilder sb = new StringBuilder(base.length());
        for (char c : base.toCharArray()) {
            if (Character.isUpperCase(c)) {
                sb.append(Character.toLowerCase(c));
            } else if (Character.isLowerCase(c)) {
                sb.append(Character.toUpperCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static void addWholeSubstitution(Set<String> out, String base, char[] replacements) {
        StringBuilder sb = new StringBuilder(base.length());
        boolean changed = false;
        for (char c : base.toCharArray()) {
            int i = leetIndex(c);
            if (i >= 0) {
                sb.append(replacements[i]);
                changed = true;
            } else {
                sb.append(c);
            }
        }
        if (changed) {
            out.add(sb.toString());
        }
    }

    private static void addSingleSubstitutions(Set<String> out, String base) {
        int added = 0;
        for (int i = 0; i < base.length() && added < MAX_SINGLE_SUBSTITUTION_VARIANTS; i++) {
            int index = leetIndex(base.charAt(i));
            if (index < 0) {
                continue;
            }
            for (char[] replacements : new char[][] { LEET_SYMBOL, LEET_DIGIT }) {
                if (added >= MAX_SINGLE_SUBSTITUTION_VARIANTS) {
                    break;
                }
                String variant = base.substring(0, i) + replacements[index] + base.substring(i + 1);
                if (out.add(variant)) {
                    added++;
                }
            }
        }
    }

    private static void addSuffixed(Set<String> out, String base) {
        for (String digits : SUFFIX_DIGITS) {
            out.add(base + digits);
        }
        for (String symbol : SUFFIX_SYMBOLS) {
            out.add(base + symbol);
        }
        for (int year = FIRST_YEAR; year <= LAST_YEAR; year++) {
            out.add(base + year);
        }
    }

    private static void addPrefixed(Set<String> out, String base) {
        for (String prefix : PREFIXES) {
            out.add(prefix + base);
        }
    }

    private static void addWhitespaceVariants(Set<String> out, String base) {
        out.add(" " + base);
        out.add(base + " ");
        out.add(" " + base + " ");
        out.add(base + "\n");
    }

    private static void addTailVariants(Set<String> out, String base) {
        char last = base.charAt(base.length() - 1);
        out.add(base + last);
        if (base.length() >= 2 && base.charAt(base.length() - 2) == last) {
            out.add(base.substring(0, base.length() - 1));
        }
    }

    private static int leetIndex(char c) {
        char lower = Character.toLowerCase(c);
        for (int i = 0; i < LEET_FROM.length; i++) {
            if (LEET_FROM[i] == lower) {
                return i;
            }
        }
        return -1;
    }
}
