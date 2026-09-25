package com.househunting.photo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A photo belonging to a listing.
 *
 * <p>Holds the storage key and metadata only. The bytes live in
 * {@link PhotoStorage}: images in Postgres bloat every backup, bypass CDN
 * caching and make the database the bottleneck for a purely static asset.
 */
@Entity
@Table(name = "photos")
public class Photo {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id = UUID.randomUUID();

    @Column(name = "listing_id", nullable = false, updatable = false)
    private UUID listingId;

    @Column(name = "object_key", nullable = false, length = 255)
    private String objectKey;

    /**
     * Position 0 IS the cover.
     *
     * <p>Deliberately not an is_cover BOOLEAN, which would permit two covers or
     * none — states the application would have to defend against forever.
     * Order and cover are one fact, stored once.
     */
    @Column(name = "position", nullable = false)
    private short position;

    @Column(name = "width")
    private Short width;

    @Column(name = "height")
    private Short height;

    @Column(name = "byte_size")
    private Integer byteSize;

    /**
     * Set once the image has been re-encoded and its EXIF discarded.
     *
     * <p>A NULL here means "not safe to serve". Phone cameras embed GPS in
     * photos, so an unprocessed image would leak the exact address that
     * listing_private exists to protect. Serving is gated on this column rather
     * than on the upload having appeared to succeed.
     */
    @Column(name = "processed_at")
    private Instant processedAt;

    @Column(name = "created_at", insertable = false, updatable = false)
    @Generated(event = EventType.INSERT)
    private Instant createdAt;

    protected Photo() {
    }

    public Photo(UUID listingId, String objectKey, int position) {
        this.listingId = Objects.requireNonNull(listingId, "listingId");
        this.objectKey = Objects.requireNonNull(objectKey, "objectKey");
        this.position = (short) position;
    }

    /** Called only after the image has been stripped and re-encoded. */
    public void markProcessed(Instant now, int width, int height, int byteSize) {
        this.processedAt = now;
        this.width = (short) Math.min(width, Short.MAX_VALUE);
        this.height = (short) Math.min(height, Short.MAX_VALUE);
        this.byteSize = byteSize;
    }

    public void moveTo(int newPosition) {
        this.position = (short) newPosition;
    }

    public boolean isCover() {
        return position == 0;
    }

    public boolean isServable() {
        return processedAt != null;
    }

    /**
     * The thumbnail's key, derived rather than stored.
     *
     * <p>One key in the table, two files in storage. A second column would be a
     * second thing to keep in step for no benefit: the thumbnail is always
     * beside its original, always with the same suffix.
     */
    public String thumbnailKey() {
        int dot = objectKey.lastIndexOf('.');
        return dot < 0 ? objectKey + "_t" : objectKey.substring(0, dot) + "_t" + objectKey.substring(dot);
    }

    public UUID getId() {
        return id;
    }

    public UUID getListingId() {
        return listingId;
    }

    public String getObjectKey() {
        return objectKey;
    }

    public short getPosition() {
        return position;
    }

    public Short getWidth() {
        return width;
    }

    public Short getHeight() {
        return height;
    }

    public Integer getByteSize() {
        return byteSize;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Photo other)) {
            return false;
        }
        return id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return "Photo{id=" + id + ", position=" + position + '}';
    }
}
