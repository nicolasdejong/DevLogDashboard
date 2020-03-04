package nl.rutilo.logdashboard.util;

import nl.rutilo.logdashboard.Application;

import java.util.*;

public class CliArgs {
    private final Set<String> flags = new HashSet<>();
    private final Map<String,String[]> options = new HashMap<>();

    public static class Builder {
        String[] args = new String[0];
        Set<String> allowedFlags = null;
        Set<String> allowedKeys = null;
        final Map<String, String> aliases = new HashMap<>();
        boolean ignoreIllegals = false;
        boolean allowIllegals = true;

        public Builder() {}
        public CliArgs build() { return new CliArgs(this); }

        public Builder setArgs(String... args) {
            if(args != null) this.args = args;
            return this;
        }
        public Builder setAllowedFlags(String... flags) {
            for(String flag: flags) setAllowedFlag(flag);
            return this;
        }
        public Builder setAllowedFlag(String flag) {
            if(allowedFlags == null) allowedFlags = new HashSet<>();
            allowedFlags.add(flag);
            return this;
        }
        public Builder setAllowedKeys(String... keys) {
            for(String key: keys) setAllowedKey(key);
            return this;
        }
        public Builder setAllowedKey(String key) {
            if(allowedKeys == null) allowedKeys = new HashSet<>();
            allowedKeys.add(key);
            return this;
        }
        public Builder setIgnoreIllegals() { return setIgnoreIllegals(true); }
        public Builder setIgnoreIllegals(boolean set) { ignoreIllegals = set; return this; }
        public Builder setAllowIllegals() { return setAllowIllegals(true); }
        public Builder setAllowIllegals(boolean set) { allowIllegals = set; return this; }
        public Builder setAliasOf(String keyOrFlag, String alias) { aliases.put(alias, keyOrFlag); return this; }

        private String convert(String s) { return aliases.getOrDefault(s, s); }
        private boolean isFlagAllowed(String flag) {
            return allowedFlags == null || allowedFlags.contains(flag);
        }
        private boolean isKeyAllowed(String key) {
            return allowedKeys == null  || allowedKeys.contains(key);
        }
    }

    /** For more flexibility, use the Builder */
    public static CliArgs createFor(String... args) {
        return new Builder().setArgs(args).build();
    }

    CliArgs(Builder settings) {
        final Map<String,List<String>> map = new TreeMap<>();
        String prevKey = null;

        for(final String arg : settings.args) {
            if(arg.startsWith("-") || prevKey == null) {
                final String[] parts = arg.split("=", 2);
                prevKey = settings.convert(parts[0].replaceAll("^-+",""));
                map.put(prevKey, new ArrayList<>());
                if(parts.length>1) map.get(prevKey).add(parts[1].replaceAll("^[\"']|[\"']$", ""));
            } else {
                map.get(prevKey).add(arg);
            }
        }
        final boolean[] hasErrors = { false };
        final List<String> errors = new ArrayList<>();
        map.forEach((key, list) -> {
            if(list.isEmpty()) {
                if(settings.isFlagAllowed(key)) {
                    flags.add(key);
                } else {
                    if(!settings.ignoreIllegals) {
                        if(settings.allowIllegals) errors.add("Ignored unsupported flag: " + key);
                        else { hasErrors[0] = true; errors.add("Unsupported flag: " + key); }
                    }
                }
            } else {
                if(settings.isKeyAllowed(key)) {
                    options.put(key, list.toArray(new String[0]));
                } else {
                    if(!settings.ignoreIllegals) {
                        if(settings.allowIllegals) errors.add("Ignored unsupported key: " + key);
                        else { hasErrors[0] = true; errors.add("Unsupported key: " + key); }
                    }
                }
            }
        });
        if(hasErrors[0]) exitWithError(errors.stream().reduce("Command line errors:", (a,s) -> a + "\n - " + s));
    }

    protected void exitWithError(String message) {
        Application.exitWithError(message);
    }

    public boolean hasFlag(String name) {
        return flags.contains(name);
    }

    public boolean hasValuesFor(String name) {
        return options.containsKey(name);
    }

    public Optional<String> getValueOf(String name) {
        return options.containsKey(name) && options.get(name).length == 1
            ? Optional.of(options.get(name)[0])
            : Optional.empty();
    }

    public String[] getValuesOf(String name) {
        return options.containsKey(name) && options.get(name).length > 0
            ? options.get(name)
            : new String[0];
    }

    public String toString() {
        final String opts = options.entrySet().stream().map(entry -> entry.getKey() + ":" + Arrays.asList(entry.getValue())).reduce((a, s) -> a + ", " + s).orElse("");
        return "[CliArgs flags=" + flags + "; options={" + opts + "}]";
    }

}
