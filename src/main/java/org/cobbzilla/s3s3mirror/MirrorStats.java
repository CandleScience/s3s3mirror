package org.cobbzilla.s3s3mirror;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
public class MirrorStats {

    @Getter private final Thread shutdownHook = new Thread() {
        @Override public void run() { logStats(); }
    };

    private static final String BANNER = "\n--------------------------------------------------------------------\n";
    public void logStats() {
        log.info(BANNER + "STATS BEGIN\n" + toString() + "STATS END " + BANNER);
    }

    private long start = System.currentTimeMillis();

    public final AtomicLong objectsRead = new AtomicLong(0);
    public final AtomicLong objectsCopied = new AtomicLong(0);
    public final AtomicLong copyErrors = new AtomicLong(0);

    public final AtomicLong s3copyCount = new AtomicLong(0);
    public final AtomicLong s3getCount = new AtomicLong(0);
    public final AtomicLong bytesCopied = new AtomicLong(0);

    public static final long HOUR = TimeUnit.HOURS.toMillis(1);
    public static final long MINUTE = TimeUnit.MINUTES.toMillis(1);
    public static final long SECOND = TimeUnit.SECONDS.toMillis(1);

    public String toString () {
        final long durationMillis = System.currentTimeMillis() - start;
        final double durationMinutes = durationMillis / 60000.0d;
        final String duration = String.format("%d:%02d:%02d", durationMillis / HOUR, (durationMillis % HOUR) / MINUTE, (durationMillis % MINUTE) / SECOND);
        final double readRate = objectsRead.get() / durationMinutes;
        final double copyRate = objectsCopied.get() / durationMinutes;
        return "read: "+objectsRead+ "\n"
                + "copied: "+objectsCopied+"\n"
                + "copy errors: "+copyErrors+"\n"
                + "duration: "+duration+"\n"
                + "read rate: "+readRate+"/minute\n"
                + "copy rate: "+copyRate+"/minute\n"
                + "bytes copied: "+formatBytes(bytesCopied.get())+"\n"
                + "GET operations: "+s3getCount+"\n"
                + "COPY operations: "+ s3copyCount+"\n";
    }

    public static final long KB = 1024;
    public static final long MB = KB * 1024;
    public static final long GB = MB * 1024;
    public static final long PB = GB * 1024;
    public static final long EB = PB * 1024;

    private String formatBytes(long bytesCopied) {
        if (bytesCopied > EB) return ((double) bytesCopied) / ((double) EB) + " EB ("+bytesCopied+" bytes)";
        if (bytesCopied > PB) return ((double) bytesCopied) / ((double) PB) + " PB ("+bytesCopied+" bytes)";
        if (bytesCopied > GB) return ((double) bytesCopied) / ((double) GB) + " GB ("+bytesCopied+" bytes)";
        if (bytesCopied > MB) return ((double) bytesCopied) / ((double) MB) + " MB ("+bytesCopied+" bytes)";
        if (bytesCopied > KB) return ((double) bytesCopied) / ((double) KB) + " KB ("+bytesCopied+" bytes)";
        return bytesCopied + " bytes";
    }

}
