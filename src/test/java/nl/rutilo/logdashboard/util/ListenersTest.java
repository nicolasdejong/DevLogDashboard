package nl.rutilo.logdashboard.util;

import org.junit.Test;

import java.time.Duration;
import java.util.function.Consumer;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.*;

public class ListenersTest {
    private static class ValueAndCount {
        int value;
        int count;
        void set(int val) { value = val; count++; }
        void clear() { value = 0; count = 0; }
    }

    @Test
    public void test() {
        final Listeners<Integer> listeners = new Listeners<>();
        final ValueAndCount vac = new ValueAndCount();
        final Consumer<Integer> vacSet = vac::set;

        listeners.add(vacSet);

        for(int i=0; i<10; i++) listeners.call(123);
        assertThat(vac.value, is(123));
        assertThat(vac.count, is(10));
        vac.clear();

        listeners.add(vacSet);
        for(int i=0; i<10; i++) listeners.call(123);
        assertThat(vac.value, is(123));
        assertThat(vac.count, is(20));
        vac.clear();

        listeners.remove(vacSet);
        for(int i=0; i<10; i++) listeners.call(123);
        assertThat(vac.value, is(123));
        assertThat(vac.count, is(10));
        vac.clear();

        listeners.debounced();
        for(int i=0; i<10; i++) listeners.call(123);
        Util.sleep(Duration.ofMillis(300));
        assertThat(vac.value, is(123));
        assertThat(vac.count, is(1));
        vac.clear();

        listeners.call(123);
        listeners.call(123);
        listeners.call(123);
        listeners.call(456);
        listeners.call(456);
        listeners.call(456);
        Util.sleep(Duration.ofMillis(300));
        assertThat(vac.value, is(456));
        assertThat(vac.count, is(2));
    }
}