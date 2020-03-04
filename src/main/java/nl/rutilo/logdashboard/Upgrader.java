package nl.rutilo.logdashboard;

import nl.rutilo.logdashboard.services.Services;
import nl.rutilo.logdashboard.util.IOUtil;
import nl.rutilo.logdashboard.util.Util;
import nl.rutilo.logdashboard.util.ZipPatcher;
import nl.rutilo.logdashboard.util.ZipUtil;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.URL;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/** Download patch, patch old jar to new jar, replace old jar, restart
  */
public class Upgrader {
    private Upgrader() { /*singleton*/ }
    private static final String DASHBOARD_NAME        = "dev-log-dashboard";
    private static final String CMD_FILENAME          = "UpdateRestarter-Command.txt";
    private static final String UPDATE_RESTARTER_NAME = "UpdateRestarter";
    private static final String UPDATE_RESTARTER_CN   = UPDATE_RESTARTER_NAME + ".class";
    public static final int CURRENT_VERSION = toIntVersion(ServerInfo.get().version);
    public static int latestVersion = CURRENT_VERSION; // NOSONAR -- this field is updated when a new version is detected
    private static boolean checking;

    public static void cleanup() throws IOException {
        Files.deleteIfExists(new File(UPDATE_RESTARTER_CN).toPath());
    }
    public static void checkForLatestVersion() {
        if(checking) return; checking = true;
        makeSureLocalDirExists();
        updateLatest();
        checking = false;
    }

    private static void makeSureLocalDirExists() {
        if(!Constants.LOCAL_DATA_DIR.exists()) Constants.LOCAL_DATA_DIR.mkdirs();
    }
    private static void updateLatest() {
        //noinspection StatementWithEmptyBody
        while (patchToNext()) ;
        latestVersion = getLatestPatchedVersion();
        storeReleaseNotes();
    }

    private static void storeReleaseNotes() {
        final File patchedFile = new File(Constants.LOCAL_DATA_DIR, getPatchedName(latestVersion));
        if(patchedFile.exists()) {
            try {
                final String indexHtml = IOUtil.fileToString(new File(patchedFile.getAbsolutePath() + "!" + "BOOT-INF/classes/static/index.html"));
                final String rn = indexHtml.replaceAll("(?s)^.*id=\"release-notes\".*?/h2>(.*?)V" + CURRENT_VERSION + "\\w*/.*$", "$1");
                IOUtil.stringToFile(rn, new File(Constants.LOCAL_DATA_DIR, "release-notes.txt"));
            } catch (final Exception e) {
                Application.log("Unable to read from " + patchedFile.getName(), ":", e.getMessage());
            }
        }
    }
    public static String getLatestReleaseNotes() {
        final String notAvailable = "-- not available --";
        try {
            return Util.or(IOUtil.fileToString(new File(Constants.LOCAL_DATA_DIR, "release-notes.txt")), notAvailable);
        } catch(final IOException e) {
            return notAvailable;
        }
    }

    private static boolean patchToNext() {
        final int patchedVersion  = getLatestPatchedVersion();
        final String patchName    = getPatchName(patchedVersion, patchedVersion + 1);
        final File dataDir        = Constants.LOCAL_DATA_DIR;
        final boolean fromCurrent = patchedVersion == CURRENT_VERSION;

        // patch currentVersion to currentVersion+1 requires patch of current running jar file
        // patch newer version requires patch of existing patched file in data dir
        final File fromJar = fromCurrent
            ? Constants.JAR_FILE.getAbsoluteFile()
            : new File(dataDir, getPatchedName(patchedVersion))
            ;

        // Running from jar? Don't upgrade while in development env
        if(!fromJar.getName().endsWith(".jar")) {
            Application.log("Don't check for upgrade -- running from IDE");
            return false;
        }

        final File patchJar = new File(dataDir, patchName);
        final File toJar    = new File(dataDir, getPatchedName(patchedVersion + 1));

        // Most of the time, the patch to the next version does not exist
        // Most of the time, the download to the next version does not exist
        if(!patchJar.exists() && !downloadPatch(patchName)) return false;

        // sanity check -- files ok?
        if(  !fromJar.exists()
          || !patchJar.exists()
          ||  toJar.exists()) return false;

        try {
            Application.log("Patch from " + fromJar + " using " + patchJar.getName() + " to " + toJar.getName());
            new ZipPatcher(fromJar).patchTo(patchJar, toJar, /*ignoreValidation:*/true);
            IOUtil.deleteFile(patchJar);
            if(!fromCurrent) IOUtil.deleteFile(fromJar);
            return true;
        } catch(final IOException e) {
            Application.log("Unable to patch from " + fromJar + " using " + patchJar + " to " + toJar + ": " + e.getMessage());
            try {
                IOUtil.deleteFile(patchJar);
                IOUtil.deleteFile(toJar);
            } catch(final Exception ignored) {
                // too bad -- can't delete
            }
            return false;
        }
    }
    private static boolean downloadPatch(String patchName) {
        return new File(Constants.LOCAL_DATA_DIR, patchName).exists()
            || downloadFile(patchName);
    }
    private static boolean downloadFile(String name) {
        final File requestedFile = new File(Constants.LOCAL_DATA_DIR, name);
        final File downloadingFile = new File(Constants.LOCAL_DATA_DIR, name + ".downloading");

        if(requestedFile.exists()) {
            Application.log("No need to download, already exists:", name);
            return true;
        }
        try {
            final URL url = new URL(Constants.DOWNLOAD_PAGE_BASE_URL + name);
            IOUtil.toFile(url, downloadingFile);

            Application.log("Just downloaded " + name + " of size " + ZipUtil.sizeToString(downloadingFile.length()));

            return downloadingFile.renameTo(requestedFile);
        } catch (final Exception patchFileNotFound) {
            return false;
        } finally {
            IOUtil.deleteFile(downloadingFile);
        }
    }
    private static String getPatchName(int fromVersion, int toVersion) {
        return "v" + fromVersion + "to" + toVersion + ".zpatch";
    }
    private static String getPatchedName(int version) {
        return DASHBOARD_NAME + "-" + version + ".jar";
    }
    private static int getLatestPatchedVersion() {
        return Stream.of(Util.or(Constants.LOCAL_DATA_DIR.listFiles(), new File[0]))
            .map(File::getName)
            .filter(name -> name.matches("^" + DASHBOARD_NAME + "-\\d+\\.jar$"))
            .map(Upgrader::toIntVersion)
            .max(Integer::compare)
            .orElse(CURRENT_VERSION)
            ;
    }
    private static int toIntVersion(String versionText) {
        try {
            return Integer.parseInt(versionText.replaceAll("^.*\\D(\\d+)\\D*$", "$1"));
        } catch(final NumberFormatException e) {
            return 0;
        }
    }

    public static String upgrade() {
        if(latestVersion == CURRENT_VERSION) {
            return "nothing to upgrade to";
        }
        try {
            if(!Constants.JAR_FILE.getName().endsWith(".jar")) return "error: not running from jar";

            final File newJarFile = new File(Constants.LOCAL_DATA_DIR, getPatchedName(latestVersion));
            if(!newJarFile.exists()) return "error: no new jar file to upgrade to";
            final File tmpJarFile = new File(Constants.JAR_FILE.getAbsolutePath() + ".tmp");
            if(!newJarFile.renameTo(tmpJarFile)) { // UpdateRestarter will rename .tmp to original jar name
                return "error: unable to rename " + newJarFile.getName() + " to " + tmpJarFile.getName();
            }

            final File cmdFile = new File(CMD_FILENAME);
            IOUtil.stringToFile(String.join("\n", getStartCommand()), cmdFile);

            final File updateRestarterFile = new File(UPDATE_RESTARTER_CN);
            IOUtil.toFile(Upgrader.class.getResourceAsStream("/" + UPDATE_RESTARTER_CN), updateRestarterFile);

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    final ProcessBuilder pb = new ProcessBuilder(getUpdateRestarterCommand(Constants.JAR_FILE.getName()));
                    pb.inheritIO();
                    pb.start();
                } catch (final IOException e) {
                    e.printStackTrace(); // NOSONAR -- this is after System.exit()
                }
            }));

            Services.stopAll();
            Util.sleep(Duration.ofSeconds(3)); // give some time for all processes to die
            System.exit(0); // Calls shutdownHook that was just added

            return "OK"; // Won't get here: after System.exit()
        } catch(final RuntimeException | IOException e) {
            return "error: unable to upgrade: " + e.getMessage();
        }
    }

    private static String[] getUpdateRestarterCommand(String targetName) {
        return new String[] {
            System.getProperty("java.home") + "/bin/java",
            UPDATE_RESTARTER_NAME,
            targetName
        };
    }

    private static String[] getStartCommand() {
        final String       javaCommand = System.getProperty("java.home") + "/bin/java";
        final List<String> vmArguments = ManagementFactory.getRuntimeMXBean().getInputArguments();
        final String[]     mainCommand = System.getProperty("sun.java.command").split(" ");
        final List<String> command     = new ArrayList<>();

        command.add(javaCommand);
        command.addAll(vmArguments);
        command.add("-jar");
        command.addAll(Arrays.asList(mainCommand));

        return command.toArray(new String[0]);
    }
}
