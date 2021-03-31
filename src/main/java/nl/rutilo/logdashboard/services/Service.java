package nl.rutilo.logdashboard.services;

import com.fasterxml.jackson.annotation.JsonIgnore;
import nl.rutilo.logdashboard.Configuration;
import nl.rutilo.logdashboard.Constants;
import nl.rutilo.logdashboard.util.StringUtil;
import nl.rutilo.logdashboard.util.Util;
import lombok.Data;

import java.io.File;
import java.lang.reflect.Modifier;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static nl.rutilo.logdashboard.services.Service.LocationType.*;

@Data
public class Service implements Comparable {
    public static Service defaults; // only for loading
    private static int serialIndex = 0;

    // Fields from yaml
    private String       name;
    private String       label;
    private int          sleep;
    private String       location;
    private String       java;
    private String       command;
    private String       dir;
    private String       logFile;
    private boolean      start;
    private String       startedPattern;
    private int          port;
    private String       params;
    private String       vmparams;
    private List<String> dependsOn;
    private String       group;
    private String       restartCmd;
    private String       restartDir;
    private String       pollInterval;
    private String       outputLogFile;
    private String       outputLogSize;
    private boolean      errToOut;
    private boolean      excludeFromStartAll;
    private List<String> logDeletes;
    private Map<String,String> jobs;

    public final int uid = serialIndex++;

    public enum LocationType { JAR, URL, PORT, LOG, EXE, NONE }

    // Extra fields for dashboard
    public ServiceState state;

    //
    private LocationType  locationType;
    @JsonIgnore private File          fileLocation; // location of jar being started, or null
    @JsonIgnore public  boolean       isGroupLead;
    @JsonIgnore public  ServiceRunner runner;
    @JsonIgnore public  ServiceLogger logger;

    public Service() {
        copyFromDefaults();

        state = new ServiceState(this);
        runner = new ServiceRunner(this);
        logger = new ServiceLogger(this);
    }

    private void copyFromDefaults() {
        if(defaults == null) return;
        // originally this was this.field = defaults.field
        // for all fields to copy, but often forgotten to
        // update when a new field was added. So automated
        // using reflection.
        try {
            Arrays.stream(getClass().getDeclaredFields())
                .filter(field -> {
                    final int mods = field.getModifiers();
                    return  Modifier.isPrivate(mods)
                        && !Modifier.isStatic(mods)
                        && !Modifier.isFinal(mods)
                        && !Modifier.isInterface(mods)
                        && !field.getName().matches("^(name|fileLocation)$"
                    );
                })
                .forEach(field -> {
                    try {
                        field.set(this, field.get(defaults));
                    } catch (final IllegalAccessException cause) {
                        throw new ServiceException("copyFromDefaults field " + field.getName() + " failed: " + cause.getMessage(), cause);
                    }
                });
        } catch(final Exception cause) {
            throw new ServiceException("copyFromDefaults failed: " + cause.getMessage(), cause);
        }
    }

    public void copyFromOld(Service old) {
        locationType = old.locationType;
        fileLocation = old.fileLocation;
        state  = old.state.setService(this);
        runner = old.runner.setService(this);
        logger = old.logger.setService(this);
        if(state.isRunning()) {
            port = old.port; // value was derived from log when service started (what if not?)
            if(!old.getOutputLogFile().equals(getOutputLogFile())
                || old.getOutputLogFileMaxBytes() != getOutputLogFileMaxBytes()) state.resetOutputLog();
        }
    }

    public String toString() {
        return "[Service name=" + name + "; state=" + state.getState() + "; isRunning=" + state.isRunning() +"]";
    }

    public Optional<File> getFileLocation() {
        return Optional.ofNullable(fileLocation);
    }
    public Optional<File> getLogFile() {
        if(logFile != null) return Optional.of(new File(logFile));
        if(getLocationType() == JAR) return Optional.of(new File(location));
        if(getLocationType() == LOG) {
            return Optional.of(dir == null ? new File(location) : new File(dir, location));
        }
        return Optional.empty();
    }
    public Optional<File> getOutputLogFile() {
        return outputLogFile == null ? Optional.empty() : Optional.of(new File(getDir().orElse(new File(".")), outputLogFile));
    }
    public long getOutputLogFileMaxBytes() {
        final long max = outputLogSize == null ? -1 : StringUtil.sizeToLong(outputLogSize);
        return max <= 0 ? Constants.OUTPUT_LOG_MAX_SIZE_DEFAULT : max;
    }
    public List<String> getDependsOn() { return dependsOn == null ? new ArrayList<>() : dependsOn; }

    public int getPollIntervalMs() {
        final String text = Util.or(pollInterval, ""+Constants.DEFAULT_DETECTION_INTERVAL_MS)
                              .replaceAll("[\\s_]","")
                              .toLowerCase(Locale.US);
        final Pattern pat = Pattern.compile("\\d+\\D+");
        final Matcher mat = pat.matcher(text);
        int ms = 0;

        while(mat.find()) {
            final String mulText = mat.group().replaceAll("\\d+","");
            final String valText = mat.group().replaceAll("\\D+","");
            int mul = 1;
          //if(mulText.matches("^(m|milli)(second)?s?$")) mul =                   1;
            if(mulText.matches("^s(ec(onds?)?)?$"))       mul =                1000;
            if(mulText.matches("^m(in(utes?)?)?$"))       mul =           60 * 1000;
            if(mulText.matches("^h(r|our)?s$"))           mul =      60 * 60 * 1000;
            if(mulText.matches("^d(ays?)?$"))             mul = 24 * 60 * 60 * 1000;

            try {
                ms += mul * Integer.parseInt(valText);
            } catch(final NumberFormatException ignored) {}
        }
        if(ms == 0) {
            try { ms = Integer.parseInt(text.replaceAll("\\D", "")); } catch(final NumberFormatException ignored) {}
        }
        return ms < 1000 ? 1000 : ms;
    }

    public String getCommand()  { return command  != null ? command  : getLocation(); }
    public String getLocation() { return location != null ? location : command;  }
    public Optional<File> getDir() {
        return Util.orSupplyOptional(
            () -> Optional.ofNullable(dir).map(dirName -> new File(dirName).isAbsolute() ? new File(dirName) : new File(Configuration.getRootDir(), dirName)).filter(File::isDirectory),
            () -> Util.whenThen(command != null && location != null, () -> Optional.of(location).map(File::new).filter(File::isDirectory)),
            () -> Util.whenThen(command == null && location != null, () -> getFileLocation().map(File::getParentFile)).filter(File::isDirectory)
        );
    }

    public LocationType getLocationType() {
        if(locationType == null) {
            final String loc = getLocation();
            locationType = loc == null ? NONE :
                           loc.matches("^https?://.*$")     ? URL :
                           loc.matches("^([^:]+:)?\\d+$")   ? PORT :
                           loc.matches(".*\\.jar$")         ? JAR :
                           loc.matches(".*\\.(log|te?xt)$") ? LOG :
                                                              EXE;
        }
        return locationType;
    }
    public boolean startedFromDashboard() { return getLocationType() == JAR; }

    /** Be aware that the given text is analyzed and may change service state */
    public void log(String outText)      {
        final String msg = logPrefix() + outText;
        System.out.println(msg);
        logger.log(msg);
    }
    public void logError(String outText) {
        final String msg = logPrefix() + outText;
        System.out.println(msg);
        logger.logError(msg);
    }
    /** 'Other' logger lines won't be analyzed so won't change service state */
    public void logOther(String outText) {
        final String msg = logPrefix() + outText;
        System.out.println(msg);
        logger.logOther(msg);
    }
    public String logPrefixTime() { return LocalDateTime.now().format(Constants.TIME_FORMATTER); }
    public String logPrefix() { return logPrefixTime() + "/" + getName() + "]  "; }

    @Override
    public int compareTo(Object o) {
        if(!(o instanceof Service)) return -1;
        return name.compareTo(((Service)o).name);
    }

    @Override
    public boolean equals(Object o) { return o instanceof Service && compareTo(o) == 0; }

    @Override
    public int hashCode() {
        return name.hashCode();
    }
}
