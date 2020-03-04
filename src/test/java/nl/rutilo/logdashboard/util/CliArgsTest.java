package nl.rutilo.logdashboard.util;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.*;

public class CliArgsTest {

    @Test
    public void testWithIllegals() {
        final String[] allErrors = { "" };
        final CliArgs args = new CliArgs(new CliArgs.Builder()
            .setArgs(new String[] { "-flag1", "-flag2", "-option1", "option1Val", "-option2", "option2Val" })
            .setAllowedFlag("flag1")
            .setAllowedKey("option1")
            .setIgnoreIllegals(false)
            .setAllowIllegals(false)
        ) {
            @Override protected void exitWithError(String message) {
                allErrors[0] = message;
            }
        };
        final List<String> errors = Arrays.stream(allErrors[0].split("\n")).skip(1).collect(Collectors.toList());
        assertThat(args.hasFlag("flag1"), is(true));
        assertThat(args.hasFlag("flag2"), is(false));
        assertThat(args.hasValuesFor("option1"), is(true));
        assertThat(args.hasValuesFor("option2"), is(false));
        assertThat(errors.size(), is(2));
        assertThat(errors.get(0), is(" - Unsupported flag: flag2"));
        assertThat(errors.get(1), is(" - Unsupported key: option2"));
    }

    private CliArgs create() {
        return CliArgs.createFor(
            "flag0",
            "-flag1",
            "-flag2",
            "-option", "value1", "value2",
            "-flag3",
            "-singleOption", "soVal",
            "-var=val",
            "-quotedVar=\"a b c\"",
            "-quotedVar2=\"a, b, c\""
        );
    }

    @Test
    public void hasFlag() {
        assertThat(create().hasFlag("flag0"), is(true));
        assertThat(create().hasFlag("flag1"), is(true));
        assertThat(create().hasFlag("flag2"), is(true));
        assertThat(create().hasFlag("option"), is(false));
        assertThat(create().hasFlag("flag3"), is(true));
        assertThat(create().hasFlag("singleOption"), is(false));
        assertThat(create().hasFlag("nonexisting"), is(false));
    }

    @Test
    public void hasValuesFor() {
        assertThat(create().hasValuesFor("flag0"), is(false));
        assertThat(create().hasValuesFor("flag1"), is(false));
        assertThat(create().hasValuesFor("nonexisting"), is(false));
        assertThat(create().hasValuesFor("option"), is(true));
    }

    @Test
    public void getValueOf() {
        assertThat(create().getValueOf("flag1").isPresent(), is(false));
        assertThat(create().getValueOf("option").isPresent(), is(false));
        assertThat(create().getValueOf("singleOption").isPresent(), is(true));
        assertThat(create().getValueOf("singleOption").get(), is("soVal"));
        assertThat(create().getValueOf("var").isPresent(), is(true));
        assertThat(create().getValueOf("var").get(), is("val"));
        assertThat(create().getValueOf("quotedVar").isPresent(), is(true));
        assertThat(create().getValueOf("quotedVar").get(), is("a b c"));
        assertThat(create().getValueOf("quotedVar2").isPresent(), is(true));
        assertThat(create().getValueOf("quotedVar2").get(), is("a, b, c"));
    }

    @Test
    public void getValuesOf() {
        assertThat(create().getValuesOf("flag1"), is(new String[0]));
        assertThat(create().getValuesOf("option"), is(new String[] { "value1", "value2" }));
        assertThat(create().getValuesOf("singleOption"), is(new String[] { "soVal" }));
    }

    @Test
    public void testAlias() {
        final CliArgs args = new CliArgs.Builder()
            .setAliasOf("help", "?")
            .setAliasOf("option", "opt")
            .setArgs("?", "-opt", "optVal")
            .build();
        assertThat(args.hasFlag("help"), is(true));
        assertThat(args.hasValuesFor("option"), is(true));
    }

    @Test
    public void testToString() {
        final CliArgs args = new CliArgs.Builder()
            .setAliasOf("help", "?")
            .setAliasOf("option", "opt")
            .setArgs("?", "-opt", "optVal")
            .build();
        assertThat(args.toString(), is("[CliArgs flags=[help]; options={option:[optVal]}]"));
    }

    @Test
    public void testSplitOnEquals() {
        final CliArgs args = CliArgs.createFor("-foo=bar", "-flag");
        assertThat(args.getValueOf("foo").orElse(""), is("bar"));
        assertThat(args.hasFlag("flag"), is(true));
        assertThat(args.hasValuesFor("foo=bar"), is(false));
    }
}
