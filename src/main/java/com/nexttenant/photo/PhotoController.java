package com.nexttenant.photo;

import com.nexttenant.auth.AuthenticatedUser;
import com.nexttenant.listing.wizard.ListingDraftService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Uploading and serving listing photos.
 */
@Controller
public class PhotoController {

    /**
     * The wizard screen photos live on.
     *
     * <p>A constant rather than four copies of a string. It was "/6" in four
     * places, and when the wizard went from six screens to two every upload
     * redirected to a step that no longer existed — a 404 immediately after a
     * successful upload, which reads as the upload having failed.
     */
    private static final String PHOTO_STEP = "/2";

    private final PhotoService photos;
    private final PhotoRepository repository;
    private final PhotoStorage storage;
    private final ListingDraftService drafts;

    public PhotoController(PhotoService photos, PhotoRepository repository,
                           PhotoStorage storage, ListingDraftService drafts) {
        this.photos = photos;
        this.repository = repository;
        this.storage = storage;
        this.drafts = drafts;
    }

    // ---- Serving ----------------------------------------------------------

    @GetMapping("/photos/{photoId}")
    public ResponseEntity<byte[]> full(@PathVariable UUID photoId) {
        return serve(photoId, false);
    }

    @GetMapping("/photos/{photoId}/thumb")
    public ResponseEntity<byte[]> thumbnail(@PathVariable UUID photoId) {
        return serve(photoId, true);
    }

    /**
     * Serves a photo, gated on {@code processed_at}.
     *
     * <p>The gate is the point. A row with a NULL processed_at is an image
     * whose EXIF has not been discarded — which means it may still contain the
     * exact coordinates every other mechanism in this application works to
     * protect. Returning 404 for one is the safe failure: a missing photo is an
     * inconvenience, a leaked address is not.
     */
    private ResponseEntity<byte[]> serve(UUID photoId, boolean thumb) {
        Photo photo = repository.findById(photoId)
                .filter(Photo::isServable)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        String key = thumb ? photo.thumbnailKey() : photo.getObjectKey();

        byte[] bytes = storage.get(key)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                // The key contains a UUID and the bytes never change, so this
                // is safe to cache hard. Replacing a photo creates a new id.
                .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
                .body(bytes);
    }

    // ---- Uploading --------------------------------------------------------

    @PostMapping("/list/{listingId}/photos")
    public String upload(@PathVariable UUID listingId,
                         @AuthenticationPrincipal AuthenticatedUser user,
                         @RequestParam("file") List<MultipartFile> files,
                         RedirectAttributes redirect) {
        // Ownership first. Without this, anyone signed in could add photos to
        // anyone's listing simply by knowing its id.
        drafts.requireOwnedDraft(listingId, user.id());

        int stored = 0;
        for (MultipartFile file : files) {
            if (file.isEmpty()) {
                continue;
            }
            try {
                photos.upload(listingId, file.getBytes(), file.getOriginalFilename());
                stored++;
            } catch (ImageProcessor.InvalidImageException e) {
                // One bad file does not discard the rest of the batch: someone
                // selecting eight photos should not lose seven because the
                // eighth was a screenshot of a PDF.
                redirect.addFlashAttribute("photoError", e.getMessage());
            } catch (IOException e) {
                redirect.addFlashAttribute("photoError", "That upload did not complete");
            }
        }

        if (stored > 0) {
            redirect.addFlashAttribute("photoNotice",
                    stored == 1 ? "Photo added" : stored + " photos added");
        }
        return "redirect:/list/" + listingId + PHOTO_STEP;
    }

    @PostMapping("/list/{listingId}/photos/{photoId}/delete")
    public String delete(@PathVariable UUID listingId, @PathVariable UUID photoId,
                         @AuthenticationPrincipal AuthenticatedUser user) {
        drafts.requireOwnedDraft(listingId, user.id());
        photos.delete(listingId, photoId);
        return "redirect:/list/" + listingId + PHOTO_STEP;
    }

    /**
     * Promotes one photo to position 0.
     *
     * <p>The design describes dragging to reorder, which needs JavaScript.
     * Choosing the cover is the part that actually matters — it is the image
     * every search result shows — so it gets an explicit control that works
     * without scripting. Drag can be layered on top later without changing
     * this endpoint.
     */
    /**
     * Captions one photo.
     *
     * <p>Saved per photo rather than all of them at once, because the photo
     * screen already has three forms on it and a fourth that wrapped the whole
     * grid would mean a stray Enter in any caption box submitting every other
     * one too.
     */
    @PostMapping("/list/{listingId}/photos/{photoId}/label")
    public String label(@PathVariable UUID listingId, @PathVariable UUID photoId,
                        @AuthenticationPrincipal AuthenticatedUser user,
                        @RequestParam(required = false) String label) {
        drafts.requireOwnedDraft(listingId, user.id());
        photos.label(listingId, photoId, label);
        return "redirect:/list/" + listingId + PHOTO_STEP;
    }

    @PostMapping("/list/{listingId}/photos/{photoId}/cover")
    public String makeCover(@PathVariable UUID listingId, @PathVariable UUID photoId,
                            @AuthenticationPrincipal AuthenticatedUser user) {
        drafts.requireOwnedDraft(listingId, user.id());
        // reorder() puts the listed ids first and appends the rest, so naming
        // one id is enough to promote it.
        photos.reorder(listingId, List.of(photoId));
        return "redirect:/list/" + listingId + PHOTO_STEP;
    }

    @PostMapping("/list/{listingId}/photos/order")
    public String reorder(@PathVariable UUID listingId,
                          @AuthenticationPrincipal AuthenticatedUser user,
                          @RequestParam("id") List<UUID> orderedIds) {
        drafts.requireOwnedDraft(listingId, user.id());
        photos.reorder(listingId, orderedIds);
        return "redirect:/list/" + listingId + PHOTO_STEP;
    }
}
