package nl.rutilo.logdashboard;

import nl.rutilo.logdashboard.services.ServicesLoader;
import nl.rutilo.logdashboard.util.ManifestUtil;

import java.util.Optional;

public class ServerInfo {
    public static final ServerInfo instance;
    public final String version;
    public final String buildTime;
    public final String introText;
    public       String cfgError; // may change when user updates services yaml
    public       boolean upgrading; // may change when upgrading starts

    static {
        instance = new ServerInfo();
    }
    public static ServerInfo get() {
        instance.cfgError = ServicesLoader.cfgError;
        instance.upgrading = WebController.isUpgrading();
        return instance;
    }

    private ServerInfo() {
        final Optional<String> dt = ManifestUtil.getManifestDateTimeText();
        version = Constants.APP_VERSION;
        buildTime = dt.orElse("" + Constants.APP_RELEASE_DATE).replaceAll("T\\d.*$","");
        introText = String.join(" ",
            "LogDashboard",
            "V" + Constants.APP_VERSION,
            "(" + Constants.APP_RELEASE_DATE + ")",
            "by Nicolas de Jong",
            ""
        );
    }

    public String getTitleBase() {
        return introText.split("by ")[0];
    }
}
