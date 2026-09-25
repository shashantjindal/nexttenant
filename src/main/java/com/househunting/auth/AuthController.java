package com.househunting.auth;

import com.househunting.user.AgeBracket;
import com.househunting.user.Gender;
import com.househunting.user.User;
import com.househunting.user.UserAccounts;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The three-step sign-in: phone, code, profile.
 *
 * <p>Sign-in and sign-up are one flow. A verified number either has an account
 * or gets one, which is also what stops this being an account-existence oracle.
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

    // ---- Step 1: the number ----------------------------------------------

    @GetMapping("/login")
    public String phoneStep(@AuthenticationPrincipal AuthenticatedUser current) {
        if (current != null) {
            return "redirect:/dashboard";
        }
        return "auth/phone";
    }

    @PostMapping("/login/phone")
    public String sendCode(@RequestParam("phone") String rawPhone,
                           HttpServletRequest request,
                           HttpSession session,
                           Model model) {
        String phoneE164;
        try {
            phoneE164 = PhoneNumbers.normalise(rawPhone);
        } catch (InvalidPhoneNumberException e) {
            model.addAttribute("error", e.getMessage());
            // Echo the input back so the field is not cleared, which is
            // infuriating on a phone keyboard.
            model.addAttribute("phone", rawPhone);
            return "auth/phone";
        }

        try {
            OtpDispatch dispatch = otp.requestCode(phoneE164, clientIp(request), OtpPurpose.LOGIN);
            // The plaintext number goes into the SESSION, never the database.
            LoginFlow.startFor(session, phoneE164, dispatch.maskedPhone());
        } catch (OtpRateLimitedException e) {
            model.addAttribute("error", e.getMessage());
            model.addAttribute("phone", rawPhone);
            return "auth/phone";
        }

        // Redirect rather than render: POST-then-redirect-then-GET, so a
        // refresh on the next page does not resubmit the form and send a
        // second SMS.
        return "redirect:/login/code";
    }

    // ---- Step 2: the code -------------------------------------------------

    @GetMapping("/login/code")
    public String codeStep(HttpSession session, Model model) {
        if (!LoginFlow.inProgress(session)) {
            return "redirect:/login";
        }
        model.addAttribute("maskedPhone", LoginFlow.maskedPhone(session));
        return "auth/code";
    }

    @PostMapping("/login/code")
    public String verifyCode(@RequestParam("code") String code,
                             HttpSession session,
                             HttpServletRequest request,
                             HttpServletResponse response,
                             Model model) {
        String phoneE164 = LoginFlow.phone(session);
        if (phoneE164 == null) {
            return "redirect:/login";
        }

        OtpResult result = otp.verify(phoneE164, code, OtpPurpose.LOGIN);

        // A switch over a sealed type: the compiler rejects this if a new
        // OtpResult case is ever added and not handled here, which is what you
        // want when the missing case would be a security decision.
        return switch (result) {
            case OtpResult.Verified verified -> {
                User user = accounts.findOrCreate(verified.phoneE164());

                if (user.getFirstName() == null || user.getFirstName().isBlank()) {
                    // New account: collect a name before signing in, so the
                    // session principal is never missing one.
                    session.setAttribute("hh.login.pendingUserId", user.getId());
                    yield "redirect:/login/profile";
                }

                sessionLogin.signIn(user, request, response);
                yield "redirect:/dashboard";
            }
            case OtpResult.WrongCode wrong -> {
                model.addAttribute("error",
                        wrong.attemptsRemaining() == 1
                                ? "That code is not right. One attempt left."
                                : "That code is not right. %d attempts left."
                                        .formatted(wrong.attemptsRemaining()));
                model.addAttribute("maskedPhone", LoginFlow.maskedPhone(session));
                yield "auth/code";
            }
            case OtpResult.Malformed ignored -> {
                model.addAttribute("error", "Enter the six digits from the message.");
                model.addAttribute("maskedPhone", LoginFlow.maskedPhone(session));
                yield "auth/code";
            }
            case OtpResult.Expired ignored -> {
                model.addAttribute("error", "That code has expired. Ask for a new one.");
                model.addAttribute("maskedPhone", LoginFlow.maskedPhone(session));
                model.addAttribute("allowResend", true);
                yield "auth/code";
            }
            case OtpResult.TooManyAttempts ignored -> {
                LoginFlow.clear(session);
                model.addAttribute("error",
                        "Too many attempts. Start again with your number.");
                yield "auth/phone";
            }
            case OtpResult.NoChallenge ignored -> {
                LoginFlow.clear(session);
                model.addAttribute("error", "That code is no longer valid. Start again.");
                yield "auth/phone";
            }
        };
    }

    @PostMapping("/login/resend")
    public String resend(HttpSession session, HttpServletRequest request, Model model) {
        String phoneE164 = LoginFlow.phone(session);
        if (phoneE164 == null) {
            return "redirect:/login";
        }
        try {
            OtpDispatch dispatch = otp.requestCode(phoneE164, clientIp(request), OtpPurpose.LOGIN);
            LoginFlow.startFor(session, phoneE164, dispatch.maskedPhone());
            model.addAttribute("notice", "A new code is on its way.");
        } catch (OtpRateLimitedException e) {
            model.addAttribute("error", e.getMessage());
        }
        model.addAttribute("maskedPhone", LoginFlow.maskedPhone(session));
        return "auth/code";
    }

    // ---- Step 3: the profile ----------------------------------------------

    @GetMapping("/login/profile")
    public String profileStep(HttpSession session, Model model) {
        if (session.getAttribute("hh.login.pendingUserId") == null) {
            return "redirect:/login";
        }
        model.addAttribute("genders", Gender.values());
        model.addAttribute("ageBrackets", AgeBracket.values());
        return "auth/profile";
    }

    @PostMapping("/login/profile")
    public String completeProfile(@RequestParam("firstName") String firstName,
                                  @RequestParam(value = "gender", required = false) Gender gender,
                                  @RequestParam(value = "ageBracket", required = false) AgeBracket ageBracket,
                                  HttpSession session,
                                  HttpServletRequest request,
                                  HttpServletResponse response,
                                  Model model) {
        Object pending = session.getAttribute("hh.login.pendingUserId");
        if (pending == null) {
            return "redirect:/login";
        }

        String trimmed = firstName == null ? "" : firstName.trim();
        if (trimmed.isEmpty() || trimmed.length() > 40) {
            model.addAttribute("error", "Enter a first name, up to 40 characters.");
            model.addAttribute("genders", Gender.values());
            model.addAttribute("ageBrackets", AgeBracket.values());
            return "auth/profile";
        }

        User user = accounts.completeProfile((java.util.UUID) pending, trimmed, gender, ageBracket);
        session.removeAttribute("hh.login.pendingUserId");
        sessionLogin.signIn(user, request, response);

        return "redirect:/dashboard";
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
     * which is exactly why the per-number limit exists alongside it.
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
