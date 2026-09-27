package com.nexttenant.moderation;

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

import java.util.UUID;

/** Reporting a listing. */
@Controller
public class ReportController {

    private final ModerationService moderation;
    private final ListingDetailService listings;

    public ReportController(ModerationService moderation, ListingDetailService listings) {
        this.moderation = moderation;
        this.listings = listings;
    }

    @GetMapping("/flats/{listingId}/report")
    public String form(@PathVariable UUID listingId, Model model) {
        ListingDetailDto listing = listings.findPublicListing(listingId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        model.addAttribute("listing", listing);
        model.addAttribute("reasons", ReportReason.values());
        return "moderation/report";
    }

    @PostMapping("/flats/{listingId}/report")
    public String submit(@PathVariable UUID listingId,
                         @AuthenticationPrincipal AuthenticatedUser user,
                         @RequestParam ReportReason reason,
                         @RequestParam(required = false) String detail,
                         Model model) {
        try {
            moderation.report(listingId, user.id(), reason, detail);
        } catch (ModerationService.ReportNotAllowedException e) {
            model.addAttribute("listing", listings.findPublicListing(listingId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)));
            model.addAttribute("reasons", ReportReason.values());
            model.addAttribute("error", e.getMessage());
            return "moderation/report";
        }
        return "moderation/report-done";
    }
}
