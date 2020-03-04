package nl.rutilo.logdashboard.services;

import org.junit.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.*;

public class ServiceStateTest {

    @Test public void test() {
        final Service service = new Service();
        final ServiceState state = service.getState();
        service.setLocation("foo.jar");
        service.setName("Testing Service");

        assertThat(state.getState(), is(ServiceState.State.OFF));
        assertThat(state.isRunning(), is(false));

        state.handleLine(false, "first line");

        assertThat(state.isRunning(), is(true));
        assertThat(state.getState(), is(ServiceState.State.STARTING));

        state.handleLine(false, "[main] Application - Started Application in 12.34 seconds");
        assertThat(state.getState(), is(ServiceState.State.RUNNING));
    }

    @Test public void testDetectPortFromLogging() {
      assertThat(ServiceState.getPortFromLine("foo bar").isPresent(), is(false));
      assertThat(ServiceState.getPortFromLine("Tomcat initialized with port(s): 1234 (http)").orElse(""), is("1234"));
      assertThat(ServiceState.getPortFromLine("Tomcat started on port(s): 1234 (http)").orElse(""), is("1234"));
      assertThat(ServiceState.getPortFromLine("Updating port to 1234").orElse(""), is("1234"));
      assertThat(ServiceState.getPortFromLine("TomcatEmbeddedServletContainer - Tomcat started on port(s): 8025 (http)").orElse(""), is("8025"));
    }
}