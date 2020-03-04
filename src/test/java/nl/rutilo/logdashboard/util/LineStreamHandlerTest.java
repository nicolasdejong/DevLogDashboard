package nl.rutilo.logdashboard.util;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.*;

public class LineStreamHandlerTest {

    @Test public void test() {
        final InputStream bis = new ByteArrayInputStream((
              "First line\n"
            + "Second line\r"
            + "\n"
            + "Third line\n"
            + "Fourth line\r"
            + "Fifth line\n"
            + "Sixth row\b\b\bline\n"
            + "\n"
            + "10% progress"
            + "\b\b\b\b\b\b\b\b\b\b\b\b20% progress"
            + "\b\b\b\b\b\b\b\b\b\b\b\b90% progress"
            + "\b\b\b\b\b\b\b\b\b\b\b\b100% progress\n"
            + "test: 1\b2\b3\b4\n"
            + "replace THIS part\r"
            + "replace THAT\n"
            //+ " 10% building modules 0/1 modules 1 active ...s?http://localhost:8014 ./src/main.ts\b \b\b \b\b \b\b \b\b \b\b \b\b \b\b \b\b \b\b \b\b \b\b \b\b \b\b \b\b \b\b \b\b \b\b \b\b \b\b \b\b \b\b \b\b \b\b \b\b \b\b \b\b \b\b \b\b \b\b \b\b \b\b \b\b \b\b \b\b \b\b \b\b \b\b \b\b \b\b \b\b \b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b 10% building modules 1/1 modules 0 active\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b 10% building modules 1/2 modules 1 active ...p://localhost:8014 ./src/polyfills.ts 10% building modules 2/3 modules 1 active ...tp://localhost:8014 ./src/styles.scss"
            + " 10% building modules 0/1 1 active ...\b\b \b\b \b\b \b\b \b\b \b\b \b\b \b\b \b\b \b\b \b\b \b\b 2 active ...\b\b \b\b \b\b \b\b \b\b \b\b \b\b \b\b \b\b \b\b \b\b \b\b 3 active ....\r\n"
            + "last line\n"
        ).getBytes());
        final List<String> lines = new LineStreamHandler(bis).stream()
            .map(l -> (l.replacesPreviousLine ? "#" : "") + l.text)
            .collect(Collectors.toList());
        final List<String> linesExpected = Arrays.asList(
            "First line",
            "Second line",
            //"#Second line", <-- optimized away
            "Third line",
            "Fourth line",
            "#Fifth linee",
            "Sixth row",
            "#Sixth line",
            "",
            "10% progress",
            "#20% progress",
            "#90% progress",
            "#100% progress",
            "test: 1",
            "#test: 2",
            "#test: 3",
            "#test: 4",
            "replace THIS part",
            "#replace THAT part",
            " 10% building modules 0/1 1 active ...",
            "# 10% building modules 0/1 2 active ...",
            "# 10% building modules 0/1 3 active ....",
            "last line"
        );

        // Easier to read diff
        String exp = toString(linesExpected).replace("\n","\\n");
        String gen = toString(lines).replace("\n","\\n");
        if(!exp.equals(gen)) {
            int pos = 0;
            while(pos < exp.length() && pos < gen.length() && exp.charAt(pos) == gen.charAt(pos)) pos++;
            while(pos > 100) {
                exp = exp.substring(100);
                gen = gen.substring(100);
                pos -= 100;
            }
            System.out.println("expected:  " + exp);
            System.out.println("generated: " + gen);
            System.out.println("           " + String.format("%1$" + pos + "s^", ""));

            assertThat(toString(lines), is(toString(linesExpected)));
        }
    }

    private static String toString(List<String> items) {
        return items.stream().reduce("", (a, s) -> a + "\n" + s.replace("\n","\\n").replace("\r","\\r"));
    }
}