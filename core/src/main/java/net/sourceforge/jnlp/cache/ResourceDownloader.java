package net.sourceforge.jnlp.cache;

import net.adoptopenjdk.icedteaweb.IcedTeaWebConstants;
import net.adoptopenjdk.icedteaweb.commandline.CommandLineOptions;
import net.adoptopenjdk.icedteaweb.http.CloseableConnection;
import net.adoptopenjdk.icedteaweb.http.ConnectionFactory;
import net.adoptopenjdk.icedteaweb.http.HttpMethod;
import net.adoptopenjdk.icedteaweb.io.IOUtils;
import net.adoptopenjdk.icedteaweb.logging.Logger;
import net.adoptopenjdk.icedteaweb.logging.LoggerFactory;
import net.sourceforge.jnlp.runtime.Boot;
import net.sourceforge.jnlp.runtime.JNLPRuntime;
import net.sourceforge.jnlp.util.UrlUtils;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;

import static net.sourceforge.jnlp.cache.Resource.Status.CONNECTED;
import static net.sourceforge.jnlp.cache.Resource.Status.CONNECTING;
import static net.sourceforge.jnlp.cache.Resource.Status.DOWNLOADED;
import static net.sourceforge.jnlp.cache.Resource.Status.DOWNLOADING;
import static net.sourceforge.jnlp.cache.Resource.Status.ERROR;
import static net.sourceforge.jnlp.cache.Resource.Status.PRECONNECT;
import static net.sourceforge.jnlp.cache.Resource.Status.PREDOWNLOAD;
import static net.sourceforge.jnlp.cache.Resource.Status.PROCESSING;

class ResourceDownloader implements Runnable {

    private static final Logger LOG = LoggerFactory.getLogger(ResourceDownloader.class);

    private static final String INVALID_HTTP_RESPONSE = "Invalid Http response";

    private final Resource resource;
    private final Object lock;

    ResourceDownloader(Resource resource, Object lock) {
        this.resource = resource;
        this.lock = lock;
    }

    /**
     * Sets the resource status to connect and download, and
     * enqueues the resource if not already started.
     *
     * @throws IllegalResourceDescriptorException if the resource is not being tracked
     */
    static void startDownload(Resource resource, final Object lock) {
        final boolean isProcessing;

        synchronized (resource) {
            if (resource.isComplete()) {
                return;
            }

            isProcessing = resource.isSet(PROCESSING);

            if (!resource.isSet(CONNECTED) && !resource.isSet(CONNECTING)) {
                resource.changeStatus(EnumSet.noneOf(Resource.Status.class), EnumSet.of(PRECONNECT, PROCESSING));
            }
            if (!resource.isSet(DOWNLOADED) && !resource.isSet(DOWNLOADING)) {
                resource.changeStatus(EnumSet.noneOf(Resource.Status.class), EnumSet.of(PREDOWNLOAD, PROCESSING));
            }

            if (!resource.isSet(PREDOWNLOAD) && !resource.isSet(PRECONNECT)) {
                return;
            }
        }

        if (!isProcessing) {
            CachedDaemonThreadPoolProvider.DAEMON_THREAD_POOL.execute(new ResourceDownloader(resource, lock));
        }
    }

    @Override
    public void run() {
        try {
            if (resource.isSet(PRECONNECT) && !resource.hasAllFlags(EnumSet.of(ERROR, CONNECTING, CONNECTED))) {
                resource.changeStatus(EnumSet.noneOf(Resource.Status.class), EnumSet.of(CONNECTING));
                initializeResource();
            }
            if (resource.isSet(PREDOWNLOAD) && !resource.hasAllFlags(EnumSet.of(ERROR, DOWNLOADING, DOWNLOADED))) {
                resource.changeStatus(EnumSet.noneOf(Resource.Status.class), EnumSet.of(DOWNLOADING));
                downloadResource();
            }
        }
        finally {
            synchronized (lock) {
                lock.notifyAll(); // wake up wait's to check for completion
            }
        }
    }

    private void initializeResource() {
        try {
            if (!JNLPRuntime.isOfflineForced() && resource.isConnectable()) {
                final UrlRequestResult location = ResourceUrlCreator.findBestUrl(resource);
                if (location != null) {
                    initializeFromURL(location);
                    return;
                }
            }
            initializeFromCache();
        } catch (Exception e) {
            LOG.error("Error while initializing resource from location " + resource.getLocation(), e);
            resource.changeStatus(EnumSet.noneOf(Resource.Status.class), EnumSet.of(ERROR));
        }
    }

    private void initializeFromURL(final UrlRequestResult location) {
        CacheEntry entry = new CacheEntry(resource.getLocation(), resource.getRequestVersion());
        entry.lock();
        try {
            resource.setDownloadLocation(location.getLocation());

            File localFile = CacheUtil.getCacheFile(resource.getLocation(), resource.getDownloadVersion());
            long size = location.getContentLength();
            long lm = location.getLastModified();
            boolean current = CacheUtil.isCurrent(resource.getLocation(), resource.getRequestVersion(), lm) && resource.getUpdatePolicy() != UpdatePolicy.FORCE;
            if (!current) {
                if (entry.isCached()) {
                    entry.markForDelete();
                    entry.store();
                    // Old entry will still exist. (but removed at cleanup)
                    localFile = CacheUtil.makeNewCacheFile(resource.getLocation(), resource.getDownloadVersion());
                    CacheEntry newEntry = new CacheEntry(resource.getLocation(), resource.getRequestVersion());
                    newEntry.lock();
                    entry.unlock();
                    entry = newEntry;
                }
            }

            synchronized (resource) {
                resource.setLocalFile(localFile);
                // resource.connection = connection;
                resource.setSize(size);
                resource.changeStatus(EnumSet.of(PRECONNECT, CONNECTING), EnumSet.of(CONNECTED, PREDOWNLOAD));

                // check if up-to-date; if so set as downloaded
                if (current) {
                    resource.changeStatus(EnumSet.of(PREDOWNLOAD, DOWNLOADING), EnumSet.of(DOWNLOADED));
                }
            }

            // update cache entry
            if (!current) {
                entry.setRemoteContentLength(size);
                entry.setLastModified(lm);
            }
            entry.setLastUpdated(System.currentTimeMillis());
            try {
                //do not die here no matter of cost. Just metadata
                //is the path from user best to store? He can run some jnlp from temp which then be stored
                //on contrary, this downloads the jnlp, we actually do not have jnlp parsed during first interaction
                //in addition, downloaded name can be really nasty (some generated has from dynamic servlet.jnlp)
                //another issue is forking. If this (eg local) jnlp starts its second instance, the url *can* be different
                //in contrary, usually si no. as fork is reusing all args, and only adding xmx/xms and xnofork.
                String jnlpPath = Boot.getOptionParser().getMainArg(); //get jnlp from args passed
                if (jnlpPath == null || jnlpPath.equals("")) {
                    jnlpPath = Boot.getOptionParser().getParam(CommandLineOptions.JNLP);
                    if (jnlpPath == null || jnlpPath.equals("")) {
                        LOG.info("Not-setting jnlp-path for missing main/jnlp argument");
                    } else {
                        entry.setJnlpPath(jnlpPath);
                    }
                } else {
                    entry.setJnlpPath(jnlpPath);
                }
            } catch (Exception ex) {
                LOG.error(IcedTeaWebConstants.DEFAULT_ERROR_MESSAGE, ex);
            }
            entry.store();
        } finally {
            entry.unlock();
        }
    }

    private void initializeFromCache() {
        final CacheEntry entry = new CacheEntry(resource.getLocation(), resource.getRequestVersion());
        entry.lock();

        try {
            final File localFile = CacheUtil.getCacheFile(resource.getLocation(), resource.getDownloadVersion());

            if (localFile != null && localFile.exists()) {
                long size = localFile.length();

                synchronized (resource) {
                    resource.setLocalFile(localFile);
                    resource.setSize(size);
                    resource.changeStatus(EnumSet.of(PREDOWNLOAD, DOWNLOADING), EnumSet.of(DOWNLOADED));
                }
            } else {
                LOG.warn("You are trying to get resource {} but it is not in cache and could not be downloaded. Attempting to continue, but you may expect failure", resource.getLocation().toExternalForm());
                resource.changeStatus(EnumSet.noneOf(Resource.Status.class), EnumSet.of(ERROR));
            }
        } finally {
            entry.unlock();
        }

    }

    private void downloadResource() {
        final URL downloadFrom = resource.getDownloadLocation(); //Where to download from
        final URL downloadLocation = resource.getLocation(); //Where to download to

        try (final CloseableConnection connection = getDownloadConnection(downloadFrom)) {
            final String contentEncoding = connection.getContentEncoding();
            LOG.debug("Downloading {} from URL {} (encoding : {})", downloadLocation, downloadFrom, contentEncoding);

            final StreamUnpacker unpacker = getStreamUnpacker(downloadFrom, contentEncoding);

            downloadFile(connection, downloadLocation, unpacker);
            resource.changeStatus(EnumSet.of(DOWNLOADING), EnumSet.of(DOWNLOADED));
        } catch (Exception ex) {
            LOG.error(IcedTeaWebConstants.DEFAULT_ERROR_MESSAGE, ex);
            resource.changeStatus(EnumSet.noneOf(Resource.Status.class), EnumSet.of(ERROR));
        }
    }

    private static CloseableConnection getDownloadConnection(URL location) throws IOException {
        final Map<String, String> requestProperties = new HashMap<>();
        requestProperties.put(ResourceUrlCreator.ACCEPT_ENCODING, ResourceUrlCreator.PACK_200_OR_GZIP);
        return ConnectionFactory.openConnection(location, HttpMethod.GET, requestProperties);
    }

    private StreamUnpacker getStreamUnpacker(URL downloadFrom, String contentEncoding) {
        boolean packgz = "pack200-gzip".equals(contentEncoding) || downloadFrom.getPath().endsWith(".pack.gz");
        boolean gzip = "gzip".equals(contentEncoding);

        // It's important to check packgz first. If a stream is both
        // pack200 and gz encoded, then con.getContentEncoding() could
        // return ".gz", so if we check gzip first, we would end up
        // treating a pack200 file as a jar file.
        if (packgz) {
            return new PackGzipUnpacker();
        } else if (gzip) {
            return new GzipUnpacker();
        }

        return new NotUnpacker();
    }

    private void downloadFile(CloseableConnection connection, URL downloadLocation, StreamUnpacker unpacker) throws IOException {
        CacheEntry downloadEntry = new CacheEntry(downloadLocation, resource.getDownloadVersion());
        LOG.debug("Downloading file: {} into: {}", downloadLocation, downloadEntry.getCacheFile().getCanonicalPath());
        if (!downloadEntry.isCurrent(connection.getLastModified())) {
            try {
                final InputStream packedStream = connection.getInputStream();
                final InputStream unpackedStream = unpacker.unpack(packedStream);
                writeDownloadToFile(downloadLocation, unpackedStream);
            } catch (IOException ex) {
                if (INVALID_HTTP_RESPONSE.equals(ex.getMessage())) {
                    LOG.error(INVALID_HTTP_RESPONSE + " message detected. Attempting direct socket", ex);
                    final InputStream packedStream = getInputStreamFromDirectSocket(connection.getURL(), downloadLocation);
                    final InputStream unpackedStream = unpacker.unpack(packedStream);
                    writeDownloadToFile(downloadLocation, unpackedStream);
                } else {
                    throw ex;
                }
            }
        } else {
            resource.setTransferred(CacheUtil.getCacheFile(downloadLocation, resource.getDownloadVersion()).length());
        }

        downloadEntry.storeEntryFields(connection.getContentLength(), connection.getLastModified());
    }

    private void writeDownloadToFile(final URL downloadLocation, final InputStream in) throws IOException {
        try (final OutputStream out = CacheUtil.getOutputStream(downloadLocation, resource.getDownloadVersion())) {
            IOUtils.copy(in, out);
        }
    }

    private InputStream getInputStreamFromDirectSocket(URL url, URL downloadLocation) throws IOException {
        final Object[] result = UrlUtils.loadUrlWithInvalidHeaderBytes(url);
        final String head = (String) result[0];
        final byte[] body = (byte[]) result[1];
        LOG.info("Header of: {} ({})", url, downloadLocation);
        LOG.info(head);
        LOG.info("Body is: {} bytes long", body.length);
        return new ByteArrayInputStream(body);
    }
}
