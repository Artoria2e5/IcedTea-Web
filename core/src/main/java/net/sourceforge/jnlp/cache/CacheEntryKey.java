package net.sourceforge.jnlp.cache;

import net.adoptopenjdk.icedteaweb.Assert;
import net.adoptopenjdk.icedteaweb.jnlp.version.VersionId;

import java.net.URL;
import java.util.Objects;

/**
 * The key for a cache entry.
 *
 * An entry downloaded using the basic download protocol must be located in the cache based on the URL.
 * An entry downloaded with the version-based download protocol must be cached using the URL and the
 * (exact) version-id from the HTTP response as a key.
 *
 * @implSpec See <b>JSR-56, Section 6.5.1 & 6.5.2</b>
 * for a detailed specification of this class.
 */
public class CacheEntryKey {

    /**
     *  the remote resource location
     */
    private final URL url;

    /**
     * the version ID of the cache entry, may be {@code null}.
     */
    private final VersionId version;

    public CacheEntryKey(URL url, VersionId version) {
        this.url = Assert.requireNonNull(url, "location");
        this.version = version;
    }

    public URL getUrl() {
        return url;
    }

    public VersionId getVersion() {
        return version;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CacheEntryKey that = (CacheEntryKey) o;
        return url.equals(that.url) &&
                Objects.equals(version, that.version);
    }

    @Override
    public int hashCode() {
        return Objects.hash(url, version);
    }

    @Override
    public String toString() {
        final String versionString = version != null ? " - " + version : "";
        return getClass().getSimpleName() + "(" + url + versionString + ")";
    }
}
