package org.fedorahosted.freeotp.main;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class PasswordCandidatesTest {
    @Test
    public void returnsEmptyListForBlankInput() {
        assertTrue(PasswordCandidates.generate(null).isEmpty());
        assertTrue(PasswordCandidates.generate("").isEmpty());
    }

    @Test
    public void firstCandidateIsTheInputItself() {
        assertEquals("Password123!", PasswordCandidates.generate("Password123!").get(0));
    }

    @Test
    public void includesCaseVariants() {
        List<String> candidates = PasswordCandidates.generate("passWord123!");

        assertTrue(candidates.contains("PassWord123!"));
        assertTrue(candidates.contains("PASSWORD123!"));
        assertTrue(candidates.contains("password123!"));
        assertTrue(candidates.contains("PASSwORD123!"));
    }

    @Test
    public void includesWholeLeetSubstitutions() {
        List<String> candidates = PasswordCandidates.generate("password");

        assertTrue(candidates.contains("p@55w0rd"));
        assertTrue(candidates.contains("p455w0rd"));
    }

    @Test
    public void includesSingleSpotLeetSubstitutions() {
        List<String> candidates = PasswordCandidates.generate("password");

        assertTrue(candidates.contains("p@ssword"));
        assertTrue(candidates.contains("p4ssword"));
        assertTrue(candidates.contains("pa5sword"));
        assertTrue(candidates.contains("pas5word"));
        assertTrue(candidates.contains("passw0rd"));
    }

    @Test
    public void includesSuffixPrefixWhitespaceAndTailVariants() {
        List<String> candidates = PasswordCandidates.generate("password123!");

        assertTrue(candidates.contains("password123!1"));
        assertTrue(candidates.contains("password123!!"));
        assertTrue(candidates.contains("password123!2020"));
        assertTrue(candidates.contains("1password123!"));
        assertTrue(candidates.contains(" password123!"));
        assertTrue(candidates.contains("password123! "));

        // 末尾已重复的字符可以去掉一个
        assertTrue(PasswordCandidates.generate("password!!").contains("password!"));
    }

    @Test
    public void singleSpotSubstitutionsAreCappedAt20() {
        String base = new String(new char[30]).replace('\0', 'o');

        int singleZeroSubstitutions = 0;
        for (String candidate : PasswordCandidates.generate(base)) {
            if (countDifferences(base, candidate) == 1 && candidate.indexOf('0') >= 0) {
                singleZeroSubstitutions++;
            }
        }
        assertEquals(20, singleZeroSubstitutions);
    }

    // 当前规则预算上限 77 个（R0..R7），断言 100 是为了守住"默认档一轮跑完"这个前提
    @Test
    public void staysWithinOneRoundBudget() {
        List<String> candidates = PasswordCandidates.generate("aeiostaeiostaeiostaeiost");

        assertTrue("unexpected size: " + candidates.size(), candidates.size() <= 100);
    }

    private static int countDifferences(String a, String b) {
        if (a.length() != b.length()) {
            return Integer.MAX_VALUE;
        }
        int differences = 0;
        for (int i = 0; i < a.length(); i++) {
            if (a.charAt(i) != b.charAt(i)) {
                differences++;
            }
        }
        return differences;
    }
}
