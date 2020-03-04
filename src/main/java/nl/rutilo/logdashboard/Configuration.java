package nl.rutilo.logdashboard;

import java.io.File;
import java.util.Optional;

public class Configuration {
    public static final int MAX_OUTPUT_LINE_COUNT = 5000;

    private Configuration() {}

    private static Optional<File> jarsDir = Optional.empty();
    public static void reset() { jarsDir = Optional.empty(); }

    public static File getJarsDir() {
        if (!jarsDir.isPresent()) {
            final String root = System.getProperty("root");
            final String currentDir = new File(root == null ? "" : root).getAbsolutePath().replace("\\", "/");
            jarsDir = Optional.of(new File(currentDir));
        }
        return jarsDir.get();
    }
    public static void setJarsDir(File jd) {
        jarsDir = Optional.ofNullable(jd);
    }
}
