package nl.rutilo.logdashboard;

import nl.rutilo.logdashboard.util.Timer;

import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import java.awt.*;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.function.Consumer;

public class TrayPopup {
    private static TrayIcon          trayIcon;
    private static JWindow           popup;
    private static Rectangle         bounds;
    private static Timer             hidePopupTimer = new Timer().set(Duration.ofSeconds(2));
    private static Timer             mouseOverTimer = new Timer().set(Duration.ofMillis(750));
    private static Consumer<JWindow> updateHandler = win -> {};
    private static boolean           mouseInsidePopup;


    public static void initializeFor(TrayIcon trayIcon, Consumer<JWindow> updateHandler) {
        TrayPopup.trayIcon = trayIcon;
        TrayPopup.updateHandler = updateHandler;
        hidePopupTimer.set(() -> { if(!mouseInTray() && !mouseInPopup()) hidePopup(); });
        resetBounds();
        handleMouseMotionPopupTrigger();
    }
    public static void checkPopupVisibility() {
        if(isPopup()) {
            mouseOverTimer.set(() -> {
                if(!mouseInTray() && !mouseInPopup()) {
                    hidePopup();
                }
            }).start();
        } else {
            mouseOverTimer.set(() -> {
                if(mouseInTray()) {
                    showPopup();
                    mouseOverTimer.start();
                }
            }).start();
        };
    }
    public static void update() {
        if(popup != null) updateHandler.accept(popup);
    }

    private static void resetBounds() {
        bounds = new Rectangle(new Point(Integer.MAX_VALUE, Integer.MAX_VALUE), trayIcon.getSize());
    }
    private static boolean mouseInTray() {
        //final Point ptr = MouseInfo.getPointerInfo().getLocation(); <-- sometimes npe
        return false; // this often incorrect --> bounds.contains(ptr);
    }
    private static boolean mouseInPopup() {
        if(!isPopup()) return false;
        final Point ptr = MouseInfo.getPointerInfo().getLocation();
        final Rectangle bounds = new Rectangle(popup.getLocationOnScreen(), popup.getSize());
        return bounds.contains(ptr);
    }

    private static void handleMouseMotionPopupTrigger() {
        trayIcon.addMouseMotionListener(new MouseAdapter() {
            private boolean first = true;

            @Override public void mouseMoved(MouseEvent e) {
                if(first) { first = false; return; } // for some reason a mousemove is triggered at tray initialization
                final int mx = e.getPoint().x;
                final int my = e.getPoint().y;

                // Tray mouse events don't have relative coordinates, so e.getPoint() gives screen coordinates
                // There is no (Java) way to get the tray location.
                // We only know that the e.getPoint() location is somewhere in the tray icon.
                // The location of the tray may change (e.g. by dragging tray icons in Windows)
                if(mx < bounds.x - bounds.width || mx > bounds.x + bounds.width || my < bounds.y - bounds.height || my > bounds.y + bounds.height) {
                    resetBounds(); // in case the tray icon has moved position
                }
                bounds.x = Math.min(bounds.x, mx);
                bounds.y = Math.min(bounds.y, my);

                checkPopupVisibility();
            }
        });
    }

    private static JWindow getPopup() {
        if(popup == null) createPopup();
        return popup;
    }
    private static void createPopup() {
        if(popup != null) hidePopup();
        popup = new JWindow();
        ((JComponent)popup.getContentPane()).setBorder(new CompoundBorder(
            new LineBorder(Color.LIGHT_GRAY),
            new EmptyBorder(2, 3, 2, 3)
        ));
        update();
        popup.setAlwaysOnTop(true);
        popup.toFront(); // in case the OS does not support always-on-top
        popup.setFocusable(true);
        popup.requestFocusInWindow();
        popup.addFocusListener(new FocusAdapter() {
            @Override public void focusLost(FocusEvent e) { hidePopup(); }
        });
        popup.addMouseMotionListener(new MouseMotionAdapter() { // entered / exited is unreliable
            @Override public void mouseMoved(MouseEvent e) { checkPopupVisibility(); }
        });
    }
    private static boolean isPopup() { return popup != null; }
    private static void showPopup() {
        if(!mouseInTray()) return;
        movePopupTo(getPopup(), new Point(bounds.x, bounds.y + bounds.height + 2));
    }
    private static void hidePopup() {
        if(popup != null) {
            popup.setVisible(false);
            popup = null;
            hidePopupTimer.stop();
        }
    }

    // Following based on
    // https://stackoverflow.com/questions/14371194/how-do-i-add-things-to-the-system-tray-and-add-mouseover-functionality
    public static void movePopupTo(JWindow popup, Point point) {
        final Rectangle bounds = getScreenViewableBounds(point);
        int x = point.x;
        int y = point.y;
        if (y < bounds.y) y = bounds.y;
        else if (y > bounds.y + bounds.height) y = bounds.y + bounds.height;

        if (x < bounds.x) x = bounds.x;
        else if (x > bounds.x + bounds.width) x = bounds.x + bounds.width;

        if (x + popup.getWidth() > bounds.x + bounds.width) {
            x = (bounds.x + bounds.width) - popup.getWidth();
        }
        if (y + popup.getWidth() > bounds.y + bounds.height) {
            y = (bounds.y + bounds.height) - popup.getHeight();
        }
        popup.setLocation(x, y);
        popup.setVisible(true);
    }

    private static GraphicsDevice getGraphicsDeviceAt(Point pos) {
        GraphicsDevice device = null;
        final GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        final GraphicsDevice lstGDs[] = ge.getScreenDevices();

        final ArrayList<GraphicsDevice> lstDevices = new ArrayList<GraphicsDevice>(lstGDs.length);

        for (GraphicsDevice gd : lstGDs) {
            GraphicsConfiguration gc = gd.getDefaultConfiguration();
            Rectangle screenBounds = gc.getBounds();

            if (screenBounds.contains(pos)) lstDevices.add(gd);
        }

        if (lstDevices.size() == 1) device = lstDevices.get(0);
        return device;
    }
    private static Rectangle getScreenViewableBounds(Point p) {
        return getScreenViewableBounds(getGraphicsDeviceAt(p));
    }
    private static Rectangle getScreenViewableBounds(GraphicsDevice gd) {
        Rectangle bounds = new Rectangle(0, 0, 0, 0);

        if (gd != null) {
            final GraphicsConfiguration gc = gd.getDefaultConfiguration();
            bounds = gc.getBounds();

            final Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(gc);

            bounds.x += insets.left;
            bounds.y += insets.top;
            bounds.width -= (insets.left + insets.right);
            bounds.height -= (insets.top + insets.bottom);
        }

        return bounds;
    }
}
