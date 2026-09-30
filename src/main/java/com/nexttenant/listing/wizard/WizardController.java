package com.nexttenant.listing.wizard;

import com.nexttenant.area.AreaRepository;
import com.nexttenant.auth.AuthenticatedUser;
import com.nexttenant.listing.AgreementHandling;
import com.nexttenant.listing.Configuration;
import com.nexttenant.listing.DepositPaidTo;
import com.nexttenant.listing.FlatmateGender;
import com.nexttenant.listing.Furnishing;
import com.nexttenant.listing.HandoverType;
import com.nexttenant.listing.Listing;
import com.nexttenant.listing.ListingDetailService;
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

    private static final int TOTAL_STEPS = 6;

    private final ListingDraftService drafts;
    private final ListingDetailService details;
    private final AreaRepository areas;
    private final PhotoService photos;

    /* Injected rather than calling LocalDate.now(), so a test can say what
       "today" is when checking that a past move-in date is refused. */
    private final Clock clock;

    /**
     * "Today" means today in Mumbai.
     *
     * <p>The Clock bean is {@code systemUTC}, which is right for timestamps and
     * wrong for a calendar date: between midnight and half past five in the
     * morning IST it is still yesterday in UTC, so the date picker would offer
     * a day that has already gone for the person reading it. Everyone using
     * this wizard is in one city; this is that city.
     */
    private static final java.time.ZoneId IST = java.time.ZoneId.of("Asia/Kolkata");

    public WizardController(ListingDraftService drafts,
                            ListingDetailService details,
                            AreaRepository areas,
                            PhotoService photos,
                            Clock clock) {
        this.drafts = drafts;
        this.details = details;
        this.areas = areas;
        this.photos = photos;
        this.clock = clock;
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
            case 1 -> {
                model.addAttribute("areaGroups", areas.findGrouped());
                drafts.privateFor(id).ifPresent(p -> {
                    model.addAttribute("priv", p);
                    // The pin coordinates are unpacked HERE rather than in the
                    // template. A template that calls T(GeoPoints).latitudeOf()
                    // needs a null guard wrapped round it, and a null guard in
                    // Thymeleaf is a ternary — which is exactly the construct
                    // that was malformed and crashed this page. Putting two
                    // plain Doubles on the model removes the need for any
                    // expression at all.
                    if (p.getExactLocation() != null) {
                        model.addAttribute("pinLat",
                                com.nexttenant.geo.GeoPoints.latitudeOf(p.getExactLocation()));
                        model.addAttribute("pinLng",
                                com.nexttenant.geo.GeoPoints.longitudeOf(p.getExactLocation()));
                    }
                });
            }
            case 2 -> {
                model.addAttribute("configurations", Configuration.values());
                model.addAttribute("roomTypes", RoomType.values());
                model.addAttribute("handoverTypes", HandoverType.values());
                model.addAttribute("furnishings", Furnishing.values());
                model.addAttribute("amenityOptions", AMENITIES);
                model.addAttribute("perkOptions", PERKS);
            }
            case 4 -> {
                model.addAttribute("depositPaidToOptions", DepositPaidTo.values());
                model.addAttribute("agreementOptions", AgreementHandling.values());
            }
            case 5 -> model.addAttribute("genders", FlatmateGender.values());
            case 6 -> {
                model.addAttribute("photos", photos.forListing(id));
                model.addAttribute("minPhotos", PhotoService.MIN_PHOTOS_TO_PUBLISH);
                model.addAttribute("maxPhotos", PhotoService.MAX_PHOTOS);
            }
            default -> {
            }
        }

        return "listing/wizard/step" + step;
    }

    /**
     * The preview, after the six steps.
     *
     * <p>Built by the same method that renders the live page, so the preview
     * cannot drift from the real thing. A separate preview renderer would
     * diverge, and the divergence would only surface after someone published
     * something different from what they approved.
     */
    @GetMapping("/list/{id}/preview")
    public String preview(@PathVariable UUID id,
                          @AuthenticationPrincipal AuthenticatedUser user,
                          Model model) {
        Listing draft = drafts.requireOwnedDraft(id, user.id());
        addCommon(model, draft, TOTAL_STEPS);
        model.addAttribute("preview", details.preview(draft));
        model.addAttribute("missing", missingFor(draft));
        return "listing/wizard/preview";
    }

    // ---- Step 1: where ----------------------------------------------------

    @PostMapping("/list/{id}/1")
    public String saveLocation(@PathVariable UUID id,
                               @AuthenticationPrincipal AuthenticatedUser user,
                               @RequestParam(required = false) String areaName,
                               @RequestParam(required = false) String station,
                               @RequestParam(required = false) Short walkMinutes,
                               @RequestParam(required = false) Short floor,
                               @RequestParam(required = false) Boolean hasLift,
                               @RequestParam(required = false) Double latitude,
                               @RequestParam(required = false) Double longitude,
                               @RequestParam(required = false) String societyName,
                               @RequestParam(required = false) String fullAddress,
                               Model model) {
        try {
            drafts.saveLocation(id, user.id(), areaName, station, walkMinutes, floor, hasLift,
                    latitude, longitude, societyName, fullAddress);
        } catch (IllegalArgumentException e) {
            Listing draft = drafts.requireOwnedDraft(id, user.id());
            addCommon(model, draft, 1);
            model.addAttribute("areaGroups", areas.findGrouped());
            model.addAttribute("error", e.getMessage());
            return "listing/wizard/step1";
        }
        return next(id, 2);
    }

    // ---- Step 2: the flat -------------------------------------------------

    @PostMapping("/list/{id}/2")
    public String saveFlat(@PathVariable UUID id,
                           @AuthenticationPrincipal AuthenticatedUser user,
                           @RequestParam(required = false) Configuration configuration,
                           @RequestParam(required = false) RoomType roomType,
                           @RequestParam(required = false) HandoverType handoverType,
                           @RequestParam(required = false) Short existingFlatmates,
                           @RequestParam(required = false) Furnishing furnishing,
                           @RequestParam(required = false) List<String> amenities,
                           @RequestParam(required = false) List<String> perks) {
        drafts.saveFlat(id, user.id(), configuration, roomType, handoverType,
                existingFlatmates, furnishing,
                amenities == null ? List.of() : amenities,
                perks == null ? List.of() : perks);
        return next(id, 3);
    }

    // ---- Step 3: the money ------------------------------------------------

    @PostMapping("/list/{id}/3")
    public String saveMoney(@PathVariable UUID id,
                            @AuthenticationPrincipal AuthenticatedUser user,
                            @RequestParam(required = false) String monthlyRent,
                            @RequestParam(required = false) String deposit,
                            @RequestParam(required = false) String maintenance,
                            @RequestParam(defaultValue = "false") boolean maintenanceIncluded,
                            @RequestParam(required = false) String brokerage,
                            @RequestParam(required = false) String buyout,
                            RedirectAttributes redirect) {
        try {
            drafts.saveMoney(id, user.id(),
                    rupees("Monthly rent", monthlyRent),
                    rupees("Security deposit", deposit),
                    rupees("Society maintenance", maintenance),
                    maintenanceIncluded,
                    orZero(rupees("Brokerage", brokerage)),
                    orZero(rupees("Buy-out", buyout)));
        } catch (InvalidAmountException e) {
            // Back to the same step, not onward. Nothing was saved, and
            // carrying on to step 4 would leave a wrong or empty amount behind
            // with the person believing they had answered.
            redirect.addFlashAttribute("error", e.getMessage());
            return "redirect:/list/%s/3".formatted(id);
        }
        return next(id, 4);
    }

    // ---- Step 4: handover terms -------------------------------------------

    @PostMapping("/list/{id}/4")
    public String saveTerms(@PathVariable UUID id,
                            @AuthenticationPrincipal AuthenticatedUser user,
                            @RequestParam(required = false) Boolean ownerInformed,
                            @RequestParam(required = false) DepositPaidTo depositPaidTo,
                            @RequestParam(required = false) Boolean registeredAgreement,
                            @RequestParam(required = false) AgreementHandling agreementHandling,
                            @RequestParam(required = false) Short noticePeriodDays,
                            @RequestParam(required = false) Boolean bachelorsAllowed) {
        drafts.saveTerms(id, user.id(), ownerInformed, depositPaidTo, registeredAgreement,
                agreementHandling, noticePeriodDays, bachelorsAllowed);
        return next(id, 5);
    }

    // ---- Step 5: flatmates and rules --------------------------------------

    @PostMapping("/list/{id}/5")
    public String saveRules(@PathVariable UUID id,
                            @AuthenticationPrincipal AuthenticatedUser user,

                            @RequestParam(required = false) FlatmateGender flatmateGender,
                            @RequestParam(required = false) String flatmateProfessions,
                            @RequestParam(required = false) Boolean nonVegAllowed,
                            @RequestParam(required = false) Boolean petsAllowed,
                            @RequestParam(required = false) Boolean smokingAllowed,
                            @RequestParam(required = false) Boolean guestsAllowed,
                            @RequestParam(required = false) String houseRulesNote,
                            @RequestParam(required = false) String availableFrom,
                            RedirectAttributes redirect) {
        Listing draft = drafts.requireOwnedDraft(id, user.id());
        try {
            drafts.saveRules(id, user.id(), flatmateGender,
                    flatmateProfessions, nonVegAllowed, petsAllowed, smokingAllowed,
                    guestsAllowed, houseRulesNote,
                    parseDate(availableFrom, draft.getAvailableFrom(), LocalDate.now(clock.withZone(IST))));
        } catch (InvalidDateException e) {
            redirect.addFlashAttribute("error", e.getMessage());
            return "redirect:/list/%s/5".formatted(id);
        }
        return next(id, 6);
    }

    // ---- Step 6: publish --------------------------------------------------

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

        drafts.publish(id, user.id());
        // Straight to the canonical address rather than the bare id, which
        // would 301 onward. Nothing breaks either way; this just does not
        // make the first view of a brand new listing a round trip.
        return "redirect:" + draft.path();
    }

    /**
     * Everything still standing between this draft and publication.
     *
     * <p>Combines the field checks from the draft service with the photo
     * minimum, because a listing with no photos is technically complete and
     * practically useless — and the design states the minimum plainly.
     */
    private List<ListingDraftService.Missing> missingFor(Listing draft) {
        List<ListingDraftService.Missing> missing =
                new java.util.ArrayList<>(drafts.missingForPublication(draft));

        long count = photos.countFor(draft.getId());
        if (count < PhotoService.MIN_PHOTOS_TO_PUBLISH) {
            missing.add(new ListingDraftService.Missing(
                    "at least %d photos (you have %d)"
                            .formatted(PhotoService.MIN_PHOTOS_TO_PUBLISH, count),
                    TOTAL_STEPS));
        }

        return missing;
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
        LocalDate today = LocalDate.now(clock.withZone(IST));
        LocalDate existing = draft.getAvailableFrom();
        model.addAttribute("earliestMoveIn",
                existing != null && existing.isBefore(today) ? existing : today);
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

    /**
     * Accepts what people actually type into a money field.
     *
     * <p>"32,000", "₹32000" and "32000 " are all the same number. Rejecting the
     * first two would be technically defensible and would irritate everybody,
     * because the rest of the site renders rents with those very separators.
     */
    /**
     * What counts as somebody typing a number: digits, and the separators the
     * site itself prints. A rupee sign because it is beside the field, commas
     * and spaces because "32,000" and "32 000" are how the amount is written
     * everywhere else on the page.
     */
    private static final java.util.regex.Pattern MONEY =
            java.util.regex.Pattern.compile("[\\u20B9\\s,0-9]+");

    private static Integer rupees(String field, String input) {
        if (input == null || input.isBlank()) {
            return null;
        }

        String cleaned = input.trim();

        // Anything else is refused rather than filtered out, and that is the
        // whole change here. Stripping non-digits turned "0234ax" into "0234"
        // and saved ₹234 — a number the person never typed, on the field that
        // decides what they are asking for, with nothing on screen to say it
        // had happened. A typo is worth an error message; it is not worth
        // guessing at.
        if (!MONEY.matcher(cleaned).matches()) {
            throw new InvalidAmountException(field);
        }

        String digits = cleaned.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) {
            return null;
        }
        try {
            return Integer.valueOf(digits);
        } catch (NumberFormatException e) {
            // Longer than an int: eleven digits of rent is a typo too.
            throw new InvalidAmountException(field);
        }
    }

    /** A money field that was not a number. Carries the label to point at. */
    static class InvalidAmountException extends RuntimeException {
        InvalidAmountException(String field) {
            super("%s needs to be a number — digits only.".formatted(field));
        }
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }

    /**
     * The move-in date, refused if it is in the past.
     *
     * <p>"Available from 3 August" on a listing read in October is not a date,
     * it is a sign nobody has looked at the listing in months — and it is the
     * one field a searcher filters on, so a stale value quietly removes the
     * room from the results it should be in.
     *
     * <p>The exception is a date already stored. A listing published in August
     * for an August move-in is still perfectly valid in October; it simply
     * means "available now". Refusing to let its owner save an unrelated edit
     * to house rules, because a field they did not touch is now in the past,
     * would be the validation making the listing worse.
     */
    private static LocalDate parseDate(String value, LocalDate current, LocalDate today) {
        if (value == null || value.isBlank()) {
            return null;
        }

        LocalDate parsed;
        try {
            parsed = LocalDate.parse(value.trim());
        } catch (java.time.format.DateTimeParseException e) {
            throw new InvalidDateException("That move-in date could not be read.");
        }

        if (parsed.isBefore(today) && !parsed.equals(current)) {
            throw new InvalidDateException(
                    "The move-in date cannot be in the past.");
        }
        return parsed;
    }

    /** A move-in date that was unreadable or already gone. */
    static class InvalidDateException extends RuntimeException {
        InvalidDateException(String message) {
            super(message);
        }
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
