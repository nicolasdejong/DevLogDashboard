package nl.rutilo.logdashboard.services;

import nl.rutilo.logdashboard.Configuration;
import nl.rutilo.logdashboard.util.LineStreamHandler;
import nl.rutilo.logdashboard.util.Util;
import org.apache.tools.ant.types.Commandline;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

class ServiceRunnerCmd {
    private final ServiceRunner              runner;
    private       String                     name;
    private       ProcessBuilder             processBuilder;
    private       Consumer<ServiceRunnerCmd> whenFinished = cmd -> {};
    private       Consumer<ServiceRunnerCmd> whenStarted = cmd -> {};
    private       Consumer<ServiceRunnerCmd> whenStopped = cmd -> {};
    private       Optional<File>             directory = Optional.empty();
    private       Process                    process;
    private       Consumer<IOException>      onError = e -> {};
    private       boolean                    failedToStart;
    private       Optional<Integer>          exitCode;

    public ServiceRunnerCmd(ServiceRunner runner) {
        this.runner = runner;
    }

    public ServiceRunnerCmd setName(String name) { this.name = name; return this; }
    public ServiceRunnerCmd setCommand(List<String> cmd) { processBuilder = new ProcessBuilder(cmd); return this; }
    public ServiceRunnerCmd setCommand(String cmd)    { processBuilder = new ProcessBuilder(Commandline.translateCommandline(cmd)); return this; }
    public ServiceRunnerCmd whenStarted(Consumer<ServiceRunnerCmd> started) { whenStarted = started; return this; }
    public ServiceRunnerCmd whenStopped(Consumer<ServiceRunnerCmd> stopped) { whenStopped = stopped; return this; }
    public ServiceRunnerCmd whenFinished(Consumer<ServiceRunnerCmd> finished) { whenFinished = finished; return this; }
    public ServiceRunnerCmd whenUnableToStart(Consumer<IOException> uts) { onError = uts; return this; }
    public ServiceRunnerCmd inDirectory(File directory) { this.directory = Optional.ofNullable(directory); return this; }
    public ServiceRunnerCmd inDirectory(String directory) { return (directory == null) ? this : inDirectory(new File(directory)); }
    public ServiceRunnerCmd start() {
        final Service service = runner.service;
        exitCode = Optional.empty();
        try {
            final File dir = directory.orElseGet(runner::getRunDir);
            processBuilder.directory(dir);

            process = processBuilder.start();
            new Thread(() -> { handleStreamLines(process.getInputStream(), /*isErrors=*/false); callWhenFinished(); }).start();
            new Thread(() -> { handleStreamLines(process.getErrorStream(), /*isErrors=*/true ); callWhenFinished(); }).start();
            whenStarted.accept(this);
        } catch (final IOException e) {
            service.getState().setFailed("Unable to start: " + e.getMessage());
            service.logger.error("Unable to start " + name + ": " + String.join(" ", processBuilder.command()));
            failedToStart = true;
            onError.accept(e);
            callWhenFinished();
        }
        return this;
    }
    private void handleStreamLines(InputStream in, boolean isErrors0) {
        final boolean isErrors = runner.service.isErrToOut() ? false : isErrors0;
        new LineStreamHandler(in).forEach(line -> runner.service.logger.handleLine(isErrors, line.text, line.replacesPreviousLine));
    }

    private void callWhenStopped() {
        whenStopped.accept(this);
    }
    private void callWhenFinished() {
        final Runnable finished = () -> {
            boolean callWhenFinished = false;
            synchronized(runner) {
                if(process != null && !process.isAlive() && !exitCode.isPresent()) {
                    exitCode = Optional.of(process.exitValue());
                    process = null; // prevent multiple whenFinished calls
                    callWhenFinished = true;
                }
            }
            if(callWhenFinished) whenFinished.accept(this); // outside sync
        };
        final Thread t = new Thread(() -> {
            final Process proc; synchronized(runner) { proc = process; }
            if(proc != null) {
                try { proc.waitFor(); } catch (final InterruptedException e) { /*interrupted*/ }
                finished.run();
            }
        }, "Waiting until command-runner-process is finished");
        t.setDaemon(true);
        t.start();
    }
    public void stop() {
        synchronized(runner) {
            if(process != null && process.isAlive()) {
                runner.service.logger.logOther("Stopping " + name);
                callWhenStopped();
                callWhenFinished();
                process.destroyForcibly();
//                new Thread(() -> {
//                    process.destroy();
//                    try { Thread.sleep(2500); } catch(final InterruptedException ignored) {}
//                    if(process.isAlive()) process.destroyForcibly();
//                });
            }
        }
    }
    public boolean failedToStart() { return failedToStart; }
    public Optional<Integer> exitCode() { return exitCode; }
}
