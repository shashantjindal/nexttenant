package com.nexttenant.auth;

import com.nexttenant.user.AgeBracket;
import com.nexttenant.user.Gender;
import com.nexttenant.user.PhoneAlreadyInUseException;
import com.nexttenant.user.User;
import com.nexttenant.user.UserAccounts;
import com.nexttenant.user.UserStatus;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.UUID;

/**
 * The three-step sign-in: email, code, profile.
 *
 * <p>Sign-in and sign-up are one flow. A verified address either has an account
 * or gets one, which is also what stops this being an account-existence oracle.
 *
 * <p><b>Why the number is asked for on step three and not step one.</b> A
 * returning visitor would otherwise type two fields to sign in, and only ever
 * need the second on their very first visit. Step three already exists for new
 * accounts — it is where the first name is collected — so the number costs no
 * extra screen and nobody types it twice.
 */
@Controller
public class AuthController {

    private final OtpService otp;
    private final UserAccounts accounts;
    private final SessionLogin sessionLogin;

    public AuthController(OtpService otp, UserAccounts accounts, SessionLogin sessionLogin) {
        this.otp = otp;
        this.accounts = accounts;
        this.sessionLogin = sessionLogin;
    }

    // ---- Step 1: the address ----------------------------------------------

    @GetMapping("/login")
    public String emailStep(@AuthenticationPrincipal AuthenticatedUser current) {
        if (current != null) {
            return "redirect:/dashboard";
        }
        return "auth/email";
    }

    @PostMapping("/login/email")
    public String sendCode(@RequestParam("email") String rawEmail,
                           HttpServletRequest request,
                           HttpSession session,
                           Model model) {
        String email;
        try {
            email = LoginEmails.validate(rawEmail);
        } catch (InvalidLoginEmailException e) {
            model.addAttribute("error", e.getMessage());
            // Echo the input back so the field is not cleared, which is
            // infuriating on a phone keyboard.
            model.addAttribute("email", rawEmail);
            return "auth/email";
        }

        try {
            OtpDispatch dispatch = otp.requestCode(
                    email, clientIp(request), OtpPurpose.EMAIL_LOGIN);
            // The plaintext address goes into the SESSION, never the database.
            LoginFlow.startFor(session, email, dispatch);
        } catch (OtpRateLimitedException | OtpDeliveryException e) {
            // Both are shown the same way: the message on each is written to
            // be read by the person who typed the address.
            model.addAttribute("error", e.getMessage());
            model.addAttribute("email", rawEmail);
            return "auth/email";
        }

        // Redirect rather than render: POST-then-redirect-then-GET, so a
        // refresh on the next page does not resubmit the form and send a
        // second code.
        return "redirect:/login/code";
    }

    // ---- Step 2: the code -------------------------------------------------

    @GetMapping("/login/code")
    public String codeStep(HttpSession session, Model model) {
        if (!LoginFlow.inProgress(session)) {
            return "redirect:/login";
        }
        addCodeScreen(model, session);
        return "auth/code";
    }

    @PostMapping("/login/code")
    public String verifyCode(@RequestParam("code") String code,
                             HttpSession session,
                             HttpServletRequest request,
                             HttpServletResponse response,
                             Model model) {
        String email = LoginFlow.subject(session);
        if (email == null) {
            return "redirect:/login";
        }

        OtpResult result = otp.verify(email, code, OtpPurpose.EMAIL_LOGIN);

        // A switch over a sealed type: the compiler rejects this if a new
        // OtpResult case is ever added and not handled here, which is what you
        // want when the missing case would be a security decision.
        return switch (result) {
            case OtpResult.Verified verified -> {
                User user = accounts.findOrCreate(verified.subject());

                // A removed or suspended account must not be able to sign back
                // in with a fresh code. Without this, removal would last only
                // until the next OTP, and moderation would be theatre.
                if (!user.isActive()) {
                    LoginFlow.clear(session);
                    model.addAttribute("error", user.getStatus() == UserStatus.REMOVED
                            ? "This account has been removed."
                            : "This account is suspended while we review a report.");
                    yield "auth/email";
                }

                if (needsProfile(user)) {
                    // New account: collect a name and a number before signing
                    // in, so the session principal is never missing one.
                    LoginFlow.awaitProfile(session, user.getId());
                    yield "redirect:/login/profile";
                }

                LoginFlow.clear(session);
                sessionLogin.signIn(user, request, response);
                yield "redirect:/dashboard";
            }
            case OtpResult.WrongCode wrong -> {
                model.addAttribute("error",
                        wrong.attemptsRemaining() == 1
                                ? "That code is not right. One attempt left."
                                : "That code is not right. %d attempts left."
                                        .formatted(wrong.attemptsRemaining()));
                addCodeScreen(model, session);
                yield "auth/code";
            }
            case OtpResult.Malformed ignored -> {
                model.addAttribute("error", "Enter the six digits from the email.");
                addCodeScreen(model, session);
                yield "auth/code";
            }
            case OtpResult.Expired ignored -> {
                model.addAttribute("error", "That code has expired. Ask for a new one.");
                addCodeScreen(model, session);
                model.addAttribute("allowResend", true);
                yield "auth/code";
            }
            case OtpResult.TooManyAttempts ignored -> {
                LoginFlow.clear(session);
                model.addAttribute("error",
                        "Too many attempts. Start again with your email address.");
                yield "auth/email";
            }
            case OtpResult.NoChallenge ignored -> {
                LoginFlow.clear(session);
                model.addAttribute("error", "That code is no longer valid. Start again.");
                yield "auth/email";
            }
        };
    }

    @PostMapping("/login/resend")
    public String resend(HttpSession session, HttpServletRequest request, Model model) {
        String email = LoginFlow.subject(session);
        if (email == null) {
            return "redirect:/login";
        }
        try {
            OtpDispatch dispatch = otp.requestCode(
                    email, clientIp(request), OtpPurpose.EMAIL_LOGIN);
            LoginFlow.startFor(session, email, dispatch);
            model.addAttribute("notice", "A new code is on its way.");
        } catch (OtpRateLimitedException | OtpDeliveryException e) {
            model.addAttribute("error", e.getMessage());
        }
        addCodeScreen(model, session);
        return "auth/code";
    }

    // ---- Step 3: the profile ----------------------------------------------

    @GetMapping("/login/profile")
    public String profileStep(HttpSession session, Model model) {
        if (LoginFlow.pendingUserId(session) == null) {
            return "redirect:/login";
        }
        addProfileScreen(model);
        return "auth/profile";
    }

    @PostMapping("/login/profile")
    public String completeProfile(@RequestParam("firstName") String firstName,
                                  @RequestParam("phone") String rawPhone,
                                  @RequestParam(value = "gender", required = false) Gender gender,
                                  @RequestParam(value = "ageBracket", required = false) AgeBracket ageBracket,
                                  HttpSession session,
                                  HttpServletRequest request,
                                  HttpServletResponse response,
                                  Model model) {
        UUID pending = LoginFlow.pendingUserId(session);
        if (pending == null) {
            return "redirect:/login";
        }

        String trimmed = firstName == null ? "" : firstName.trim();
        if (trimmed.isEmpty() || trimmed.length() > 40) {
            return profileError(model, "Enter a first name, up to 40 characters.",
                    trimmed, rawPhone);
        }

        // Normalised here, not in the service: the same E.164 discipline the
        // phone-login flow used, so "9876543210" and "+91 98765 43210" cannot
        // become two accounts for one number.
        String phoneE164;
        try {
            phoneE164 = PhoneNumbers.normalise(rawPhone);
        } catch (InvalidPhoneNumberException e) {
            return profileError(model, e.getMessage(), trimmed, rawPhone);
        }

        User user;
        try {
            user = accounts.completeProfile(pending, trimmed, gender, ageBracket, phoneE164);
        } catch (PhoneAlreadyInUseException e) {
            return profileError(model, e.getMessage(), trimmed, rawPhone);
        }

        LoginFlow.clear(session);
        sessionLogin.signIn(user, request, response);

        return "redirect:/dashboard";
    }

    // ---- Shared model plumbing --------------------------------------------

    /**
     * Everything the code screen needs, in one call.
     *
     * <p>Six places rendered {@code auth/code}, and each had to remember to add
     * the masked subject. One that forgot showed "we sent a code to" followed
     * by nothing — the kind of defect that only appears on an error path, which
     * is the path nobody clicks through by hand.
     */
    private void addCodeScreen(Model model, HttpSession session) {
        model.addAttribute("maskedSubject", LoginFlow.maskedSubject(session));
        model.addAttribute("channel", LoginFlow.channel(session));
    }

    private void addProfileScreen(Model model) {
        model.addAttribute("genders", Gender.values());
        model.addAttribute("ageBrackets", AgeBracket.values());
    }

    /** Re-renders the profile form with what was typed still in it. */
    private String profileError(Model model, String message, String firstName, String phone) {
        model.addAttribute("error", message);
        model.addAttribute("firstName", firstName);
        model.addAttribute("phone", phone);
        addProfileScreen(model);
        return "auth/profile";
    }

    /** A new account has neither a name nor a number yet. */
    private static boolean needsProfile(User user) {
        return user.getFirstName() == null || user.getFirstName().isBlank();
    }

    /**
     * The caller's IP, for the second rate-limit dimension.
     *
     * <p>X-Forwarded-For is read because in production a proxy or load balancer
     * sits in front and {@code getRemoteAddr()} would return the proxy — making
     * every request share one rate-limit bucket.
     *
     * <p>The header is trivially forged by the client, so it is trustworthy
     * only when a proxy we control overwrites it. Until this is deployed behind
     * such a proxy, treat IP limiting as a speed bump rather than a control —
     * which is exactly why the per-subject limit exists alongside it.
     */
    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            // Left-most entry is the original client; the rest are proxies.
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
