package nl.rutilo.logdashboard.services;

import nl.rutilo.logdashboard.util.Util;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Optional;

import static nl.rutilo.logdashboard.services.Service.LocationType.LOG;

public class ServiceRunnerLog implements Runnable {
    private static final Object              waitObject = new Object();
    private static final int                 PAUSE_MS   = 1000;
    private              Service             service;
    private              boolean             running;
    private              LogRunner           logRunner;

    public ServiceRunnerLog(Service service) {
        this.service = service;
    }
    public void setService(Service service) {
        this.service = service;
        if(logRunner != null) logRunner.setService(service);
    }

    public void run() {
        if(service.getLocationType() == LOG) {
            service.getState().reset();
            service.getState().setWaiting();
        }

        logRunner = new LogRunner(service);
        service.logger.logOther("Reading logger file: " + service.getLogFile());

        running = true;

        while(running) {
            logRunner.poll();
            pause();
        }
    }
    public void stop() {
        running = false;
        awake();
    }

    private void pause() {
        synchronized(waitObject) {
            try {
                waitObject.wait(PAUSE_MS);
            } catch(final InterruptedException ignored) { stop(); }
        }
    }
    private void awake() {
      synchronized(waitObject) { waitObject.notifyAll(); }
    }
}

class LogRunner {
    private static final long    MAX_BLOCK_SIZE = 100 * 1024;
    private static final String  EOL_REGEX      = "\\r\\n|\\r|\\n";
    private              Service service;
    private boolean              initializing   = true;
    private long                 previousLength = -1L;

    public LogRunner(Service service) {
        this.service = service;
        final Optional<RandomAccessFile> file = open();
        Util.ifPresentOrElse(file, f -> up(), this::down);
        file.ifPresent(LogRunner::close);
        initializing = false;
    }
    public void setService(Service service) {
        this.service = service;
    }

    void up() { handleDownState(false); }
    void down() { handleDownState(true); }
    private void handleDownState(boolean isDown) {
        if(service.getLocationType() != LOG) return;
        if(isDown != service.getState().isError() || initializing) {
            if(isDown) {
                service.logger.logError("Service is down.");
                service.getState().setError();
            } else {
                service.logger.logOther("Service is up.");
                service.getState().setRunning();
            }
        }
    }

    private File getFile() { return service.getLogFile().orElse(new File("!none:;")); }
    private Optional<RandomAccessFile> open() {
        try {
            return Optional.of(new RandomAccessFile(getFile(), "r"));
        } catch(final IOException e) {
            return Optional.empty();
        }
    }
    private static void close(RandomAccessFile file) {
        try { file.close(); } catch (IOException ignored) {}
    }

    void poll() {
        final long newLength = getFile().length();
        if(newLength < 0 || !getFile().exists()) return;
        if(newLength != previousLength) {
            if(previousLength < 0 || newLength < previousLength) {
                service.logger.clearBuffer();
                service.getRunner().addInitialExternalMessage();
                service.logger.logOther("Reading logger file: " + service.getLogFile().map(File::getAbsolutePath).orElse("-"));
                previousLength = load(0, newLength);
            } else {
                previousLength = load(previousLength, newLength);
            }
        }
    }
    private long load(long previousLength, long newLength) {
        return open().map(reader -> {
            final long               readSize  = Math.min(MAX_BLOCK_SIZE, newLength - previousLength);
            final long               seekPos   = readSize == MAX_BLOCK_SIZE ? newLength - readSize : previousLength;
            final Optional<String[]> readLines = readLinesFrom(reader, seekPos, readSize);
            close(reader);

            return readLines.map(lines -> {
                for(int i=0; i<lines.length-1; i++) service.logger.handleOutLine(lines[i]);
                return lines.length > 0 ? newLength - lines[lines.length-1].length() : 0L;
            }).orElse(previousLength);
        }).orElse(-1L);
    }
    private Optional<String[]> readLinesFrom(RandomAccessFile file, long readOffset, long readLength) {
        final byte[] buffer = new byte[(int)readLength];
        try {
            file.seek(readOffset);
            file.readFully(buffer);
            final String lastOfLog = new String(buffer, "UTF-8");
            return Optional.of(lastOfLog.split(EOL_REGEX, -1));
        } catch (IOException e) {
            e.printStackTrace();
            service.logger.handleErrorLine("Failure to read logger file: " + Util.or(e.getMessage(), e.getClass().getSimpleName()));
            return Optional.empty();
        }
    }
}