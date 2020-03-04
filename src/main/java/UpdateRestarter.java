import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.io.IOException;
import java.io.File;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

public class UpdateRestarter {
    private static final int MAX_DELETE_RETRIES = 20;
    private static final int SLEEP_BEFORE_RETRY = 750;
    private static final String DEFAULT_NAME = "dev-log-dashboard";
    private static final String CMD_FILENAME = "UpdateRestarter-Command.txt";

    public static void main(final String[] args) {
        final String name = ((args.length > 0) ? args[0] : DEFAULT_NAME).replaceAll("\\.jar$", "");
        final File jarFile = new File(name + ".jar");
        final File tmpFile = new File(name + ".jar.tmp");
        final File cmdFile = new File(CMD_FILENAME);
        final String[] cmd = readFile(cmdFile).trim().split("\n");

        check(() -> cmd.length > 1, () -> "No command in " + CMD_FILENAME);
        check(jarFile::exists, () -> "Jar file not found: " + jarFile);
        check(tmpFile::exists, () -> "Temp jar file not found: " + jarFile);

        deleteWithRetries(jarFile, jarFile.getName(), MAX_DELETE_RETRIES);

        log("Set replacement jar...");
        if (!tmpFile.renameTo(jarFile)) {
            fail("Unable to rename temp file (" + tmpFile + ") to jar file (" + jarFile + ")");
        }

        exec(cmd, "replacement jar");

        delete(cmdFile, "command file");

        log("Success");
        done();
    }

    private static void exec(String[] cmd, String label) {
        if(label != null) log("Run " + label + "...");
        log(String.join(" ", cmd));
        try {
            final ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.inheritIO();
            pb.start();
        }
        catch (final IOException e) {
            fail("Unable to start" + (label == null ? "" : " " + label) + ":" + String.join(" ", cmd) + ":" + e.getMessage());
        }
    }

    private static void check(BooleanSupplier toCheck, Supplier<String> errorMessage) {
        if(!toCheck.getAsBoolean()) fail(errorMessage.get());
    }

    private static void delete(File file, String label) { deleteWithRetries(file, label, 1); }
    private static void deleteWithRetries(File file, String label, int maxRetries) {
        if(label != null) log("Delete " + label + "...");
        int retryCount;
        for (retryCount = 1; !file.delete() && retryCount <= maxRetries; ++retryCount) {
            sleep(SLEEP_BEFORE_RETRY);
        }
        if (file.exists()) {
            fail("Unable to delete jar file: " + file.getName());
        } else {
            if(label != null) log(retryCount == 1 ? "Deleted" : "Deleted after " + retryCount + " attempts.");
        }
    }

    private static void sleep(final int ms) {
        try {
            Thread.sleep(ms);
        }
        catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            done();
        }
    }
    private static String readFile(final File file) {
        try {
            final byte[] encoded = Files.readAllBytes(file.toPath());
            return new String(encoded, StandardCharsets.UTF_8);
        }
        catch (final IOException e) {
            return "";
        }
    }

    private static void log(final String... args) {
        System.out.println(String.join(" ", (CharSequence[])args));
    }
    private static void fail(final String reason) {
        System.err.println(reason);
        System.exit(10);
    }
    private static void done() {
        System.exit(0);
    }
}
