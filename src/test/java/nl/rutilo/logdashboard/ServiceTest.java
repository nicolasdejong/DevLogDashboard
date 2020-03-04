package nl.rutilo.logdashboard;

import nl.rutilo.logdashboard.services.Service;
import org.junit.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

public class ServiceTest {
    @Test public void testGetPollIntervalMs() {
        final Service service = new Service();
        final int sec = 1000;
        final int min = 60 * sec;
        final int hour = 60 * min;

        final String[] input = { "1234ms", "1234millis", "12s34ms", "12min", "123minutes", "123m45s67ms",       "2d3s" };
        final int[] expected = {     1234,         1234,     12034,  12*min,      123*min,  123*min+45*sec+67,  48*hour+3*sec };

        for(int i=0; i<input.length; i++) {
            service.setPollInterval(input[i]);
            assertThat(input[i], service.getPollIntervalMs(), is(expected[i]));
        }
    }
}