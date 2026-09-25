package com.househunting.photo;

import com.househunting.auth.AuthenticatedUser;
import com.househunting.listing.wizard.ListingDraftService;
import com.househunting.user.AgeBracket;
import com.househunting.user.Gender;
import com.househunting.user.User;
import com.househunting.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Photo upload, serving and ordering through the HTTP layer.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class PhotoUploadTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository users;
    @Autowired private PhotoService photos;
    @Autowired private PhotoRepository photoRepository;
    @Autowired private ListingDraftService drafts;
    @Autowired private JdbcClient jdbc;

    private User owner;
    private User stranger;
    private UUID listingId;

    @BeforeEach
    void setUp() {
        owner = users.save(new User("Owner", Gender.FEMALE, AgeBracket.AGE_23_27));
        stranger = users.save(new User("Stranger", Gender.MALE, AgeBracket.AGE_28_32));
        listingId = drafts.currentDraft(owner.getId()).getId();
    }

    private static Authentication as(User user) {
        return UsernamePasswordAuthenticationToken.authenticated(
                new AuthenticatedUser(user.getId(), user.getFirstName(), user.isAdmin()),
                null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    private static byte[] jpeg(Color colour) throws Exception {
        BufferedImage image = new BufferedImage(200, 150, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(colour);
        g.fillRect(0, 0, 200, 150);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpeg", out);
        return out.toByteArray();
    }

    private MockMultipartFile file(Color colour) throws Exception {
        return new MockMultipartFile("file", "room.jpg", MediaType.IMAGE_JPEG_VALUE, jpeg(colour));
    }

    // ---- Uploading --------------------------------------------------------

    @Test
    @DisplayName("an upload is processed and recorded as servable")
    void uploadIsProcessed() throws Exception {
        mockMvc.perform(multipart("/list/" + listingId + "/photos")
                        .file(file(Color.BLUE))
                        .with(authentication(as(owner))).with(csrf()))
                .andExpect(status().is3xxRedirection());

        List<Photo> stored = photos.forListing(listingId);
        assertThat(stored).hasSize(1);

        Photo photo = stored.getFirst();
        assertThat(photo.getProcessedAt()).isNotNull();
        assertThat(photo.isCover()).isTrue();
        assertThat(photo.getObjectKey()).startsWith("listings/" + listingId + "/");
        // The key is generated, never taken from the uploaded filename, which
        // is attacker-controlled and ends up as a filesystem path.
        assertThat(photo.getObjectKey()).doesNotContain("room.jpg");
    }

    @Test
    @DisplayName("a non-image is rejected without discarding the rest of the batch")
    void invalidFileIsRejected() throws Exception {
        MockMultipartFile bad =
                new MockMultipartFile("file", "notes.txt", "text/plain", "not an image".getBytes());

        mockMvc.perform(multipart("/list/" + listingId + "/photos")
                        .file(file(Color.RED)).file(bad)
                        .with(authentication(as(owner))).with(csrf()))
                .andExpect(status().is3xxRedirection());

        // The good one survived; someone selecting eight photos should not lose
        // seven because the eighth was a screenshot of a PDF.
        assertThat(photos.forListing(listingId)).hasSize(1);
    }

    // ---- Ownership --------------------------------------------------------

    @Test
    @DisplayName("a stranger cannot add, delete or reorder photos on someone else's listing")
    void ownershipIsEnforced() throws Exception {
        photos.upload(listingId, jpeg(Color.GREEN), "a.jpg");
        UUID photoId = photos.forListing(listingId).getFirst().getId();

        mockMvc.perform(multipart("/list/" + listingId + "/photos")
                        .file(file(Color.BLACK))
                        .with(authentication(as(stranger))).with(csrf()))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/list/" + listingId + "/photos/" + photoId + "/delete")
                        .with(authentication(as(stranger))).with(csrf()))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/list/" + listingId + "/photos/" + photoId + "/cover")
                        .with(authentication(as(stranger))).with(csrf()))
                .andExpect(status().isNotFound());

        assertThat(photos.forListing(listingId)).hasSize(1);
    }

    // ---- Serving ----------------------------------------------------------

    @Test
    @DisplayName("photos are served publicly, with an immutable cache header")
    void photosAreServedPublicly() throws Exception {
        photos.upload(listingId, jpeg(Color.ORANGE), "a.jpg");
        UUID photoId = photos.forListing(listingId).getFirst().getId();

        // No authentication: a listing page shows these to anybody.
        mockMvc.perform(get("/photos/" + photoId))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().string("Cache-Control",
                                org.hamcrest.Matchers.containsString("immutable")));

        mockMvc.perform(get("/photos/" + photoId + "/thumb"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("an UNPROCESSED photo is never served")
    void unprocessedPhotoIsNotServed() throws Exception {
        photos.upload(listingId, jpeg(Color.PINK), "a.jpg");
        UUID photoId = photos.forListing(listingId).getFirst().getId();

        // Simulate a row that never completed processing — a crash mid-upload,
        // or a future code path that records before stripping.
        jdbc.sql("UPDATE photos SET processed_at = NULL WHERE id = ?").param(photoId).update();

        // 404 is the safe failure. An unprocessed image may still contain the
        // EXIF GPS that every other mechanism here works to keep private; a
        // missing photo is an inconvenience, a leaked address is not.
        mockMvc.perform(get("/photos/" + photoId)).andExpect(status().isNotFound());
        mockMvc.perform(get("/photos/" + photoId + "/thumb")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("an unknown photo id is a 404, not an error")
    void unknownPhotoIs404() throws Exception {
        mockMvc.perform(get("/photos/" + UUID.randomUUID())).andExpect(status().isNotFound());
    }

    // ---- Ordering ---------------------------------------------------------

    @Test
    @DisplayName("making a photo the cover reorders without violating the unique constraint")
    void makeCoverReorders() throws Exception {
        photos.upload(listingId, jpeg(Color.RED), "a.jpg");
        photos.upload(listingId, jpeg(Color.GREEN), "b.jpg");
        photos.upload(listingId, jpeg(Color.BLUE), "c.jpg");

        UUID third = photos.forListing(listingId).get(2).getId();

        // Without V8's DEFERRABLE constraint this fails: the first UPDATE
        // collides with a position another photo still holds.
        mockMvc.perform(post("/list/" + listingId + "/photos/" + third + "/cover")
                        .with(authentication(as(owner))).with(csrf()))
                .andExpect(status().is3xxRedirection());

        List<Photo> ordered = photos.forListing(listingId);
        assertThat(ordered.getFirst().getId()).isEqualTo(third);
        assertThat(ordered).extracting(Photo::getPosition).containsExactly(
                (short) 0, (short) 1, (short) 2);
    }

    @Test
    @DisplayName("deleting a photo closes the gap so there is always a cover")
    void deleteCompactsPositions() throws Exception {
        photos.upload(listingId, jpeg(Color.RED), "a.jpg");
        photos.upload(listingId, jpeg(Color.GREEN), "b.jpg");
        photos.upload(listingId, jpeg(Color.BLUE), "c.jpg");

        UUID cover = photos.forListing(listingId).getFirst().getId();

        mockMvc.perform(post("/list/" + listingId + "/photos/" + cover + "/delete")
                        .with(authentication(as(owner))).with(csrf()))
                .andExpect(status().is3xxRedirection());

        List<Photo> remaining = photos.forListing(listingId);
        assertThat(remaining).hasSize(2);
        // Deleting position 0 must promote the next photo, not leave the
        // listing with no cover at all.
        assertThat(remaining).extracting(Photo::getPosition).containsExactly((short) 0, (short) 1);
        assertThat(photoRepository.findById(cover)).isEmpty();
    }
}
