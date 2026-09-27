package com.nexttenant.photo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * Uploading, ordering and removing listing photos.
 *
 * <p>The upload path is deliberately synchronous: process first, store second,
 * record third. A background job would return faster and introduce a window in
 * which an unprocessed image exists — and an unprocessed image is one with GPS
 * coordinates still inside it. When the work is the safety property, doing it
 * before acknowledging the upload is the simpler correct choice.
 */
@Service
public class PhotoService {

    private static final Logger log = LoggerFactory.getLogger(PhotoService.class);

    /** Enough for a room without becoming a slideshow. */
    public static final int MAX_PHOTOS = 10;

    /** "Minimum 3 required" — from the wizard's photo step. */
    public static final int MIN_PHOTOS_TO_PUBLISH = 3;

    /** Rejected before decoding. A 20MB upload is a mistake or an attack. */
    public static final long MAX_UPLOAD_BYTES = 12L * 1024 * 1024;

    private final PhotoRepository photos;
    private final PhotoStorage storage;
    private final ImageProcessor processor;
    private final Clock clock;

    public PhotoService(PhotoRepository photos, PhotoStorage storage,
                        ImageProcessor processor, Clock clock) {
        this.photos = photos;
        this.storage = storage;
        this.processor = processor;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<Photo> forListing(UUID listingId) {
        return photos.findByListingIdOrderByPosition(listingId);
    }

    /** Only processed images. This is what the public pages ask for. */
    @Transactional(readOnly = true)
    public List<Photo> servableFor(UUID listingId) {
        return photos.findByListingIdAndProcessedAtIsNotNullOrderByPosition(listingId);
    }

    @Transactional(readOnly = true)
    public long countFor(UUID listingId) {
        return photos.countByListingIdAndProcessedAtIsNotNull(listingId);
    }

    /**
     * Processes and stores an upload.
     *
     * <p>Order matters and is worth reading carefully:
     *
     * <ol>
     *   <li>strip and re-encode — the original bytes are never stored, not even
     *       briefly, so there is no window during which a file containing GPS
     *       exists on disk;</li>
     *   <li>write the processed bytes to storage;</li>
     *   <li>record the row and mark it processed.</li>
     * </ol>
     *
     * <p>The original upload is held in memory and discarded. It is never
     * written anywhere.
     */
    @Transactional
    public Photo upload(UUID listingId, byte[] bytes, String originalFilename) {
        if (bytes == null || bytes.length == 0) {
            throw new ImageProcessor.InvalidImageException("That file is empty");
        }
        if (bytes.length > MAX_UPLOAD_BYTES) {
            throw new ImageProcessor.InvalidImageException("That photo is larger than 12 MB");
        }
        if (photos.countByListingId(listingId) >= MAX_PHOTOS) {
            throw new ImageProcessor.InvalidImageException(
                    "That is the maximum of " + MAX_PHOTOS + " photos");
        }

        // 1. Strip and re-encode BEFORE anything touches storage.
        ImageProcessor.Processed processed = processor.process(bytes);

        UUID photoId = UUID.randomUUID();
        // The key is generated, never derived from the uploaded filename: a
        // filename is attacker-controlled and ends up as a filesystem path.
        String key = "listings/%s/%s.jpg".formatted(listingId, photoId);

        Photo photo = new Photo(listingId, key, nextPosition(listingId));

        // 2. Store the processed bytes.
        storage.put(key, processed.full());
        storage.put(photo.thumbnailKey(), processed.thumbnail());

        // 3. Record it, and only now is it servable.
        photo.markProcessed(clock.instant(), processed.width(), processed.height(),
                processed.full().length);

        log.debug("Photo stored for listing {}: {} bytes in, {} bytes out",
                listingId, bytes.length, processed.full().length);

        return photos.save(photo);
    }

    /**
     * Reorders a listing's photos to the given sequence of ids.
     *
     * <p>Works as a plain sequence of updates because V8 made the position
     * constraint DEFERRABLE INITIALLY DEFERRED: the set may be inconsistent
     * inside the transaction and must be consistent at COMMIT. Without that,
     * the first update would collide with a position another photo still holds.
     *
     * <p>Ids not belonging to this listing are ignored rather than trusted.
     */
    @Transactional
    public void reorder(UUID listingId, List<UUID> orderedIds) {
        List<Photo> existing = photos.findByListingIdOrderByPosition(listingId);

        int position = 0;
        for (UUID id : orderedIds) {
            for (Photo photo : existing) {
                if (photo.getId().equals(id)) {
                    photo.moveTo(position++);
                    break;
                }
            }
        }

        // Anything the client omitted keeps a valid position at the end, so a
        // truncated or stale request cannot leave gaps or duplicates.
        for (Photo photo : existing) {
            if (!orderedIds.contains(photo.getId())) {
                photo.moveTo(position++);
            }
        }

        photos.saveAll(existing);
    }

    /**
     * Removes a photo and closes the gap it leaves.
     *
     * <p>Compacting matters: position 0 is the cover, so deleting the first
     * photo must promote the next one rather than leave a listing with no
     * cover at all.
     */
    @Transactional
    public void delete(UUID listingId, UUID photoId) {
        List<Photo> remaining = photos.findByListingIdOrderByPosition(listingId).stream()
                .filter(p -> {
                    if (p.getId().equals(photoId)) {
                        storage.delete(p.getObjectKey());
                        storage.delete(p.thumbnailKey());
                        photos.delete(p);
                        return false;
                    }
                    return true;
                })
                .toList();

        int position = 0;
        for (Photo photo : remaining) {
            photo.moveTo(position++);
        }
        photos.saveAll(remaining);
    }

    private int nextPosition(UUID listingId) {
        return photos.findByListingIdOrderByPosition(listingId).stream()
                .mapToInt(Photo::getPosition)
                .max()
                .orElse(-1) + 1;
    }
}
