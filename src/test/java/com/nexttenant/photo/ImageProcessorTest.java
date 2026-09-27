package com.nexttenant.photo;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Directory;
import com.drew.metadata.Metadata;
import com.drew.metadata.Tag;
import com.drew.metadata.exif.GpsDirectory;
import org.apache.commons.imaging.formats.jpeg.exif.ExifRewriter;
import org.apache.commons.imaging.formats.tiff.constants.ExifTagConstants;
import org.apache.commons.imaging.formats.tiff.constants.TiffTagConstants;
import org.apache.commons.imaging.formats.tiff.write.TiffOutputDirectory;
import org.apache.commons.imaging.formats.tiff.write.TiffOutputSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The most security-relevant test in the project.
 *
 * <p>A photo taken on a phone carries the exact coordinates inside the file.
 * If they survive publication, the location jitter, the table split and the
 * DTO boundaries are all decorative — anyone can open the image and read the
 * address off it.
 *
 * <p>Note the shape of these tests: each one first builds a photo that
 * genuinely carries GPS, asserts the GPS is there, and only then processes it.
 * Asserting that an output has no GPS proves nothing unless the input did.
 */
class ImageProcessorTest {

    private static final double FLAT_LAT = 19.0596;   // Bandra West
    private static final double FLAT_LNG = 72.8295;

    private final ImageProcessor processor = new ImageProcessor();

    // ---- Fixtures ---------------------------------------------------------

    /** A plain JPEG with no metadata at all. */
    private static byte[] plainJpeg(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(122, 52, 32));
        g.fillRect(0, 0, width, height);
        // A distinguishable corner, so rotation can be checked by pixel.
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, Math.max(1, width / 4), Math.max(1, height / 4));
        g.dispose();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpeg", out);
        return out.toByteArray();
    }

    /** The same JPEG, with GPS coordinates written into its EXIF. */
    private static byte[] jpegWithGps(double latitude, double longitude) throws Exception {
        byte[] plain = plainJpeg(400, 300);

        TiffOutputSet outputSet = new TiffOutputSet();
        outputSet.setGpsInDegrees(longitude, latitude);

        TiffOutputDirectory exif = outputSet.getOrCreateExifDirectory();
        exif.add(ExifTagConstants.EXIF_TAG_USER_COMMENT, "taken on a phone");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new ExifRewriter().updateExifMetadataLossless(plain, out, outputSet);
        return out.toByteArray();
    }

    /** A JPEG claiming a given EXIF orientation. */
    private static byte[] jpegWithOrientation(int orientation, int w, int h) throws Exception {
        byte[] plain = plainJpeg(w, h);

        TiffOutputSet outputSet = new TiffOutputSet();
        TiffOutputDirectory root = outputSet.getOrCreateRootDirectory();
        // Orientation is a TIFF tag that EXIF inherits, so it lives in
        // TiffTagConstants rather than ExifTagConstants.
        root.add(TiffTagConstants.TIFF_TAG_ORIENTATION, (short) orientation);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new ExifRewriter().updateExifMetadataLossless(plain, out, outputSet);
        return out.toByteArray();
    }

    private static List<String> allTagsIn(byte[] jpeg) throws Exception {
        Metadata metadata = ImageMetadataReader.readMetadata(new ByteArrayInputStream(jpeg));
        List<String> tags = new ArrayList<>();
        for (Directory directory : metadata.getDirectories()) {
            for (Tag tag : directory.getTags()) {
                tags.add(directory.getName() + " / " + tag.getTagName() + " = " + tag.getDescription());
            }
        }
        return tags;
    }

    private static boolean hasGps(byte[] jpeg) throws Exception {
        Metadata metadata = ImageMetadataReader.readMetadata(new ByteArrayInputStream(jpeg));
        GpsDirectory gps = metadata.getFirstDirectoryOfType(GpsDirectory.class);
        return gps != null && gps.getGeoLocation() != null;
    }

    // ---- The headline test ------------------------------------------------

    @Test
    @DisplayName("GPS coordinates are removed from a photo that genuinely had them")
    void stripsGpsCoordinates() throws Exception {
        byte[] original = jpegWithGps(FLAT_LAT, FLAT_LNG);

        // First prove the fixture is real. Without this the test could pass
        // against an input that never carried GPS at all.
        assertThat(hasGps(original))
                .as("fixture must actually contain GPS or this test proves nothing")
                .isTrue();

        Metadata before = ImageMetadataReader.readMetadata(new ByteArrayInputStream(original));
        var gpsBefore = before.getFirstDirectoryOfType(GpsDirectory.class).getGeoLocation();
        assertThat(gpsBefore.getLatitude()).isCloseTo(FLAT_LAT, org.assertj.core.data.Offset.offset(0.001));

        ImageProcessor.Processed processed = processor.process(original);

        assertThat(hasGps(processed.full())).isFalse();
        assertThat(hasGps(processed.thumbnail())).isFalse();
    }

    @Test
    @DisplayName("no EXIF directory of any kind survives, not only GPS")
    void stripsAllMetadata() throws Exception {
        byte[] original = jpegWithGps(FLAT_LAT, FLAT_LNG);
        assertThat(allTagsIn(original)).anyMatch(t -> t.contains("GPS"));

        ImageProcessor.Processed processed = processor.process(original);

        // Device model, serial numbers, capture timestamps and software
        // versions are all as unwelcome as coordinates. Nothing is read across,
        // so nothing survives.
        List<String> after = allTagsIn(processed.full());
        assertThat(after).noneMatch(t -> t.contains("GPS"));
        assertThat(after).noneMatch(t -> t.contains("Exif IFD0"));
        assertThat(after).noneMatch(t -> t.contains("User Comment"));
    }

    @Test
    @DisplayName("the coordinates do not survive as raw bytes anywhere in the file")
    void coordinatesAreNotHidingInTheBytes() throws Exception {
        byte[] processed = processor.process(jpegWithGps(FLAT_LAT, FLAT_LNG)).full();

        // A blunter check than parsing metadata: scan the whole file for the
        // coordinate rendered as text, in case anything smuggled it into a
        // comment segment or a thumbnail.
        String asText = new String(processed, java.nio.charset.StandardCharsets.ISO_8859_1);
        assertThat(asText).doesNotContain("19.0596");
        assertThat(asText).doesNotContain("72.8295");
        assertThat(asText).doesNotContain("taken on a phone");
    }

    // ---- Orientation ------------------------------------------------------

    @Test
    @DisplayName("a rotated photo has its PIXELS rotated, not just its tag removed")
    void appliesOrientationBeforeStripping() throws Exception {
        // Orientation 6 means "rotate 90° clockwise when displaying", which is
        // what a phone records when held upright. Stripping the tag without
        // acting on it leaves every portrait photo sideways — a bug that looks
        // like sloppiness and is actually the privacy fix misapplied.
        byte[] landscapeBytes = jpegWithOrientation(6, 400, 200);

        ImageProcessor.Processed processed = processor.process(landscapeBytes);

        // 400x200 rotated 90° becomes taller than it is wide.
        assertThat(processed.height()).isGreaterThan(processed.width());
    }

    @Test
    @DisplayName("an upright photo is left the right way up")
    void orientationOneIsUnchanged() throws Exception {
        ImageProcessor.Processed processed = processor.process(jpegWithOrientation(1, 400, 200));

        assertThat(processed.width()).isGreaterThan(processed.height());
    }

    @Test
    @DisplayName("every orientation value is handled without error")
    void allOrientationsAreHandled() throws Exception {
        for (int orientation = 1; orientation <= 8; orientation++) {
            byte[] bytes = jpegWithOrientation(orientation, 400, 200);
            ImageProcessor.Processed processed = processor.process(bytes);

            assertThat(processed.full()).isNotEmpty();
            assertThat(hasGps(processed.full())).isFalse();
        }
    }

    // ---- Resizing ---------------------------------------------------------

    @Test
    @DisplayName("large photos are scaled down to the published maximum")
    void resizesLargePhotos() throws Exception {
        ImageProcessor.Processed processed = processor.process(plainJpeg(4000, 3000));

        assertThat(Math.max(processed.width(), processed.height()))
                .isEqualTo(ImageProcessor.MAX_DIMENSION);
        // Aspect ratio preserved: 4:3 in, 4:3 out.
        assertThat((double) processed.width() / processed.height())
                .isCloseTo(4.0 / 3.0, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    @DisplayName("small photos are not enlarged")
    void doesNotUpscale() throws Exception {
        ImageProcessor.Processed processed = processor.process(plainJpeg(300, 200));

        assertThat(processed.width()).isEqualTo(300);
        assertThat(processed.height()).isEqualTo(200);
    }

    @Test
    @DisplayName("a thumbnail is produced alongside the full image")
    void producesThumbnail() throws Exception {
        ImageProcessor.Processed processed = processor.process(plainJpeg(4000, 3000));

        assertThat(processed.thumbnail()).isNotEmpty();
        // Smaller in bytes, which is the point: a search page shows twenty of
        // these and should not download twenty full-size photos.
        assertThat(processed.thumbnail().length).isLessThan(processed.full().length);
    }

    // ---- Rejections -------------------------------------------------------

    @Test
    @DisplayName("non-images are rejected with a message safe to show")
    void rejectsNonImages() {
        assertThatThrownBy(() -> processor.process("this is not an image".getBytes()))
                .isInstanceOf(ImageProcessor.InvalidImageException.class)
                .hasMessageContaining("image");

        assertThatThrownBy(() -> processor.process(new byte[0]))
                .isInstanceOf(ImageProcessor.InvalidImageException.class);

        assertThatThrownBy(() -> processor.process(null))
                .isInstanceOf(ImageProcessor.InvalidImageException.class);
    }

    @Test
    @DisplayName("a PNG with transparency converts without black artefacts")
    void handlesTransparency() throws Exception {
        BufferedImage png = new BufferedImage(200, 200, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = png.createGraphics();
        g.setColor(new Color(122, 52, 32, 128));
        g.fillRect(0, 0, 200, 200);
        g.dispose();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(png, "png", out);

        // JPEG cannot represent alpha. Written directly, a transparent PNG
        // comes out with black or inverted regions; flattening onto white first
        // is what avoids it.
        ImageProcessor.Processed processed = processor.process(out.toByteArray());
        assertThat(processed.full()).isNotEmpty();

        BufferedImage result = ImageIO.read(new ByteArrayInputStream(processed.full()));
        Color corner = new Color(result.getRGB(5, 5));
        assertThat(corner.getRed()).isGreaterThan(100);   // not blacked out
    }
}
