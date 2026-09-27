package com.nexttenant.auth;

import com.nexttenant.user.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Signs a verified user in.
 *
 * <p>Spring Security's own login machinery is bypassed because there is no
 * password to check — the OTP already established identity. That means two
 * steps Spring would normally handle have to be done explicitly here, and both
 * are easy to leave out without noticing.
 */
@Service
public class SessionLogin {

    private static final List<SimpleGrantedAuthority> USER_AUTHORITIES =
            List.of(new SimpleGrantedAuthority("ROLE_USER"));

    private static final List<SimpleGrantedAuthority> ADMIN_AUTHORITIES =
            List.of(new SimpleGrantedAuthority("ROLE_USER"),
                    new SimpleGrantedAuthority("ROLE_ADMIN"));

    private final SecurityContextRepository contextRepository;

    public SessionLogin(SecurityContextRepository contextRepository) {
        this.contextRepository = contextRepository;
    }

    public void signIn(User user, HttpServletRequest request, HttpServletResponse response) {
        // ---- 1. Rotate the session id, BEFORE storing the authentication ----
        //
        // Session fixation: an attacker obtains a session id (just visiting the
        // site provides one), gets the victim to use it — a link, a shared
        // machine, an injected cookie — and waits. If the id does not change
        // when the victim authenticates, the attacker's copy of that id is now
        // an authenticated session, and they are signed in as the victim
        // without ever knowing a code.
        //
        // changeSessionId() issues a new identifier and carries the contents
        // across, so the attacker is left holding an id that refers to nothing.
        // Spring does this automatically for form login; this login is
        // programmatic, so it is our job.
        request.changeSessionId();

        AuthenticatedUser principal = new AuthenticatedUser(
                user.getId(), user.getFirstName(), user.isAdmin());

        Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(
                principal, null, user.isAdmin() ? ADMIN_AUTHORITIES : USER_AUTHORITIES);

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);

        // ---- 2. Save the context explicitly --------------------------------
        //
        // Setting the holder alone lasts only for the current request: the
        // holder is thread-local and cleared when the request ends. Since
        // Spring Security 6 the context is no longer persisted automatically,
        // which produces a memorable bug — login appears to work, and the user
        // is anonymous again on the very next click.
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);

        // The login flow is finished; drop the number from the session rather
        // than leaving it in SPRING_SESSION_ATTRIBUTES for the next 30 days.
        LoginFlow.clear(request.getSession());
    }
}
