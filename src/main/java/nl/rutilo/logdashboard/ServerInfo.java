package nl.rutilo.logdashboard;

import nl.rutilo.logdashboard.services.ServicesLoader;
import nl.rutilo.logdashboard.util.ManifestUtil;

import java.util.Optional;

public class ServerInfo {
    public static final ServerInfo instance;
    public final String version;
    public final String buildTime;
    public final String introText;
    public final String cfgError;
    public final boolean upgrading;

    static {
        instance = new ServerInfo();
    }
    public static ServerInfo get() {
        return instance;
    }

    private ServerInfo() {
        final Optional<String> dt = ManifestUtil.getManifestDateTimeText();
        version = ManifestUtil.getVersion();
        buildTime = dt.orElse("" + Constants.APP_START_TIME).replaceAll("T\\d.*$","");
        introText = String.join(" ",
            "LogDashboard",
            "V" + version,
            "(" + dt.orElse("dev") + ")",
            "by Nicolas de Jong",
            ""
        );
        cfgError = ServicesLoader.cfgError;
        upgrading = WebController.isUpgrading();
    }

    public String getTitleBase() {
        return introText.split("by ")[0];
    }
}
