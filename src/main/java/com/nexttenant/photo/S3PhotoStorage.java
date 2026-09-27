package com.nexttenant.photo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.util.Optional;

/**
 * Photos in object storage.
 *
 * <p><b>Not Amazon-specific despite the name.</b> Cloudflare R2, DigitalOcean
 * Spaces, Backblaze B2 and MinIO all implement the S3 API, so this one class
 * covers every realistic option; changing provider is an endpoint and a pair
 * of keys. The SDK is Amazon's only because it is the best-maintained client
 * for that API.
 *
 * <p><b>Why any of this rather than the local disk.</b>
 * {@link LocalPhotoStorage} writes next to the running process, which on a
 * container platform means the photos disappear on the next deploy, and on a
 * single server means they are not in any backup and cannot be served by a
 * second instance. Losing a listing's photos is losing the listing: nobody
 * takes over a room they cannot see.
 *
 * <p><b>The bucket stays private.</b> Bytes are fetched here and served
 * through {@code PhotoController}, which is what keeps EXIF-stripping,
 * moderation and takedown meaningful — a public bucket URL is a permanent
 * link to an image nobody can retract. The cost is that serving a photo is a
 * round trip; if that ever matters, the answer is a CDN in front of this
 * application, not a public bucket behind it.
 */
public class S3PhotoStorage implements PhotoStorage {

    private static final Logger log = LoggerFactory.getLogger(S3PhotoStorage.class);

    private final S3Client s3;
    private final String bucket;

    public S3PhotoStorage(S3Client s3, String bucket) {
        this.s3 = s3;
        this.bucket = bucket;
        log.info("Photos stored in object storage bucket {}", bucket);
    }

    @Override
    public void put(String key, byte[] bytes) {
        s3.putObject(
                PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        // Set explicitly rather than left to the provider to
                        // guess. A JPEG served as application/octet-stream
                        // downloads instead of rendering, which looks exactly
                        // like a broken image.
                        .contentType(contentTypeOf(key))
                        .build(),
                RequestBody.fromBytes(bytes));
    }

    @Override
    public Optional<byte[]> get(String key) {
        try {
            ResponseBytes<GetObjectResponse> object = s3.getObjectAsBytes(
                    GetObjectRequest.builder().bucket(bucket).key(key).build());
            return Optional.of(object.asByteArray());
        } catch (NoSuchKeyException e) {
            // A missing photo is a 404, not a 500. Rows and objects can drift
            // apart — a failed upload, a restored database — and the page
            // should show a placeholder rather than an error page.
            return Optional.empty();
        }
    }

    @Override
    public void delete(String key) {
        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    }

    @Override
    public boolean exists(String key) {
        try {
            s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            // Some S3-compatible providers answer a HEAD for a missing key
            // with a bare 404 rather than the typed NoSuchKeyException, so the
            // status is checked as well. Anything else is a real failure and
            // is allowed to propagate.
            if (e.statusCode() == 404) {
                return false;
            }
            throw e;
        }
    }

    /**
     * Derived from the key, which we generate, so the extension is trusted.
     *
     * <p>Deliberately not taken from the upload's declared type: that is
     * attacker-controlled, and {@code ImageProcessor} has already re-encoded
     * the bytes by this point anyway.
     */
    private static String contentTypeOf(String key) {
        String lower = key.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        return "image/jpeg";
    }
}
