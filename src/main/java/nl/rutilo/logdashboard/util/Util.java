package nl.rutilo.logdashboard.util;

import nl.rutilo.logdashboard.LogDashboardException;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

public class Util {
    private Util() {}

    public static <T> T or(T nullable, T def) {
        if(def == null) throw new NullPointerException("def should not be null");
        return nullable == null ? def : nullable;
    }

    public static <T> void ifPresentOrElse(Optional<T> opt, Consumer<T> whenOpt, Runnable whenNotOpt) {
        if(opt.isPresent()) whenOpt.accept(opt.get()); else whenNotOpt.run();
    }

    public static <T> Optional<T> whenThen(boolean when, ThrowingSupplier<Optional<T>> optional) {
        return when ? optional.get() : Optional.empty();
    }

    @SafeVarargs
    public static <T> Optional<T> orSupplyOptional(ThrowingSupplier<Optional<T>>... optionals) {
        for(final Supplier<Optional<T>> or : optionals) {
            final Optional<T> result = or == null ? Optional.empty() : or.get();
            if(result != null && result.isPresent()) return result;
        }
        return Optional.empty();
    }

    @SafeVarargs
    public static <T> Optional<T> orSupplyNullable(ThrowingSupplier<T>... nullables) {
        for(final Supplier<T> or : nullables) {
            final T result = or == null ? null : or.get();
            if(result != null) return Optional.of(result);
        }
        return Optional.empty();
    }

    public static boolean getSystemPropertyBoolean(String name) {
        final String boolText = System.getProperty(name);
        return boolText != null && boolText.trim().equalsIgnoreCase("true");
    }

    public static void touch(File file) throws IOException {
        if(file.exists()) file.setLastModified(System.currentTimeMillis());
        else {
            file.getParentFile().mkdirs();
            new FileOutputStream(file).close();
        }
    }

    public static List<File> getAllFilesIn(File dir) {
        return getAllFilesIn(new ArrayList<>(), dir);
    }
    private static List<File> getAllFilesIn(List<File> files, File dirOrFile) {
        if (dirOrFile.isDirectory()) {
            //noinspection ConstantConditions -- listFiles() is not null for isDirectory()
            Arrays.stream(dirOrFile.listFiles()).forEach(file -> getAllFilesIn(files, file));
        } else {
            files.add(dirOrFile);
        }
        return files;
    }

    public static long lastModified(File dir) { return lastModified(dir, null); }
    public static long lastModified(File dir, Predicate<File> filter) {
        final Predicate<File> fileFilter = filter == null ? file -> true : filter;
        return getAllFilesIn(dir).stream().filter(fileFilter).map(File::lastModified).max(Long::compare).orElse(0L);
    }

    public static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch(final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LogDashboardException("Interrupted", e);
        }
    }
    public static void waitOn(Object obj, int timeoutMs) {
        //noinspection SynchronizationOnLocalVariableOrMethodParameter
        synchronized(obj) {
            try {
                obj.wait(timeoutMs); // NOSONAR -- util call
            } catch(final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LogDashboardException("Interrupted", e);
            }
        }
    }
    public static void debounce(Runnable r) { debounce(Duration.ofMillis(100), r); }
    public static void debounce(Duration delay, Runnable r) { Timer.start("" + r.hashCode(), delay, r); }

    private static String OS = System.getProperty("os.name").toLowerCase();
    public static final boolean IS_WINDOWS = OS.contains("win");
    public static final boolean IS_MAC     = OS.contains("mac");
    public static final boolean IS_UNIX    = OS.contains("nix") || OS.contains("nux") || OS.contains("aix") || IS_MAC;

    public static <T extends Throwable> Optional<T> getCauseOfType(T ex, Class<T> type) {
        if(ex == null) return Optional.empty();
        if(type.isAssignableFrom(ex.getClass())) return Optional.of(ex);
        //noinspection unchecked
        return getCauseOfType((T)ex.getCause(), type);
    }
    public static Throwable getRootCause(Throwable t) {
        final Throwable cause = t.getCause();
        return cause == null ? t : getRootCause(cause);
    }
}
