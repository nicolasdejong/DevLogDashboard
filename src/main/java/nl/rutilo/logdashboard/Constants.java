package nl.rutilo.logdashboard;

import nl.rutilo.logdashboard.services.Services;
import nl.rutilo.logdashboard.util.IOUtil;
import nl.rutilo.logdashboard.util.StringUtil;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.time.format.DateTimeFormatter;

public class Constants {
    private Constants() {}

    public static final File   JAR_FILE =
        new File(Constants.class.getProtectionDomain().getCodeSource().getLocation().toString().replaceAll("^jar:", "").replaceAll("^file:|!.*$",""));

    public static final boolean IN_DEV_ENV                 = JAR_FILE.getName().endsWith("classes");
    public static final String APP_NAME                    = "dev-log-dashboard";
    public static final String APP_VERSION;
    public static final String APP_RELEASE_DATE;
    public static final long   APP_START_TIME              = System.currentTimeMillis();
    public static final int    DEFAULT_PORT                = 8099;
    public static final String DOWNLOAD_PAGE_BASE_URL      = "https://www.rutilo.nl/dld/";
    public static final File   LOCAL_DATA_DIR              = new File("." + (IN_DEV_ENV ? APP_NAME : JAR_FILE.getName().replaceAll("\\..+$","")));
    public static final File   DEV_STATIC_DIR              = new File("target/classes/static"); // in IntelliJ under 'Before Launch' *remove* 'build'
    public static final File   OUTPUT_LOG_FILE             = new File(LOCAL_DATA_DIR, APP_NAME + ".log");
    public static final long   OUTPUT_LOG_SIZE             = 1024L * 1024;
    public static final long   OUTPUT_LOG_MAX_SIZE_DEFAULT = 1024L * 1024 * 5;
    public static final long   END_ERROR_DEBOUNCE_MS       = 4000L;

    public static final String MSG_TOPIC_SCRIPTS_CHANGED      = "/topic/scripts-changed";
    public static final String MSG_TOPIC_SERVICES_RELOADED    = "/topic/services-reloaded";
    public static final String MSG_TOPIC_STATE_CHANGE         = "/topic/service-state-changes";
    public static final String MSG_TOPIC_LOG_VELOCITIES       = "/topic/service-log-velocities";
    public static final String MSG_TOPIC_PROCESS_OUTPUT       = "/topic/process-output";
    public static final String MSG_TOPIC_CLEAR_PROCESS_OUTPUT = "/topic/clear-process-output";
    public static final String MSG_TOPIC_LAST_STATE_HISTORY   = "/topic/last-state-history";
    public static final String MSG_TOPIC_PORT_CHANGED         = "/topic/port-changed";
    public static final String MSG_TOPIC_PORT_CAN_UPGRADE     = "/topic/upgrade-available";

    public static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("u-LL-dd HH:mm:ss.SSS");

    public static final int DEFAULT_DETECTION_INTERVAL_MS = 10_000;

    public static final Duration LV_DURATION   = Duration.ofMinutes(10); // logger velocity
    public static final Duration LV_RESOLUTION = Duration.ofSeconds(5);

    static {
        LOCAL_DATA_DIR.mkdirs();

        final String indexHtml = IOUtil.asString(Services.class.getResourceAsStream("/static/index.html"));
        final String[] vd = StringUtil.getStringPart(indexHtml, "(?s)id=\"release-notes\".*?V([^/]+/[\\w\\?]+).*?\n").orElse("?/?").split("/");

        APP_VERSION      = vd[0];
        APP_RELEASE_DATE = vd[1];
    }
}
