package nl.rutilo.logdashboard.util;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

public class TempFile implements AutoCloseable {
    private final File file;

    public TempFile() { this("DevLogDashboard", "tmp"); }
    public TempFile(String prefix, String suffix) {
        try {
            file = File.createTempFile(prefix, suffix);
        } catch(final IOException e) {
            throw new RuntimeException("Unable to create temp file:" + e.getMessage(), e);
        }
    }
    public File get() {
        return file;
    }

    @Override
    public void close() {
        try {
            Files.deleteIfExists(file.toPath());
        } catch (final IOException ignored) {
            // ignored
        }
    }
}
