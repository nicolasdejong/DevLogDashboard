package nl.rutilo.logdashboard.util;

import org.junit.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

public class IOUtilTest {
    @Test
    public void testToBytesToString() {
        final String abc = "abc";
        assertThat(IOUtil.asString(IOUtil.toBytes(abc)), is(abc));
    }
}
