package nl.rutilo.logdashboard.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class IOUtil {
    private IOUtil() { throw new IllegalStateException("singleton"); }
    public static final int COPY_BUFFER_SIZE = 8192;
    public static final Charset TEXT_CHARSET = StandardCharsets.UTF_8;

    public static long   copy(InputStream source, OutputStream sink) throws IOException {
        long nread = 0L;
        final byte[] buf = new byte[COPY_BUFFER_SIZE]; // when moved outside, this call is no longer threadsafe
        int n;
        while ((n = source.read(buf)) > 0) {
            sink.write(buf, 0, n);
            nread += n;
        }
        return nread;
    }
    public static byte[] exhaust(InputStream source) throws IOException {
        final ByteArrayOutputStream bout = new ByteArrayOutputStream();
        copy(source, bout);
        return bout.toByteArray();
    }
    public static String asString(byte[] data) {
        return new String(data, TEXT_CHARSET);
    }
    public static String fileToString(File file) throws IOException {
        final String[] epParts = file.getAbsolutePath().replace("\\","/").split("!");
        if(epParts.length > 1) {
            try(final ZipInputStream zipIn = ZipUtil.openZipForReading(new File(epParts[0]))) {
                ZipEntry entry;
                do entry = zipIn.getNextEntry(); while(entry != null && !entry.getName().equals(epParts[1]));
                if(entry == null) throw new FileNotFoundException("No " + epParts[1] + " in " + epParts[0]);
                return asString(exhaust(zipIn));
            }
        } else {
            try (final FileInputStream fin = new FileInputStream(file)) {
                return asString(exhaust(fin));
            }
        }
    }
    public static void   stringToFile(String s, File file) throws IOException {
        try(final ByteArrayInputStream bin = new ByteArrayInputStream(toBytes(s))) {
            toFile(bin, file);
        }
    }
    public static void   toFile(InputStream in, File file) throws IOException {
        try(final FileOutputStream fout = new FileOutputStream(file)) {
            copy(in, fout);
        }
    }
    public static void   toFile(URL url, File file) throws IOException {
        final URLConnection urlConnection = url.openConnection();
        final HttpURLConnection http = urlConnection instanceof HttpURLConnection ? (HttpURLConnection)urlConnection :  null;
        if(http != null) { // this one checks the response code instead of downloading the error body
            http.setInstanceFollowRedirects(true);
            toFile(http.getInputStream(), file);
        } else {
            toFile(url.openStream(), file);
        }
    }
    public static byte[] toBytes(String s) {
        return s.getBytes(TEXT_CHARSET);
    }

    public static void delete(File file) {
        if(file.isDirectory()) deleteDir(file); else deleteFile(file);
    }
    public static void deleteFile(File file) {
        try {
            Files.deleteIfExists(file.toPath());
        } catch (final IOException e) {
            throw new RuntimeException("failed to delete " + file.getAbsolutePath(), e); // NOSONAR
        }
    }
    public static void deleteDir(File file) {
        if(!file.isDirectory() || !file.exists()) deleteFile(file);
        else Arrays.stream(Util.or(file.listFiles(), new File[0])).forEach(IOUtil::delete);
    }
}
