package nl.rutilo.logdashboard.util;

import org.junit.Test;

import java.time.Duration;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.*;

public class TimerTest {
    @Test
    public void testTimer() throws InterruptedException {
        final int[] count = { 0 };
        final Runnable inc = () -> count[0]++;
        final Runnable reset = () -> count[0]  = 0;

        Timer.start(Duration.ofMillis(10), inc);
        Thread.sleep(20);
        assertThat(count[0], is(1));
        reset.run();

        Timer.start("a", Duration.ofMillis(10), inc);
        Timer.start("a", Duration.ofMillis(10), inc);
        Timer.start("a", Duration.ofMillis(10), inc);
        Timer.start("a", Duration.ofMillis(10), inc);
        Thread.sleep(20);
        assertThat(count[0], is(1));
        reset.run();

        Timer.start("a", Duration.ofMillis(10), inc);
        Timer.start("b", Duration.ofMillis(10), inc);
        Timer.clear("a");
        Thread.sleep(20);
        assertThat(count[0], is(1));
        reset.run();
    }

    @Test
    public void testInstance() throws InterruptedException {
        final int[] count = { 0 };
        final Runnable inc = () -> count[0]++;
        final Runnable reset = () -> count[0]  = 0;
        final Timer timer = new Timer().set(inc);

        timer.set(Duration.ofMillis(10)).start();
        Thread.sleep(20);
        assertThat(count[0], is(1));
        reset.run();

        timer.set(Duration.ofMillis(10)).set(() -> { inc.run(); inc.run(); }).start();
        Thread.sleep(15);
        assertThat(count[0], is(2));
        reset.run();
    }
}