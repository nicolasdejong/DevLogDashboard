package nl.rutilo.logdashboard.services;

import nl.rutilo.logdashboard.Application;

import java.io.File;
import java.util.Optional;

public class ServicesConfigurationChecker {
    private Runnable       whenChanged = () -> {};
    private Optional<File> configFile      = ServicesLoader.getConfigurationFile();
    private long           configFileSize  = ServicesLoader.getConfigurationFile().map(File::length).orElse(-1L);
    private long           configFileTime  = ServicesLoader.getConfigurationFile().map(File::lastModified).orElse(-1L);

    public ServicesConfigurationChecker() {
        reset();
    }

    public ServicesConfigurationChecker whenChanged(Runnable runner) {
        whenChanged = runner;
        return this;
    }

    public void check() {
        try {
            if (isFileChanged() || isTimeChanged() || isSizeChanged()) {
                reset();
                whenChanged.run();
            }
        } catch(final Exception e) {
            e.printStackTrace();
        }
    }

    private void reset() {
        configFile      = ServicesLoader.getConfigurationFile();
        configFileSize  = configFile.map(File::length).orElse(-1L);
        configFileTime  = configFile.map(File::lastModified).orElse(-1L);
    }

    private boolean isFileChanged() { return !configFile.equals(ServicesLoader.getConfigurationFile()); }
    private boolean isTimeChanged() { return configFileTime != configFile.map(File::lastModified).orElse(-1L); }
    private boolean isSizeChanged() { return configFileSize != configFile.map(File::length).orElse(-1L); }
}
