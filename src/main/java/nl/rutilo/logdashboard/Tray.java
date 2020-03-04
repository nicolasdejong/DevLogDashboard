package nl.rutilo.logdashboard;

import nl.rutilo.logdashboard.services.Service;
import nl.rutilo.logdashboard.services.ServiceState;
import nl.rutilo.logdashboard.services.Services;
import nl.rutilo.logdashboard.util.Timer;
import nl.rutilo.logdashboard.util.Util;
import nl.rutilo.logdashboard.util.WebBrowser;

import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.util.HashMap;
import java.util.List;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static java.awt.Image.SCALE_SMOOTH;
import static javax.swing.UIManager.*;

public class Tray {
    private static TrayIcon trayIcon;
    private static int port;

    public static void init() {
        if(!SystemTray.isSupported()) {
            Application.log("No tray icon added -- this system does not support it.");
            return;
        }
        setWindowsLookAndFeel();

        trayIcon = new TrayIcon(getTrayImage());
        trayIcon.setPopupMenu(getPopupMenu());
        trayIcon.addActionListener(action -> open());
        TrayPopup.initializeFor(trayIcon, Tray::updatePopup);
        addTrayIcon(trayIcon);

        Services.addStateChangeListener(state -> { updateTrayImage(); TrayPopup.update(); });
        Timer.start(Duration.ofSeconds(1), Tray::updateTrayImage);
    }

    public static void portChangedTo(int port) { Tray.port = port; TrayPopup.update(); }

    private static String getTitle() {
        return ServerInfo.get().getTitleBase() + (port != 0 ? " on port " + port : "");
    }

    private static String servicesStatesOverviewAsHtmlString() {
        final List<Service> services = Services.get();
        final StringBuilder html = new StringBuilder();
        html.append("<html>");
        html.append("States of the " + services.size() + " services:<br/>");
        for(final ServiceState.State state : ServiceState.State.values()) {
            final long count = services.stream().filter(s -> s.getState().getState() == state).count();
            if(count > 0) html.append("<li>" + count + " " + state.toString().replace("RUNNING_ERROR", "ERROR").toLowerCase(Locale.US) + "</li>");
        }
        html.append("</html>");
        return html.toString();
    }

    private static void setWindowsLookAndFeel() {
        try {
            setLookAndFeel("com.sun.java.swing.plaf.windows.WindowsLookAndFeel");
        } catch (final Exception ignored) {
            // don't care
        }
    }
    private static void addTrayIcon(TrayIcon trayIcon) {
        final SystemTray tray = SystemTray.getSystemTray();
        try {
            tray.add(trayIcon);
        } catch (AWTException e) {
            e.printStackTrace();
            Application.exitWithError("Unable to add tray icon.");
        }
    }

    private static PopupMenu getPopupMenu() {
        final PopupMenu popup = new PopupMenu();

        final MenuItem openItem = new MenuItem("Open");
        openItem.addActionListener(action -> open());
        popup.add(openItem);

        final MenuItem startItem = new MenuItem("Start all services");
        startItem.addActionListener(action -> Services.startAll());
        popup.add(startItem);

        final MenuItem stopItem = new MenuItem("Stop all services");
        stopItem.addActionListener(action -> Services.stopAll());
        popup.add(stopItem);

        popup.addSeparator();

        final MenuItem exitItem = new MenuItem("Exit");
        exitItem.addActionListener(action -> Application.exit());
        popup.add(exitItem);

        return popup;
    }
    private static void updateTrayImage() {
        trayIcon.setImage(getTrayImage());
    }
    private static JWindow oldTooltip;
    private static void updatePopup(JWindow popup) {
        final Map<Rectangle, Service> tiles = new HashMap<>();
        final JLabel                  title = new JLabel("<html>" + getTitle().replace(" V", "<br/>V").replace(" on port", "<br/>on port") + "</html>", JLabel.CENTER);
        final JLabel                  img   = new JLabel(new ImageIcon(getTrayImage(new Dimension(128, 128), tiles)));
        final JLabel                  info  = new JLabel(servicesStatesOverviewAsHtmlString());
        final JLabel            tooltipText = new JLabel();
        final JWindow               tooltip = new JWindow();
        ((JComponent)tooltip.getContentPane()).setBorder(new CompoundBorder(
            new LineBorder(Color.LIGHT_GRAY),
            new EmptyBorder(2, 3, 2, 3)
        ));
        tooltip.setLayout(new BorderLayout());
        tooltip.add(tooltipText);
        tooltip.pack();
        tooltip.setAlwaysOnTop(true);
        tooltip.toFront();

        if(oldTooltip != null) { oldTooltip.setVisible(false); } // in case of update while tooltip is visible
        oldTooltip = tooltip;

        popup.addComponentListener(new ComponentAdapter() {
            public void componentHidden(ComponentEvent e) { tooltip.setVisible(false); }
        });

        title.setBorder(new EmptyBorder(0, 0, 3, 0));
        popup.getContentPane().removeAll();
        popup.setLayout(new BoxLayout(popup.getContentPane(), BoxLayout.Y_AXIS));
        popup.add(title);
        popup.add(img);
        popup.add(info);
        popup.pack();
        img.addMouseMotionListener(new MouseMotionAdapter() {
            @Override public void mouseMoved(MouseEvent evt) {
                TrayPopup.checkPopupVisibility();
                final Optional<Service> serviceOpt = tiles.keySet().stream()
                    .filter(rect -> rect.contains(evt.getPoint()))
                    .findFirst()
                    .map(tiles::get);

                Util.ifPresentOrElse(serviceOpt, service -> {
                        final Point ref = img.getLocationOnScreen();
                        tooltip.toFront(); // on top of popup
                        tooltipText.setText(service.getName());
                        tooltip.pack();
                        TrayPopup.movePopupTo(tooltip, new Point(ref.x + evt.getPoint().x, ref.y + evt.getPoint().y + 20));
                        tooltip.setVisible(true);
                    }, () -> {
                    tooltip.setVisible(false);
                });
            }
        });
    }

    private static final Color COLOR_OK         = Color.GREEN; //new Color(150, 191, 72);
    private static final Color COLOR_STARTING   = new Color(0xFFFF32); // new Color(0x3e9bff);
    private static final Color COLOR_WAITING    = new Color(0xAAD9FF);
    private static final Color COLOR_ERROR      = Color.RED; //  new Color(191, 72, 72)
    private static final Color COLOR_INIT_ERROR = new Color(250, 80, 221);
    private static final Color COLOR_OFF        = new Color(0x777777);

    private static Image getTrayImage() { return getTrayImage(new HashMap<>()); }
    private static Image getTrayImage(Map<Rectangle, Service> tiles) { return getTrayImage(SystemTray.getSystemTray().getTrayIconSize(), tiles); }
    private static Image getTrayImage(Dimension imageSize, Map<Rectangle, Service> tiles) {
        final java.util.List<Service> services = Services.get();
        final int count = Math.max(1, services.size());
        final int xblocks = (int)Math.sqrt(count - 1) + 1;
        final int xoffset = imageSize.width / 32;
        final int yoffset = imageSize.height / 32;

        final boolean isScaled = imageSize.width / xblocks < 3; // 2px + 1px gap is minimal size that looks good
        final int w = isScaled ? 256 : imageSize.width;
        final int h = isScaled ? 256 : imageSize.height;
        final BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        final Graphics2D g = (Graphics2D)image.getGraphics();
        final double scaleX = (double)w / imageSize.width;
        final double scaleY = (double)h / imageSize.height;

        g.setColor(Color.BLACK);
        g.fillRect(0, 0, w, h);

        int blockWidth = (w - xoffset/2) / xblocks;
        int blockHeight= (h - yoffset/2) / xblocks;
        int gap = Math.max(1, w / 32); // small gap makes it look better
        blockWidth -= gap;
        blockHeight -= gap;

        tiles.clear();

        for(int i=0; i<services.size(); i++) {
            final int x = i%(w/(blockWidth+gap));
            final int y = i/(h/(blockHeight+gap));
            final Service service = services.get(i);
            final ServiceState state = service.getState();

            if(state.isInitError()) g.setColor(COLOR_INIT_ERROR); else
            if(state.isError())     g.setColor(COLOR_ERROR);      else
            if(state.isWaiting())   g.setColor(COLOR_WAITING);    else
            if(state.isStarting())  g.setColor(COLOR_STARTING);   else
            if(state.isOff())       g.setColor(COLOR_OFF);
            else                    g.setColor(COLOR_OK);

            final int rx = xoffset + x*(blockWidth+gap);
            final int ry = yoffset + y*(blockWidth+gap);

            g.fillRect(rx, ry, blockWidth, blockHeight);
            tiles.put(new Rectangle((int)(rx * scaleX), (int)(ry * scaleY), (int)(blockWidth * scaleX), (int)(blockHeight * scaleY)), service);
        }
        return isScaled
            ? image.getScaledInstance(imageSize.width, imageSize.height, SCALE_SMOOTH)
            : image;
    }

    private static void open() {
        WebBrowser.openUrlInBrowser("http://localhost:" + Application.getPort());
    }

}
