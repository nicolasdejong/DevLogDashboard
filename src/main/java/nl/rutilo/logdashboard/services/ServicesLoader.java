package nl.rutilo.logdashboard.services;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.ObjectCodec;
import com.fasterxml.jackson.core.io.IOContext;
import com.fasterxml.jackson.core.util.BufferRecycler;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLParser;
import nl.rutilo.logdashboard.Application;
import nl.rutilo.logdashboard.Configuration;
import nl.rutilo.logdashboard.Constants;
import nl.rutilo.logdashboard.util.NaturalOrderComparator;
import nl.rutilo.logdashboard.util.StringUtil;
import nl.rutilo.logdashboard.util.Timer;
import nl.rutilo.logdashboard.util.Util;
import org.apache.tomcat.util.http.fileupload.util.Streams;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static nl.rutilo.logdashboard.services.Service.LocationType.JAR;

public class ServicesLoader {
    private static final String       SERVICES_CFG_NAME           = "services.yaml";
    private static final String       CHECK_TIMER_NAME            = "ServicesLocationCheck";
    private static final Duration     CHECK_TIMER_DURATION        = Duration.ofSeconds(5);
    private static final Set<Service> servicesWithoutFileLocation = Collections.synchronizedSet(new HashSet<>());
    private static Optional<ConfigurationData> loadedConfiguration = Optional.empty();
    private static Optional<File> sourcePath = Optional.empty();
    public static String cfgError = null;

    public static class ConfigurationData {
        @SuppressWarnings("CanBeFinal") // set via reflection
        public       int                port      = Constants.DEFAULT_PORT;
        public       String             root      = null;
        public final Map<String,String> variables = new LinkedHashMap<>();
        public final List<Service>      services  = new ArrayList<>();
    }


    public static Optional<ConfigurationData> getConfiguration() { return loadedConfiguration.isPresent() ? loadedConfiguration : load(); }
    public static Optional<ConfigurationData> load() {
        Timer.clear(CHECK_TIMER_NAME);
        servicesWithoutFileLocation.clear();
        Service.defaults = null;
        loadedConfiguration = parseServicesConfiguration();
        loadedConfiguration.ifPresent(cfg -> {
            if(cfg.root != null) Configuration.setJarsDir(new File(cfg.root));
            final List<Service> services = cfg.services;
            services.forEach(s -> {
                if(s.getName() == null) s.setName("Service" + s.getUid());
                replaceVarsIn(s, cfg.variables);
            });
            repeatCheckFileLocations();
            handleGroups(services);
        });
        return loadedConfiguration;
    }

    private static void replaceVarsIn(Service service, Map<String,String> vars) {
        final Map<String, String> map = StringUtil.objectToMap(service);
        for(final Field field : Service.class.getDeclaredFields()) {
            if(field.getType() == String.class) {
                try {
                    field.setAccessible(true);
                    final String value = (String)field.get(service);

                    if(value != null && value.contains("$")) {
                        String newValue;
                        newValue = StringUtil.replaceVariable(map, value, /*hideUnknowns=*/false);
                        newValue = StringUtil.replaceVariable(vars, newValue, /*hideUnknowns=*/false);
                        if(!value.equals(newValue)) field.set(service, newValue);
                    }
                } catch (final IllegalAccessException ignored) {/*setAccessible(true) prevents this*/}
            }
        }
    }

    private static void repeatCheckFileLocations() {
        Timer.start(CHECK_TIMER_NAME, CHECK_TIMER_DURATION, () -> {
            setFileLocations(Services.get());
            repeatCheckFileLocations();
        });
    }

    public static void setFileLocations(Collection<Service> services) {
        services.forEach(ServicesLoader::setFileLocationOf);
    }
    public static void setFileLocationOf(Service service) {
        if(service.getLocationType() != JAR) return;
        if(service.getState().isRunningOk()) return;
        if(service.getFileLocation().map(File::exists).orElse(false)) return;

        final File serviceDir = service.getDir().orElseGet(Configuration::getJarsDir);
        final String location0 = service.getLocation().replace("\\", "/");
        final int lastSlash = location0.lastIndexOf('/');
        final String location = location0.substring(lastSlash + 1);
        final String locationDir = lastSlash >= 0 ? location0.substring(0, lastSlash) : "";
        final Pattern locPattern = Pattern.compile(
            Util.or(location, "")
                .replace("*", "@:@")
                .replace(".", "\\.")
                .replace("@:@", ".*")
        );
        final File jarsDir = new File(locationDir).equals(new File(locationDir).getAbsoluteFile())
                                                              ? new File(locationDir)
                                                              : new File(serviceDir, locationDir);
        final File[] files = jarsDir.listFiles((dir, name) -> locPattern.matcher(name).matches());
        if (files == null || files.length == 0) {
            service.setFileLocation(null);
            if (!servicesWithoutFileLocation.contains(service)) {
                service.logger.logError("ERROR: Could not find jar in " + jarsDir.getAbsolutePath());
                service.getState().setInitFailed();
                servicesWithoutFileLocation.add(service);
            }
        } else {
            Arrays.sort(files, new NaturalOrderComparator<>(File::getName).reversed()); // Most recent first
            service.setFileLocation(files[0]);
            servicesWithoutFileLocation.remove(service);
            service.getState().setWaiting();
            service.logger.logOther("Found jar in " + jarsDir.getAbsolutePath());
            service.getState().setOff();
        }
    }

    private static void handleGroups(Collection<Service> services) {
        final Map<String, String> groupToRestartCmd = new HashMap<>();
        final Set<String> groups = new HashSet<>();
        services.forEach(s -> {
            final String group = s.getGroup();
            final String cmd = s.getRestartCmd();
            if(group != null && cmd != null) groupToRestartCmd.put(group, cmd);
            if(group != null && !groups.contains(group)) { s.isGroupLead = true; groups.add(group); }
        });
        services.forEach(s -> {
            final String group = s.getGroup();
            final String cmd = s.getRestartCmd();
            if(group != null && cmd == null) s.setRestartCmd(groupToRestartCmd.get(group));
        });
    }
    private static Optional<ConfigurationData> parseServicesConfiguration() {
        final String cfgText = getServicesConfigurationText();
        final ObjectMapper objectMapper = new ObjectMapper(new ConfigurationYamlFactory());
        try {
            cfgError = null;
            return Optional.of(objectMapper
                    .enable(JsonParser.Feature.ALLOW_TRAILING_COMMA)
                    .configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES, true)
                    .readValue(cfgText, ConfigurationData.class)
            ).map(cfg -> {
                cfg.services.remove(Service.defaults);
                return cfg;
            });
        } catch (IOException e) {
            System.err.println((cfgError = "Configuration syntax error:\n"
                + e.getMessage()
                    .replace(", not marked as ignorable", "")
                    .replaceAll("\\s+\\(class [^)]+\\)", "")
                    .replaceAll("Cannot construct instance[^\n]+", "Syntax error")
                    .replaceAll("\\(through reference chain[^)]+\\)", "")
                    .replace("Source: (StringReader); ", "")
                    .trim()
            ));
            return Optional.empty();
        }
    }

    private static class ConfigurationYamlFactory extends YAMLFactory {
        @Override
        protected YAMLParser _createParser(Reader reader, IOContext ctxt) {
            return new ConfigurationYAMLParser(ctxt, _getBufferRecycler(), _parserFeatures, _yamlParserFeatures, _objectCodec, reader);
        }
    }
    private static class ConfigurationYAMLParser extends YAMLParser {
        private String lastName;
        private String structName;
        private ConfigurationData cfgData;
        public ConfigurationYAMLParser(IOContext ctxt, BufferRecycler bufferRecycler, int parserFeatures, int yamlParserFeatures, ObjectCodec objectCodec, Reader reader) {
            super(ctxt, bufferRecycler, parserFeatures, yamlParserFeatures, objectCodec, reader);
        }
        // normalize all key to lower case keys

        @Override
        public String getCurrentName() throws IOException {
            if (_currToken == JsonToken.FIELD_NAME) {
                lastName = super.getCurrentName();
            }
            return super.getCurrentName();
        }

        @Override
        public String getText() throws IOException {
            if (_currToken == JsonToken.VALUE_STRING) {
                String textValue = super.getText();
                if (cfgData != null && textValue.contains("$")) {
                    textValue = StringUtil.replaceVariable(cfgData.variables, textValue, /*hideUnknowns=*/false);
                }
                return textValue;
            }
            return super.getText();
        }

        @Override
        public JsonToken nextToken() throws IOException {
            final JsonToken tok = super.nextToken();
            if (tok.isStructStart()) {
                structName = lastName;
            }

            // variables
            if (tok.isStructEnd() && "variables".equals(structName)) {
                final Object currentValue = _parsingContext.getCurrentValue();
                if (currentValue instanceof ConfigurationData) {
                    cfgData = (ConfigurationData) currentValue;
                }
            }

            // default service
            if (tok.isStructEnd() && Service.defaults == null) {
                final Object currentValue = _parsingContext.getCurrentValue();

                if (currentValue instanceof Service && ((Service) currentValue).getName().matches("(?i)^defaults?$")) {
                    Service.defaults = (Service) currentValue; // NOSONAR -- static from configuration
                }
            }
            return tok;
        }
    }


    private static String getServicesConfigurationText() {
        try {
            final InputStream cfgIn = Util.orSupplyOptional(
                ServicesLoader::getConfigurationStreamFromFile,
                ServicesLoader::getConfigurationStreamFromJarFile
            ).orElseThrow(() -> new IllegalStateException("No configuration found?!")); // unlikely as cfg is in jar

            final String cfgData = Streams.asString(cfgIn);

            return cfgData.startsWith("<?xml")
                    ? cfgData // Jackson cannot handle this type of xml, so convert to json
                        .replaceAll("(?s)^.*?(<Service.*?)</root>", "{ services: [$1] }")
                        .replaceAll("<([^>]+)>(.*?)</\\1>", "\"$1\": \"$2\",")
                        .replace("<Service>", "{")
                        .replace("</Service>", "},")
                    : cfgData;
        } catch (IOException e) {
            e.printStackTrace();
            Application.exitWithError("Unable to load services configuration");
            return ""; // never get here but keep the compiler happy
        }
    }

    private static Optional<InputStream> getConfigurationStreamFromFile() {
        return getConfigurationFile().map(file -> {
            try {
                final InputStream stream = new FileInputStream(file);
                Application.log("Loading configuration from " + file);
                return stream;
            } catch (FileNotFoundException ignored) {
                return null;
            }
        });
    }
    private static Optional<InputStream> getConfigurationStreamFromJarFile() {
        return Util.orSupplyNullable(
            () -> Services.class.getResourceAsStream("/services.yaml"),
            () -> Services.class.getResourceAsStream("/services.xml")
        );
    }
    public static void setConfigurationPath(Optional<File> path) {
        sourcePath = path;
    }

    public static Optional<File> getConfigurationFile() {
        return Util.orSupplyOptional(
            () -> Optional.of(new File(Configuration.getJarsDir(), SERVICES_CFG_NAME)).filter(File::isFile),
            () -> sourcePath                                                          .filter(File::isFile),
            () -> sourcePath.map(f -> new File(f, SERVICES_CFG_NAME))                 .filter(File::isFile),
            () -> Optional.of(new File(SERVICES_CFG_NAME))                            .filter(File::isFile),
            () -> Optional.ofNullable(System.getProperty("propFile")).map(File::new)  .filter(File::isFile)
        );
    }
}
