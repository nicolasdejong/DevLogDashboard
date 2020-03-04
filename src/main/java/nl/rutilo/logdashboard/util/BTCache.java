package nl.rutilo.logdashboard.util;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import static nl.rutilo.logdashboard.util.Util.waitOn;

/** Thread safe Building Timing Cache with AutoClosable support.<br><br>
  *
  * The cache is building because a builder function can be set. This means
  * get(key) can be called without any add(key, value) because the get(key)
  * will build the value. Doing a get(key) without a set(..) or builder
  * will result in a default (if set) or null value.<br><br>
  *
  * The cache is timing because a maximum time can be set for a value to
  * be valid. After that it will be rebuilt on a get() (when a builder is
  * set) or result in a default (if set) or null value.<br><br>
  *
  * Also a maximum count can be set, which will evict (remove) least recently
  * accessed (default evict type) or oldest items from the cache.<br><br>
  *
  * Also a maximum age can be set, which will evict older items.<br><br>
  *
  * AutoClosables will be closed on eviction. A dispose function can be set
  * to call when a value is evicted.<br><br>
  *
  * This implementation does not use threading. Lazy building and eviction
  * is used instead. This means the BTCache will build or evict only when
  * called (with a get(), set() or remove()).<br><br>
  *
  * TODO: Tests need to be added (converted from C# and completed)
  */
@SuppressWarnings("unused") // this is a utility class
public class BTCache<K,V> {
    private class CacheItem implements Comparator<CacheItem> {
        private final long timeAdded;
        private long timeLastAccessed;
        private long addedAgo() { return System.currentTimeMillis() - timeAdded; }
        private final V data;
        private CacheItem(V data) { this.data = data; timeAdded = System.currentTimeMillis(); }
        private CacheItem accessed() { this.timeLastAccessed = System.currentTimeMillis(); return this; }

        @Override
        public int compare(CacheItem a, CacheItem b) {
            return Long.compare(a.timeLastAccessed, b.timeLastAccessed);
        }
    }
    @SuppressWarnings("UnusedReturnValue") // keep return values consistent
    public static class ObjectCounter<T> {
        private final Map<T,Integer> counts = new HashMap<>();
        public int of(T obj) {
            synchronized(counts) {
                return counts.getOrDefault(obj, 0);
            }
        }
        public int inc(T obj) { return inc(obj, 1); }
        public int inc(T obj, int delta) {
            if(delta < 0) return dec(obj, -delta);
            synchronized(counts) {
                final int count = of(obj);
                counts.put(obj, count + delta);
                return count + delta;
            }
        }
        public int dec(T obj) { return dec(obj, 1); }
        public int dec(T obj, int delta) {
            if(delta < 0) return inc(obj, -delta);
            synchronized(counts) {
                final int count = of(obj);
                if (count <= delta) {
                    counts.remove(obj);
                } else {
                    counts.put(obj, count - delta);
                }
                return Math.max(0, count - delta);
            }
        }
    }
    public static class DisposeException extends RuntimeException {
        public DisposeException(Exception cause) { super(cause); }
    }
    public enum EvictType { LEAST_RECENT_ACCESS, OLDEST }
    public enum ChangeType { ADD, REFRESH, REMOVE }
    private final Comparator<Map.Entry<K,CacheItem>> accessTimeComparator = Comparator.comparingLong(ci -> ci.getValue().timeLastAccessed);
    private final Comparator<Map.Entry<K,CacheItem>> addedTimeComparator = Comparator.comparingLong(ci -> ci.getValue().timeAdded);
    private final Map<K,CacheItem> items = new HashMap<>();
    private final ObjectCounter<V> instanceCount = new ObjectCounter<>();
    private final List<BiConsumer<K, ChangeType>> updateListeners = new ArrayList<>();
    private final Set<K> keysBeingAdded = new HashSet<>();
    private int maxCount = 999;
    private long maxAge = 0;
    private EvictType evictType = EvictType.LEAST_RECENT_ACCESS;
    private Function<K, V> builder;
    private Supplier<V> defaultBuilder;
    private V defaultValue = null;
    private boolean disposeDisposables = true;
    private Consumer<V> disposer = null;

    private void removeOldest() {
        items.entrySet().stream()
            .min(evictType == EvictType.LEAST_RECENT_ACCESS ? accessTimeComparator : addedTimeComparator)
            .ifPresent(entry -> remove(entry.getKey()));
    }
    private void removeOverAge() {
        if(maxAge <= 0) return;
        items.entrySet().stream()
            .filter(entry -> entry.getValue().addedAgo() > maxAge)
            .forEach(entry -> remove(entry.getKey()));
    }
    private void checkMaxAgeAndCount() {
        if(items.size() > maxCount) removeOverAge();
        while(items.size() > maxCount) removeOldest();
    }
    private void callUpdateListeners(K key, ChangeType changeType) {
        if(!updateListeners.isEmpty()) synchronized(updateListeners) {
            updateListeners.forEach(l -> l.accept(key, changeType));
        }
    }
    private V getDefaultValue() {
        if(defaultValue == null && defaultBuilder != null) defaultValue = defaultBuilder.get();
        return defaultValue;
    }

    private void toBeAdded(V value) {
        if(!disposeDisposables || value == defaultValue) return;
        if(value instanceof AutoCloseable  || disposer != null) instanceCount.inc(value);
    }
    private void toBeRemoved(V value) {
        if(!disposeDisposables || value == defaultValue) return;
        if((value instanceof AutoCloseable || disposer != null) && instanceCount.dec(value) == 0) dispose(value);
    }
    private void dispose(V value) {
        try {
            if (disposer != null) disposer.accept(value);
            else if (value instanceof AutoCloseable) ((AutoCloseable) value).close();
        } catch (final Exception e) {
            throw new DisposeException(e);
        }
    }

    public void close() {
        clear();
    }

    public int count() { return items.size(); }
    public Set<K> keys() { return items.keySet(); }

    public V get(K key) { return get(key, false); }
    public V get(K key, boolean quiet) {
        CacheItem cacheItem;
        synchronized(items) {
            cacheItem = items.get(key);
            if (cacheItem != null && maxAge > 0 && cacheItem.addedAgo() > maxAge) {
                remove(key, /*quiet:*/ false);
                cacheItem = null;
            }
            if (cacheItem == null) {
                synchronized (items) {
                    if (keysBeingAdded.contains(key)) {
                        waitOn(items, 10 * 1000);
                        return get(key, quiet);
                    }
                    keysBeingAdded.add(key);
                }
            }
        }
        try {
            final V result = set(key, /*quiet:*/ false); // this may be an expensive call (so don't lock)

            synchronized(items) {
                cacheItem = items.get(key);
            }

            if(cacheItem == null) {
                return result; // Nothing was added -- a default value was returned by add
            }
        } finally {
            synchronized(items) {
                keysBeingAdded.remove(key);
                items.notifyAll();
            }
        }
        synchronized(items) {
            return cacheItem.accessed().data;
        }
    }
    public V set(K key) { return set(key, false); }
    public V set(K key, boolean quiet) {
        if(builder == null) return getDefaultValue();
        V value;
        try { value = builder.apply(key); } catch(final Exception e) { value = getDefaultValue(); } // perhaps later add option to not catch
        if(value == null) value = getDefaultValue();
        set(key, value, quiet);
        return value;
    }
    public BTCache<K,V> set(K key, V data) { return set(key, data, false); }
    public BTCache<K,V> set(K key, V data, boolean quiet) {
        final boolean existed;
        synchronized(items) {
            toBeAdded(data); // before remove because it may be replacing itself so no Dispose!
            existed = items.containsKey(key);
            if(existed) remove(key, /*quiet:*/ true); // in case it exists and needs disposing
            items.put(key, new CacheItem(data).accessed()); // accessed() or it will be removed at checkMaxCount()
            checkMaxAgeAndCount();
        }
        if(!quiet) callUpdateListeners(key, existed ? ChangeType.REFRESH : ChangeType.ADD);
        return this;
    }
    public V refresh(K key) {
        remove(key);
        final V result = get(key, /*quiet:*/ true);
        callUpdateListeners(key, ChangeType.REFRESH);
        return result;
    }
    public V remove(K key) { return remove(key, false); }
    public V remove(K key, boolean quiet) {
        synchronized(items) {
            if(items.containsKey(key)) {
                final V value = items.get(key).data;
                toBeRemoved(value);
                items.remove(key);
                if(!quiet) callUpdateListeners(key, ChangeType.REMOVE);
                return value;
            }
        }
        return getDefaultValue();
    }
    @SafeVarargs
    public final BTCache<K,V> removeAll(K... keys) { return removeAll(Arrays.asList(keys), false); }
    public final BTCache<K,V> removeAll(Collection<K> keys) { return removeAll(keys, false); }
    public BTCache<K,V> removeAll(Collection<K> keys, boolean quiet) {
        keys.forEach(item -> remove(item, quiet));
        return this;
    }
    public boolean containsKey(K key) {
        synchronized(items) {
            final CacheItem cacheItem = items.get(key);
            if(maxAge > 0 && cacheItem.addedAgo() > maxAge) remove(key, /*quiet:*/false);
            return items.containsKey(key);
        }
    }
    public boolean holdsMaxCount() {
        return items.size() == maxCount;
    }
    public BTCache<K,V> clear() {
        synchronized(items) {
            items.values().forEach(cacheItem -> toBeRemoved(cacheItem. data));
            items.clear();
        }
        return this;
    }

    public BTCache<K,V> setMaxCount(int maxCount)          { this.maxCount = maxCount; checkMaxAgeAndCount(); return this; }
    public BTCache<K,V> setMaxAge(long maxAgeMs)           { this.maxAge = maxAgeMs; checkMaxAgeAndCount(); return this; }
    public BTCache<K,V> setMaxAge(Duration maxAge)         { return setMaxAge(maxAge.toMillis()); }
    public BTCache<K,V> setEvictType(EvictType evictType)  { this.evictType = evictType; return this; }
    public BTCache<K,V> setBuilder(Function<K, V> builder) { this.builder = builder; return this; }
    public BTCache<K,V> setDefault(V defaultValue)         { this.defaultValue = defaultValue; return this; }
    public BTCache<K,V> setDefault(Supplier<V> defaultBuilder) { this.defaultBuilder = defaultBuilder; return this; }
    public BTCache<K,V> setDisposeDisposables(boolean set) { this.disposeDisposables = set; return this; }
    public BTCache<K,V> setDisposer(Consumer<V> disposer)  { this.disposer = disposer; return this; }
    public BTCache<K,V> setUpdateListener(BiConsumer<K,ChangeType> handler) {
        synchronized(this.updateListeners) {
            this.updateListeners.add(handler);
        }
        return this;
    }

    public BTCache<K,V> resetDefault() { this.defaultValue = null; return this; }
}
