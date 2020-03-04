package nl.rutilo.logdashboard.util;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/* Copied from ZipDiff */
public class ZipUtil {
    private ZipUtil() { /*singleton*/ }
    public static final int COPY_BUFFER_SIZE = 8192;
    public static final Charset TEXT_CHARSET = StandardCharsets.UTF_8;

    public static ZipInputStream openZipForReading(File file) throws IOException { return openZipForReading(file, null); }
    public static ZipInputStream openZipForReading(File file, Consumer<byte[]> header) throws IOException {
        // search for 0x 50 4B 03 04 which is start of zip -- before that, other data may lurk
        final int blen = 4;
        final int[] hbuf = new int[blen];
        int hoff = 0;
        int read = 0;

        class LocalMethods {
            boolean isStartOfZip(int hoff) {
                return hoff >= blen
                    && hbuf[(hoff-4)%4] == 0x50 // P
                    && hbuf[(hoff-3)%4] == 0x4B // K
                    && hbuf[(hoff-2)%4] <= 5
                    && hbuf[(hoff-1)%4] <= 6
                    ;
            }
        }
        final LocalMethods m = new LocalMethods();
        final PushbackInputStream in = new PushbackInputStream(new BufferedInputStream(new FileInputStream(file)), blen); // NOSONAR: don't close, this stream is returned
        final ByteArrayOutputStream headerData = new ByteArrayOutputStream();
        for(; read >= 0 && !m.isStartOfZip(hoff); hoff++) {
            if(hoff>=blen && header != null) headerData.write(hbuf[(hoff-blen)%blen]);
            hbuf[hoff % blen] = read = in.read();
        }
        if(read >= 0) {
            for(int i=0; i<hbuf.length; i++) in.unread(hbuf[(hoff-i-1)%blen]);
            if(headerData.size() > 0 && header != null) header.accept(headerData.toByteArray());
        } else {
            in.close();
            throw new IOException("Not a ZIP file");
        }
        return new ZipInputStream(in);
    }
    public static ZipOutputStream openZipForWriting(File file, byte[] headerData) throws IOException {
        final BufferedOutputStream bout = new BufferedOutputStream(new FileOutputStream(file)); // NOSONAR: this stream is returned
        if(headerData != null && headerData.length != 0) bout.write(headerData);
        return new ZipOutputStream(bout);
    }

    public static ZipEntry copyOf(ZipEntry entry) {
        final ZipEntry copy = new ZipEntry(entry);
        copy.setCompressedSize(-1);
        return copy;
    }

    /** Only used in test, but handy none the less, so added to util */
    public static void updateZip(File zipFile, Map<String,Object> toAdd, String... toDelete) throws IOException {
        final Set<String> namesToDelete = new HashSet<>(Arrays.asList(toDelete));
        final byte[][] hdr = { new byte[0] };
        final File tmp = File.createTempFile("updating-zip-file", ".zip");
        try(final ZipInputStream  zipIn  = ZipUtil.openZipForReading(zipFile, h -> hdr[0] = h);
            final ZipOutputStream zipOut = ZipUtil.openZipForWriting(tmp, hdr[0])) {

            for(final ZipEntry entryIn : entryIterableOf(zipIn)) {
                if(!namesToDelete.contains(entryIn.getName())) {
                    zipOut.putNextEntry(copyOf(entryIn));
                    ZipUtil.copyAndReturnCount(zipIn, zipOut);
                }
            }
            for(final Map.Entry<String,Object> e : toAdd.entrySet()) {
                final ZipEntry newEntry = new ZipEntry(e.getKey());
                zipOut.putNextEntry(newEntry);
                final byte[] data = e.getValue() instanceof byte[] ? (byte[])e.getValue() : toBytes(e.getValue().toString());
                ZipUtil.copyAndReturnCount(data, zipOut);
            }
        } finally {
            Files.delete(zipFile.toPath());
        }
        Files.move(tmp.toPath(), zipFile.toPath());
    }

    public static Iterable<ZipEntry> entryIterableOf(ZipInputStream zipIn) {
        return () -> new Iterator<ZipEntry>() {
            private ZipEntry next;

            @Override
            public boolean hasNext() {
                try {
                    return next != null || (next = zipIn.getNextEntry()) != null;
                } catch(final IOException e) {
                    throw new RuntimeException(e);
                }
            }

            @Override
            public ZipEntry next() {
                if(next == null) throw new NoSuchElementException("Next() entry requested while !hasNext()");
                final ZipEntry result = next;
                next = null;
                return result;
            }
        };
    }

    public static List<String> sorted(Collection<String> strings) {
        final List<String> list = new ArrayList<>(strings);
        list.sort(String::compareTo);
        return list;
    }

    public static long copyAndReturnCount(byte[] source, OutputStream sink) throws IOException {
        return copyAndReturnCount(new ByteArrayInputStream(source), sink);
    }
    public static long copyAndReturnCount(InputStream source, OutputStream sink) throws IOException {
        final byte[] buf = new byte[COPY_BUFFER_SIZE];
        int n;
        int count = 0;
        while ((n = source.read(buf)) > 0) {
            sink.write(buf, 0, n);
            count += n;
        }
        return count;
    }

    public static byte[] exhaust(InputStream source) throws IOException {
        final ByteArrayOutputStream bout = new ByteArrayOutputStream();
        copyAndReturnCount(source, bout);
        return bout.toByteArray();
    }
    public static void   drain(InputStream source) throws IOException {
        final byte[] buf = new byte[COPY_BUFFER_SIZE];
        //noinspection StatementWithEmptyBody
        while (source.read(buf) > 0);
    }
    public static boolean isEqual(byte[] a, byte[] b) {
        if(a == null && b == null) return true;
        if((a == null) != (b == null)) return false;
        if(a.length != b.length) return false;
        for(int i=0; i<a.length; i++) if(a[i] != b[i]) return false;
        return true;
    }
    public static String asString(byte[] data) {
        return new String(data, TEXT_CHARSET);
    }
    public static byte[] toBytes(String s) {
        return s.getBytes(TEXT_CHARSET);
    }
    public static String sizeToString(long sizeIn) {
        final String[] suffixes = { "", "K", "M", "G", "T", "P" }; // higher won't fit in long
        int exp = Math.min(suffixes.length-1, (int)(Math.log(sizeIn) / Math.log(1024)));
        long size = sizeIn / (long)Math.pow(1024, exp);
        if(size <= 4 && exp > 0) { exp--; size = sizeIn / (long)Math.pow(1024, exp); }
        return size + " " + suffixes[exp] + "B";
    }
}
