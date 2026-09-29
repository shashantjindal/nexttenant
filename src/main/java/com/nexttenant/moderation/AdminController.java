package com.nexttenant.moderation;

import com.nexttenant.auth.AuthenticatedUser;
import com.nexttenant.listing.Listing;
import com.nexttenant.listing.ListingRepository;
import com.nexttenant.listing.ListingStatus;
import com.nexttenant.user.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The moderation queue.
 *
 * <p>Access is enforced by SecurityConfig, which requires ROLE_ADMIN for
 * /admin/**. Doing it there rather than with a check in each method means a
 * route added later is protected by default.
 */
@Controller
public class AdminController {

    /** One row of the queue, with the context a decision needs. */
    public record QueueItem(
            UUID reportId,
            UUID listingId,
            UUID ownerId,
            String reason,
            String reasonHelp,
            String detail,
            String listingArea,
            String ownerName,
            long strikesAlready,
            String filedAt
    ) {
        /** True if confirming this one would remove the account. */
        public boolean wouldRemoveAccount() {
            return strikesAlready + 1 >= ModerationService.STRIKES_TO_REMOVE;
        }
    }

    private final ModerationService moderation;
    private final ListingRepository listings;
    private final UserRepository users;

    public AdminController(ModerationService moderation, ListingRepository listings,
                           UserRepository users) {
        this.moderation = moderation;
        this.listings = listings;
        this.users = users;
    }

    @GetMapping("/admin/reports")
    public String queue(Model model) {
        List<QueueItem> items = new ArrayList<>();

        for (Report report : moderation.openQueue()) {
            Listing listing = listings.findById(report.getListingId()).orElse(null);
            UUID ownerId = listing == null ? null : listing.getLeaverUserId();

            items.add(new QueueItem(
                    report.getId(),
                    report.getListingId(),
                    ownerId,
                    report.getReason().getLabel(),
                    report.getReason().getHelp(),
                    report.getDetail(),
                    listing == null ? null : listing.getAreaName(),
                    ownerId == null ? null : users.findById(ownerId)
                            .map(u -> u.getFirstName()).orElse(null),
                    // Shown so a moderator knows, before clicking, that this
                    // decision removes an account rather than discovering it
                    // afterwards.
                    ownerId == null ? 0 : moderation.strikesAgainst(ownerId),
                    report.getCreatedAt() == null ? "" : report.getCreatedAt().toString()));
        }

        model.addAttribute("items", items);
        model.addAttribute("openCount", moderation.openCount());
        model.addAttribute("strikesToRemove", ModerationService.STRIKES_TO_REMOVE);
        return "moderation/queue";
    }

    @PostMapping("/admin/reports/{id}/confirm")
    public String confirm(@PathVariable UUID id,
                          @AuthenticationPrincipal AuthenticatedUser admin,
                          @RequestParam(required = false) String note,
                          RedirectAttributes redirect) {
        try {
            boolean removed = moderation.confirm(id, admin.id(), note);
            redirect.addFlashAttribute("notice", removed
                    ? "Confirmed. That was the second strike, so the account has been removed."
                    : "Confirmed. The listing is down; the account has one strike.");
        } catch (IllegalStateException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/reports";
    }

    // ---- Taking a listing down without a report ---------------------------

    /**
     * The confirmation page.
     *
     * <p>An interstitial rather than a button in the table, for two reasons.
     * Taking a listing down cannot be undone — {@code isResumable()} covers
     * PAUSED and EXPIRED and not TAKEN_DOWN — so a stray click in a row of
     * fifty is a mistake with no route back. And the reason field needs room
     * to be answered properly rather than a cramped box in a scrolling table.
     */
    @GetMapping("/admin/listings/{id}/take-down")
    public String confirmTakeDown(@PathVariable UUID id,
                                  @RequestParam(required = false) String status,
                                  @RequestParam(defaultValue = "0") int page,
                                  Model model) {
        Listing listing = listings.findById(id).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        model.addAttribute("listing", listing);
        model.addAttribute("ownerName", users.findById(listing.getLeaverUserId())
                .map(u -> u.getFirstName()).orElse(null));
        model.addAttribute("alreadyDown",
                listing.getStatus() == ListingStatus.TAKEN_DOWN);
        // Carried through so confirming returns to the filtered page the
        // moderator was reading, not to the top of an unfiltered list.
        model.addAttribute("status", status);
        model.addAttribute("page", page);
        return "admin/take-down";
    }

    @PostMapping("/admin/listings/{id}/take-down")
    public String takeDown(@PathVariable UUID id,
                           @AuthenticationPrincipal AuthenticatedUser admin,
                           @RequestParam(required = false) String reason,
                           @RequestParam(required = false) String status,
                           @RequestParam(defaultValue = "0") int page,
                           RedirectAttributes redirect) {
        try {
            boolean done = moderation.takeDownListing(id, admin.id(), reason);
            redirect.addFlashAttribute("notice", done
                    ? "Taken down. Recorded against you in the moderation log."
                    : "That listing was already down. Nothing changed.");
        } catch (IllegalArgumentException e) {
            // Back to the form rather than on to the list: the moderator still
            // means to do this and has only to say why.
            redirect.addFlashAttribute("error", e.getMessage());
            redirect.addAttribute("status", status);
            redirect.addAttribute("page", page);
            return "redirect:/admin/listings/{id}/take-down";
        }

        if (status != null && !status.isBlank()) {
            redirect.addAttribute("status", status);
        }
        if (page > 0) {
            redirect.addAttribute("page", page);
        }
        return "redirect:/admin/listings";
    }

    @PostMapping("/admin/reports/{id}/dismiss")
    public String dismiss(@PathVariable UUID id,
                          @AuthenticationPrincipal AuthenticatedUser admin,
                          @RequestParam(required = false) String note,
                          RedirectAttributes redirect) {
        try {
            moderation.dismiss(id, admin.id(), note);
            redirect.addFlashAttribute("notice", "Dismissed. Nothing happened to the account.");
        } catch (IllegalStateException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/reports";
    }
}
