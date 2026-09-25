package com.househunting.support;

import com.househunting.auth.SmsSender;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Captures messages instead of sending them, so a test can read the code that
 * was issued.
 *
 * <p>This is what the {@link SmsSender} interface was for. Because delivery sits
 * behind one method, the tests never touch an SMS provider, need no network and
 * cost nothing to run — and the production path is the same code with a
 * different bean.
 *
 * <p>Defining this as a bean also displaces {@code ConsoleSmsSender}
 * automatically, since that one is {@code @ConditionalOnMissingBean}.
 */
public class RecordingSmsSender implements SmsSender {

    private static final Pattern SIX_DIGITS = Pattern.compile("\\b(\\d{6})\\b");

    public record Sent(String phoneE164, String message) {
    }

    private final List<Sent> sent = new ArrayList<>();

    @Override
    public void send(String phoneE164, String message) {
        sent.add(new Sent(phoneE164, message));
    }

    public List<Sent> all() {
        return List.copyOf(sent);
    }

    public int count() {
        return sent.size();
    }

    public Sent last() {
        if (sent.isEmpty()) {
            throw new IllegalStateException("no message was sent");
        }
        return sent.get(sent.size() - 1);
    }

    /** Pulls the six-digit code out of the most recent message. */
    public String lastCode() {
        Matcher matcher = SIX_DIGITS.matcher(last().message());
        if (!matcher.find()) {
            throw new IllegalStateException("no six-digit code in: " + last().message());
        }
        return matcher.group(1);
    }

    public void clear() {
        sent.clear();
    }
}
