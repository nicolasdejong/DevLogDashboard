package nl.rutilo.logdashboard.services;

import nl.rutilo.logdashboard.Configuration;
import nl.rutilo.logdashboard.util.StringUtil;
import nl.rutilo.logdashboard.util.Util;

import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static nl.rutilo.logdashboard.services.Service.LocationType.*;

public class ServiceRunner {
    protected            Service                    service;
    private              Thread                     extLoopThread;
    private              Thread                     logLoopThread;
    private              Optional<ServiceRunnerCmd> runnerCmd  = Optional.empty();
    private              Optional<ServiceRunnerExt> runnerExt  = Optional.empty();
    private              Optional<ServiceRunnerLog> runnerLog  = Optional.empty();

    public ServiceRunner(Service service) {
        this.service = service;
    }

    public ServiceRunner setService(Service newService) {
        this.service = newService;
        runnerExt.ifPresent(r -> r.setService(newService));
        runnerLog.ifPresent(r -> r.setService(newService));
        return this;
    }

    public boolean canBeStarted() {
        switch(service.getLocationType()) {
            case NONE: return false;
            case EXE:  return !runnerCmd.isPresent();
            case JAR:  return !runnerCmd.isPresent();
            case LOG:  return !runnerLog.isPresent();
            case URL:
            case PORT: return service.getLogFile().map(lf -> runnerLog).isPresent() && !runnerExt.isPresent();
        }
        return false;
    }

    public void start() {
        switch(service.getLocationType()) {
         case NONE: return;
         case EXE:  if(!runnerCmd.isPresent()) runExe(); break;
         case JAR:  if(!runnerCmd.isPresent()) runJar(); break;
         case LOG:  if(!runnerLog.isPresent()) runLogCheckLoop(); break;
         case URL:
         case PORT: service.getLogFile().map(lf -> runnerLog).ifPresent(cmd -> runLogCheckLoop());
                    if(!runnerExt.isPresent()) runExternalCheckLoop();
                    break;
        }
    }
    public void start(String job) {
        runExe(job);
    }

    public void addInitialExternalMessage() {
        service.logger.logOther("This service is not running in the dashboard so can not be controlled by it. Running state display only.");
        if(service.getRestartCmd() != null) service.logger.logOther("Watchdog mode: service will be restarted when down.");
    }

    public File getRunDir() {
        return Util.orSupplyOptional(
            () -> service.getDir(),
            () -> Optional.ofNullable(service.getRestartDir()).map(File::new),
            () -> service.getFileLocation()
        ).orElseGet(Configuration::getRootDir);
    }

    private void runExe() {
        runExe(service.getCommand());
    }
    private void runExe(String command) {
        service.logger.clear();
        if(service.getCommand() == null) { // this should not happen, as this is checked for in service
            service.logger.error("No command configured for this service");
            return;
        }
        service.logger.logOther("Starting command:");
        final List<String> cmd = new ArrayList<>();
        if(Util.IS_WINDOWS) {
            cmd.add("cmd");
            cmd.add("/c");
        } else {
            cmd.add("sh");
            cmd.add("-c");
        }
        cmd.add(command);
        runCommand(cmd);
    }

    private void runJar() {
        final File fileLocation;
        final String jarName;
        if (service.getFileLocation().isPresent()) {
            fileLocation = service.getFileLocation().orElseGet(() -> new File(""));
            final String fileLocPath = fileLocation.getAbsolutePath();
            final String runDirPath = getRunDir().getAbsolutePath();
            if(!fileLocPath.startsWith(runDirPath)) {
                service.logger.error("Unexpected path difference:");
                service.logger.error("runDir: " + runDirPath);
                service.logger.error("jarLoc: " + fileLocPath);
                return;
            }
            jarName = fileLocPath.substring(runDirPath.length()).replaceFirst("^[\\\\/]+", "");
        } else {
            service.logger.error("unknown service jar for " + service.getName());
            service.logger.error("jar expected in " + Configuration.getRootDir().getAbsolutePath());
            service.logger.error("Start with -root <root of jars directory> to set jars dir");
            return;
        }

        service.logger.clear();
        service.logger.logOther("Starting jar in dir " + getRunDir());

        final String vmParamsText  = StringUtil.replaceVariable(service, Util.or(service.getVmparams(), ""));
        final String jarParamsText = StringUtil.replaceVariable(service, Util.or(service.getParams(), ""));

        StringUtil.getStringParts(vmParamsText, "\\$\\{([^{}]+)\\}").forEach(s -> service.logger.logOther("[WARNING] Unknown variable: " + s));
        StringUtil.getStringParts(jarParamsText, "\\$\\{([^{}]+)\\}").forEach(s -> service.logger.logOther("[WARNING] Unknown variable: " + s));

        final List<String> vmParams  = Arrays.asList(StringUtil.splitCommandLine(vmParamsText));
        final List<String> jarParams = Arrays.asList(StringUtil.splitCommandLine(jarParamsText));

        final List<String> cmd = new ArrayList<>();
        cmd.add(Optional.ofNullable(service.getJava()).orElse("java"));
        cmd.addAll(vmParams.stream().map(s -> s.replace("\"","")).collect(Collectors.toList()));
        cmd.add("-jar");
        cmd.add(jarName);
        cmd.addAll(jarParams);

        runCommand(cmd);
    }

    private void runCommand(List<String> cmd) {
        service.logger.logOther(cmd.get(0).matches("cmd|sh") ? cmd.get(2) : String.join(" ", cmd));
        final boolean[] stopped = { false };

        service.getState().aboutToStart();
        new ServiceRunnerCmd(this)
            .setName(service.getName())
            .setCommand(cmd)
            .inDirectory(getRunDir())
            .whenStarted(rcmd -> runnerCmd = Optional.of(rcmd))
            .whenStopped(rcmd -> stopped[0] = true)
            .whenUnableToStart(ioException -> {
                service.logger.logOther("FAILED TO START");
                service.getState().setInitFailed();
            })
            .whenFinished(serviceRunnerCmd -> {
                Util.sleep(Duration.ofMillis(100)); // in case output is being flushed -- no guarantees
                service.logger.logOther("PROCESS FINISHED");
                if(stopped[0]) {
                    service.getState().stopped();
                } else if(serviceRunnerCmd.failedToStart()) {
                    service.getState().setInitFailed();
                } else if(serviceRunnerCmd.exitCode().orElse(0) != 0) {
                    service.logger.logOther("Exit code: " + serviceRunnerCmd.exitCode().orElse(0));
                    service.getState().setExitError();
                } else {
                    service.getState().stopped();
                }
                runnerCmd = Optional.empty();
            })
            .start();
    }
    private void runExternalCheckLoop() {
        if(extLoopThread != null && extLoopThread.isAlive()) return;
        addInitialExternalMessage();
        service.getState().reset();
        service.getState().setWaiting();

        extLoopThread = new Thread((runnerExt = Optional.of(new ServiceRunnerExt(service))).get());
        extLoopThread.setDaemon(true);
        extLoopThread.start();
    }
    private void runLogCheckLoop() {
        if(logLoopThread != null && logLoopThread.isAlive()) return;
        if(service.getLocationType() == LOG) { // location can be logger, OR logFile can be set, OR BOTH. Location sets state.
            service.getState().reset();
            service.getState().setWaiting();
        }
        logLoopThread = new Thread((runnerLog = Optional.of(new ServiceRunnerLog(service))).get());
        logLoopThread.setDaemon(true);
        logLoopThread.start();
    }
    public void stop() {
        runnerCmd.ifPresent(ServiceRunnerCmd::stop);
        runnerCmd = Optional.empty();
        runnerExt.ifPresent(ServiceRunnerExt::stop);
        runnerExt = Optional.empty();
        runnerLog.ifPresent(ServiceRunnerLog::stop);
        runnerLog = Optional.empty();
    }
}
