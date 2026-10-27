// Mp4SalvageTest.java — locating journalled samples inside a moov-less .mp4.tmp
package com.overdrive.app.surveillance;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

public class Mp4SalvageTest {

    /** In-memory Source over a byte array. */
    private static Mp4Salvage.Source source(byte[] bytes) {
        return new Mp4Salvage.Source() {
            @Override public long length() { return bytes.length; }

            @Override public int read(long pos, byte[] buf, int off, int len) {
                if (pos >= bytes.length) return 0;
                int n = (int) Math.min(len, bytes.length - pos);
                System.arraycopy(bytes, (int) pos, buf, off, n);
                return n;
            }
        };
    }

    /** One AVCC sample: each NAL as 4-byte length + header byte (forbidden bit clear) + payload. */
    private static byte[] sample(int... nalSizes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(out);
        for (int n : nalSizes) {
            d.writeInt(n);
            d.writeByte(0x65);
            for (int i = 1; i < n; i++) d.writeByte(i & 0x7F);
        }
        return out.toByteArray();
    }

    private static byte[] box(String type, byte[] body) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(out);
        d.writeInt(8 + body.length);
        d.write(type.getBytes(StandardCharsets.US_ASCII));
        d.write(body);
        return out.toByteArray();
    }

    @Test
    public void findsMdatAfterFtypEvenWithAPlaceholderSize() throws IOException {
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        file.write(box("ftyp", new byte[16]));
        // crash leaves "size=1, largesize=0" as written at open
        DataOutputStream d = new DataOutputStream(file);
        d.writeInt(1);
        d.write("mdat".getBytes(StandardCharsets.US_ASCII));
        d.writeLong(0);
        file.write(new byte[100]);
        byte[] bytes = file.toByteArray();

        long[] mdat = Mp4Salvage.findMdat(source(bytes));
        assertArrayEquals(new long[] {24 + 16, bytes.length}, mdat);
    }

    @Test
    public void noMdatMeansNothingToRecover() throws IOException {
        assertNull(Mp4Salvage.findMdat(source(box("ftyp", new byte[16]))));
    }

    @Test
    public void locatesSamplesAcrossInterleavedAudioAndStopsAtTheTruncation() throws IOException {
        byte[] s0 = sample(20, 300);
        byte[] s1 = sample(150);
        byte[] s2 = sample(90);
        byte[] audio = new byte[777];
        Arrays.fill(audio, (byte) 0xFF); // AAC-ish bytes: never parse as a NAL run
        byte[] s3 = sample(400);

        ByteArrayOutputStream mdat = new ByteArrayOutputStream();
        mdat.write(s0);
        mdat.write(s1);
        mdat.write(audio);
        mdat.write(s2);
        mdat.write(Arrays.copyOf(s3, 100)); // last chunk only half on disk
        byte[] bytes = mdat.toByteArray();

        List<long[]> journal = new ArrayList<>();
        journal.add(new long[] {s0.length, 0, 1});
        journal.add(new long[] {s1.length, 66_000, 0});
        journal.add(new long[] {s2.length, 133_000, 0});
        journal.add(new long[] {s3.length, 200_000, 0});

        List<Long> offsets = Mp4Salvage.locateSamples(source(bytes), 0, bytes.length, journal);
        assertEquals(3, offsets.size());
        assertEquals(0L, (long) offsets.get(0));
        assertEquals(s0.length, (long) offsets.get(1));
        assertEquals(s0.length + s1.length + audio.length, (long) offsets.get(2));
    }

    @Test
    public void aSampleMustBeTiledExactlyByItsNalLengths() throws IOException {
        byte[] s = sample(50, 60);
        byte[] head5 = new byte[5];
        assertTrue(Mp4Salvage.matchesSample(source(s), 0, s.length, s.length, head5));
        // one byte too short / too long never matches
        assertTrue(!Mp4Salvage.matchesSample(source(s), 0, s.length - 1, s.length, head5));
        byte[] longer = Arrays.copyOf(s, s.length + 1);
        assertTrue(!Mp4Salvage.matchesSample(source(longer), 0, s.length + 1, longer.length, head5));
    }

    @Test
    public void readsJournalHeaderAndRecordsAndDropsATornTail() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(out);
        String b64 = Base64.getEncoder().encodeToString(new byte[] {0, 0, 0, 1, 0x67});
        d.write((Mp4Salvage.MAGIC + "\nmime=video/avc\nwidth=1920\nheight=1080\ncsd-0=" + b64 + "\nend\n")
                .getBytes(StandardCharsets.UTF_8));
        d.writeInt(1234);
        d.writeLong(66_000L);
        d.writeInt(1);
        d.writeInt(999); // torn: size only

        Mp4Salvage.JournalData data = Mp4Salvage.readJournal(
                new DataInputStream(new ByteArrayInputStream(out.toByteArray())));
        assertEquals("video/avc", data.mime);
        assertEquals(1920, data.width);
        assertEquals(1080, data.height);
        assertEquals(1, data.csd.size());
        assertEquals(1, data.samples.size());
        assertArrayEquals(new long[] {1234, 66_000, 1}, data.samples.get(0));
    }

    @Test(expected = IOException.class)
    public void rejectsAForeignFile() throws IOException {
        Mp4Salvage.readJournal(new DataInputStream(new ByteArrayInputStream(
                "hello\n".getBytes(StandardCharsets.UTF_8))));
    }
}
