package org.oagi.score.gateway.http.api.ai_management.agent;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Language-level delegation facts extracted from the current accepted user turn. */
public final class DelegationIntent {

    private static final String LATIN_AGENT = "(?:sub[\\s-]?agents?|agents?)";
    private static final String KOREAN_AGENT = "(?:서브[\\s-]?)?에이전트(?:들)?";
    private static final Pattern AGENT_TERM = Pattern.compile(
            "(?iu)(?:\\b" + LATIN_AGENT + "\\b|\\b" + LATIN_AGENT
                    + "(?:를|을)|" + KOREAN_AGENT + ")");
    private static final Pattern DELEGATION_TERM = Pattern.compile(
            "(?iu)(?:\\bfan[\\s-]?out\\b|\\bspawn\\b|\\bdelegate\\b|\\bin parallel\\b|"
                    + "\\bsplit up\\b|병렬|동시|분산|위임|펼쳐)");
    private static final Pattern AGENT_USAGE_TERM = Pattern.compile(
            "(?iu)(?:\\b(?:use|using|with|via)\\s+" + LATIN_AGENT + "\\b|"
                    + "(?:\\b" + LATIN_AGENT + "(?:를|을)|" + KOREAN_AGENT
                    + "(?:를|을)?)\\s*(?:사용|이용|활용|써))");
    private static final Pattern NEGATED_DELEGATION = Pattern.compile(
            "(?iu)(?:(?:do not|don't|never|without|no|stop)\\s+"
                    + "(?:(?:spawn|use|using)\\s+|delegate(?:\\s+to)?\\s+)?" + LATIN_AGENT
                    + "|" + LATIN_AGENT + ".{0,24}(?:forbidden|disabled|never)"
                    + "|(?:\\b" + LATIN_AGENT + "(?:를|을)|" + KOREAN_AGENT
                    + "(?:를|을)?).{0,16}"
                    + "(?:사용하지|이용하지|활용하지|쓰지|금지))");
    private static final Pattern NUMERIC_AGENT_COUNT = Pattern.compile(
            "(?iu)([2-4])\\s*(?:sub[\\s-]?)?agents?");
    private static final Pattern WORD_AGENT_COUNT = Pattern.compile(
            "(?iu)\\b(two|three|four)\\s+(?:sub[\\s-]?)?agents?\\b");
    private static final String LOCALIZED_AGENT = "(?:(?:sub[\\s-]?)?agents?"
            + "|(?:서브[\\s-]?)?에이전트(?:들)?)";
    private static final Pattern LOCALIZED_PREFIX_AGENT_COUNT = Pattern.compile(
            "(?iu)([2-4])\\s*(?:개|명)(?:의)?\\s*" + LOCALIZED_AGENT);
    private static final Pattern LOCALIZED_SUFFIX_AGENT_COUNT = Pattern.compile(
            "(?iu)" + LOCALIZED_AGENT + "(?:를|을)?\\s*([2-4])\\s*(?:개|명)");
    private static final Pattern LOCALIZED_PREFIX_WORD_AGENT_COUNT = Pattern.compile(
            "(?iu)(두|세|네)\\s*(?:개|명)(?:의)?\\s*" + LOCALIZED_AGENT);
    private static final Pattern LOCALIZED_SUFFIX_WORD_AGENT_COUNT = Pattern.compile(
            "(?iu)" + LOCALIZED_AGENT + "(?:를|을)?\\s*(두|세|네)\\s*(?:개|명)");

    private DelegationIntent() {
    }

    public static boolean explicitlyRequestsAgents(String prompt) {
        if (negatesDelegation(prompt)) return false;
        return explicitlyRequestsFanOut(prompt) || AGENT_USAGE_TERM.matcher(prompt).find();
    }

    public static boolean mentionsAgents(String prompt) {
        return prompt != null && !prompt.isBlank() && AGENT_TERM.matcher(prompt).find();
    }

    public static boolean explicitlyRequestsFanOut(String prompt) {
        if (negatesDelegation(prompt)) return false;
        return AGENT_TERM.matcher(prompt).find() && DELEGATION_TERM.matcher(prompt).find();
    }

    public static boolean explicitlyNegatesAgents(String prompt) {
        return prompt != null && !prompt.isBlank()
                && NEGATED_DELEGATION.matcher(prompt).find();
    }

    public static Optional<Integer> requestedAgentCount(String prompt) {
        if (prompt == null || prompt.isBlank()) return Optional.empty();
        Matcher numeric = NUMERIC_AGENT_COUNT.matcher(prompt);
        if (numeric.find()) {
            return Optional.of(Integer.parseInt(numeric.group(1)));
        }
        Optional<Integer> localizedNumeric = firstNumericCount(
                prompt, LOCALIZED_PREFIX_AGENT_COUNT, LOCALIZED_SUFFIX_AGENT_COUNT);
        if (localizedNumeric.isPresent()) return localizedNumeric;
        Matcher word = WORD_AGENT_COUNT.matcher(prompt);
        if (word.find()) {
            return Optional.of(switch (word.group(1).toLowerCase(Locale.ROOT)) {
                case "two" -> 2;
                case "three" -> 3;
                case "four" -> 4;
                default -> throw new IllegalStateException("Unsupported bounded agent count.");
            });
        }
        return firstLocalizedWordCount(
                prompt, LOCALIZED_PREFIX_WORD_AGENT_COUNT, LOCALIZED_SUFFIX_WORD_AGENT_COUNT);
    }

    private static Optional<Integer> firstNumericCount(String prompt, Pattern... patterns) {
        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(prompt);
            if (matcher.find()) return Optional.of(Integer.parseInt(matcher.group(1)));
        }
        return Optional.empty();
    }

    private static Optional<Integer> firstLocalizedWordCount(String prompt, Pattern... patterns) {
        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(prompt);
            if (!matcher.find()) continue;
            return Optional.of(switch (matcher.group(1)) {
                case "두" -> 2;
                case "세" -> 3;
                case "네" -> 4;
                default -> throw new IllegalStateException("Unsupported localized agent count.");
            });
        }
        return Optional.empty();
    }

    private static boolean negatesDelegation(String prompt) {
        return prompt == null || prompt.isBlank() || explicitlyNegatesAgents(prompt);
    }
}
