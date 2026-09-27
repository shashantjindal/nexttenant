package com.nexttenant.photo;

import java.util.Optional;

/**
 * Where processed photos live.
 *
 * <p>An interface with three methods, so the storage backend is a detail the
 * rest of the application never sees. Today it is the local filesystem;
 * in production it will be Cloudflare R2 or S3, and only the implementation
 * changes.
 *
 * <p>Images never go in the database. Bytes in Postgres bloat every backup,
 * bypass CDN caching, and make the database the bottleneck for serving a purely
 * static asset. The {@code photos} table holds the key and the metadata; the
 * bytes live here.
 */
public interface PhotoStorage {

    void put(String key, byte[] bytes);

    Optional<byte[]> get(String key);

    void delete(String key);

    boolean exists(String key);
}
