package nl.rutilo.logdashboard.services;

import org.glassfish.jersey.internal.util.Producer;
import org.junit.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.*;

public class ServicesStateHistoryTest {

    private static List<Service> createServices() {
        final List<Service> list = new ArrayList<>();
        for(int i=0; i<10; i++) list.add(new Service());
        list.forEach(s -> s.state.setOff());
        return list;
    }

    @Test public void testStates() throws InterruptedException {
        final List<Service> services = createServices();
        final ServicesStateHistory ssh = ServicesStateHistory
            .forServices(services)
            .withDuration(Duration.ofMillis(500))
            .andResolution(Duration.ofMillis(100));
        final Producer<String> toString = () -> toString(ssh.getAsTimeToString());

        assertThat(toString.call(), is("O,O,O,O,O,O,O,O,O,O"));

        services.get(0).state.setWaiting();
        assertThat(toString.call(), is("OW,O,O,O,O,O,O,O,O,O"));

        services.get(0).state.setStarting();
        assertThat(toString.call(), is("OWS,O,O,O,O,O,O,O,O,O"));

        services.get(1).state.setRunning();
        assertThat(toString.call(), is("OWS,OR,O,O,O,O,O,O,O,O"));

        services.get(2).state.setInitFailed();
        assertThat(toString.call(), is("OWS,OR,OI,O,O,O,O,O,O,O"));

        services.get(1).state.setError();
        assertThat(toString.call(), is("OWS,ORE,OI,O,O,O,O,O,O,O"));

        Thread.sleep(510); // next call will create a new row

        services.get(5).state.setStarting();
        assertThat(toString.call(), is("OWS,ORE,OI,O,O,O,O,O,O,O -- S,E,I,O,O,S,O,O,O,O"));

        services.get(0).state.setOff();
        ssh.update();

        Thread.sleep(510); // next call will create a new row

        assertThat(toString.call(), is("OWS,ORE,OI,O,O,O,O,O,O,O -- SO,E,I,O,O,S,O,O,O,O -- O,E,I,O,O,S,O,O,O,O"));
    }

    @Test public void testMultipleEqualStates() {
        final List<Service> services = createServices();
        final ServicesStateHistory ssh = ServicesStateHistory
            .forServices(services)
            .withDuration(Duration.ofMillis(500))
            .andResolution(Duration.ofMillis(100));
        final Producer<String> toString = () -> toString(ssh.getAsTimeToString());

        assertThat(toString.call(), is("O,O,O,O,O,O,O,O,O,O"));

        services.get(0).state.setWaiting();
        assertThat(toString.call(), is("OW,O,O,O,O,O,O,O,O,O"));

        services.get(0).state.setStarting();
        ssh.update();
        services.get(0).state.setOff();
        ssh.update();
        services.get(0).state.setWaiting();
        ssh.update();
        services.get(0).state.setStarting();
        ssh.update();
        services.get(0).state.setRunning();
        assertThat(toString.call(), is("OWSOWSR,O,O,O,O,O,O,O,O,O"));
    }

    private static String toString(Map<Long,String> map) {
        final Long[] keys = map.keySet().toArray(new Long[0]);
        Arrays.sort(keys);
        return String.join(" -- ", Arrays.stream(keys).map(map::get).collect(Collectors.toList()));
    }
}