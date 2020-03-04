package nl.rutilo.logdashboard.util;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.time.Duration;
import java.util.Arrays;

@Data
public class HistoricCount {
    @JsonIgnore protected static final int   MAX_BAG_COUNT = 1000;
    private final          long  durationMs;
    private final          long  bagDurationMs;
    private final          int[] bags;
    private                int   lastBagIndex;
    private                long  startTime;

    public static class HistoricCountConstructor {
        private final Duration duration;
        private HistoricCountConstructor(Duration duration) { this.duration = duration; }
        public HistoricCount andResolution(Duration resolution) {
            return new HistoricCount(duration, resolution);
        }
    }
    public static HistoricCountConstructor ofDuration(Duration duration) {
        return new HistoricCountConstructor(duration);
    }

    private HistoricCount(Duration duration, Duration resolution) {
        durationMs = Math.max(1, duration.toMillis());

        final long resolutionMs = Math.max(1, resolution.toMillis());
        final int bagCount = Math.min(MAX_BAG_COUNT, (int)(durationMs / resolutionMs));
        this.bags = new int[bagCount];
        this.bagDurationMs = durationMs / bagCount;
        this.reset();
    }

    public Duration getDuration() { return Duration.ofMillis(durationMs); }
    public Duration getResolution() { return Duration.ofMillis(bagDurationMs); }

    public final HistoricCount reset() {
        Arrays.fill(bags, 0);
        this.lastBagIndex = this.bags.length - 1;
        this.startTime = System.currentTimeMillis();
        return this;
    }

    public void add() { add(1); }
    public void add(int amount) {
        removeOldBags();
        bags[lastBagIndex] += amount;
    }

    public int get() { return get(Duration.ofMillis(durationMs)); }
    public int get(Duration duration) {
        removeOldBags();
        long durationLeftMs = duration.toMillis();
        if(durationLeftMs < bagDurationMs) return 0; // smaller than resolution

        int bagsLeft = bags.length;
        int bagIndex = lastBagIndex;
        int count = 0;

        while(durationLeftMs > 0 && bagsLeft > 0) {
            count += bags[bagIndex];

            durationLeftMs -= bagDurationMs;
            bagsLeft--;
            bagIndex = bagIndex -1;
            if(bagIndex < 0) bagIndex = bags.length - 1;
        }
        return count;
    }

    private void removeOldBags() {
        final long now = System.currentTimeMillis();
        final long age = now - startTime;
        int bagsNeeded = (int)(age / bagDurationMs);
        final long restTime= age - (bagsNeeded * this.bagDurationMs);

        if(bagsNeeded == 0) return;

        while(bagsNeeded > 0) {
            lastBagIndex = (lastBagIndex + 1) % bags.length;
            bags[lastBagIndex] = 0;
            bagsNeeded--;
        }
        startTime = now - restTime;
    }

    public long getLastUpdateAgoMs() {
        return System.currentTimeMillis() - startTime;
    }
}
