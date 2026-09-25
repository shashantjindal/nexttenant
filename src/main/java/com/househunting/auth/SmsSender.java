package com.househunting.auth;

/**
 * Sends a text message.
 *
 * <p>An interface with one method, so the delivery provider is a detail the
 * rest of the application never sees. Today it is {@link ConsoleSmsSender};
 * tomorrow it is MSG91 or Twilio, and only the implementation changes.
 *
 * <p>This matters more than usual here because of DLT: Indian carriers require
 * every transactional SMS template to be pre-registered with a regulator-approved
 * entity, which takes weeks. Developing against the console means that
 * paperwork runs in parallel with the code rather than blocking it.
 */
public interface SmsSender {

    /**
     * @param phoneE164 destination in E.164 form
     * @param message   the body; must match a registered DLT template in production
     */
    void send(String phoneE164, String message);
}
