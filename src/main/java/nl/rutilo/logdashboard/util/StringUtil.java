package nl.rutilo.logdashboard.util;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class StringUtil {
    private StringUtil() {}
    protected static final Map<String,Function<String[],String>> pipeFunctions = new HashMap<>();

    static {
        pipeFunctions.put("base64", args -> PipeCommands.base64(firstOf(args, ""), PipeCommands.cli(restOf(args))));
        pipeAlias("base64encode", "base64", "--encode");
        pipeAlias("base64decode", "base64", "--decode");
        pipeFunctions.put("toupper", args -> PipeCommands.toUpper(firstOf(args, "")));
        pipeFunctions.put("tolower", args -> PipeCommands.toLower(firstOf(args, "")));
        pipeAlias("toUpper", "toupper");
        pipeAlias("toLower", "tolower");
    }

    private static void pipeAlias(String alias, String target, String... args) {
        final Function<String[], String> func = pipeFunctions.get(target);
        if(func == null) throw new IllegalArgumentException("Unknown pipe function: " + target);
        pipeFunctions.put(alias, fargs -> func.apply(join(fargs, args)));
    }

    static class PipeCommands {
        private PipeCommands() {}
        public static CliArgs.Builder cli(String... args) { return new CliArgs.Builder().setArgs(args); }

        public static String base64(String targetIn, CliArgs.Builder argsb) {
            final String target = Util.or(targetIn, "");
            final CliArgs args = argsb.setAllowedKeys("encode", "decode").build();
            if(args.hasFlag("decode")) return new String(Base64.getDecoder().decode(target));
            return Base64.getEncoder().encodeToString(target.getBytes(StandardCharsets.UTF_8));
        }
        public static String toUpper(String targetIn) {
            return targetIn.toUpperCase(Locale.getDefault());
        }
        public static String toLower(String targetIn) {
            return targetIn.toLowerCase(Locale.getDefault());
        }
    }

    public static Optional<String> firstOf(String[] array) { return Optional.ofNullable(firstOf(array, null)); }
    public static String firstOf(String[] array, String ifNoFirst) { return array.length > 0 ? array[0] : ifNoFirst; }
    public static String[] restOf(String[] array) { return skip(array, 1); }
    public static String[] skip(String[] args, int count) {
        return Arrays.stream(args).skip(count).toArray(String[]::new);
    }
    public static String[] join(String[] a, String[] b) {
        final String[] c = (String[]) Array.newInstance(a.getClass().getComponentType(), a.length + b.length);
        System.arraycopy(a, 0, c, 0, a.length);
        System.arraycopy(b, 0, c, a.length, b.length);
        return c;
    }
    public static String[] join(String a, String[] b) { return join(new String[] { a }, b); }
    public static String[] join(String[] a, String b) { return join(a, new String[] { b }); }

    public static String replacem(String input, Pattern regex, Function<Matcher, String> replacer) {
        final Matcher      m  = regex.matcher(input);
        final StringBuffer sb = new StringBuffer();
        while (m.find()) { m.appendReplacement(sb, ""); sb.append(replacer.apply(m)); }
        m.appendTail(sb);
        return sb.toString();
    }
    public static String replacem(String input, String regex, Function<Matcher, String> replacer) {
        return replacem(input, Pattern.compile(regex), replacer);
    }
    public static String replace(String input, Pattern regex, UnaryOperator<String> replacer) {
        return replacem(input, regex, mat -> replacer.apply(mat.group()));
    }
    public static String replace(String input, String regex, UnaryOperator<String> replacer) {
        return replace(input, Pattern.compile(regex), replacer);
    }

    public static void handleStringPartsm(String input, Pattern regex, Consumer<Matcher> handler) {
        replacem(input, regex, mat -> { handler.accept(mat); return ""; });
    }
    public static void handleStringPartsm(String input, String regex, Consumer<Matcher> handler) {
        handleStringPartsm(input, Pattern.compile(regex), handler);
    }
    public static void handleStringParts(String input, Pattern regex, Consumer<String> handler) {
        replacem(input, regex, mat -> { handler.accept(mat.group()); return ""; });
    }
    public static void handleStringParts(String input, String regex, Consumer<String> handler) {
        handleStringParts(input, Pattern.compile(regex), handler);
    }

    public static List<String> getStringParts(String input, Pattern regex) {
        final List<String> results = new ArrayList<>();
        handleStringPartsm(input, regex, m -> {
            if(m.groupCount() == 0) results.add(m.group());
            else for(int i=1; i<=m.groupCount(); i++) results.add(m.group(i));
        });
        return results;
    }
    public static List<String> getStringParts(String input, String regex) {
        return getStringParts(input, Pattern.compile(regex));
    }

    /**
     * Split a command line into its separate parts.
     * @param toProcess the command line to process.
     * @return the command line broken into strings.
     */
    public static String[] splitCommandLine(String toProcess) {
        final String       line     = Util.or(toProcess, "").trim();
        final String       qprefix  = "@@Q:@@";
        final List<String> quotes   = new ArrayList<>();
        final String       noQuotes = replace(line, "(['\"`]).*?\\1", q -> { quotes.add(q); return qprefix + quotes.size() + "@"; });
        final List<String> parts    = new ArrayList<>();

        handleStringPartsm(noQuotes, "(\\S+(\\s*=\\s*)?)+", m -> parts.add(m.group()));
        return parts.stream()
            .map(p -> p.replaceAll("\\s*=\\s*", "="))
            .map(p -> replacem(p, qprefix + "(\\d+)@", m -> quotes.get(Integer.parseInt(m.group(1))-1)))
            .toArray(String[]::new);
    }

    /**
     * Replaces variables in the form ${variableName} by their value from the given map.
     * <pre>
     * Examples:
     * - replaceVariable({a:aVal}, "${a}", false) = "aVal"
     * - replaceVariable({a:aVal}, "${b}", false) = "${b}"
     * - replaceVariable({a:aVal}, "${c}", true)  = ""
     *
     * @param vars           Map of variable name to variable value.
     * @param s              String to replace variables in.
     * @param hideUnknowns   True to replace variables that have no key in vars by an empty string.
     *                       False to leave the variable as is.
     * @return String with variables replaced.
     */
    public static String replaceVariable(Map<String,String> vars, String s, boolean hideUnknowns) {
        final String dollar = "@%dollar%@{";
        String oldS;
        String newS = s;
        int maxLoops = 100;
        do {
            oldS = newS;
            newS = replacem(oldS, "\\$\\{([^{}]+)}",
                m -> Util.orSupplyOptional(
                        () -> getVariableValue(vars, m.group(1)),
                        () -> getSystemVar(m.group(1))
                    )
                    .orElseGet(() -> hideUnknowns ? "" : dollar + m.group(1) + "}")
                    //.replace("$", "\\$")
            );
        } while(!oldS.equals(newS) && maxLoops-->0);
        return newS.replace(dollar, "${");
    }

    private static Optional<String> getSystemVar(String varName) {
        return Optional.ofNullable(System.getenv(varName));
    }

    private static Optional<String> getVariableValue(Map<String,String> vars, String varText) {
        final String[] parts = varText.split("\\s+\\|\\s+", -1);
        return Util.orSupplyNullable(()->vars.get(parts[0]), ()->parts.length == 1 ? null : parts[0])
            .map(valText -> Arrays.stream(parts).skip(1).reduce(valText, (result, funcText) -> {
                final String[] funcParts = splitCommandLine(funcText);
                final Function<String[],String> func = funcParts.length > 0 ? pipeFunctions.get(funcParts[0]) : null;
                //if(func == null) throw new IllegalArgumentException("Unknown pipe function requested: " + funcParts[0]);
                return func == null ? funcText : func.apply(join(result, skip(funcParts, 1)));
            }));
    }

    /** Calls @{link #replaceVariable(Map, String, boolean)} for each element in the given array */
    public static String[] replaceVariables(Map<String,String> vars, String[] parts, boolean hideUnknowns) {
        return Arrays.stream(parts).map(s -> replaceVariable(vars, s, hideUnknowns)).toArray(String[]::new);
    }

    public static String replaceVariable(Object obj, String text) {
        return replaceVariable(objectToMap(obj), text, /*hideUnknowns=*/false);
    }

    public static Map<String,String> objectToMap(Object obj) {
        final Map<String, String> objVars = new HashMap<>();
        for(final Field field : obj.getClass().getDeclaredFields()) {
            try {
                field.setAccessible(true);
                final String name = field.getName();
                final Object value = field.get(obj);
                if(value != null) objVars.put(name, value.toString());
            } catch (final IllegalAccessException ignored) {/*setAccessible(true) prevents this*/}
        }
        return objVars;
    }

    public static long sizeToLong(String size) {
        final String[]  parts = size.replaceAll("[\\s_]","").split("(?=\\D)");
        final long      value = parts.length > 0 && parts[0].matches("\\d+") ? Integer.parseInt(parts[0]) : 0;
        final String   suffix = parts.length > 1 ? parts[1].toLowerCase(Locale.US) : "";
        final long   multiply = (long)Math.pow(1024, Math.max(0, "bkmgtpe".indexOf(suffix)));
        return value * multiply;
    }
}
