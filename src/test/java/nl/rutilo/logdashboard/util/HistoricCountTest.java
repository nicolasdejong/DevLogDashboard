package nl.rutilo.logdashboard.util;

import org.junit.Test;

import java.time.Duration;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.*;

public class HistoricCountTest {

    @Test public void getDurationAndResolution() {
        final Duration duration = Duration.ofMillis(120);
        final Duration resolution = Duration.ofMillis(20);
        final HistoricCount hcount = HistoricCount.ofDuration(duration).andResolution(resolution);

        assertThat(hcount.getDuration(), is(duration));
        assertThat(hcount.getResolution(), is(resolution));
    }
    @Test public void getDurationAndResolution2() {
        final Duration duration = Duration.ofMillis(5000);
        final Duration resolution = Duration.ofMillis(1);
        final HistoricCount hcount = HistoricCount.ofDuration(duration).andResolution(resolution);

        assertThat(hcount.getDuration(), is(duration));
        assertThat(hcount.getResolution(), is(Duration.ofMillis(duration.toMillis()/HistoricCount.MAX_BAG_COUNT)));
    }

    @Test public void addAndCount() throws InterruptedException {
        final Duration duration = Duration.ofMillis(500);
        final Duration resolution = Duration.ofMillis(50);
        final HistoricCount hcount = HistoricCount.ofDuration(duration).andResolution(resolution);

        // 0 ms
        hcount.add();
        assertThat(hcount.get(), is(1));
        hcount.add();
        assertThat(hcount.get(), is(2));
        hcount.add(2);
        assertThat(hcount.get(), is(4));
        Thread.sleep(200);

        // 200ms
        assertThat(hcount.get(), is(4));
        hcount.add();
        assertThat(hcount.get(), is(5));
        Thread.sleep(200);

        // 400ms
        hcount.add();
        assertThat(hcount.get(), is(6));
        Thread.sleep(200);

        // 600ms (first 100ms were removed)
        assertThat(hcount.get(), is(2));
        hcount.add();
        assertThat(hcount.get(), is(3));
        Thread.sleep(250);

        // 850ms (first 350ms were removed)
        assertThat(hcount.get(), is(2));
        hcount.add();
        assertThat(hcount.get(), is(3));
        Thread.sleep(300);

        // 1150ms (first 650ms were removed)
        assertThat(hcount.get(), is(1));
    }

    @Test public void testTiming() throws InterruptedException {
        final Duration duration = Duration.ofSeconds(1);
        final Duration resolution = Duration.ofMillis(100);
        final HistoricCount hcount = HistoricCount.ofDuration(duration).andResolution(resolution);

        hcount.add(10);
        Thread.sleep(100);
        hcount.add(9);
        Thread.sleep(100);
        hcount.add(8);
        Thread.sleep(600);

        //Thread.sleep(200); // equivalent sleep from loop below
        for(int j=0; j<5; j++) { Thread.sleep(40); hcount.get(); } // multiple calls to get shouldn't interfere

        assertThat(hcount.get(), is(17));
    }
}