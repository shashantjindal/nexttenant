package com.househunting.workemail;

import com.househunting.auth.AuthenticatedUser;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The work-email verification flow: address, then code.
 *
 * <p>Every route here is behind {@code anyRequest().authenticated()} in
 * SecurityConfig, so there is no signed-out case to handle — verification only
 * ever attaches a badge to an account that already exists.
 */
@Controller
public class WorkEmailController {

    /**
     * Where the address under verification lives.
     *
     * <p>In the session, not in a hidden form field and not in the database.
     *
     * <p>Not a hidden field, because the final step would then take the address
     * from the request body: somebody could get a code at an address they
     * control and submit a colleague's, and the colleague's is what would be
     * written to their profile.
     *
     * <p>Not the database, because until the code comes back it is an
     * unverified claim about where somebody works, possibly about somebody
     * else. Storing that is a liability with no upside.
     */
    private static final String PENDING_EMAIL = "hh.workEmail.pending";
    private static final String PENDING_MASKED = "hh.workEmail.masked";

    private final WorkEmailService workEmail;

    public WorkEmailController(WorkEmailService workEmail) {
        this.workEmail = workEmail;
    }

    // ---- Step 1: the address ----------------------------------------------

    @GetMapping("/verify-work-email")
    public String addressStep(@AuthenticationPrincipal AuthenticatedUser user, Model model) {
        model.addAttribute("verified", workEmail.isVerified(user.id()));
        model.addAttribute("maskedExisting", workEmail.maskedAddress(user.id()).orElse(null));
        return "workemail/start";
    }

    @PostMapping("/verify-work-email")
    public String sendCode(@AuthenticationPrincipal AuthenticatedUser user,
                           @RequestParam("email") String address,
                           HttpSession session,
                           Model model) {
        String masked;
        try {
            masked = workEmail.requestCode(user.id(), address);
        } catch (InvalidWorkEmailException | WorkEmailService.ResendTooSoonException e) {
            model.addAttribute("error", e.getMessage());
            // Echo the input back, so the field is not cleared — infuriating
            // on a phone keyboard.
            model.addAttribute("email", address);
            model.addAttribute("verified", workEmail.isVerified(user.id()));
            return "workemail/start";
        }

        session.setAttribute(PENDING_EMAIL, WorkEmailAddresses.normalise(address));
        session.setAttribute(PENDING_MASKED, masked);

        // Redirect rather than render: POST-then-redirect-then-GET, so a
        // refresh on the next page does not resubmit and send a second email.
        return "redirect:/verify-work-email/code";
    }

    // ---- Step 2: the code -------------------------------------------------

    @GetMapping("/verify-work-email/code")
    public String codeStep(HttpSession session, Model model) {
        if (session.getAttribute(PENDING_EMAIL) == null) {
            return "redirect:/verify-work-email";
        }
        model.addAttribute("maskedEmail", session.getAttribute(PENDING_MASKED));
        return "workemail/code";
    }

    @PostMapping("/verify-work-email/code")
    public String verify(@AuthenticationPrincipal AuthenticatedUser user,
                         @RequestParam("code") String code,
                         HttpSession session,
                         RedirectAttributes redirect,
                         Model model) {
        String pending = (String) session.getAttribute(PENDING_EMAIL);
        if (pending == null) {
            return "redirect:/verify-work-email";
        }

        WorkEmailResult result = workEmail.verify(user.id(), pending, code);

        // A switch over a sealed type: the compiler rejects this if a new
        // WorkEmailResult case is added and not handled, which is what you want
        // when the missing case would be a security decision.
        return switch (result) {
            case WorkEmailResult.Verified ignored -> {
                clear(session);
                redirect.addFlashAttribute("notice",
                        "Work email verified. Your listings now carry the badge.");
                yield "redirect:/dashboard";
            }
            case WorkEmailResult.WrongCode wrong -> {
                model.addAttribute("error", wrong.attemptsRemaining() == 1
                        ? "That code is not right. One attempt left."
                        : "That code is not right. %d attempts left."
                                .formatted(wrong.attemptsRemaining()));
                model.addAttribute("maskedEmail", session.getAttribute(PENDING_MASKED));
                yield "workemail/code";
            }
            case WorkEmailResult.Malformed ignored -> {
                model.addAttribute("error", "Enter the six digits from the email.");
                model.addAttribute("maskedEmail", session.getAttribute(PENDING_MASKED));
                yield "workemail/code";
            }
            case WorkEmailResult.Expired ignored -> {
                model.addAttribute("error", "That code has expired. Ask for a new one.");
                model.addAttribute("allowResend", true);
                model.addAttribute("maskedEmail", session.getAttribute(PENDING_MASKED));
                yield "workemail/code";
            }
            case WorkEmailResult.TooManyAttempts ignored -> {
                clear(session);
                redirect.addFlashAttribute("error",
                        "Too many attempts. Start again with your address.");
                yield "redirect:/verify-work-email";
            }
            case WorkEmailResult.NoChallenge ignored -> {
                clear(session);
                redirect.addFlashAttribute("error",
                        "That code is no longer valid. Start again.");
                yield "redirect:/verify-work-email";
            }
            case WorkEmailResult.AlreadyUsed ignored -> {
                clear(session);
                // Deliberately vague about whose account holds it. Naming it
                // would confirm that a particular colleague is on the site.
                redirect.addFlashAttribute("error",
                        "That address is already verified on another account.");
                yield "redirect:/verify-work-email";
            }
        };
    }

    @PostMapping("/verify-work-email/resend")
    public String resend(@AuthenticationPrincipal AuthenticatedUser user,
                         HttpSession session,
                         Model model) {
        String pending = (String) session.getAttribute(PENDING_EMAIL);
        if (pending == null) {
            return "redirect:/verify-work-email";
        }
        try {
            session.setAttribute(PENDING_MASKED, workEmail.requestCode(user.id(), pending));
            model.addAttribute("notice", "A new code is on its way.");
        } catch (InvalidWorkEmailException | WorkEmailService.ResendTooSoonException e) {
            model.addAttribute("error", e.getMessage());
        }
        model.addAttribute("maskedEmail", session.getAttribute(PENDING_MASKED));
        return "workemail/code";
    }

    // ---- Undo -------------------------------------------------------------

    @PostMapping("/verify-work-email/remove")
    public String remove(@AuthenticationPrincipal AuthenticatedUser user,
                         HttpSession session,
                         RedirectAttributes redirect) {
        workEmail.remove(user.id());
        clear(session);
        redirect.addFlashAttribute("notice", "Work email removed. The badge is gone from your listings.");
        return "redirect:/dashboard";
    }

    private static void clear(HttpSession session) {
        session.removeAttribute(PENDING_EMAIL);
        session.removeAttribute(PENDING_MASKED);
    }
}
