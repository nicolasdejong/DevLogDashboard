package nl.rutilo.logdashboard.util;

import org.junit.Test;

import java.util.Optional;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.*;

public class UtilTest {

    @Test public void or() {
        assertThat(Util.or("a", "b"), is("a"));
        assertThat(Util.or(null, "b"), is("b"));
        try { Util.or("a", null); fail("expected nullpointer exception"); } catch(final NullPointerException ignored) { /*ok*/ }
        try { Util.or(null, null); fail("expected nullpointer exception"); } catch(final NullPointerException ignored) { /*ok*/ }
    }

    @Test public void ifPresentOrElse() {
        final Optional<?> empty = Optional.empty();
        final Optional<String> present = Optional.of("test");
        Util.ifPresentOrElse(empty, v->fail("not present"), ()->{/*ok*/});
        Util.ifPresentOrElse(present, v->{/*ok*/}, ()->fail("present"));
    }

    @Test public void orSupplyOptional() {
        final Optional<String>                   empty      = Optional.empty();
        final Optional<String>                   present    = Optional.of("test");
        final ThrowingSupplier<Optional<String>> supEmpty   = () -> empty;
        final ThrowingSupplier<Optional<String>> supPresent = () -> present;
        final ThrowingSupplier<Optional<String>> supNull    = Optional::empty;

        assertThat(Util.orSupplyOptional(supEmpty, supEmpty, supPresent, supEmpty), is(present));
        assertThat(Util.orSupplyOptional(supEmpty, supEmpty), is(empty));
        assertThat(Util.orSupplyOptional(null, supEmpty), is(empty));
        assertThat(Util.orSupplyOptional(supNull, supEmpty), is(empty));
        assertThat(Util.orSupplyOptional(), is(empty));
    }

    @Test public void orSupplyNullable() {
        final Optional<String>         empty    = Optional.empty();
        final Optional<String>         val      = Optional.of("test");
        final ThrowingSupplier<String> supEmpty = () -> null;
        final ThrowingSupplier<String> supVal   = () -> "test";

        assertThat(Util.orSupplyNullable(supVal), is(val));
        assertThat(Util.orSupplyNullable(null, null), is(empty));
        assertThat(Util.orSupplyNullable(null, supEmpty), is(empty));
        assertThat(Util.orSupplyNullable(null, supEmpty, supVal), is(val));
    }

    @Test public void getSystemPropertyBoolean() {
        final String name = "test.bool";

        System.clearProperty(name);         assertThat(Util.getSystemPropertyBoolean(name), is(false));
        System.setProperty(name, "");       assertThat(Util.getSystemPropertyBoolean(name), is(false));
        System.setProperty(name, "yes");    assertThat(Util.getSystemPropertyBoolean(name), is(false));
        System.setProperty(name, "true");   assertThat(Util.getSystemPropertyBoolean(name), is(true));
        System.setProperty(name, "TRUE");   assertThat(Util.getSystemPropertyBoolean(name), is(true));
        System.setProperty(name, " true "); assertThat(Util.getSystemPropertyBoolean(name), is(true));
    }
}