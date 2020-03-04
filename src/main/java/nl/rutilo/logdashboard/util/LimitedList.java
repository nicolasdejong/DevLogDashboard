package nl.rutilo.logdashboard.util;

import java.time.Duration;
import java.util.Collection;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.IntPredicate;

/** List where its items have a limited lifetime, depending on configured max item count and max lifetime.
  * This class is thread safe.
  */
public class LimitedList<T> extends EnvelopeList<T> {
    private static final int LIMIT_PAUSE_SECONDS = 10;
    private static final ScheduledThreadPoolExecutor threadPool = new ScheduledThreadPoolExecutor(1);
    private static final class LLItem extends Envelope {
        long timestamp;
        long size;
        public LLItem(Object entry, long size) {
            super(entry);
            this.size = size;
            timestamp = System.currentTimeMillis();
        }
        @Override public boolean equals(Object o) { return o instanceof LLItem && super.equals(o); }
        @Override public int hashCode() { return super.hashCode(); }
    }

    private int     maxCount = -1;
    private long    maxMs = -1;
    private long    maxSize = -1;
    private boolean isSortedOnTime = true;
    private ScheduledFuture<?> limitFuture;

    public LimitedList() { super(); }
    public LimitedList<T> setMaxCount(int mc) { this.maxCount = mc; pruneOnCount(); return this; }
    public LimitedList<T> clearMaxCount() { this.maxCount = -1; return this; }
    public LimitedList<T> setMaxAge(Duration d) {
        if(limitFuture != null) limitFuture.cancel(false);
        this.maxMs = d.toMillis();
        limitFuture = threadPool.scheduleAtFixedRate(this::pruneOnAge, 0, LIMIT_PAUSE_SECONDS, TimeUnit.SECONDS);
        return this;
    }
    public LimitedList<T> clearMaxAge() {
        this.maxMs = -1;
        return this;
    }

    /** Sort on timestamp */
    public void sort() {
        if(isSortedOnTime) return;
        sort(null);
        isSortedOnTime = true;
    }

    protected long sizeOf(Object value) { return 0; } // for overloading
    @Override protected Envelope newEnvelopeFor(Object value) {
        return new LLItem(value, sizeOf(value));
    }
    @Override public boolean add(T t) {
        final boolean result = super.add(t);
        pruneOnCount();
        pruneOnSize();
        return result;
    }
    @Override public boolean addAll(Collection<? extends T> c) {
        final boolean result = super.addAll(c);
        prune();
        return result;
    }
    @Override public boolean addAll(int index, Collection<? extends T> c) {
        if(!c.isEmpty() && index < size() - 1) isSortedOnTime = false;
        final boolean result = super.addAll(index, c);
        prune();
        return result;
    }
    @Override public T set(int index, T element) {
        if(index < size() - 1) isSortedOnTime = false;
        return super.set(index, element);
    }

    protected void resetAges() {
        final long now = System.currentTimeMillis();
        for(int index=0; index<size(); index++) ((LLItem)getEnvelopeAt(index)).timestamp = now;
    }

    public void prune() {
        pruneOnCount();
        pruneOnAge();
    }
    protected void pruneOnCount() {
        if(maxCount < 0) return;
        while(size() > maxCount) removeFirst();
    }
    protected void pruneOnSize() {
        if(maxSize < 0) return;
        // TODO
    }
    protected void pruneOnAge() {
        if(maxMs < 0) return;
        final long now = System.currentTimeMillis();
        final long earliest = now - maxMs;
        final IntPredicate isTooOld = index -> ((LLItem)getEnvelopeAt(index)).timestamp < earliest;

        while( !isEmpty() && isTooOld.test(0) ) removeFirst();
        if(!isSortedOnTime) {
            for(int i=0; i<size(); i++) if(isTooOld.test(i)) remove(i--);
        }
    }
}