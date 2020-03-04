package nl.rutilo.logdashboard.util;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.RandomAccessFile;

/** File than can be written to, but removes the first half when larger than given maximum size */
public class LimitedSizeFile {
    private final  File file;
    private        FileWriter fout;
    private final  long maxSizeBytes;
    private static final int COPY_BUFFER_SIZE = 1024;
    private final  Object sync = new Object();

    public LimitedSizeFile(File file, long maxSizeBytes) {
        this.file = file;
        this.maxSizeBytes = maxSizeBytes;
        open();
    }

    private void open() {
        try {
            fout = new FileWriter(file, /*append=*/true);
        } catch(final IOException e) {
            throw new RuntimeException("Unable to open file for writing: " + file.getAbsolutePath(), e);
        }
    }

    public void close() { quiet(fout::close); }
    public void write(String text) {
        synchronized(sync) {
            mayThrow(()-> { fout.write(text); fout.flush(); }); checkSize();
        }
    }
    public File getFile() { return file; }
    public long getMaxSizeBytes() { return maxSizeBytes; }

    private void checkSize() {
        final long length = file.length();
        if(length > maxSizeBytes) {
            final File tempFile = new File(file.getAbsolutePath() + ".tmp");
            try {
                final byte[] buffer = new byte[COPY_BUFFER_SIZE];
                final RandomAccessFile ranFile = new RandomAccessFile(file, "r");
                final FileOutputStream tempOut = new FileOutputStream(tempFile);

                ranFile.seek(length - maxSizeBytes/2);
                int c = 0;
                while(c != -1 && c != '\n') c = ranFile.read(); // skip to first unclipped line

                int bytesRead;
                while( (bytesRead = ranFile.read(buffer)) >= 0) tempOut.write(buffer, 0, bytesRead);

                tempOut.close();
                ranFile.close();
                close();
                file.delete();
                tempFile.renameTo(file);
                open();
            } catch(final IOException e) {
                throw new RuntimeException("Unable to truncate file", e);
            }
        }
    }

    private static void quiet(ThrowingRunnable r) { try { r.run(); } catch(final Exception ignored) {/*quiet*/} }
    private static void mayThrow(ThrowingRunnable r) { r.run(); }
}
