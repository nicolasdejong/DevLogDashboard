package nl.rutilo.logdashboard.util;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TimerTask;

public class Timer {
    private static       int                       nextTimerTaskId = Integer.MIN_VALUE;
    private static final java.util.Timer           timer           = new java.util.Timer("Util-Timer", /*daemon*/true);
    private static final Map<String, CallbackTask> timerTasks      = new HashMap<>();
    private static final class CallbackTask extends TimerTask {
        final String id;
        final Runnable callback;
        CallbackTask(String id, Runnable r) { this.id=id; callback=r; }
        @Override public void run() {
            clear(id);
            callback.run();
        }
    }

    public static boolean has(int id) { return has("" + id); }
    public static boolean has(String id) {
        synchronized(timer) {
            return timerTasks.containsKey(id);
        }
    }

    public static int start(Duration delay, Runnable callback) {
        synchronized(timer) {
            final int id = nextTimerTaskId++;
            start("" + id, delay, callback);
            return id;
        }
    }
    public static void start(String id, Duration delay, Runnable callback) {
        synchronized(timer) {
            clear(id);

            final CallbackTask task = new CallbackTask(id, callback);
            timerTasks.put(task.id, task);
            timer.schedule(task, delay.toMillis());
        }
    }

    public static void clear(int id) { clear("" + id); }
    public static void clear(String id) {
        synchronized(timer) {
            Optional.ofNullable(timerTasks.remove(id)).ifPresent(TimerTask::cancel);
        }
    }

    public static String createRandomId() {
        return "timer." + ("" + Math.random()).substring(2) + "." + System.currentTimeMillis();
    }

    private final String id;
    private Duration delay = null;
    private Runnable callback = null;

    public Timer()                      { id = createRandomId(); }
    public Timer set(Duration delay)    { this.delay = delay; return this; }
    public Timer set(Runnable callback) { this.callback = callback; return this; }
    public Timer start() {
        if(callback == null) throw new IllegalStateException("No callback set");
        if(delay == null)    throw new IllegalStateException("No delay set");
        start(id, delay, callback);
        return this;
    }
    public Timer stop() { clear(id); return this; }
}
