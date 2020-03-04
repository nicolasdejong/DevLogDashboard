package nl.rutilo.logdashboard.services;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static nl.rutilo.logdashboard.services.ServiceState.State;

/** Make sure to call update() on state changes. */
public class ServicesStateHistory {
    private final List<Service> services;
    private final long resolutionMs;
    private final long maxCount;

    class Row {
        long time;
        final List<List<State>> items;
        Row() {
            time = System.currentTimeMillis();
            items = getCurrentStates().stream().map(ServicesStateHistory::listFor).collect(Collectors.toList());
        }
        Row(Row other) {
            time = other.time;
            items = other.items.stream().map(ArrayList::new).collect(Collectors.toList());
        }
        public long getTime() { return time; }
        public String toString() {
            return String.join(",", items.stream().map(this::getStateLetters).collect(Collectors.toList()));
        }
        private String getStateLetters(List<State> states) {
            return String.join("", states.stream().map(this::getStateLetter).collect(Collectors.toList()));
        }
        private String getStateLetter(State state) {
            switch(state) {
                case OFF:           return "O";
                case WAITING:       return "W";
                case STARTING:      return "S";
                case RUNNING:       return "R";
                case INIT_ERROR:    return "I";
                case RUNNING_ERROR: return "E";
            }
            return ""; // never get here, but the compiler wants it;
        }
    }
    final List<Row> rows = new ArrayList<>();

    public static class ServicesStateHistoryBuilder1 {
        private final List<Service> services;
        private ServicesStateHistoryBuilder1(List<Service> services) { this.services = new ArrayList<>(services); }
        public ServicesStateHistoryBuilder2 withDuration(Duration duration) {
            return new ServicesStateHistoryBuilder2(services, duration);
        }
    }
    public static class ServicesStateHistoryBuilder2 {
        private final List<Service> services;
        private final Duration      duration;
        private ServicesStateHistoryBuilder2(List<Service> services, Duration duration) {
            this.services = services;
            this.duration = duration;
        }
        public ServicesStateHistory andResolution(Duration resolution) {
            return new ServicesStateHistory(services, resolution.toMillis(), duration.toMillis());
        }
    }
    public static ServicesStateHistoryBuilder1 forServices(List<Service> services) {
        return new ServicesStateHistoryBuilder1(services);
    }

    public static ServicesStateHistory empty() {
        return forServices(new ArrayList<>())
            .withDuration(Duration.ofHours(1000))
            .andResolution(Duration.ofHours(100));
    }

    private ServicesStateHistory(List<Service> services, long resolutionMs, long durationMs) {
        this.services = services;
        this.resolutionMs = resolutionMs;
        this.maxCount = durationMs / resolutionMs;
        if(maxCount < 1) throw new IllegalArgumentException("duration should be longer than resolution");
        update();
    }

    public Map<Long,String> getAsTimeToString() {
        update();
        return rows.stream().collect(Collectors.toMap(Row::getTime, Object::toString));
    }
    public Map<Long,String> getAsLastTimeToString() {
        update();
        final Map<Long, String> map = new HashMap<>();
        if(!rows.isEmpty()) map.put(lastOf(rows).time, lastOf(rows).toString());
        return map;
    }

    public void update() {
        if(needsNewRow()) {
            addRow();
            update();
        } else {
            final Row lastRow = lastOf(rows);
            final List<State> states = getCurrentStates();
            for(int i=0; i<states.size(); i++) {
                final State state = states.get(i);
                final List<State> item = lastRow.items.get(i);
                // Only add state changes.
                if(item.isEmpty() || lastOf(item) != state) {
                    item.add(state);
                }
            }
        }
    }

    public void servicesWereUpdated(List<Service> newServices, Map<Service,Service> oldToNewMapping) {
        class Locals {
            List<Row> createNewRows() {
                final List<Row> newRows = new ArrayList<>();
                for(final Row oldRow : rows) {
                    newRows.add(createNewRow(oldRow));
                }
                return newRows;
            }
            Row createNewRow(Row oldRow) {
                final Row newRow = new Row(oldRow);
                newRow.items.forEach(List::clear);
                while(newRow.items.size() < newServices.size()) newRow.items.add(new ArrayList<>());
                keepStatesOfRemainingServices(oldRow, newRow);
                while(newRow.items.size() > newServices.size()) newRow.items.remove(newRow.items.size()-1);

                return newRow;
            }
            void keepStatesOfRemainingServices(Row oldRow, Row newRow) {
                for(int oldIndex=0; oldIndex<oldRow.items.size(); oldIndex++) {
                    final Service oldService = services.get(oldIndex);
                    final Service newService = oldToNewMapping.get(oldService);
                    if(newService != null) {
                        final List<State> oldStates = oldRow.items.get(oldIndex);
                        final int newIndex = newServices.indexOf(newService);
                        if(newIndex >= 0 && newIndex < newRow.items.size()) newRow.items.set(newIndex, oldStates);
                    }
                }
            }
        }
        final Locals locals = new Locals();
        final List<Row> newRows = locals.createNewRows();
        rows.clear();
        rows.addAll(newRows);
        services.clear();
        services.addAll(newServices);
    }


    private boolean needsNewRow() {
        final long now = System.currentTimeMillis();
        return rows.isEmpty() || now - lastOf(rows).time > resolutionMs;
    }
    private void addRow() {
        final long now = System.currentTimeMillis();
        final Row lastRow = rows.isEmpty() ? null : lastOf(rows);
        final Row row = rows.isEmpty() ? new Row() : new Row(lastOf(rows));
        row.time = (lastRow == null ? now - resolutionMs : lastRow.time) + resolutionMs;
        rows.add(new Row());
        if(rows.size() > maxCount) rows.remove(0);
    }

    private List<State> getCurrentStates() {
        return services.stream().map(s -> s.state.getState()).collect(Collectors.toList());
    }

    private static List<State> listFor(State state) {
        final List<State> list = new ArrayList<>();
        list.add(state);
        return list;
    }
    private static <T> T lastOf(List<T> list) {
        return list.get(list.size()-1);
    }
}
