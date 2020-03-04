package nl.rutilo.logdashboard.util;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.*;
import java.util.function.Consumer;

public class Listeners<T> {
    @JsonIgnore private static final Map<Listeners<?>, Set<Object>> calls = new HashMap<>();
    @JsonIgnore private final  List<Consumer<T>>     listeners = new ArrayList<>();
    @JsonIgnore private        boolean               debounced = false;

    static {
        final Thread t = new Thread(Listeners::checkCalls);
        t.setDaemon(true);
        t.start();
    }

    public Listeners<T> debounced() { return debounced(true); }
    public Listeners<T> debounced(boolean set) { debounced = set; return this; }

    public void add(Consumer<T> listener) {
        synchronized (listeners) { listeners.add(listener); }
    }
    public void remove(Consumer<T> listener) {
        synchronized (listeners) { listeners.remove(listener); }
    }
    public void call(T param) {
        if (debounced) {
            synchronized(calls) {
                if (!calls.containsKey(this)) calls.put(this, new TreeSet<>());
                calls.get(this).add(param);
            }
        } else {
            doCall(param);
        }
    }
    private void doCall(Object param) {
        final List<Consumer<T>> copy;
        synchronized(listeners) { copy = new ArrayList<>(listeners); }
        //noinspection unchecked
        copy.forEach(l -> l.accept((T)param));
    }

    private static void checkCalls() {
        for(;;) {
            try { Thread.sleep(250); } catch(final InterruptedException ignored) { break; }

            if(!calls.isEmpty()) {
                final HashMap<Listeners<?>, Set<Object>> callsCopy;
                synchronized (calls) {
                    callsCopy = new HashMap<>();
                    calls.forEach((key, value) -> callsCopy.put(key, new TreeSet<Object>(value)));
                    calls.clear();
                }
                // call() may be called by the listener
                // to prevent ConcurrentModificationException,
                // do forEach() on copy.
                callsCopy.forEach((listeners, params) -> params.forEach(listeners::doCall));
            }
        }
    }
}
