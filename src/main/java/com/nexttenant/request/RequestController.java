package com.nexttenant.request;

import com.nexttenant.auth.AuthenticatedUser;
import com.nexttenant.listing.ListingDetailService;
import com.nexttenant.listing.dto.ListingDetailDto;
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

import java.util.UUID;

/**
 * Sending and answering interest requests.
 */
@Controller
public class RequestController {

    private final RequestService requests;
    private final RequestViews views;
    private final ListingDetailService listings;

    public RequestController(RequestService requests, RequestViews views,
                             ListingDetailService listings) {
        this.requests = requests;
        this.views = views;
        this.listings = listings;
    }

    // ---- Sending ----------------------------------------------------------

    @GetMapping("/flats/{listingId}/request")
    public String requestForm(@PathVariable UUID listingId,
                              @AuthenticationPrincipal AuthenticatedUser user,
                              Model model) {
        ListingDetailDto listing = listings.findPublicListing(listingId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        // Already asked? Show them that rather than a form that will be
        // refused on submit.
        requests.existingRequest(listingId, user.id())
                .ifPresent(existing -> model.addAttribute("existing", existing));

        model.addAttribute("listing", listing);
        model.addAttribute("maxNote", RequestService.MAX_NOTE_LENGTH);
        return "request/send";
    }

    @PostMapping("/flats/{listingId}/request")
    public String send(@PathVariable UUID listingId,
                       @AuthenticationPrincipal AuthenticatedUser user,
                       @RequestParam(value = "note", required = false) String note,
                       RedirectAttributes redirect,
                       Model model) {
        try {
            requests.send(listingId, user.id(), note);
        } catch (RequestService.RequestNotAllowedException e) {
            ListingDetailDto listing = listings.findPublicListing(listingId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
            model.addAttribute("listing", listing);
            model.addAttribute("maxNote", RequestService.MAX_NOTE_LENGTH);
            model.addAttribute("error", e.getMessage());
            return "request/send";
        }

        redirect.addFlashAttribute("notice", "Request sent.");
        return "redirect:/requests";
    }

    // ---- The requests screen ----------------------------------------------

    @GetMapping("/requests")
    public String myRequests(@AuthenticationPrincipal AuthenticatedUser user, Model model) {
        // Read BEFORE marking seen. The other order would clear the badge and
        // then render a page with no indication of which answers were the new
        // ones — the notification would vanish in the act of being delivered.
        model.addAttribute("received", views.receivedBy(user.id()));
        model.addAttribute("sent", views.sentBy(user.id()));
        model.addAttribute("me", user);

        // This page IS where answers are shown, so opening it is exactly what
        // "seen" means. The header badge for the requester clears from here.
        requests.markAnswersSeen(user.id());

        return "request/index";
    }

    // ---- Answering --------------------------------------------------------

    /**
     * Accepting is the moment contact is released.
     *
     * <p>A POST, not a GET, and not only for convention: a GET that changed
     * state could be triggered by a prefetching browser, an email scanner or a
     * link preview — accepting a request on someone's behalf without them ever
     * clicking.
     */
    @PostMapping("/requests/{id}/accept")
    public String accept(@PathVariable UUID id,
                         @AuthenticationPrincipal AuthenticatedUser user,
                         RedirectAttributes redirect) {
        try {
            requests.accept(id, user.id());
            redirect.addFlashAttribute("notice", "Connected. You can both see each other's number.");
        } catch (IllegalStateException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/requests";
    }

    @PostMapping("/requests/{id}/decline")
    public String decline(@PathVariable UUID id,
                          @AuthenticationPrincipal AuthenticatedUser user,
                          RedirectAttributes redirect) {
        try {
            requests.decline(id, user.id());
            redirect.addFlashAttribute("notice", "Declined. Nothing was shared.");
        } catch (IllegalStateException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/requests";
    }

    @PostMapping("/requests/{id}/withdraw")
    public String withdraw(@PathVariable UUID id,
                           @AuthenticationPrincipal AuthenticatedUser user,
                           RedirectAttributes redirect) {
        try {
            requests.withdraw(id, user.id());
            redirect.addFlashAttribute("notice", "Request withdrawn.");
        } catch (IllegalStateException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/requests";
    }

    @PostMapping("/requests/{id}/revoke")
    public String revoke(@PathVariable UUID id,
                         @AuthenticationPrincipal AuthenticatedUser user,
                         RedirectAttributes redirect) {
        try {
            requests.revoke(id, user.id());
            redirect.addFlashAttribute("notice",
                    "Access removed. Neither of you can see the other's number now.");
        } catch (IllegalStateException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/requests";
    }
}
