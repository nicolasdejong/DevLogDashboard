package nl.rutilo.logdashboard.services;

import com.fasterxml.jackson.annotation.JsonIgnore;
import nl.rutilo.logdashboard.Constants;
import nl.rutilo.logdashboard.util.HistoricCount;
import nl.rutilo.logdashboard.util.LimitedSizeFile;
import nl.rutilo.logdashboard.util.Listeners;
import nl.rutilo.logdashboard.util.Timer;
import nl.rutilo.logdashboard.util.Util;
import lombok.Data;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import static nl.rutilo.logdashboard.services.Service.LocationType.JAR;
import static nl.rutilo.logdashboard.services.ServiceState.State.*;

@Data
public class ServiceState implements Comparable {
    @JsonIgnore private Service service;
    @JsonIgnore private final Listeners<ServiceState> listeners = new Listeners<ServiceState>().debounced();
    @JsonIgnore private final HistoricCount           logHistoricCount = HistoricCount.ofDuration(Constants.LV_DURATION).andResolution(Constants.LV_RESOLUTION);
    @JsonIgnore private final Object                  outputSync = new Object();

    private State           state = OFF;
    private long            timeLastStateChange;
    private long            timeStarted;
    private long            timeLastError;
    private long            timeSinceRunningOk;
    private int             logVelocity;
    private String          lastError;
    private LimitedSizeFile outputFile;
    private boolean         startPatternHasError;
    private boolean         aboutToStart;

    private static Pattern portPattern = Pattern.compile("(?:started on|initialized with|updating) port[s():]*(?: to)? (\\d+)", Pattern.CASE_INSENSITIVE);
    private static Pattern defaultStartedPattern = Pattern.compile("(?i)^.*(Started .*? in \\d|Hello from).*$");
    private        Pattern serviceStartedPattern = null;
    private        String  serviceStartedPatternText = null;


    @Override public int compareTo(Object obj) {
        return !(obj instanceof ServiceState) ? -1 : state.compareTo(((ServiceState)obj).state);
    }
    @Override public boolean equals(Object obj) {
        return (obj instanceof ServiceState)
            && ((ServiceState)obj).service == service;
    }
    @Override public int hashCode() {
        return Objects.hash(service.getName(), state);
    }

    public enum State { OFF, WAITING, STARTING, RUNNING, INIT_ERROR, EXIT_ERROR, RUNNING_ERROR }

    public ServiceState(Service service) {
        this.service = service;
    }
    public ServiceState setService(Service service) { this.service = service; return this; }
    public Service getService() { return service; }

    public String toString() {
        return "[ServiceState " + state + " name=" + service.getName() + "]";
    }

    public void addChangeListener(Consumer<ServiceState> listener) { listeners.add(listener); }
    public void removeChangeListener(Consumer<ServiceState> listener) { listeners.remove(listener); }
    private void callChangeListeners() { listeners.call(this); }

    public boolean isWaiting()   { return state == WAITING; }
    public boolean isStarting()  { return state == STARTING; }
    public boolean isError()     { return state == INIT_ERROR || state == RUNNING_ERROR; }
    public boolean isInitError() { return state == INIT_ERROR; }
    public boolean isExitError() { return state == EXIT_ERROR; }
    public boolean isOff()       { return state == OFF; }
    public boolean isRunning()   { return state == STARTING || state == RUNNING || state == RUNNING_ERROR; }
    public boolean isRunningOk() { return isRunning() && !isError(); }
    public long runningOkAgo()   { return System.currentTimeMillis() - timeSinceRunningOk; }

    public void aboutToStart() {
        if(aboutToStart) return; // in case any of the below functions will log, which will lead to an aboutToStart() call
        aboutToStart = true;
        reset();
        initOutputLog();
        setStarting();
        aboutToStart = false;
    }
    public void reset() {
        timeStarted = System.currentTimeMillis();
        logHistoricCount.reset();
        logVelocity = 0;
        closeOutputLog();
    }
    public void stopped() {
        setState(OFF);
        closeOutputLog();
    }
    public void setFailed(String error) {
        service.logger.logError(error);
        setState( (state == STARTING ? INIT_ERROR : (isRunning() ? RUNNING_ERROR : INIT_ERROR)) );
        closeOutputLog();
    }
    public void setInitFailed() { setState(INIT_ERROR); }

    public void resetState() {
        if(this.state == WAITING) setState(OFF);
    }

    private String getStateTimerId() {
        return service.getName() + "-state-timer";
    }

    private void setState(State newState) { setState(newState, /*noDebounce=*/false); }
    private void setState(State newState, boolean noDebounce) {
        final String stateTimerId = getStateTimerId();
        final long now = System.currentTimeMillis();
        final boolean justStarted = (timeStarted == 0 || now - timeStarted < 2000);

        // debounce change of error -> running for a few seconds because
        // error messages are often interlaced with normal messages
        // (which are interpreted as no-error).
        if (newState == RUNNING_ERROR && Timer.has(stateTimerId)) {
            Timer.clear(stateTimerId);
        }
        if (newState == RUNNING && state == RUNNING_ERROR && !noDebounce && !justStarted && now - timeLastError < Constants.END_ERROR_DEBOUNCE_MS) {
            Timer.start(stateTimerId, Duration.ofMillis(Constants.END_ERROR_DEBOUNCE_MS), () -> setState(newState, true));
            return;
        }
        if(state != newState) {
            Timer.clear(stateTimerId);
            state = newState;
            if(newState == RUNNING) timeSinceRunningOk = System.currentTimeMillis();
            if(newState == RUNNING_ERROR || newState == INIT_ERROR) timeLastError = System.currentTimeMillis();
            if(newState == STARTING) startPatternHasError = false;
            timeLastStateChange = System.currentTimeMillis();
            callChangeListeners();
        }
    }
    public void setOff()      { setState(OFF); }
    public void setStarting() { setState(STARTING); }
    public void setRunning()  { setState(RUNNING); }
    public void setRunningImmediately()  { setState(RUNNING, /*noDebounce=*/true); }
    public void setWaiting()  { setState(WAITING); }
    public void setError() {
        setState(isRunning() || service.getLocationType() != JAR ? RUNNING_ERROR : INIT_ERROR);
    }
    public void setExitError() { setState(EXIT_ERROR, /*noDebounce=*/true); }

    protected static Optional<String> getPortFromLine(String line) {
        final Matcher matcher = portPattern.matcher(line);
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    public void resetOutputLog() {
        synchronized(outputSync) {
            if(outputFile != null) Util.ifPresentOrElse(service.getOutputLogFile(), file -> {
                if(!outputFile.getFile().equals(file)) closeOutputLog();
            }, this::closeOutputLog);
            if(outputFile == null && service.getOutputLogFile().isPresent()) initOutputLog();
        }
    }
    private void initOutputLog() {
        closeOutputLog();
        service.getOutputLogFile().ifPresent(ofile -> {
            service.log("Output log file: " + ofile.getAbsolutePath());
            synchronized(outputSync) {
                outputFile = new LimitedSizeFile(ofile, service.getOutputLogFileMaxBytes());
            }
        });
    }
    private void closeOutputLog() {
        synchronized(outputSync) {
            if(outputFile != null) outputFile.close();
            outputFile = null;
        }
    }

    public void handleLine(boolean isError, String line) { handleLine(isError, line, /*replacesPrevious=*/false); }
    public void handleLine(boolean isError, String line, boolean replacesPrevious) {
        addToLogVelocity();
        if(outputFile != null && !replacesPrevious) {
            synchronized(outputSync) {
                outputFile.write((isError ? "!" : " ") + line + "\n");
            }
        }

        if (!isRunning() && state != WAITING && service.startedFromDashboard()) aboutToStart();
        // no else
        if(isError) {
            if(state != RUNNING_ERROR) lastError = line;
            setError();
        } else
        if (state == WAITING) {
            // do nothing
        } else
        if (state == STARTING) {
            getPortFromLine(line).ifPresent(port -> {
                try {
                    final int oldPort = service.getPort();
                    service.setPort(Integer.parseInt(port));
                    if (oldPort != service.getPort()) callChangeListeners();
                } catch (final NumberFormatException noNumber) {
                    // don't update port
                }
            });

            if(!Util.or(service.getStartedPattern(), "").equals(Util.or(serviceStartedPatternText,""))) {
                serviceStartedPatternText = service.getStartedPattern();
                serviceStartedPattern = null;
                if(serviceStartedPatternText != null) {
                    try {
                        serviceStartedPattern = Pattern.compile(service.getStartedPattern());
                    } catch (final PatternSyntaxException ex) {
                        if (!startPatternHasError) {
                            startPatternHasError = true;
                            service.logError("startPattern has error: " + ex.getMessage());
                        }
                    }
                }
            }
            if(serviceStartedPattern == null && defaultStartedPattern.matcher(line).matches()) setState(RUNNING);
            if(serviceStartedPattern != null && serviceStartedPattern.matcher(line).matches()) setState(RUNNING);
        } else {
            if (line.matches("^(PROCESS FINISHED)")) setState(OFF);
            else
            if (line.startsWith("\tat ")) setError(); // stack trace
            else
                setState(RUNNING); // business as usual
        }
    }

    private void addToLogVelocity() {
        if(isRunning()) logHistoricCount.add();
        updateLogVelocity();
    }
    public int updateLogVelocity() {
        logVelocity = logHistoricCount.get();
        return logVelocity;
    }
}
