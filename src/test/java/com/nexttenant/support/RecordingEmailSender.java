package com.nexttenant.support;

import com.nexttenant.mail.EmailSender;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Captures emails instead of sending them, so a test can read the code that
 * was issued.
 *
 * <p>The twin of {@link RecordingSmsSender}, and the reason
 * {@link EmailSender} was an interface from the start: because delivery sits
 * behind one method, the tests never touch a mail provider, need no network
 * and cost nothing to run — and the production path is the same code with a
 * different bean.
 *
 * <p>Registered by a test's own {@code @TestConfiguration} alongside
 * {@code nexttenant.mail.provider=recording}, which stands
 * {@code ConsoleEmailSender} down. Naming a provider is deterministic;
 * relying on bean ordering is not.
 */
public class RecordingEmailSender implements EmailSender {

    private static final Pattern SIX_DIGITS = Pattern.compile("\\b(\\d{6})\\b");

    public record Sent(String to, String subject, String body) {
    }

    private final List<Sent> sent = new ArrayList<>();

    @Override
    public void send(String to, String subject, String body) {
        sent.add(new Sent(to, subject, body));
    }

    public List<Sent> all() {
        return List.copyOf(sent);
    }

    public int count() {
        return sent.size();
    }

    public Sent last() {
        if (sent.isEmpty()) {
            throw new IllegalStateException("no email was sent");
        }
        return sent.get(sent.size() - 1);
    }

    /**
     * Pulls the six-digit code out of the most recent email.
     *
     * <p>Reads the BODY rather than the subject line, so a test still passes if
     * the subject stops carrying the code — the body is the part that must
     * always contain it.
     */
    public String lastCode() {
        Matcher matcher = SIX_DIGITS.matcher(last().body());
        if (!matcher.find()) {
            throw new IllegalStateException("no six-digit code in: " + last().body());
        }
        return matcher.group(1);
    }

    public void clear() {
        sent.clear();
    }
}
