package nl.rutilo.logdashboard.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Objects;
import java.util.Spliterator;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** List where its items are stored in an envelope.
  */
@SuppressWarnings("unchecked")
public abstract class EnvelopeList<T> implements List<T> {
    private static <N> N or(N value, Supplier<N> p) { return value != null ? value : p.get(); }

    public abstract static class Envelope implements Comparable<Envelope>, Comparator<Envelope> {
        private final Object value;
        public Envelope(Object value) {
            this.value = value;
        }
        public boolean equals(Object o) {
            return o instanceof EnvelopeList.Envelope && Objects.equals(this.value, ((Envelope)o).value);
        }
        public int hashCode() { return value == null ? 0 : value.hashCode(); }
        public Object get() { return value; }
        @Override public int compareTo(Envelope o) { return compare(this, o); }
        @Override public int compare(Envelope e1, Envelope e2) {
            return e1 == e2
                       ? 0
                       : e1 == null || e1.value == null
                           ? -1
                           : e2 == null || e2.value == null
                               ? 1
                               : Long.signum(e1.value.hashCode() - e2.value.hashCode());
        }
    }

    private final LinkedList<Envelope> list = new LinkedList<>();

    public EnvelopeList() {}
    private Collection<Envelope> itemCopy(Collection<?> objects) {
        final ArrayList<Envelope> copy = new ArrayList(objects.size());
        objects.forEach(obj -> copy.add(newEnvelopeFor(obj)));
        return copy;
    }

    protected abstract Envelope newEnvelopeFor(Object value);

    public Envelope getEnvelopeAt(int index) {
        return list.get(index);
    }
    public Iterator<Envelope> envelopeIterator() {
        return list.iterator();
    }

    public T removeFirst() { return (T)list.removeFirst().get(); }

    @Override public int size() {
        return list.size();
    }
    @Override public boolean isEmpty() {
        return list.isEmpty();
    }
    @Override public boolean contains(Object o) {
        return list.contains(newEnvelopeFor(o));
    }
    @Override public Iterator<T> iterator() {
        return listIterator();
    }
    @Override public void forEach(Consumer<? super T> action) {
        list.forEach(item -> action.accept((T)item.get()));
    }
    @Override public Object[] toArray() {
        final Object[] objects = list.toArray();
        for(int i=0; i<objects.length; i++) { objects[i] = ((Envelope)objects[i]).get(); }
        return objects;
    }
    @Override public <AT> AT[] toArray(AT[] a) {
        final Object[] objects = list.toArray();
        final AT[] entries = a.length < objects.length
            ? (AT[])java.lang.reflect.Array.newInstance(a.getClass().getComponentType(), objects.length)
            : a;
        for(int i=0; i<objects.length; i++) { entries[i] = (AT)((Envelope)objects[i]).get(); }
        return entries;
    }
    @Override public boolean add(T t) {
        return list.add(newEnvelopeFor(t));
    }
    @Override public boolean remove(Object o) {
        return list.remove(newEnvelopeFor(o));
    }
    @Override public boolean containsAll(Collection<?> c) {
        return list.containsAll(itemCopy(c));
    }
    @Override public boolean addAll(Collection<? extends T> c) {
        return list.addAll(itemCopy(c));
    }
    @Override public boolean addAll(int index, Collection<? extends T> c) {
        return list.addAll(index, itemCopy(c));
    }
    @Override public boolean removeAll(Collection<?> c) {
        return list.removeAll(itemCopy(c));
    }
    @Override public boolean removeIf(Predicate<? super T> filter) {
        return list.removeIf(item -> filter.test((T)item.get()));
    }
    @Override public boolean retainAll(Collection<?> c) {
        return list.retainAll(itemCopy(c));
    }
    @Override public void replaceAll(UnaryOperator<T> operator) {
        list.replaceAll(item -> newEnvelopeFor(operator.apply((T)item.get())));
    }
    @Override public void sort(Comparator<? super T> c) {
        final class ItemComparator implements Comparator<Envelope> {
            private Comparator<? super T> parent;
            private boolean isReversed;
            public ItemComparator(Comparator<? super T> parent, boolean isReversed) {
                this.parent = parent;
                this.isReversed = isReversed;
            }
            @Override public int compare(Envelope o1, Envelope o2) {
                return c.compare((T)o1.get(), (T)o2.get());
            }
            @Override  public Comparator<Envelope> reversed() {
                return new ItemComparator(parent, true);
            }
        }
        if(c == null) {
            list.sort(null);
        } else {
            list.sort(new ItemComparator(c, false));
        }
    }
    @Override public void clear() {
        list.clear();
    }
    @Override public T get(int index) {
        return (T)list.get(index).get();
    }
    @Override public T set(int index, T element) {
        return (T)or(list.set(index, newEnvelopeFor(element)), () -> newEnvelopeFor(null)).get();
    }
    @Override public void add(int index, T element) {
        list.add(index, newEnvelopeFor(element));
    }
    @Override public T remove(int index) {
        return (T)list.remove(index).get();
    }
    @Override public int indexOf(Object o) {
        return list.indexOf(newEnvelopeFor(o));
    }
    @Override public int lastIndexOf(Object o) {
        return list.lastIndexOf(newEnvelopeFor(o));
    }
    @Override public ListIterator<T> listIterator() {
        return listIterator(0);
    }
    @Override public ListIterator<T> listIterator(int index) {
        final ListIterator<Envelope> it = list.listIterator(index);
        return new ListIterator<T>() {
            @Override public boolean hasNext()     { return it.hasNext(); }
            @Override public T next()              { return (T)it.next().get(); }
            @Override public boolean hasPrevious() { return it.hasPrevious(); }
            @Override public T previous()          { return (T)or(it.previous(), () -> newEnvelopeFor(null)).get(); }
            @Override public int nextIndex()       { return it.nextIndex(); }
            @Override public int previousIndex()   { return it.previousIndex(); }
            @Override public void remove()         { it.remove(); }
            @Override public void set(T t)         { it.set(newEnvelopeFor(t)); }
            @Override public void add(T t)         { it.add(newEnvelopeFor(t)); }
        };
    }
    @Override public List<T> subList(int fromIndex, int toIndex) {
        return list.subList(fromIndex, toIndex).stream().map(item -> (T)item.get()).collect(Collectors.toList());
    }
    @Override public Spliterator<T> spliterator() {
        return list.stream().map(item -> (T)item.get()).spliterator();
    }
    @Override public Stream<T> stream() {
        return list.stream().map(item -> (T)item.get());
    }
    @Override public Stream<T> parallelStream() {
        return list.parallelStream().map(item -> (T)item.get());
    }
}