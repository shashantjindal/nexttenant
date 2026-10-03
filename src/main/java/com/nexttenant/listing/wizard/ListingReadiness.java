package com.nexttenant.listing.wizard;

import com.nexttenant.listing.Listing;
import com.nexttenant.photo.PhotoService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything still standing between a listing and publication.
 *
 * <p>Two sources, one answer. {@link ListingDraftService#missingForPublication}
 * knows which fields are required, because it is the same rule
 * {@code listings_live_complete_ck} enforces in the database. The photo
 * minimum lives in {@link PhotoService} and is not a database constraint at
 * all — a listing with no photos is technically complete and practically
 * useless.
 *
 * <p>This was a private method on {@code WizardController} until a second
 * screen needed to publish. Copying it would have been four lines, and the
 * copies would have disagreed the first time the photo minimum changed: one
 * door would publish a listing the other refused, which is the kind of
 * difference nobody notices until somebody complains that the same listing
 * behaves differently depending on who filled it in.
 *
 * <p>This does mean the wizard package now reaches into the photo package,
 * which already reaches back into this one. That is a genuine cost and it is
 * the smaller one: a package-level cycle between two packages that are
 * obviously about the same thing, against two definitions of when a listing
 * may go live.
 */
@Component
public class ListingReadiness {

    private final ListingDraftService drafts;
    private final PhotoService photos;

    public ListingReadiness(ListingDraftService drafts, PhotoService photos) {
        this.drafts = drafts;
        this.photos = photos;
    }

    /**
     * @return what is outstanding, each item carrying the step that fixes it
     *         so the page can render it as a link rather than a complaint
     */
    public List<ListingDraftService.Missing> missingFor(Listing draft, int photoStep) {
        List<ListingDraftService.Missing> missing =
                new ArrayList<>(drafts.missingForPublication(draft));

        long count = photos.countFor(draft.getId());
        if (count < PhotoService.MIN_PHOTOS_TO_PUBLISH) {
            missing.add(new ListingDraftService.Missing(
                    "at least %d photos (you have %d)"
                            .formatted(PhotoService.MIN_PHOTOS_TO_PUBLISH, count),
                    photoStep));
        }

        return missing;
    }
}
