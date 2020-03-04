package nl.rutilo.logdashboard.util;

import nl.rutilo.logdashboard.services.Service;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.*;

public class StringUtilTest {
    @Test
    public void replace() {
        assertThat(StringUtil.replace("this cat is scattered", "cat", String::toUpperCase), is("this CAT is sCATtered"));
        assertThat(StringUtil.replacem("this cat is scattered", "c(a)t", m -> m.group(1)), is("this a is satered"));
    }

    @Test public void splitCommandLine() {
        assertThat(StringUtil.splitCommandLine("-Xmx128m -DFOO=\"bar $(${VAR}:${VAR2} | base64)\" -Dvar = val"),
                   is(new String[] { "-Xmx128m", "-DFOO=\"bar $(${VAR}:${VAR2} | base64)\"", "-Dvar=val" } ));

        assertThat(StringUtil.splitCommandLine("java -Xmx128m -DHSDP_ACCESS_URL=\"https://iam/a/oauth2/token\" -HEADER=\"Basic UGFwz\" -HEADER2=\"Basic SW5w==\" -ID=\"foobar\" -jar work.jar"),
                   is(new String[] {
                       "java",
                       "-Xmx128m",
                       "-DHSDP_ACCESS_URL=\"https://iam/a/oauth2/token\"",
                       "-HEADER=\"Basic UGFwz\"",
                       "-HEADER2=\"Basic SW5w==\"",
                       "-ID=\"foobar\"",
                       "-jar",
                       "work.jar"
                   } ));

    }

    @Test public void replaceVariable() {
        final Map<String, String> vars = new HashMap<String, String>() {{
           put("avar", "avalue");
           put("bvar", "bvalue");
           put("cvar", "cvalue");
           put("deepvar", "a${deepvar2}a");
           put("deepvar2", "b${deepvar3}b");
           put("deepvar3", "cDEEPc");
           put("hw", "Hello, World!");
        }};
        assertThat(StringUtil.replaceVariable(vars, "${avar}", /*hideUnknowns=*/false), is("avalue"));
        assertThat(StringUtil.replaceVariable(vars, "before ${avar} after", /*hideUnknowns=*/false), is("before avalue after"));
        assertThat(StringUtil.replaceVariable(vars, "${avar} after", /*hideUnknowns=*/false), is("avalue after"));
        assertThat(StringUtil.replaceVariable(vars, "before ${avar}", /*hideUnknowns=*/false), is("before avalue"));
        assertThat(StringUtil.replaceVariable(vars, "deep=${deepvar}", /*hideUnknowns=*/false), is("deep=abcDEEPcba"));
        assertThat(StringUtil.replaceVariable(vars, "${avar} ${zvar}", /*hideUnknowns=*/false), is("avalue ${zvar}"));
        assertThat(StringUtil.replaceVariable(vars, "${avar} ${zvar}", /*hideUnknowns=*/true), is("avalue "));
        assertThat(StringUtil.replaceVariable(vars, "${unknown}", /*hideUnknowns=*/true), is(""));
        assertThat(StringUtil.replaceVariable(vars, "${unknown}", /*hideUnknowns=*/false), is("${unknown}"));

        assertThat(StringUtil.replaceVariable(vars, "${hw | base64}", false), is("SGVsbG8sIFdvcmxkIQ=="));
        assertThat(StringUtil.replaceVariable(vars, "${Hello, World! | base64}", false), is("SGVsbG8sIFdvcmxkIQ=="));
        assertThat(StringUtil.replaceVariable(vars, "${Hello, World! | base64 --encode}", false), is("SGVsbG8sIFdvcmxkIQ=="));
        assertThat(StringUtil.replaceVariable(vars, "${Hello, World! | base64 --encode | base64 -decode}", false), is("Hello, World!"));
        assertThat(StringUtil.replaceVariable(vars, "${Hello, World! | base64encode | base64decode}", false), is("Hello, World!"));
        assertThat(StringUtil.replaceVariable(vars, "${Hello, World! | base64 --encode | base64 --decode}", false), is("Hello, World!"));
        assertThat(StringUtil.replaceVariable(vars, "${aBc | toUpper}", false), is("ABC"));
        assertThat(StringUtil.replaceVariable(vars, "${aBc | toLower}", false), is("abc"));
        assertThat(StringUtil.replaceVariable(vars, "${${avar}:${bvar} | toUpper}", false), is("AVALUE:BVALUE"));

        assertThat(StringUtil.replaceVariable(vars, "${nonexisting | }", false), is(""));
        assertThat(StringUtil.replaceVariable(vars, "${nonexisting | foo}", false), is("foo"));
        assertThat(StringUtil.replaceVariable(vars, "${nonexisting | foo bar}", false), is("foo bar"));

        // the next assumes the PATH env var exists on the system running this (win / linux)
        final String path = System.getenv("PATH");
        assertThat(StringUtil.replaceVariable(vars, "${path}", false), is(path));
        assertThat(StringUtil.replaceVariable(vars, "${nonexisting}", true), is(""));
    }
    @Test public void replaceVariableObject() {
        final Service service = new Service();
        service.setName("TestService");
        service.setPort(1234);

        System.out.println("service name: " + service.getName());
        assertThat(StringUtil.replaceVariable(service, "port of ${name} is ${port}"), is("port of TestService is 1234"));
    }

    @Test public void replaceVariables() {
        final Map<String, String> vars = new HashMap<String, String>() {{
           put("avar", "avalue");
           put("bvar", "bvalue");
        }};
        assertThat(StringUtil.replaceVariables(vars, new String[] {"a=${avar}", "b=${bvar}"}, false), is(new String[] { "a=avalue", "b=bvalue" }));
    }

    @Test public void getStringParts() {
        assertThat(StringUtil.getStringParts("foo ${abc} bar ${def}", "\\$\\{[^{}]+\\}"), is(Arrays.asList("${abc}", "${def}")));
        assertThat(StringUtil.getStringParts("foo ${abc} bar ${def}", "\\$\\{([^{}]+)\\}"), is(Arrays.asList("abc", "def")));
    }

    @Test public void sizeToLong() {
        assertThat(StringUtil.sizeToLong(""),             is(0L));
        assertThat(StringUtil.sizeToLong("K"),            is(0L));
        assertThat(StringUtil.sizeToLong("128B"),         is(128L));
        assertThat(StringUtil.sizeToLong("128 B"),        is(128L));
        assertThat(StringUtil.sizeToLong("128 Bytes"),    is(128L));
        assertThat(StringUtil.sizeToLong("128KB"),        is(128L*1024));
        assertThat(StringUtil.sizeToLong("128 K"),        is(128L*1024));
        assertThat(StringUtil.sizeToLong("128 K"),        is(128L*1024));
        assertThat(StringUtil.sizeToLong("128Kilobytes"), is(128L*1024));
        assertThat(StringUtil.sizeToLong("128MB"),        is(128L*1024*1024));
    }
}