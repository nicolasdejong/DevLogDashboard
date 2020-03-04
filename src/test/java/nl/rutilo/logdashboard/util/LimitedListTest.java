package nl.rutilo.logdashboard.util;

import org.junit.Before;
import org.junit.Test;

import java.time.Duration;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.*;

public class LimitedListTest {
    LimitedList<String> list;

    @Before  public void setUp() throws Exception {
        list = new LimitedList<>();
        list.add("a");
        list.add("b");
        list.add("c");
    }

    private String listToString() { return String.join("", list.toArray(new String[0])); }
    private void sleep(int ms) {
        try { Thread.sleep(ms); } catch (final InterruptedException e) { System.exit(10); }
    }

    @Test public void setMaxCount() {
        assertThat(listToString(), is("abc"));
        list.setMaxCount(2);
        assertThat(listToString(), is("bc"));
        list.add("d");
        assertThat(listToString(), is("cd"));
    }
    @Test public void setMaxAge() {
        assertThat(listToString(), is("abc"));
        list.setMaxAge(Duration.ofMillis(80));
        list.resetAges();
        assertThat(listToString(), is("abc"));

        sleep(40); list.prune();
        assertThat(listToString(), is("abc"));

        list.add("d");
        sleep(20); list.prune();
        assertThat(listToString(), is("abcd"));

        list.add("e");
        sleep(30); list.prune();
        assertThat(listToString(), is("de"));

        sleep(40); list.prune();
        assertThat(listToString(), is("e"));

        list.clearMaxAge();
        sleep(100); list.prune();
        assertThat(listToString(), is("e"));
    }
}