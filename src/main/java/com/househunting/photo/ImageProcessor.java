package com.househunting.photo;

import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

/**
 * Turns an uploaded photo into something safe to publish.
 *
 * <p><strong>This is the most security-relevant class in the codebase.</strong>
 * Every other privacy mechanism — the table split, the location jitter, the
 * DTOs — protects a coordinate the application holds. A photo taken on a phone
 * carries the coordinates <em>inside the file</em>, in its EXIF metadata.
 * Serve that file untouched and anyone can open it in any image viewer and read
 * the exact address off it. The jitter becomes decorative, and the promise on
 * every listing page becomes false.
 *
 * <p>The pipeline, in this order, and the order matters:
 *
 * <ol>
 *   <li><strong>Read the orientation tag</strong> before discarding metadata.
 *       Phones almost always record the sensor's raw pixels and add a tag
 *       saying "rotate this when displaying". Strip the tag without acting on
 *       it and every portrait photo renders sideways — a bug that looks like
 *       sloppiness and is actually the privacy fix misapplied.</li>
 *   <li><strong>Rotate the pixels</strong> so the image is correct without any
 *       tag telling a viewer what to do.</li>
 *   <li><strong>Resize</strong> to a sane maximum. A 12-megapixel phone photo
 *       is four megabytes of detail nobody needs to judge a room.</li>
 *   <li><strong>Re-encode via ImageIO.</strong> This is what actually removes
 *       the metadata: ImageIO writes a JPEG from pixel data and does not carry
 *       across anything it was not given. There is no "strip" call — the
 *       stripping is a consequence of writing a new file from pixels alone.</li>
 * </ol>
 */
@Component
public class ImageProcessor {

    private static final Logger log = LoggerFactory.getLogger(ImageProcessor.class);

    /** Long edge of a published photo. Plenty for a listing, a fraction of the bytes. */
    public static final int MAX_DIMENSION = 1600;

    /** Long edge of the card thumbnail. */
    public static final int THUMB_DIMENSION = 480;

    /** JPEG quality. 0.82 is the usual knee: past it, bytes rise and eyes do not notice. */
    private static final float JPEG_QUALITY = 0.82f;

    /**
     * Upper bound on decoded pixels, checked BEFORE decoding.
     *
     * <p>A "decompression bomb": a few kilobytes of highly compressed image can
     * decode to tens of thousands of pixels square, which is gigabytes of heap
     * and an out-of-memory kill. File size alone does not protect against it —
     * the dimensions have to be read from the header first.
     */
    private static final long MAX_PIXELS = 50_000_000L;

    /** A processed image, ready to store. */
    public record Processed(byte[] full, byte[] thumbnail, int width, int height) {
    }

    /**
     * @throws InvalidImageException if the bytes are not a usable image
     */
    public Processed process(byte[] original) {
        if (original == null || original.length == 0) {
            throw new InvalidImageException("That file is empty");
        }

        int orientation = readOrientation(original);

        BufferedImage image = decodeWithLimits(original);
        image = applyOrientation(image, orientation);

        BufferedImage full = resizeToFit(image, MAX_DIMENSION);
        BufferedImage thumb = resizeToFit(image, THUMB_DIMENSION);

        return new Processed(
                encodeJpeg(full),
                encodeJpeg(thumb),
                full.getWidth(),
                full.getHeight());
    }

    /**
     * Reads the EXIF orientation tag, defaulting to 1 (no rotation).
     *
     * <p>This is the ONLY thing taken from the metadata. Everything else — GPS,
     * device model, timestamps, serial numbers — is discarded by never being
     * read in the first place.
     */
    private int readOrientation(byte[] bytes) {
        try {
            Metadata metadata = ImageMetadataReader.readMetadata(new ByteArrayInputStream(bytes));
            ExifIFD0Directory dir = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
            if (dir != null && dir.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) {
                return dir.getInt(ExifIFD0Directory.TAG_ORIENTATION);
            }
        } catch (ImageProcessingException | IOException
                 | com.drew.metadata.MetadataException | RuntimeException e) {
            // A missing or unreadable tag is entirely normal — screenshots and
            // edited images often have none. Treat it as "no rotation needed"
            // rather than as a failure.
            log.debug("No readable EXIF orientation; assuming upright");
        }
        return 1;
    }

    /**
     * Decodes, refusing anything absurdly large.
     *
     * <p>The dimensions come from the header before any pixels are decoded, so
     * a bomb is rejected without ever allocating for it.
     */
    private BufferedImage decodeWithLimits(byte[] bytes) {
        try (ImageInputStream stream = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) {
                throw new InvalidImageException("That does not look like an image");
            }

            ImageReader reader = readers.next();
            try {
                reader.setInput(stream);
                long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
                if (pixels > MAX_PIXELS) {
                    throw new InvalidImageException("That image is too large");
                }
                BufferedImage image = reader.read(0);
                if (image == null) {
                    throw new InvalidImageException("That image could not be read");
                }
                return image;
            } finally {
                reader.dispose();
            }
        } catch (IOException e) {
            throw new InvalidImageException("That image could not be read");
        }
    }

    /**
     * Rotates and flips the pixels according to the EXIF orientation.
     *
     * <p>All eight values are handled. Six and eight (the two 90-degree cases)
     * are overwhelmingly the common ones — they are what a phone records when
     * held upright — and the mirrored variants come from front cameras and some
     * editing software.
     */
    private BufferedImage applyOrientation(BufferedImage image, int orientation) {
        if (orientation == 1) {
            return image;
        }

        int w = image.getWidth();
        int h = image.getHeight();

        // Odd rotations swap the canvas dimensions.
        boolean swaps = orientation >= 5;
        BufferedImage rotated = new BufferedImage(
                swaps ? h : w, swaps ? w : h, BufferedImage.TYPE_INT_RGB);

        AffineTransform transform = switch (orientation) {
            case 2 -> flipHorizontal(w);
            case 3 -> rotate180(w, h);
            case 4 -> flipVertical(h);
            case 5 -> transpose();
            case 6 -> rotate90(h);
            case 7 -> transverse(w, h);
            case 8 -> rotate270(w);
            default -> new AffineTransform();
        };

        Graphics2D g = rotated.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(image, transform, null);
        g.dispose();

        return rotated;
    }

    private static AffineTransform flipHorizontal(int w) {
        AffineTransform t = new AffineTransform();
        t.scale(-1, 1);
        t.translate(-w, 0);
        return t;
    }

    private static AffineTransform flipVertical(int h) {
        AffineTransform t = new AffineTransform();
        t.scale(1, -1);
        t.translate(0, -h);
        return t;
    }

    private static AffineTransform rotate180(int w, int h) {
        AffineTransform t = new AffineTransform();
        t.translate(w, h);
        t.rotate(Math.PI);
        return t;
    }

    private static AffineTransform rotate90(int h) {
        AffineTransform t = new AffineTransform();
        t.translate(h, 0);
        t.rotate(Math.PI / 2);
        return t;
    }

    private static AffineTransform rotate270(int w) {
        AffineTransform t = new AffineTransform();
        t.translate(0, w);
        t.rotate(3 * Math.PI / 2);
        return t;
    }

    private static AffineTransform transpose() {
        AffineTransform t = new AffineTransform();
        t.rotate(Math.PI / 2);
        t.scale(1, -1);
        return t;
    }

    private static AffineTransform transverse(int w, int h) {
        AffineTransform t = new AffineTransform();
        t.scale(-1, 1);
        t.translate(-h, 0);
        t.translate(h, 0);
        t.rotate(3 * Math.PI / 2);
        t.translate(-w, 0);
        return t;
    }

    /** Scales so the long edge is at most {@code max}, never enlarging. */
    private BufferedImage resizeToFit(BufferedImage image, int max) {
        int w = image.getWidth();
        int h = image.getHeight();

        if (w <= max && h <= max) {
            // Still redrawn rather than returned as-is, so the output is always
            // a fresh TYPE_INT_RGB image built from pixels — which is what makes
            // the re-encode unconditional.
            return toRgb(image);
        }

        double scale = (double) max / Math.max(w, h);
        int targetW = Math.max(1, (int) Math.round(w * scale));
        int targetH = Math.max(1, (int) Math.round(h * scale));

        BufferedImage resized = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = resized.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING,
                RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(image, 0, 0, targetW, targetH, null);
        g.dispose();
        return resized;
    }

    /**
     * Flattens to RGB.
     *
     * <p>Also drops any alpha channel, which JPEG cannot represent — a PNG with
     * transparency written directly as JPEG otherwise comes out with black or
     * inverted regions.
     */
    private BufferedImage toRgb(BufferedImage image) {
        if (image.getType() == BufferedImage.TYPE_INT_RGB) {
            return image;
        }
        BufferedImage rgb = new BufferedImage(
                image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        g.drawImage(image, 0, 0, java.awt.Color.WHITE, null);
        g.dispose();
        return rgb;
    }

    /**
     * Writes a JPEG from pixel data.
     *
     * <p>This is the step that removes the metadata, and it is worth being
     * precise about why: there is no "strip EXIF" call anywhere in this class.
     * ImageIO is handed a BufferedImage — an array of pixels — and asked to
     * write a JPEG. It has no metadata to write because it was given none. The
     * stripping is a consequence of re-encoding, not an operation performed on
     * the original file.
     */
    private byte[] encodeJpeg(BufferedImage image) {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IllegalStateException("No JPEG writer available");
        }
        ImageWriter writer = writers.next();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(stream);

            ImageWriteParam params = writer.getDefaultWriteParam();
            if (params.canWriteCompressed()) {
                params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                params.setCompressionQuality(JPEG_QUALITY);
            }

            // null metadata: nothing is carried over from the source.
            writer.write(null, new IIOImage(image, null, null), params);
        } catch (IOException e) {
            throw new InvalidImageException("That image could not be processed");
        } finally {
            writer.dispose();
        }

        return out.toByteArray();
    }

    /** The upload was not a usable image. Message is safe to show a person. */
    public static class InvalidImageException extends RuntimeException {
        public InvalidImageException(String message) {
            super(message);
        }
    }
}
