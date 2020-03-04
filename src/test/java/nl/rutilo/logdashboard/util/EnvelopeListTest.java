package nl.rutilo.logdashboard.util;

import org.assertj.core.util.Lists;
import org.junit.Before;
import org.junit.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.*;

public class EnvelopeListTest {
    EnvelopeList<String> list;

    static class TestEnvelope extends EnvelopeList.Envelope {
        private static int lastId = 0;
        int id;
        public TestEnvelope(Object value) {
            super(value);
            id = lastId++;
        }
    }

    @Before public void setUp() throws Exception {
        list = new EnvelopeList<String>() {
            @Override protected Envelope newEnvelopeFor(Object value) {
                return new TestEnvelope(value);
            }
            @Override public String toString() {
                return String.join("", toArray(new String[0]));
            }
        };
        list.add("a");
        list.add("b");
        list.add("c");
    }

    private String listToString() { return String.join("", list.toArray(new String[0])); }

    @Test public void removeFirst() {
        assertThat(list.removeFirst(), is("a"));
    }

    @Test public void size() {
        assertThat(list.size(), is(3));
    }
    @Test public void isEmpty() {
        assertThat(list.isEmpty(), is(false));
        list.clear();
        assertThat(list.isEmpty(), is(true));
    }
    @Test public void contains() {
        assertThat(list.contains("d"), is(false));
        assertThat(list.contains("a"), is(true));
    }
    @Test public void iterator() {
        final StringBuilder sb = new StringBuilder();
        list.iterator().forEachRemaining(sb::append);
        assertThat(sb.toString(), is("abc"));
    }
    @Test public void forEach() {
        final StringBuilder sb = new StringBuilder();
        list.forEach(sb::append);
        assertThat(sb.toString(), is("abc"));
    }
    @Test public void toArray() {
        assertThat(list.toArray(), is(new Object[] { "a", "b", "c" }));
    }
    @Test public void toArrayOfType() {
        assertThat(list.toArray(new String[3]), is(new String[] { "a", "b", "c" }));
        assertThat(list.toArray(new String[3]).getClass() == String[].class, is(true));
    }
    @Test public void add() {
        list.add("d");
        assertThat(list.contains("d"), is(true));
    }
    @Test public void removeObject() {
        assertThat(list.contains("a"), is(true));
        list.remove("a");
        assertThat(list.contains("a"), is(false));
    }
    @Test public void containsAll() {
        assertThat(list.containsAll(Lists.newArrayList("a", "b")), is(true));
        assertThat(list.containsAll(Lists.newArrayList("c", "d")), is(false));
    }
    @Test public void addAll() {
        list.addAll(Lists.newArrayList("d", "e"));
        assertThat(String.join("", list.toArray(new String[0])), is("abcde"));
    }
    @Test public void addAllOnIndex() {
        list.addAll(1, Lists.newArrayList("d", "e"));
        assertThat(list.toString(), is("adebc"));
    }
    @Test public void removeAll() {
        list.removeAll(Lists.newArrayList("b", "c"));
        assertThat(list.toString(), is("a"));
    }
    @Test public void removeIf() {
        list.removeIf("a"::equals);
        assertThat(list.toString(), is("bc"));
    }
    @Test public void retainAll() {
        list.retainAll(Lists.newArrayList("a", "b"));
        assertThat(list.toString(), is("ab"));
    }
    @Test public void replaceAll() {
        list.replaceAll(s -> s + ":");
        assertThat(list.toString(), is("a:b:c:"));
    }
    @Test public void sort() {
        list.add("1");
        assertThat(list.toString(), is("abc1"));
        list.sort(null);
        assertThat(list.toString(), is("1abc"));
    }
    @Test public void clear() {
        list.clear();
        assertThat(list.isEmpty(), is(true));
    }
    @Test public void get() {
        assertThat(list.get(0), is("a"));
        assertThat(list.get(1), is("b"));
        assertThat(list.get(2), is("c"));
        try { list.get(-1); fail("expected IndexOutOfBoundsException"); } catch(final IndexOutOfBoundsException e) { /* expected */ }
        try { list.get( 3); fail("expected IndexOutOfBoundsException"); } catch(final IndexOutOfBoundsException e) { /* expected */ }
    }
    @Test public void set() {
        list.set(0, "A");
        assertThat(list.toString(), is("Abc"));
        list.set(2, "C");
        assertThat(list.toString(), is("AbC"));
    }
    @Test public void addAtIndex() {
        list.add(0, "A");
        assertThat(list.toString(), is("Aabc"));
        list.add(3, "C");
        assertThat(list.toString(), is("AabCc"));
    }
    @Test public void removeIndex() {
        list.remove(1);
        assertThat(list.toString(), is("ac"));
        list.remove(0);
        assertThat(list.toString(), is("c"));
    }
    @Test public void indexOf() {
        assertThat(list.indexOf("b"), is(1));
        assertThat(list.indexOf("d"), is(-1));
        list.add("a");
        assertThat(list.indexOf("a"), is(0));
    }
    @Test public void lastIndexOf() {
        assertThat(list.lastIndexOf("a"), is(0));
        list.add("a");
        assertThat(list.lastIndexOf("a"), is(3));
    }
    @Test public void listIterator() {
        final StringBuilder sb = new StringBuilder();
        list.listIterator().forEachRemaining(sb::append);
        assertThat(sb.toString(), is("abc"));
    }
    @Test public void listIteratorAtIndex() {
        final StringBuilder sb = new StringBuilder();
        list.listIterator(1).forEachRemaining(sb::append);
        assertThat(sb.toString(), is("bc"));
    }
    @Test public void subList() {
        assertThat(String.join("", list.subList(1,3).toArray(new String[0])), is("bc"));
    }
    @Test public void spliterator() {
        final StringBuilder sb = new StringBuilder();
        list.spliterator().forEachRemaining(sb::append);
        assertThat(sb.toString(), is("abc"));
    }
    @Test public void stream() {
        assertThat(list.stream().skip(1).reduce("-" ,(a, s) -> a + s), is("-bc"));
    }
}