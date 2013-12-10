package org.cobbzilla.s3s3mirror;

import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.*;
import lombok.extern.slf4j.Slf4j;

import java.util.Date;

/**
 * Handles a single key. Determines if it should be copied, and if so, performs the copy operation.
 */
@Slf4j
public class KeyJob implements Runnable {

    private final AmazonS3Client client;
    private final MirrorContext context;
    private final S3ObjectSummary summary;
    private final Object notifyLock;

    public KeyJob(AmazonS3Client client, MirrorContext context, S3ObjectSummary summary, Object notifyLock) {
        this.client = client;
        this.context = context;
        this.summary = summary;
        this.notifyLock = notifyLock;
    }

    @Override public String toString() { return summary.getKey(); }

    @Override
    public void run() {
        final MirrorOptions options = context.getOptions();
        final MirrorStats stats = context.getStats();
        final boolean verbose = options.isVerbose();
        final int maxRetries = options.getMaxRetries();
        final String key = summary.getKey();
        try {
            if (!shouldTransfer()) return;

            String keydest = key;
            if (options.hasDestPrefix()) {
                keydest = key.substring(options.getPrefix().length());
                keydest = options.getDestPrefix() + keydest;
            }
            final CopyObjectRequest request = new CopyObjectRequest(options.getSourceBucket(), key, options.getDestinationBucket(), keydest);

            final ObjectMetadata sourceMetadata = getObjectMetadata(options.getSourceBucket(), key, options);
            request.setNewObjectMetadata(sourceMetadata);

            final AccessControlList objectAcl = getAccessControlList(options, key);
            request.setAccessControlList(objectAcl);

            if (options.isDryRun()) {
                log.info("Would have copied "+ key +" to destination: "+keydest);
            } else {
                boolean copiedOK = false;
                for (int tries=0; tries<maxRetries; tries++) {
                    if (verbose) log.info("copying (try #"+tries+"): "+key+" to: "+keydest);
                    try {
                        client.copyObject(request);
                        stats.s3copyCount.incrementAndGet();
                        stats.bytesCopied.addAndGet(sourceMetadata.getContentLength());
                        copiedOK = true;
                        if (verbose) log.info("successfully copied (on try #"+tries+"): "+key+" to: "+keydest);
                        break;

                    } catch (AmazonS3Exception s3e) {
                        log.error("s3 exception copying (try #"+tries+") "+key+" to: "+keydest+": "+s3e);

                    } catch (Exception e) {
                        log.error("unexpected exception copying (try #"+tries+") "+key+" to: "+keydest+": "+e);
                    }
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        log.error("interrupted while waiting to retry key: "+key);
                        break;
                    }
                }
                if (copiedOK) {
                    context.getStats().objectsCopied.incrementAndGet();
                } else {
                    context.getStats().copyErrors.incrementAndGet();
                }
            }

        } catch (Exception e) {
            log.error("error copying key: "+key+": "+e);

        } finally {
            synchronized (notifyLock) {
                notifyLock.notifyAll();
            }
            if (verbose) log.info("done with "+key);
        }
    }

    private ObjectMetadata getObjectMetadata(String bucket, String key, MirrorOptions options) throws Exception {
        Exception ex = null;
        for (int tries=0; tries<options.getMaxRetries(); tries++) {
            try {
                final ObjectMetadata objectMetadata = client.getObjectMetadata(bucket, key);
                context.getStats().s3getCount.incrementAndGet();
                return objectMetadata;

            } catch (AmazonS3Exception e) {
                if (e.getStatusCode() == 404) throw e;

            } catch (Exception e) {
                ex = e;
                if (options.isVerbose()) {
                    if (tries >= options.getMaxRetries()) {
                        log.error("getObjectMetadata(" + key + ") failed (try #" + tries + "), giving up");
                        break;
                    } else {
                        log.warn("getObjectMetadata("+key+") failed (try #"+tries+"), retrying...");
                    }
                }
            }
        }
        throw ex;
    }

    private AccessControlList getAccessControlList(MirrorOptions options, String key) throws Exception {
        Exception ex = null;
        for (int tries=0; tries<options.getMaxRetries(); tries++) {
            try {
                final AccessControlList objectAcl = client.getObjectAcl(options.getSourceBucket(), key);
                context.getStats().s3getCount.incrementAndGet();
                return objectAcl;

            } catch (Exception e) {
                ex = e;
                if (options.isVerbose()) {
                    if (tries >= options.getMaxRetries()) {
                        log.error("getObjectAcl(" + key + ") failed (try #" + tries + "), giving up");
                        break;
                    } else {
                        log.warn("getObjectAcl("+key+") failed (try #"+tries+"), retrying...");
                    }
                }
            }
        }
        throw ex;
    }

    private boolean shouldTransfer() {

        final MirrorOptions options = context.getOptions();

        final String key = summary.getKey();
        final boolean verbose = options.isVerbose();
        String keydest = key;
        if (options.hasDestPrefix()) {
            keydest = key.substring(options.getPrefix().length());
            keydest = options.getDestPrefix() + keydest;
        }

        if (options.hasCtime()) {
            final Date lastModified = summary.getLastModified();
            if (lastModified == null) {
                if (verbose) log.info("No Last-Modified header for key: " + key);

            } else {
                if (lastModified.getTime() < options.getMaxAge()) {
                    if (verbose) log.info("key "+keydest+" (lastmod="+lastModified+") is older than "+options.getCtime()+" days (cutoff="+options.getMaxAgeDate()+"), not copying");
                    return false;
                }
            }
        }

        final KeyFingerprint sourceFingerprint = new KeyFingerprint(summary.getSize(), summary.getETag());

        final ObjectMetadata metadata;
        try {
            metadata = getObjectMetadata(options.getDestinationBucket(), keydest, options);
        } catch (AmazonS3Exception e) {
            if (e.getStatusCode() == 404) {
                if (verbose) log.info("Key not found in destination bucket (will copy): "+ keydest);
                return true;
            } else {
                log.warn("Error getting metadata for " + options.getDestinationBucket() + "/" + keydest + " (not copying): " + e);
                return false;
            }
        } catch (Exception e) {
            log.warn("Error getting metadata for " + options.getDestinationBucket() + "/" + keydest + " (not copying): " + e);
            return false;
        }

        final KeyFingerprint destFingerprint = new KeyFingerprint(metadata.getContentLength(), metadata.getETag());

        final boolean objectChanged = !sourceFingerprint.equals(destFingerprint);
        if (verbose && !objectChanged) log.info("Destination file is same as source, not copying: "+ key);

        return objectChanged;
    }
}
