package nl.rutilo.logdashboard.util;

@FunctionalInterface
public interface ThrowingRunnable extends Runnable {

    @Override
    default void run() {
        try {
            runThrows();
        } catch(final Throwable t) {
            throw new RuntimeException(t);
        }
    }

    void runThrows() throws Throwable;
}
