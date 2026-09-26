package com.househunting.dashboard;

import com.househunting.auth.AuthenticatedUser;
import com.househunting.listing.ListingLifecycle;
import com.househunting.shortlist.ShortlistService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.UUID;

/**
 * The signed-in dashboard, and the listing lifecycle actions on it.
 *
 * <p>No explicit access check anywhere here: SecurityConfig's
 * {@code anyRequest().authenticated()} covers the routes, and
 * {@link ListingLifecycle} re-checks ownership on every action. Two layers,
 * neither relying on the other being remembered.
 */
@Controller
public class DashboardController {

    private final DashboardService dashboard;
    private final ListingLifecycle lifecycle;
    private final ShortlistService shortlists;

    public DashboardController(DashboardService dashboard, ListingLifecycle lifecycle,
                               ShortlistService shortlists) {
        this.dashboard = dashboard;
        this.lifecycle = lifecycle;
        this.shortlists = shortlists;
    }

    @GetMapping("/dashboard")
    public String dashboard(@AuthenticationPrincipal AuthenticatedUser user, Model model) {
        model.addAttribute("user", user);
        model.addAttribute("myListings", dashboard.myListings(user.id()));
        model.addAttribute("pendingRequests", dashboard.pendingRequestCount(user.id()));
        model.addAttribute("shortlistCount", dashboard.shortlistCount(user.id()));
        model.addAttribute("profile", dashboard.profileStrength(user.id()));
        return "dashboard/index";
    }

    @GetMapping("/shortlist")
    public String shortlist(@AuthenticationPrincipal AuthenticatedUser user, Model model) {
        java.util.List<com.househunting.listing.dto.ListingCardDto> cards =
                shortlists.shortlistedListings(user.id());
        model.addAttribute("cards", cards);
        // Every card on this page is saved by definition — that is what the
        // page is — so the hearts are all filled, and clicking one unsaves it
        // and drops it from the list on the next render.
        model.addAttribute("savedIds", cards.stream()
                .map(com.househunting.listing.dto.ListingCardDto::id)
                .collect(java.util.stream.Collectors.toSet()));
        // Marks the <body> so shortlist.js knows that clearing a heart here
        // should remove the card rather than leave it sitting there unsaved.
        //
        // "true" and not "": Thymeleaf th:attr drops an attribute whose value
        // is null OR the empty string, so an empty marker renders as no marker
        // at all — and the only symptom is a feature quietly not happening.
        model.addAttribute("wishlistPage", "true");
        return "dashboard/shortlist";
    }

    // ---- Lifecycle --------------------------------------------------------
    // All POSTs. A GET that changes state can be triggered by a prefetching
    // browser or a link preview — taking somebody's listing down without them
    // ever clicking.

    @PostMapping("/listings/{id}/extend")
    public String extend(@PathVariable UUID id,
                         @AuthenticationPrincipal AuthenticatedUser user,
                         RedirectAttributes redirect) {
        return act(redirect, () -> lifecycle.extend(id, user.id()),
                "Extended by " + ListingLifecycle.EXTENSION_DAYS + " days.");
    }

    @PostMapping("/listings/{id}/pause")
    public String pause(@PathVariable UUID id,
                        @AuthenticationPrincipal AuthenticatedUser user,
                        RedirectAttributes redirect) {
        return act(redirect, () -> lifecycle.pause(id, user.id()),
                "Paused. Nobody can find it while it is paused.");
    }

    @PostMapping("/listings/{id}/resume")
    public String resume(@PathVariable UUID id,
                         @AuthenticationPrincipal AuthenticatedUser user,
                         RedirectAttributes redirect) {
        return act(redirect, () -> lifecycle.resume(id, user.id()), "Back online.");
    }

    @PostMapping("/listings/{id}/rented")
    public String markRented(@PathVariable UUID id,
                             @AuthenticationPrincipal AuthenticatedUser user,
                             RedirectAttributes redirect) {
        return act(redirect, () -> lifecycle.markRented(id, user.id()),
                "Marked as rented. Hope it went well.");
    }

    @PostMapping("/listings/{id}/take-down")
    public String takeDown(@PathVariable UUID id,
                           @AuthenticationPrincipal AuthenticatedUser user,
                           RedirectAttributes redirect) {
        return act(redirect, () -> lifecycle.takeDown(id, user.id()), "Taken down.");
    }

    // ---- Shortlist --------------------------------------------------------

    /**
     * The heart, answered as JSON.
     *
     * <p>Two mappings on one path, separated by {@code produces}. Spring picks
     * this one when the request's Accept header asks for JSON and the HTML one
     * below otherwise — so the choice is made by the caller, declaratively,
     * and neither method has to inspect headers itself.
     *
     * <p>The HTML version stays because it is the one that works. A plain form
     * POST followed by a redirect needs no JavaScript at all; shortlist.js only
     * intercepts it to avoid the reload. If the script fails to load, or a
     * browser extension breaks it, the heart still saves the room — it just
     * jumps the page back to the top, which is the behaviour being fixed here
     * rather than the behaviour being replaced.
     *
     * @return whether the listing is shortlisted AFTER the toggle
     */
    @PostMapping(value = "/listings/{id}/shortlist",
                 produces = org.springframework.http.MediaType.APPLICATION_JSON_VALUE)
    @org.springframework.web.bind.annotation.ResponseBody
    public ShortlistState toggleShortlistAsJson(@PathVariable UUID id,
                                                @AuthenticationPrincipal AuthenticatedUser user) {
        return new ShortlistState(shortlists.toggle(user.id(), id),
                shortlists.countFor(user.id()));
    }

    /**
     * @param saved whether it is now on the wishlist
     * @param total how many rooms are on it, for the header count
     */
    public record ShortlistState(boolean saved, long total) {
    }

    @PostMapping("/listings/{id}/shortlist")
    public String toggleShortlist(@PathVariable UUID id,
                                  @AuthenticationPrincipal AuthenticatedUser user,
                                  @org.springframework.web.bind.annotation.RequestParam(
                                          value = "returnTo", required = false) String returnTo) {
        shortlists.toggle(user.id(), id);

        // Return the person to where they were, but only to a path on this
        // site: an open redirect would let someone craft a link that bounces
        // users off to a phishing page carrying our domain in the referrer.
        if (returnTo != null && returnTo.startsWith("/") && !returnTo.startsWith("//")) {
            return "redirect:" + returnTo;
        }
        return "redirect:/flats/" + id;
    }

    /** Runs an action, turning a refused transition into a message. */
    private String act(RedirectAttributes redirect, Runnable action, String successMessage) {
        try {
            action.run();
            redirect.addFlashAttribute("notice", successMessage);
        } catch (IllegalStateException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/dashboard";
    }
}
