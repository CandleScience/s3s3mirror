package org.cobbzilla.s3s3mirror;

import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.AccessControlList;
import com.amazonaws.services.s3.model.CannedAccessControlList;
import com.amazonaws.services.s3.model.AmazonS3Exception;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import org.slf4j.Logger;

public abstract class KeyJob implements Runnable {

    protected final AmazonS3Client client;
    protected final MirrorContext context;
    protected final S3ObjectSummary summary;
    protected final Object notifyLock;

    public KeyJob(AmazonS3Client client, MirrorContext context, S3ObjectSummary summary, Object notifyLock) {
        this.client = client;
        this.context = context;
        this.summary = summary;
        this.notifyLock = notifyLock;
    }

    public abstract Logger getLog();

    @Override public String toString() { return summary.getKey(); }

    protected ObjectMetadata getObjectMetadata(String bucket, String key, MirrorOptions options) throws Exception {
        Exception ex = null;
        for (int tries=0; tries<options.getMaxRetries(); tries++) {
            try {
                context.getStats().s3getCount.incrementAndGet();
                return client.getObjectMetadata(bucket, key);

            } catch (AmazonS3Exception e) {
                if (e.getStatusCode() == 404) throw e;

            } catch (Exception e) {
                ex = e;
                if (options.isVerbose()) {
                    if (tries >= options.getMaxRetries()) {
                        getLog().error("getObjectMetadata(" + key + ") failed (try #" + tries + "), giving up");
                        break;
                    } else {
                        getLog().warn("getObjectMetadata("+key+") failed (try #"+tries+"), retrying...");
                    }
                }
            }
        }
        throw ex;
    }

    protected AccessControlList getAccessControlList(MirrorOptions options, String key) throws Exception {
        Exception ex = null;

        for (int tries=0; tries<options.getMaxRetries(); tries++) {
            try {
                context.getStats().s3getCount.incrementAndGet();
                return client.getObjectAcl(options.getSourceBucket(), key);

            } catch (Exception e) {
                if (options.isEncrypt()) {
                    getLog().debug("Bucket encryption enabled, unable to read ACL. Setting new ACL to no permissions.");
                    return new AccessControlList();
                } else {
                    ex = e;
                    if (options.isVerbose()) {
                        if (tries >= options.getMaxRetries()) {
                            getLog().error("getObjectAcl(" + key + ") failed (try #" + tries + "), giving up");
                            break;
                        } else {
                            getLog().warn("getObjectAcl("+key+") failed (try #"+tries+"), retrying...");
                        }
                    }
                }
            }
        }
        throw ex;
    }

}
