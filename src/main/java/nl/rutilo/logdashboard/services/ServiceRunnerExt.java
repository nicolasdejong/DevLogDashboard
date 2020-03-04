package nl.rutilo.logdashboard.services;

import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.net.Socket;
import java.util.Optional;
import java.util.function.Consumer;

/** Running code is external to the dashboard -- this code only observes its state */
public class ServiceRunnerExt implements Runnable {
    private static final Object       waitObject = new Object();
    private       Service             service;
    private       boolean             running;
    private       boolean             needsInitialize;
    private final Consumer<Void>      cfgChangeConsumer = nil -> updateForNewConfiguration();
    private       Optional<ExtRunner> runner = Optional.empty();

    public ServiceRunnerExt(Service service) {
        this.service = service;
        needsInitialize = true;
    }
    public void setService(Service service) {
        this.service = service;
        runner.ifPresent(r -> r.setService(service));
    }

    public void run() {
        service.getState().reset();
        service.getState().setWaiting();
        running = true;

        Services.addConfigurationChangeListener(cfgChangeConsumer);

        while(running) {
            if(needsInitialize) {
                needsInitialize = false;
                initialize();
            }
            runner.ifPresent(ExtRunner::poll);
            pause();
        }

        Services.removeConfigurationChangeListener(cfgChangeConsumer);
        dispose();
    }
    public void stop() {
        running = false;
        awake();
    }

    private void initialize() {
        dispose();
        switch(service.getLocationType()) {
            case URL:  runner = Optional.of(new UrlRunner(service)); break;
            case PORT: runner = Optional.of(new PortRunner(service)); break;
            case LOG:  break; // ServiceRunnerLog uses location when it is of type LOG
            default:   runner = Optional.empty();
                service.logError("Unable to check: location configuration not supported: " + service.getLocation());
                service.logError("Supported locations are URLs (http[s]://...), PORTs ([host:]port) or LOGs (name.logger)");
                service.getState().setError();
        }
        if(runner.isPresent()) {
            service.logOther("Checking location: " + service.getLocation());
            if(service.getState().isError()) service.getState().setOff();
        }
    }
    private void dispose() {
        runner.ifPresent(ExtRunner::dispose);
        runner = Optional.empty();
    }
    private void updateForNewConfiguration() {
        needsInitialize = true;
        awake();
    }

    private void pause() {
        synchronized(waitObject) {
            try {
                final int ms = runner.flatMap(ExtRunner::pauseOverride).orElse(service.getPollIntervalMs());
                waitObject.wait(ms);
            } catch(final InterruptedException ignored) { stop(); }
        }
    }
    private void awake() {
      synchronized(waitObject) { waitObject.notifyAll(); }
    }
}

abstract class ExtRunner {
    private boolean restarting = false;
    protected Service service;

    ExtRunner(Service service) {
        setService(service);
    }
    public final void setService(Service service) {
        this.service = service;
    }
    void dispose() {}
    abstract void poll();
    Optional<Integer> pauseOverride() { return Optional.empty(); }
    void up() { handleDownState(false); }
    void down() { handleDownState(true); }
    private void handleDownState(boolean isDown) {
        if(service.getState().isWaiting()) {
            if(!isDown) {
                service.logOther("Service is up.");
                service.getState().setRunningImmediately();
            }
        }
        if(isDown != service.getState().isError() || isRestarting()) {
            if(isDown && isRestarting()) {
                // A non-error log-line may have set the service to non-error. It is still down though.
                service.getState().setError();
            } else
            if(isDown && !isRestarting()) {
                service.logOther("Service is down.");
                service.getState().setError();

                // Only grouplead should have power to restart, to prevent multiple restarts by the same group
                if(service.isGroupLead && !isRestarting()) {
                    Optional.ofNullable(service.getRestartCmd()).ifPresent(this::restart);
                }
            } else
            if(!isDown) {
                if(isRestarting()) {
                    service.logOther("Stopped waiting for restart.");
                    setRestarting(false);
                }
                service.logOther("Service is up.");
                service.getState().setRunningImmediately();
            }
        }
    }
    private void restart(String cmd) {
        final String target = service.getGroup() == null ? ": " + service.getName() : "group: " + service.getGroup();
        service.logOther("Attempting restart of service " + target + " -- " + cmd);
        setRestarting(true);
        new ServiceRunnerCmd(service.getRunner())
            .setName("restart " + target)
            .setCommand(cmd)
            .inDirectory(service.getRestartDir())
            .whenFinished(cr -> {
                service.logOther("Now waiting for service to come back up.");
            })
            .start();
    }
    protected boolean isRestarting() { return restarting; }
    private void setRestarting(boolean set) { restarting = set; }
}

class UrlRunner extends ExtRunner {
    private final RestTemplate restTemplate = new RestTemplate();
    UrlRunner(Service service) {
        super(service);

        ((org.springframework.http.client.SimpleClientHttpRequestFactory)
            restTemplate.getRequestFactory()).setReadTimeout(1000*10);

        ((org.springframework.http.client.SimpleClientHttpRequestFactory)
            restTemplate.getRequestFactory()).setConnectTimeout(1000*10);
    }
    @Override void poll() {
        final String loc = service.getLocation();
        try {
            restTemplate.getForObject(service.getLocation(), String.class);
            up();
        } catch(final RuntimeException unableToAccess) {
            down();
        }
    }
}
class PortRunner extends ExtRunner {
    PortRunner(Service service) { super(service); }
    @Override public void poll() {
        try {
            final String   loc    = service.getLocation().replaceFirst("^\\s*:\\s*", "");
            final String[] parts  = loc.contains(":") ? loc.split(":") : new String[] { "localhost", loc };
            final Socket   socket = new Socket(parts[0], Integer.parseInt(parts[1]));
            up();
            socket.close();
        } catch(final RuntimeException | IOException unableToAccess) {
            down();
        }
    }
}
