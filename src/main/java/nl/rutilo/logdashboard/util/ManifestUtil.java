package nl.rutilo.logdashboard.util;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ManifestUtil {
    private ManifestUtil() {}
    public static final boolean RUNNING_FROM_JAR = ManifestUtil.class.getResource("ManifestUtil.class").getPath().contains(".jar!/");

    public static Optional<URL> getUrl() {
        if(!RUNNING_FROM_JAR) return Optional.empty();
        try {
            return Collections
                .list(ManifestUtil.class.getClassLoader().getResources("META-INF/MANIFEST.MF"))
                .stream()
                .filter(url -> url.toString().contains("dashboard"))
                .findAny();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    public static Optional<Manifest> getManifest() {
	    try {
            return getUrl().map(url -> {
                final Manifest mf = new Manifest();
                try {
                    mf.read(url.openStream());
                    return mf;
                } catch (IOException e) {
                    return null;
                }
            });
        } catch(final Exception ignore) {
            return Optional.empty();
        }
    }

    public static Optional<Long> getManifestLastModified() {
        return getUrl().map(url -> {
            try {
                final URLConnection conn = url.openConnection();
                final long lm = conn.getLastModified();
                conn.getInputStream().close();
                return lm;
            } catch (final Exception ignore) {
                return null;
            }
        });
    }

    public static Optional<String> getManifestDateTimeText() {
        return getManifestLastModified().map(lastModified -> {
            final ZonedDateTime zdt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(lastModified), ZoneId.systemDefault());
            return zdt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        });
    }

	public static String getVersion() {
		return getManifest().map(manifest -> {
            final Attributes attrs = manifest.getMainAttributes();

            return Util.or(attrs.getValue("Implementation-Version"), "unknown");
        }).orElseGet(() -> {
            try {
                final String  pom = IOUtil.fileToString(new File("pom.xml"));
                final Matcher matcher = Pattern.compile("<version>([\\d.]+)</version>").matcher(pom);
                matcher.find();
                return matcher.group(1);
            } catch (final IOException e) {
                return "<dev>";
            }
        });
    }
}
