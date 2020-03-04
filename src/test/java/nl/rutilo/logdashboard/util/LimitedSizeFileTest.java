package nl.rutilo.logdashboard.util;

import org.junit.Test;

import java.io.File;
import java.io.IOException;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.*;

public class LimitedSizeFileTest {

    @Test public void write() throws IOException {
        final File tempFile = File.createTempFile("limitedFile", "test");
        tempFile.deleteOnExit();

        final LimitedSizeFile lfile = new LimitedSizeFile(tempFile, 100);
        assertThat(tempFile.length(), is(0L));

        lfile.write("abc\n");
        assertThat(tempFile.length(), is(4L));

        lfile.write("123456789A123456789B123456789\n");
        lfile.write("123456789A123456789B123456789\n");
        lfile.write("123456789A123456789B123456789\n");
        assertThat(tempFile.length(), is(94L));

        lfile.write("56789X\n");
        assertThat(tempFile.length(), is(30+7L));

        lfile.write("123456789A123456789B123456789\n");
        lfile.write("123456789A123456789B123456789\n");
        assertThat(tempFile.length(), is(30+7+30+30L));

        lfile.write("89X1234567\n");
        assertThat(tempFile.length(), is(30+11L));
        lfile.close();
    }
}