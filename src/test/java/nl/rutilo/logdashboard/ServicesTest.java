package nl.rutilo.logdashboard;

import nl.rutilo.logdashboard.services.Service;
import nl.rutilo.logdashboard.services.Services;
import nl.rutilo.logdashboard.util.IOUtil;
import nl.rutilo.logdashboard.util.Util;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.*;

@SuppressWarnings("Duplicates")
public class ServicesTest {

    @Before public void setup() {
        Configuration.reset();
        Services.reset();
        Services.reload();
    }
    @After public void teardown() {
        Configuration.reset();
        Services.stopAll();
        Services.reset();
        try { Thread.sleep(5000); } catch(final InterruptedException e) {}
        new File("Example jar file-output.log").delete();
    }

    @Test public void load() {
        final List<Service> services = Services.get();
        assertThat(services.size() > 2, is(true));
    }

    @Test public void testSetFileLocation() throws IOException {
        final File tempDir = Files.createTempDirectory("servicesTest").toFile();
        final File foobarDir = new File(tempDir, "foo/bar/");
        final String userDir = System.getProperty("user.dir"); // current dir for new File() calls
        final File jar = new File(Configuration.getJarsDir(), "test-server-1.23.jar");

        try {
            foobarDir.mkdirs();
            System.setProperty("user.dir", foobarDir.getAbsolutePath());
            Util.touch(jar);

            final Service testServer = Services.get("MySQL").orElseThrow(() -> new RuntimeException("MySQL service not in services configuration"));
            testServer.setFileLocation(jar);
            assertThat(testServer.getFileLocation().isPresent(), is(true));
            assertThat(testServer.getFileLocation().get(), is(jar));
        } finally {
            if (userDir != null) System.setProperty("user.dir", userDir);
            IOUtil.delete(tempDir);
            IOUtil.delete(jar);
        }
    }

    @Test public void testOrder() {
        final List<String> list = Arrays.asList("a", "b", "c", "d", "e", "f");
        final  Set<String> set = Collections.synchronizedSortedSet(new TreeSet<>());
        set.addAll(list);
        //set.stream().filter(s -> !s.equals("b")).limit(1).forEach(s -> System.out.println("s="+s));
    }
}