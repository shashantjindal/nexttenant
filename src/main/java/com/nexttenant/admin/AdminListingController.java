package com.nexttenant.admin;

import com.nexttenant.auth.AuthenticatedUser;
import com.nexttenant.auth.InvalidLoginEmailException;
import com.nexttenant.auth.InvalidPhoneNumberException;
import com.nexttenant.listing.Configuration;
import com.nexttenant.listing.FlatType;
import com.nexttenant.listing.FlatmateGender;
import com.nexttenant.listing.FlatmateMakeup;
import com.nexttenant.listing.Furnishing;
import com.nexttenant.listing.Listing;
import com.nexttenant.listing.ListingStatus;
import com.nexttenant.listing.RoomType;
import com.nexttenant.listing.wizard.Actor;
import com.nexttenant.listing.wizard.ListingDraftService;
import com.nexttenant.listing.wizard.ListingFormOptions;
import com.nexttenant.listing.wizard.ListingInputs;
import com.nexttenant.listing.wizard.ListingReadiness;
import com.nexttenant.photo.PhotoService;
import com.nexttenant.user.AgeBracket;
import com.nexttenant.user.Gender;
import com.nexttenant.user.PhoneAlreadyInUseException;
import com.nexttenant.user.User;
import com.nexttenant.user.UserAccounts;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.nexttenant.listing.wizard.ListingInputs.orZero;
import static com.nexttenant.listing.wizard.ListingInputs.rupees;

/**
 * Listing a flat for somebody else.
 *
 * <p>Three pages. The ordinary wizard is two, and the extra one at the front
 * is the whole difference: it asks <em>whose listing this is</em>, because an
 * operator typing details off a WhatsApp message has to say who they are
 * typing for before anything else means anything.
 *
 * <pre>
 *   1  /admin/listings/new       who it is for    &rarr; account + draft
 *   2  /admin/listings/{id}/2    the basics       same fields as /list/{id}/1
 *   3  /admin/listings/{id}/3    photos and money same fields as /list/{id}/2
 * </pre>
 *
 * <p><b>The fields are not copied, they are the same markup.</b> Pages two and
 * three include the fragments the public wizard includes; only the form action
 * and the chrome around them differ. A copy would have been quicker and would
 * have drifted on the first new field — this codebase has watched exactly that
 * happen to a shared row mapper twice.
 *
 * <p><b>Who owns the result: the person, never the operator.</b> The account
 * created on page one becomes the listing's {@code leaver_user_id}, so
 * requests go to them, their number is what {@code ContactRelease} hands over,
 * and the listing is waiting for them the first time they sign in. An
 * operator-owned listing would release the operator's number, which is not a
 * marketplace.
 *
 * <p>No security annotation, like every other handler under this prefix:
 * {@code SecurityConfig} matches {@code /admin/**} to {@code hasRole("ADMIN")}
 * before {@code anyRequest()}, so a route added here is protected by default
 * and forgetting an annotation cannot expose one.
 */
@Controller
public class AdminListingController {

    /** Pages, not wizard steps — page one is the account. */
    private static final int TOTAL_PAGES = 3;

    /**
     * Where an outstanding photo minimum points.
     *
     * <p>{@link ListingDraftService.Missing} carries wizard step numbers, 1
     * and 2, because that is where those fields live on the public flow. This
     * flow shifts everything by one page, so the templates render a step as
     * {@code step + 1} — and the photo check is handed "2" here so it lands on
     * page three with the rest of them.
     */
    private static final int PHOTO_STEP = 2;

    private final AdminListingOnboarding onboarding;
    private final ListingDraftService drafts;
    private final ListingFormOptions options;
    private final ListingReadiness readiness;
    private final PhotoService photos;
    private final UserAccounts accounts;
    private final Clock clock;

    public AdminListingController(AdminListingOnboarding onboarding,
                                  ListingDraftService drafts,
                                  ListingFormOptions options,
                                  ListingReadiness readiness,
                                  PhotoService photos,
                                  UserAccounts accounts,
                                  Clock clock) {
        this.onboarding = onboarding;
        this.drafts = drafts;
        this.options = options;
        this.readiness = readiness;
        this.photos = photos;
        this.accounts = accounts;
        this.clock = clock;
    }

    // ---- Page 1: who it is for --------------------------------------------

    @GetMapping("/admin/listings/new")
    public String newListing(Model model) {
        addAccountOptions(model);
        return "admin/listing-new";
    }

    /**
     * Creates or finds the account, opens a draft, moves to page two.
     *
     * <p>Everything that can be refused is refused here rather than two pages
     * later. A mistyped number discovered after the photos are uploaded is a
     * listing with nowhere to send its requests.
     */
    @PostMapping("/admin/listings/new")
    public String openListing(@RequestParam(required = false) String email,
                              @RequestParam(required = false) String firstName,
                              @RequestParam(required = false) String phone,
                              @RequestParam(required = false) Gender gender,
                              @RequestParam(required = false) AgeBracket ageBracket,
                              Model model) {
        AdminListingOnboarding.Opened opened;
        try {
            opened = onboarding.open(new AdminListingOnboarding.Details(
                    email, firstName, phone, gender, ageBracket));
        } catch (InvalidLoginEmailException | InvalidPhoneNumberException
                 | PhoneAlreadyInUseException | IllegalArgumentException
                 | AdminListingOnboarding.SuspendedAccountException e) {
            // Re-rendered rather than redirected, so what was typed is still
            // in the boxes. Five fields retyped because of one bad digit is
            // how an operator stops using a tool.
            addAccountOptions(model);
            model.addAttribute("error", e.getMessage());
            model.addAttribute("email", email);
            model.addAttribute("firstName", firstName);
            model.addAttribute("phone", phone);
            return "admin/listing-new";
        }

        return "redirect:/admin/listings/%s/2".formatted(opened.draft().getId());
    }

    // ---- Page 2: the basics -----------------------------------------------

    @GetMapping("/admin/listings/{id}/2")
    public String basics(@PathVariable UUID id,
                         @AuthenticationPrincipal AuthenticatedUser user,
                         Model model) {
        Listing draft = drafts.requireEditable(id, Actor.admin(user.id()));
        addCommon(model, draft, 2);
        options.addTo(model);
        return "admin/listing-basics";
    }

    @PostMapping("/admin/listings/{id}/2")
    public String saveBasics(@PathVariable UUID id,
                             @AuthenticationPrincipal AuthenticatedUser user,
                             @RequestParam(required = false) String areaName,
                             @RequestParam(required = false) Configuration configuration,
                             @RequestParam(required = false) RoomType roomType,
                             @RequestParam(required = false) Furnishing furnishing,
                             @RequestParam(required = false) FlatType flatType,
                             @RequestParam(required = false) FlatmateGender flatmateGender,
                             @RequestParam(required = false) FlatmateMakeup flatmateMakeup,
                             @RequestParam(required = false) String availableFrom,
                             @RequestParam(required = false) Short existingFlatmates,
                             RedirectAttributes redirect) {
        Actor actor = Actor.admin(user.id());
        Listing draft = drafts.requireEditable(id, actor);
        try {
            drafts.saveBasics(id, actor, areaName, configuration, roomType,
                    furnishing, flatType, flatmateGender, flatmateMakeup,
                    ListingInputs.parseDate(availableFrom, draft.getAvailableFrom(),
                            ListingInputs.today(clock)),
                    existingFlatmates);
        } catch (ListingInputs.InvalidDateException | IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
            return "redirect:/admin/listings/%s/2".formatted(id);
        }
        return "redirect:/admin/listings/%s/3".formatted(id);
    }

    // ---- Page 3: photos and money -----------------------------------------

    @GetMapping("/admin/listings/{id}/3")
    public String details(@PathVariable UUID id,
                          @AuthenticationPrincipal AuthenticatedUser user,
                          Model model) {
        Listing draft = drafts.requireEditable(id, Actor.admin(user.id()));
        addCommon(model, draft, 3);

        model.addAttribute("photos", photos.forListing(id));
        model.addAttribute("minPhotos", PhotoService.MIN_PHOTOS_TO_PUBLISH);
        model.addAttribute("maxPhotos", PhotoService.MAX_PHOTOS);
        model.addAttribute("missing", readiness.missingFor(draft, PHOTO_STEP));
        drafts.privateFor(id).ifPresent(p -> model.addAttribute("priv", p));

        return "admin/listing-details";
    }

    /**
     * Saves the money, and publishes if nothing is outstanding.
     *
     * <p>Finishing the last page IS publishing, exactly as on the public flow.
     * A separate confirm step would be a second deliberate decision asked of
     * somebody who has already answered every question — and an operator who
     * fills in nine fields and then forgets one more button has done the whole
     * job for nothing.
     */
    @PostMapping("/admin/listings/{id}/3")
    public String saveDetails(@PathVariable UUID id,
                              @AuthenticationPrincipal AuthenticatedUser user,
                              @RequestParam(required = false) String monthlyRent,
                              @RequestParam(required = false) String deposit,
                              @RequestParam(required = false) String brokerage,
                              @RequestParam(required = false) String buyout,
                              @RequestParam(required = false) String mapsLink,
                              @RequestParam(required = false) String description,
                              RedirectAttributes redirect) {
        Actor actor = Actor.admin(user.id());
        try {
            drafts.saveMoneyAndExtras(id, actor,
                    rupees("Monthly rent", monthlyRent),
                    rupees("Security deposit", deposit),
                    orZero(rupees("Brokerage", brokerage)),
                    orZero(rupees("Buy-out", buyout)),
                    mapsLink, description);
        } catch (ListingInputs.InvalidAmountException e) {
            redirect.addFlashAttribute("error", e.getMessage());
            return "redirect:/admin/listings/%s/3".formatted(id);
        }

        Listing draft = drafts.requireEditable(id, actor);
        List<ListingDraftService.Missing> missing = readiness.missingFor(draft, PHOTO_STEP);

        if (!missing.isEmpty()) {
            // Back to this page, which renders the outstanding list with a
            // link per item. Saying "incomplete" without saying what is
            // missing is the error message that makes somebody guess.
            redirect.addFlashAttribute("error", "Still needed: " + missing.stream()
                    .map(ListingDraftService.Missing::label)
                    .collect(Collectors.joining(", ")));
            return "redirect:/admin/listings/%s/3".formatted(id);
        }

        if (draft.getStatus() == ListingStatus.DRAFT) {
            drafts.publish(id, actor);
        }
        redirect.addFlashAttribute("notice",
                "Listing is live for " + ownerName(draft) + ".");
        return "redirect:" + draft.path();
    }

    // ---- Shared -----------------------------------------------------------

    private void addCommon(Model model, Listing draft, int page) {
        model.addAttribute("draft", draft);
        model.addAttribute("page", page);
        model.addAttribute("totalPages", TOTAL_PAGES);
        model.addAttribute("ownerName", ownerName(draft));

        // The wizard fragments read this to decide between "Continue" and
        // "Save" wording. It is the listing's own state, not a fact about the
        // operator, so it is answered the same way the public flow answers it.
        model.addAttribute("editing", drafts.isPublished(draft));

        model.addAttribute("earliestMoveIn", ListingInputs.earliestMoveIn(
                draft.getAvailableFrom(), ListingInputs.today(clock)));
    }

    /**
     * Whose listing this is, named on every page.
     *
     * <p>An operator doing several of these in one sitting is one tab away
     * from typing somebody's rent onto somebody else's room. A name in the
     * header is the cheapest guard against that there is.
     */
    private String ownerName(Listing draft) {
        return accounts.byId(draft.getLeaverUserId())
                .map(User::getFirstName)
                .filter(name -> name != null && !name.isBlank())
                .orElse("this account");
    }

    private void addAccountOptions(Model model) {
        // Named apart from the listing's own "genders", which is
        // FlatmateGender — who may TAKE the room, a different question with
        // different values. Two model attributes called genders in one flow is
        // a bug waiting for the day both pages share a fragment.
        model.addAttribute("userGenders", Gender.values());
        model.addAttribute("ageBrackets", AgeBracket.values());
    }
}
