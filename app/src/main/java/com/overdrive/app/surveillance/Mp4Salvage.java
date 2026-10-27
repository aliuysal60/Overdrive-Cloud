// Mp4Salvage.java — journal + recovery for recording segments cut off before MediaMuxer.stop()
package com.overdrive.app.surveillance;

import com.overdrive.app.logging.DaemonLogger;

import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Recovers the video of a segment whose {@code .mp4.tmp} never got its {@code moov} box (power cut
 * or daemon killed mid-recording), which {@link com.overdrive.app.storage.StorageManager} otherwise
 * deletes as an orphan — so the last minutes before every ignition-off were lost.
 *
 * <p>While recording, a small journal ({@code <tmp>.salvage}) holds the video track's codec config
 * (csd-0/1/2) and, per written video sample, its size, timestamp and flags (16 bytes/frame). The
 * samples themselves are only in the {@code .mp4.tmp}'s {@code mdat}: MPEG4Writer stores them as
 * length-prefixed NAL units, in write order per track, with audio chunks interleaved. Recovery walks
 * the {@code mdat}, locates each journalled sample by checking that its NAL length prefixes add up
 * to exactly the journalled size (skipping interleaved audio by scanning forward), and remuxes the
 * found samples into a playable {@code .mp4}. Audio is not recovered.
 */
public final class Mp4Salvage {
    private static final DaemonLogger logger = DaemonLogger.getInstance("Mp4Salvage");

    public static final String SUFFIX = ".salvage";
    static final String MAGIC = "ODSALV1";
    /** How far past a sample's expected position to look for it (interleaved audio, gaps). */
    static final int MAX_SKIP_BYTES = 4 * 1024 * 1024;
    static final int RECORD_BYTES = 16;
    private static final int FLUSH_EVERY = 15;

    private Mp4Salvage() {}

    // ── Recording side ─────────────────────────────────────────────────────

    /** Journal of one segment. All methods swallow I/O errors: recording must never fail on it. */
    public static final class Journal {
        private final File file;
        private DataOutputStream out;
        private int unflushed;

        private Journal(File file, DataOutputStream out) {
            this.file = file;
            this.out = out;
        }

        /** Starts the journal next to {@code tmpMp4}; null when it can't be written. */
        public static Journal open(File tmpMp4, android.media.MediaFormat videoFormat) {
            if (tmpMp4 == null || videoFormat == null) return null;
            File file = new File(tmpMp4.getAbsolutePath() + SUFFIX);
            try {
                DataOutputStream out = new DataOutputStream(
                        new BufferedOutputStream(new FileOutputStream(file, false), 8192));
                out.write(header(videoFormat).getBytes(StandardCharsets.UTF_8));
                out.flush();
                return new Journal(file, out);
            } catch (Throwable t) {
                logger.warn("journal open failed: " + t.getMessage());
                file.delete();
                return null;
            }
        }

        public synchronized void append(int size, long ptsUs, int flags) {
            if (out == null || size <= 0) return;
            try {
                out.writeInt(size);
                out.writeLong(ptsUs);
                out.writeInt(flags);
                if (++unflushed >= FLUSH_EVERY) {
                    out.flush();
                    unflushed = 0;
                }
            } catch (IOException e) {
                closeQuietly();
            }
        }

        /** The segment was finalized (or dropped) normally: the journal is no longer needed. */
        public synchronized void discard() {
            closeQuietly();
            file.delete();
        }

        private void closeQuietly() {
            if (out == null) return;
            try {
                out.close();
            } catch (IOException ignored) {
            }
            out = null;
        }
    }

    static String header(android.media.MediaFormat format) {
        StringBuilder sb = new StringBuilder(MAGIC).append('\n');
        sb.append("mime=").append(format.getString(android.media.MediaFormat.KEY_MIME)).append('\n');
        sb.append("width=").append(format.getInteger(android.media.MediaFormat.KEY_WIDTH)).append('\n');
        sb.append("height=").append(format.getInteger(android.media.MediaFormat.KEY_HEIGHT)).append('\n');
        for (int i = 0; i < 3; i++) {
            String key = "csd-" + i;
            if (!format.containsKey(key)) continue;
            java.nio.ByteBuffer csd = format.getByteBuffer(key).duplicate();
            byte[] bytes = new byte[csd.remaining()];
            csd.get(bytes);
            sb.append(key).append('=').append(Base64.getEncoder().encodeToString(bytes)).append('\n');
        }
        return sb.append("end\n").toString();
    }

    // ── Recovery side ──────────────────────────────────────────────────────

    /** Parsed journal. */
    static final class JournalData {
        String mime;
        int width;
        int height;
        final List<byte[]> csd = new ArrayList<>();
        final List<long[]> samples = new ArrayList<>(); // {size, ptsUs, flags}
    }

    static JournalData readJournal(File file) throws IOException {
        try (DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(new FileInputStream(file)))) {
            return readJournal(in);
        }
    }

    static JournalData readJournal(DataInputStream in) throws IOException {
        if (!MAGIC.equals(readLine(in))) throw new IOException("not a salvage journal");
        JournalData data = new JournalData();
        String line;
        while (!"end".equals(line = readLine(in))) {
            if (line == null) throw new IOException("truncated header");
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String key = line.substring(0, eq);
            String value = line.substring(eq + 1);
            if ("mime".equals(key)) data.mime = value;
            else if ("width".equals(key)) data.width = Integer.parseInt(value);
            else if ("height".equals(key)) data.height = Integer.parseInt(value);
            else if (key.startsWith("csd-")) data.csd.add(Base64.getDecoder().decode(value));
        }
        while (true) {
            try {
                int size = in.readInt();
                long pts = in.readLong();
                int flags = in.readInt();
                data.samples.add(new long[] {size, pts, flags});
            } catch (EOFException partialOrEnd) {
                break; // a torn last record is simply dropped
            }
        }
        if (data.mime == null || data.width <= 0 || data.height <= 0 || data.csd.isEmpty()) {
            throw new IOException("incomplete journal header");
        }
        return data;
    }

    private static String readLine(DataInputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1 && c != '\n') {
            sb.append((char) c);
            if (sb.length() > 65536) throw new IOException("header line too long");
        }
        return c == -1 && sb.length() == 0 ? null : sb.toString();
    }

    /** Random read access over the {@code .mp4.tmp} (a file, or bytes in tests). */
    interface Source {
        long length();

        /** Reads up to {@code len} bytes at {@code pos}; returns the count read (0 at EOF). */
        int read(long pos, byte[] buf, int off, int len) throws IOException;
    }

    /** {@code [dataStart, dataEnd)} of the top-level {@code mdat}, or null. */
    static long[] findMdat(Source src) throws IOException {
        long pos = 0;
        long len = src.length();
        byte[] head = new byte[16];
        while (pos + 8 <= len) {
            if (readFully(src, pos, head, 16) < 8) return null;
            long size = u32(head, 0);
            String type = new String(head, 4, 4, StandardCharsets.US_ASCII);
            int headerLen = 8;
            if (size == 1) {
                if (pos + 16 > len) return null;
                size = (u32(head, 8) << 32) | u32(head, 12);
                headerLen = 16;
            }
            if ("mdat".equals(type)) {
                long end = (size >= headerLen && pos + size <= len) ? pos + size : len;
                // a crash leaves the size field as a placeholder: trust the file end instead
                if (size <= headerLen) end = len;
                return new long[] {pos + headerLen, end};
            }
            if (size < headerLen) return null; // "to end of file" or garbage before any mdat
            pos += size;
        }
        return null;
    }

    /**
     * Offsets of the journalled samples inside {@code mdat}, in order; stops at the first sample it
     * cannot find within {@link #MAX_SKIP_BYTES}.
     */
    static List<Long> locateSamples(Source src, long dataStart, long dataEnd, List<long[]> samples)
            throws IOException {
        List<Long> offsets = new ArrayList<>();
        long pos = dataStart;
        byte[] head5 = new byte[5];
        for (long[] sample : samples) {
            int size = (int) sample[0];
            long found = -1;
            long limit = Math.min(dataEnd - size, pos + MAX_SKIP_BYTES);
            for (long p = pos; p <= limit; p++) {
                if (matchesSample(src, p, size, dataEnd, head5)) {
                    found = p;
                    break;
                }
            }
            if (found < 0) break;
            offsets.add(found);
            pos = found + size;
        }
        return offsets;
    }

    /**
     * True when 4-byte NAL length prefixes at {@code p} tile exactly {@code size} bytes.
     * {@code head5} is a caller-owned 5-byte scratch buffer (length prefix + NAL header byte).
     */
    static boolean matchesSample(Source src, long p, int size, long dataEnd, byte[] head5) throws IOException {
        if (size < 5 || p + size > dataEnd) return false;
        long end = p + size;
        long q = p;
        while (q < end) {
            if (end - q < 5) return false;
            if (readFully(src, q, head5, 5) < 5) return false;
            long nal = u32(head5, 0);
            if (nal < 1 || q + 4 + nal > end) return false;
            if ((head5[4] & 0x80) != 0) return false; // forbidden_zero_bit (H.264 and HEVC)
            q += 4 + nal;
        }
        return q == end;
    }

    private static int readFully(Source src, long pos, byte[] buf, int len) throws IOException {
        int total = 0;
        while (total < len) {
            int n = src.read(pos + total, buf, total, len - total);
            if (n <= 0) break;
            total += n;
        }
        return total;
    }

    private static long u32(byte[] b, int off) {
        return ((b[off] & 0xFFL) << 24) | ((b[off + 1] & 0xFFL) << 16)
                | ((b[off + 2] & 0xFFL) << 8) | (b[off + 3] & 0xFFL);
    }

    /** Windowed file reader: sample scanning reads tiny pieces at nearby offsets. */
    static final class FileSource implements Source, AutoCloseable {
        private final RandomAccessFile raf;
        private final long length;
        private final byte[] window = new byte[64 * 1024];
        private long windowStart = -1;
        private int windowLen;

        FileSource(File file) throws IOException {
            raf = new RandomAccessFile(file, "r");
            length = raf.length();
        }

        @Override public long length() { return length; }

        @Override public int read(long pos, byte[] buf, int off, int len) throws IOException {
            if (pos >= length) return 0;
            if (windowStart < 0 || pos < windowStart || pos >= windowStart + windowLen) {
                raf.seek(pos);
                windowLen = Math.max(0, raf.read(window, 0, window.length));
                windowStart = pos;
                if (windowLen == 0) return 0;
            }
            int n = (int) Math.min(len, windowStart + windowLen - pos);
            System.arraycopy(window, (int) (pos - windowStart), buf, off, n);
            return n;
        }

        @Override public void close() throws IOException { raf.close(); }
    }

    /**
     * Rebuilds {@code out} from {@code tmpMp4} + {@code journal}. Returns the number of video samples
     * written (0 = nothing recoverable; {@code out} is then removed).
     */
    public static int recover(File tmpMp4, File journal, File out) {
        android.media.MediaMuxer muxer = null;
        boolean started = false;
        int written = 0;
        try (FileSource src = new FileSource(tmpMp4)) {
            JournalData data = readJournal(journal);
            long[] mdat = findMdat(src);
            if (mdat == null) return 0;
            List<Long> offsets = locateSamples(src, mdat[0], mdat[1], data.samples);
            int first = -1;
            for (int i = 0; i < offsets.size(); i++) {
                if ((data.samples.get(i)[2] & android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) {
                    first = i;
                    break;
                }
            }
            if (first < 0) return 0;

            android.media.MediaFormat format =
                    android.media.MediaFormat.createVideoFormat(data.mime, data.width, data.height);
            for (int i = 0; i < data.csd.size(); i++) {
                format.setByteBuffer("csd-" + i, java.nio.ByteBuffer.wrap(data.csd.get(i)));
            }
            muxer = new android.media.MediaMuxer(out.getAbsolutePath(),
                    android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int track = muxer.addTrack(format);
            muxer.start();
            started = true;

            android.media.MediaCodec.BufferInfo info = new android.media.MediaCodec.BufferInfo();
            long basePts = data.samples.get(first)[1];
            long lastPts = -1;
            for (int i = first; i < offsets.size(); i++) {
                long[] sample = data.samples.get(i);
                int size = (int) sample[0];
                byte[] bytes = new byte[size];
                if (readFully(src, offsets.get(i), bytes, size) < size) break;
                long pts = Math.max(0, sample[1] - basePts);
                if (pts <= lastPts) pts = lastPts + 1;
                lastPts = pts;
                info.set(0, size, pts, (int) sample[2]);
                muxer.writeSampleData(track, java.nio.ByteBuffer.wrap(bytes), info);
                written++;
            }
            muxer.stop();
            started = false;
            logger.info("recovered " + written + "/" + data.samples.size() + " video samples: "
                    + out.getName());
            return written;
        } catch (Throwable t) {
            logger.warn("recovery failed for " + tmpMp4.getName() + ": " + t.getMessage());
            written = 0;
            return 0;
        } finally {
            if (muxer != null) {
                try {
                    if (started) muxer.stop();
                } catch (Throwable ignored) {
                }
                try {
                    muxer.release();
                } catch (Throwable ignored) {
                }
            }
            if (written == 0) out.delete();
        }
    }
}
