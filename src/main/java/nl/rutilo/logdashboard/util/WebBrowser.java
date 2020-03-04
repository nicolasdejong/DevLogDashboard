package nl.rutilo.logdashboard.util;

import java.awt.*;
import java.net.URL;
import java.util.Arrays;

// Fallback copied and slightly adapted from https://www.mkyong.com/java/open-browser-in-java-windows-or-linux/
public class WebBrowser {
    public static void openUrlInBrowser(String url) {
        try {
            Desktop.getDesktop().browse(new URL(url).toURI());
            return;
        } catch (Exception ignored) {
            // continue on and try again
        }

        final String  os  = System.getProperty("os.name").toLowerCase();
        final Runtime rt  = Runtime.getRuntime();
        try {
            if (os.contains("win")) {

                // this doesn't support showing urls in the form of "page.html#nameLink"
                rt.exec("rundll32 url.dll,FileProtocolHandler " + url);

            } else if (os.contains("mac")) {

                rt.exec("open " + url);

            } else if (os.contains("nix") || os.contains("nux")) {

                // Do a best guess on unix until we get a platform independent way
                // Build a list of browsers to try, in this order.
                final String[] browsers = {
                    "epiphany", "firefox", "mozilla", "chrome", "konqueror",
                    "netscape", "opera", "links", "lynx"
                };

                // Build a command string which looks like "browser1 "url" || browser2 "url" ||..."
                final String cmd = Arrays.stream(browsers)
                    .map(b -> b + " \"" + url + "\"")
                    .reduce((a, b) -> a + " || " + b)
                    .get();

                rt.exec(new String[]{"sh", "-c", cmd});
            }
        } catch (Exception ignored) {
            // don't care
        }
    }
}
