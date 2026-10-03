package com.nexttenant.listing.wizard;

import com.nexttenant.auth.AuthenticatedUser;
import com.nexttenant.listing.AgreementHandling;
import com.nexttenant.listing.Configuration;
import com.nexttenant.listing.DepositPaidTo;
import com.nexttenant.listing.FlatmateGender;
import com.nexttenant.listing.Furnishing;
import com.nexttenant.listing.HandoverType;
import com.nexttenant.listing.Listing;
import com.nexttenant.listing.ListingDetailService;
import com.nexttenant.listing.ListingStatus;
import com.nexttenant.listing.RoomType;
import com.nexttenant.photo.PhotoService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import static com.nexttenant.listing.wizard.ListingInputs.orZero;
import static com.nexttenant.listing.wizard.ListingInputs.parseDate;
import static com.nexttenant.listing.wizard.ListingInputs.rupees;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The six-step listing wizard.
 *
 * <p>Each step is a separate request that saves on submit. "Save and exit" is
 * therefore not a feature to implement — the work is already saved, and the
 * button is a link to the dashboard.
 *
 * <p>Every handler loads the draft through
 * {@link ListingDraftService#requireOwnedDraft}, which refuses anyone who does
 * not own it. Ownership enforced in one place is ownership enforced; a check
 * each step has to remember is a check one step will omit.
 */
@Controller
public class WizardController {

    /**
     * Two.
     *
     * <p>It was six, asking for 33 fields and 15 checkboxes to collect seven
     * required answers. What this competes with is a leaver typing four lines
     * into a WhatsApp group, and against that bar the old flow was designed as
     * though filling it in were free.
     *
     * <p>Nothing was deleted. Everything optional moved to "add more detail",
     * offered once the listing is live and reached only by people who want it.
     */
    private static final int TOTAL_STEPS = 2;

    private final ListingDraftService drafts;
    private final ListingDetailService details;
    private final ListingFormOptions options;
    private final ListingReadiness readiness;
    private final PhotoService photos;

    /* Injected rather than calling LocalDate.now(), so a test can say what
       "today" is when checking that a past move-in date is refused. */
    private final Clock clock;

    public WizardController(ListingDraftService drafts,
                            ListingDetailService details,
                            ListingFormOptions options,
                            ListingReadiness readiness,
                            PhotoService photos,
                            Clock clock) {
        this.drafts = drafts;
        this.details = details;
        this.options = options;
        this.readiness = readiness;
        this.photos = photos;
        this.clock = clock;
    }

    /**
     * This door is the owner's, and only the owner's.
     *
     * <p>An admin signed in here is an ordinary person with their own
     * listings. Filling one in for somebody else happens at
     * {@code /admin/listings/...}, where it is a deliberate act with its own
     * URL — rather than an extra power that quietly applies to every request
     * an operator makes while browsing their own flat.
     */
    private static Actor actor(AuthenticatedUser user) {
        return Actor.owner(user.id());
    }

    /** "List your flat" — resume where they left off, or start. */
    @GetMapping("/list")
    public String start(@AuthenticationPrincipal AuthenticatedUser user) {
        Listing draft = drafts.currentDraft(user.id());
        return "redirect:/list/%s/%d".formatted(draft.getId(), draft.getWizardStep());
    }

    @GetMapping("/list/{id}/{step}")
    public String showStep(@PathVariable UUID id, @PathVariable int step,
                           @AuthenticationPrincipal AuthenticatedUser user,
                           Model model) {
        if (step < 1 || step > TOTAL_STEPS) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }

        Listing draft = drafts.requireOwnedDraft(id, user.id());
        addCommon(model, draft, step);

        switch (step) {
            case 1 -> options.addTo(model);
            case 2 -> {
                model.addAttribute("photos", photos.forListing(id));
                model.addAttribute("minPhotos", PhotoService.MIN_PHOTOS_TO_PUBLISH);
                model.addAttribute("maxPhotos", PhotoService.MAX_PHOTOS);
                drafts.privateFor(id).ifPresent(p -> model.addAttribute("priv", p));
            }
            default -> {
            }
        }

        return "listing/wizard/step" + step;
    }

    /**
     * Everything the two screens no longer ask for, on one optional page.
     *
     * <p>Offered after publishing rather than before it. All of it is genuinely
     * useful — amenities, house rules, who holds the deposit — and none of it
     * decides whether a room can go live, so none of it belongs in front of a
     * leaver deciding whether this is worth the effort.
     */
    @GetMapping("/list/{id}/more")
    public String moreDetail(@PathVariable UUID id,
                             @AuthenticationPrincipal AuthenticatedUser user,
                             Model model) {
        Listing draft = drafts.requireOwnedDraft(id, user.id());
        addCommon(model, draft, TOTAL_STEPS);

        model.addAttribute("handoverTypes", HandoverType.values());
        model.addAttribute("amenityOptions", AMENITIES);
        model.addAttribute("perkOptions", PERKS);
        model.addAttribute("depositPaidToOptions", DepositPaidTo.values());
        model.addAttribute("agreementOptions", AgreementHandling.values());
        drafts.privateFor(id).ifPresent(p -> model.addAttribute("priv", p));

        return "listing/wizard/more";
    }

    /**
     * What the listing looks like to everybody else.
     *
     * <p>Built by the same method that renders the live page, so it cannot
     * drift from the real thing. A separate preview renderer would diverge,
     * and the divergence would only surface after somebody published something
     * different from what they approved.
     *
     * <p>This is no longer a gate before publishing — finishing screen two
     * publishes. It is now two pages wearing one URL: for a live listing, "here
     * is what people see and here is where to change it"; for one still
     * missing something, the list of what is outstanding and a button that
     * works once it is not.
     */
    @GetMapping("/list/{id}/preview")
    public String preview(@PathVariable UUID id,
                          @AuthenticationPrincipal AuthenticatedUser user,
                          Model model) {
        Listing draft = drafts.requireOwnedDraft(id, user.id());
        addCommon(model, draft, TOTAL_STEPS);
        model.addAttribute("preview", details.preview(draft));
        model.addAttribute("missing", missingFor(draft));
        model.addAttribute("isLive", draft.getStatus() == ListingStatus.LIVE);
        // The public address, so "View it" goes to the real page rather than
        // this rehearsal of it.
        model.addAttribute("publicPath", draft.path());
        return "listing/wizard/preview";
    }

    // ---- Screen 1: the basics ---------------------------------------------

    @PostMapping("/list/{id}/1")
    public String saveBasics(@PathVariable UUID id,
                             @AuthenticationPrincipal AuthenticatedUser user,
                             @RequestParam(required = false) String areaName,
                             @RequestParam(required = false) Configuration configuration,
                             @RequestParam(required = false) RoomType roomType,
                             @RequestParam(required = false) Furnishing furnishing,
                             @RequestParam(required = false) com.nexttenant.listing.FlatType flatType,
                             @RequestParam(required = false) FlatmateGender flatmateGender,
                             @RequestParam(required = false) com.nexttenant.listing.FlatmateMakeup flatmateMakeup,
                             @RequestParam(required = false) String availableFrom,
                             @RequestParam(required = false) Short existingFlatmates,
                             RedirectAttributes redirect) {
        Listing draft = drafts.requireOwnedDraft(id, user.id());
        try {
            drafts.saveBasics(id, actor(user), areaName, configuration, roomType,
                    furnishing, flatType, flatmateGender, flatmateMakeup,
                    parseDate(availableFrom, draft.getAvailableFrom(),
                            ListingInputs.today(clock)),
                    existingFlatmates);
        } catch (ListingInputs.InvalidDateException | IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
            return "redirect:/list/%s/1".formatted(id);
        }
        return next(id, 2);
    }

    // ---- Screen 2: money, photos and the two optional extras ---------------

    @PostMapping("/list/{id}/2")
    public String saveMoneyAndExtras(@PathVariable UUID id,
                                     @AuthenticationPrincipal AuthenticatedUser user,
                                     @RequestParam(required = false) String monthlyRent,
                                     @RequestParam(required = false) String deposit,
                                     @RequestParam(required = false) String brokerage,
                                     @RequestParam(required = false) String buyout,
                                     @RequestParam(required = false) String mapsLink,
                                     @RequestParam(required = false) String description,
                                     RedirectAttributes redirect) {
        try {
            drafts.saveMoneyAndExtras(id, actor(user),
                    rupees("Monthly rent", monthlyRent),
                    rupees("Security deposit", deposit),
                    orZero(rupees("Brokerage", brokerage)),
                    orZero(rupees("Buy-out", buyout)),
                    mapsLink, description);
        } catch (ListingInputs.InvalidAmountException e) {
            redirect.addFlashAttribute("error", e.getMessage());
            return "redirect:/list/%s/2".formatted(id);
        }

        // Finishing the last screen IS publishing.
        //
        // There used to be a preview with a Publish button after it, and that
        // button was a second deliberate decision asked of somebody who had
        // already answered every question. Anyone who reached it and did not
        // press it had done the entire job for nothing — and the preview page
        // gives them no new information at that point, because they have just
        // typed all of it.
        //
        // So the listing goes live and the preview becomes a page about a live
        // listing: here is what people see, here is where to change it.
        // Nothing is lost by it being live first — editing is immediate, and
        // taking it down is one click away on the dashboard.
        Listing draft = drafts.requireOwnedDraft(id, user.id());
        if (draft.getStatus() == ListingStatus.DRAFT && missingFor(draft).isEmpty()) {
            drafts.publish(id, actor(user));
            redirect.addFlashAttribute("notice", "Your listing is live.");
        }
        return "redirect:/list/%s/preview".formatted(id);
    }

    // ---- Add more detail, after publishing --------------------------------

    /**
     * Saves the optional screen.
     *
     * <p>One handler for what used to be four, because these are now answered
     * on one page and none of them gates anything. The service calls
     * underneath are unchanged — this is a different doorway into the same
     * rooms.
     */
    @PostMapping("/list/{id}/more")
    public String saveMoreDetail(@PathVariable UUID id,
                                 @AuthenticationPrincipal AuthenticatedUser user,
                                 @RequestParam(required = false) HandoverType handoverType,
                                 @RequestParam(required = false) List<String> amenities,
                                 @RequestParam(required = false) List<String> perks,
                                 @RequestParam(required = false) String maintenance,
                                 @RequestParam(defaultValue = "false") boolean maintenanceIncluded,
                                 @RequestParam(required = false) Short floor,
                                 @RequestParam(required = false) Boolean hasLift,
                                 @RequestParam(required = false) String societyName,
                                 @RequestParam(required = false) String fullAddress,
                                 @RequestParam(required = false) Boolean ownerInformed,
                                 @RequestParam(required = false) DepositPaidTo depositPaidTo,
                                 @RequestParam(required = false) Boolean registeredAgreement,
                                 @RequestParam(required = false) AgreementHandling agreementHandling,
                                 @RequestParam(required = false) Short noticePeriodDays,
                                 @RequestParam(required = false) Boolean bachelorsAllowed,
                                 @RequestParam(required = false) String flatmateProfessions,
                                 @RequestParam(required = false) Boolean nonVegAllowed,
                                 @RequestParam(required = false) Boolean petsAllowed,
                                 @RequestParam(required = false) Boolean smokingAllowed,
                                 @RequestParam(required = false) Boolean guestsAllowed,
                                 @RequestParam(required = false) String houseRulesNote,
                                 RedirectAttributes redirect) {
        try {
            drafts.saveMoreDetail(id, actor(user),
                    handoverType, amenities, perks,
                    rupees("Society maintenance", maintenance), maintenanceIncluded,
                    floor, hasLift, societyName, fullAddress,
                    ownerInformed, depositPaidTo, registeredAgreement, agreementHandling,
                    noticePeriodDays, bachelorsAllowed,
                    flatmateProfessions, nonVegAllowed, petsAllowed, smokingAllowed,
                    guestsAllowed, houseRulesNote);
        } catch (ListingInputs.InvalidAmountException e) {
            redirect.addFlashAttribute("error", e.getMessage());
            return "redirect:/list/%s/more".formatted(id);
        }
        redirect.addFlashAttribute("notice", "Saved.");
        return "redirect:/list/%s/more".formatted(id);
    }

    // ---- Publish ----------------------------------------------------------

    @PostMapping("/list/{id}/publish")
    public String publish(@PathVariable UUID id,
                          @AuthenticationPrincipal AuthenticatedUser user,
                          Model model) {
        Listing draft = drafts.requireOwnedDraft(id, user.id());

        List<ListingDraftService.Missing> missing = missingFor(draft);
        if (!missing.isEmpty()) {
            addCommon(model, draft, TOTAL_STEPS);
            model.addAttribute("preview", details.preview(draft));
            model.addAttribute("missing", missing);
            model.addAttribute("error", "This listing still needs a few things.");
            return "listing/wizard/preview";
        }

        drafts.publish(id, actor(user));
        // Straight to the canonical address rather than the bare id, which
        // would 301 onward. Nothing breaks either way; this just does not
        // make the first view of a brand new listing a round trip.
        return "redirect:" + draft.path();
    }

    /** Delegated, because the admin flow publishes through the same rule. */
    private List<ListingDraftService.Missing> missingFor(Listing draft) {
        return readiness.missingFor(draft, TOTAL_STEPS);
    }

    // ---- Shared -----------------------------------------------------------

    private void addCommon(Model model, Listing draft, int step) {
        model.addAttribute("draft", draft);
        model.addAttribute("step", step);
        model.addAttribute("totalSteps", TOTAL_STEPS);

        // The same six screens serve two jobs now: walking through a new
        // listing, and editing one that is already published. The wording has
        // to say which — "Continue" and a progress bar are right for the first
        // and actively misleading for the second, where nothing is pending and
        // every change has already been saved.
        model.addAttribute("editing", drafts.isPublished(draft));

        // The earliest move-in date the date picker will offer. Today, unless
        // this listing already holds an earlier one — an August date on a
        // listing published in August still means "available now", and its
        // owner should not be stopped from saving an edit to something else
        // because of it.
        model.addAttribute("earliestMoveIn", ListingInputs.earliestMoveIn(
                draft.getAvailableFrom(), ListingInputs.today(clock)));
    }

    /**
     * Turns a refused edit into a message on the page it came from.
     *
     * <p>A published listing may not be left incomplete — see
     * {@code ListingDraftService.refuseIfLiveAndIncomplete}. When that fires
     * the transaction has already rolled back, so nothing was saved and the
     * database still holds the previous values.
     *
     * <p>One handler rather than a try/catch in each of the five step POSTs.
     * Five copies of the same recovery is five chances to get one of them
     * wrong, and the one that is wrong will be the step nobody edits often.
     *
     * <p>It redirects to the same URL instead of rendering the step directly,
     * which matters more than it looks: each step's GET populates its own
     * dropdowns — areas, enums, the photo list — and rendering the view from
     * here would mean duplicating whichever of those the failed step needed.
     * The error survives the redirect as a flash attribute, which the layout
     * shows as a toast.
     */
    @org.springframework.web.bind.annotation.ExceptionHandler(
            ListingDraftService.IncompleteListingException.class)
    public String onIncompleteEdit(ListingDraftService.IncompleteListingException e,
                                   jakarta.servlet.http.HttpServletRequest request,
                                   org.springframework.web.servlet.mvc.support.RedirectAttributes redirect) {
        redirect.addFlashAttribute("error",
                "A published listing cannot be left without "
                        + e.getMissing().stream()
                                .map(ListingDraftService.Missing::label)
                                .collect(java.util.stream.Collectors.joining(", "))
                        + ". That change was not saved.");
        return "redirect:" + request.getRequestURI();
    }

    /** POST-redirect-GET, so a refresh does not resubmit the step. */
    private static String next(UUID id, int step) {
        return "redirect:/list/%s/%d".formatted(id, step);
    }

    private static final List<Option> AMENITIES = List.of(
            new Option("LIFT", "Lift"),
            new Option("PARKING", "Parking"),
            new Option("WIFI", "Wi-Fi"),
            new Option("WASHING_MACHINE", "Washing machine"),
            new Option("FRIDGE", "Fridge"),
            new Option("AC", "Air conditioning"),
            new Option("GEYSER", "Geyser"),
            new Option("POWER_BACKUP", "Power backup"),
            new Option("SECURITY", "Security"),
            new Option("GYM", "Gym"));

    private static final List<Option> PERKS = List.of(
            new Option("BALCONY", "Balcony"),
            new Option("SEA_VIEW", "Sea view"),
            new Option("PET_FRIENDLY", "Pet friendly"),
            new Option("TERRACE_ACCESS", "Terrace access"),
            new Option("STUDY_DESK", "Study desk"));

    public record Option(String code, String label) {
    }
}
