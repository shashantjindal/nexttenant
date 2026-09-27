package com.nexttenant.photo;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.net.URI;

/**
 * Builds the object-storage client, when object storage is what is configured.
 *
 * <p>Nothing here is created under the default {@code local} setting, so a
 * fresh clone still needs no account anywhere.
 */
@Configuration
@ConditionalOnProperty(name = "nexttenant.photos.storage", havingValue = "s3")
public class PhotoStorageConfig {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(PhotoStorageConfig.class);

    /**
     * An S3 client pointed at whichever provider is configured.
     *
     * @param endpoint blank for Amazon S3 itself; set for R2, Spaces or B2
     * @param region   Amazon's region name, or "auto" for providers that have
     *                 no regions but still require the field to be present
     */
    @Bean
    public S3Client s3Client(
            @org.springframework.beans.factory.annotation.Value("${nexttenant.photos.s3.endpoint:}")
            String endpoint,
            @org.springframework.beans.factory.annotation.Value("${nexttenant.photos.s3.region:ap-south-1}")
            String region,
            @org.springframework.beans.factory.annotation.Value("${nexttenant.photos.s3.access-key:}")
            String accessKey,
            @org.springframework.beans.factory.annotation.Value("${nexttenant.photos.s3.secret-key:}")
            String secretKey) {

        var builder = S3Client.builder().region(Region.of(region));

        if (accessKey.isBlank()) {
            // No keys configured: fall back to the SDK's default chain, which
            // on EC2 or ECS picks up the instance's own IAM role.
            //
            // This is the better way to run on AWS, and the reason the keys are
            // optional rather than required. A long-lived access key on a
            // server is a credential that exists in a file, gets copied into a
            // backup, and is still valid a year after the person who created it
            // forgot about it. A role hands the instance short-lived
            // credentials that rotate on their own and cannot leave the box.
            //
            // Every non-Amazon provider — R2, Spaces, B2 — has no such
            // mechanism, so they set the keys and take this branch's absence.
            log.info("No S3 keys configured; using the default AWS credentials chain");
        } else {
            builder.credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(accessKey, secretKey)));
        }

        if (!endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint));

            // Path-style addressing (host/bucket/key) rather than virtual-host
            // style (bucket.host/key). Amazon has deprecated it; almost every
            // S3-COMPATIBLE provider still needs it, because bucket-as-subdomain
            // requires wildcard DNS and a matching TLS certificate that only
            // Amazon reliably has. Getting this wrong produces DNS failures
            // that look nothing like a storage misconfiguration.
            builder.serviceConfiguration(
                    S3Configuration.builder().pathStyleAccessEnabled(true).build());
        }

        return builder.build();
    }

    @Bean
    public PhotoStorage s3PhotoStorage(
            S3Client s3,
            @org.springframework.beans.factory.annotation.Value("${nexttenant.photos.s3.bucket}")
            String bucket) {
        return new S3PhotoStorage(s3, bucket);
    }
}
