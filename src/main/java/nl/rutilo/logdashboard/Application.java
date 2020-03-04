package nl.rutilo.logdashboard;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.LogbackException;
import ch.qos.logback.core.OutputStreamAppender;
import nl.rutilo.logdashboard.services.Services;
import nl.rutilo.logdashboard.services.ServicesLoader;
import nl.rutilo.logdashboard.util.CliArgs;
import nl.rutilo.logdashboard.util.IOUtil;
import nl.rutilo.logdashboard.util.LimitedSizeFile;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import javax.annotation.PostConstruct;
import javax.swing.*;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@SpringBootApplication
@Configuration
@EnableScheduling
public class Application {
    private static boolean noArgs;
    private static String[] mainArgs;
    private static CliArgs args;
    private static final Map<String, Object> props = new HashMap<>();
    private static final LimitedSizeFile logFile = initConsoleOutput();
    private static Application application;
    private static ConfigurableApplicationContext context;
    private static int port;

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(logFile::close));
    }


    public static void main(String[] argsArray) throws IOException {
        Upgrader.cleanup();
        Constants.LOCAL_DATA_DIR.mkdirs();

        mainArgs = argsArray; // used when restarting
        noArgs = argsArray.length == 0; // typical when doubleclick on jar icon -- any other way to detect if not started from cli?
        printIntro();
        args = new CliArgs.Builder()
            .setArgs(argsArray)
            .setAllowedKeys("root", "notray", "noautostart", "startall", "port", "config")
            .setAliasOf("help", "?")
            .setAliasOf("help", "h")
            .setIgnoreIllegals() // args also used by SpringBoot
            .build();
        if(args.hasFlag("help")) { printHelp(); return; }
        if(args.hasFlag("noautostart")) Services.noAutoStart = true;
        if(args.hasFlag("startall")) Services.autoStartAll = true;
        args.getValueOf("root").ifPresent(root -> nl.rutilo.logdashboard.Configuration.setJarsDir(checkExists(new File(root), "-root")));
        args.getValueOf("config").ifPresent(config -> ServicesLoader.setConfigurationPath(Optional.of(checkExists(new File(config), "-config"))));

        if(!args.hasFlag("notray")) Tray.init();

        start();
    }

    private static void start() {
        final int port = args.getValueOf("port").map(Integer::parseInt)
                             .orElseGet(() -> ServicesLoader.getConfiguration().map(cfg -> cfg.port).orElse(Constants.DEFAULT_PORT));
        props.put("server.port", port);

        if(!isPortAvailable(port)) {
            Application.log("Cannot start because port " + port + " is already in use");
            exit();
        }
        try {
            context = new SpringApplicationBuilder()
                .sources(Application.class)
                .properties(props)
                .initializers(new LoggingInterceptorInitializer())
                .run(mainArgs);
        } catch (final Exception failedToStart) {
            Application.log("Failed to start -- exit");
            exit();
        }
        application.storePort();
        Services.startProcesses();
    }
    public static void restart() {
        final Thread restartThread = new Thread(() -> {
            context.close();
            start();
        });
        restartThread.setDaemon(false);
        restartThread.start();
    }

    @PostConstruct
    public void started() {
        application = this;
    }

    public static void exitWithError(String message) {
        if(noArgs) JOptionPane.showMessageDialog(null, message + "\n\n(more details in console)", "Service Runner/Dashboard", JOptionPane.ERROR_MESSAGE);
        System.err.println("\n" + message);
        exitWithError();
    }
    public static void exitWithError() {
        System.exit(10);
    }
    public static void exit() {
        System.exit(0);
    }

    private static File checkExists(File path, String optName) {
        if(!path.exists()) exitWithError("Path for " + optName + " not found: " + path);
        return path;
    }

    private static void printIntro() {
        System.out.println(ServerInfo.get().introText); // NOSONAR first log line
    }
    private static void printHelp() throws IOException {
        System.out.println(IOUtil.asString(IOUtil.exhaust(Application.class.getResourceAsStream("/cli_help.txt")))); // NOSONAR help output
    }

    public static int getPort() { return port; }

    private static int getPortInSpring() {
        return Integer.parseInt(context.getEnvironment().getProperty("server.port", "0"));
    }
    private void storePort() {
        try {
            port = getPortInSpring();
            Tray.portChangedTo(port);
        } catch(final Exception e) {
            e.printStackTrace();
            port = 0;
        }
    }
    public static boolean isPortAvailable(int port) {
        try(final ServerSocket ss = new ServerSocket(port)) {
            return true;
        } catch (final IOException ignored) {
            return false;
        }
    }

    public static void log(Object... messageParts) {
        final String prefix = LocalDateTime.now().format(Constants.TIME_FORMATTER) + "]  ";
        System.out.println(prefix + // NOSONAR this is a log endpoint
            Arrays.stream(messageParts)
            .map(a -> a == null ? "[null]" : a.toString())
            .collect(Collectors.joining(" "))
        );
    }

    private static class LoggingInterceptorInitializer implements ApplicationContextInitializer {
         @Override public void initialize(ConfigurableApplicationContext applicationContext) {
              final LoggerContext context    = (LoggerContext) LoggerFactory.getILoggerFactory();
              final Logger        rootLogger = context.getLogger(Logger.ROOT_LOGGER_NAME);

              final OutputStreamAppender<ILoggingEvent> consoleAppender =
                    (OutputStreamAppender<ILoggingEvent>) rootLogger.getAppender("CONSOLE");
              final String consolePattern = consoleAppender == null
                  ? "%d %-5p [%c{1}] %m%n"
                  : ((PatternLayoutEncoder)consoleAppender.getEncoder()).getPattern();

              rootLogger.detachAppender(consoleAppender);

              final PatternLayout pattern = new PatternLayout();
              pattern.setPattern(consolePattern);
              pattern.setContext(context);
              pattern.start();
              final OutputStreamAppender<ILoggingEvent> appender = new OutputStreamAppender<ILoggingEvent>() {
                  public void doAppend(ILoggingEvent event) throws LogbackException {
                      System.out.print(pattern.doLayout(event));
                  }
              };
              appender.setContext(context);
              appender.setLayout(pattern);
              rootLogger.addAppender(appender);
         }
    }

    private static class ConsoleInterceptor extends PrintStream {
        final LimitedSizeFile file;
        final boolean isErrors;
        public ConsoleInterceptor(OutputStream out, LimitedSizeFile file, boolean isErrors) {
            super(out, /*autoFlush=*/true);
            this.file = file;
            this.isErrors = isErrors;
        }
        @Override public void print(String s) {
            final String noCtrl = s.replaceAll("\\x1B\\[[\\d;]+m", "");
            super.print((isErrors ? "!" : " ") + s);
            file .write((isErrors ? "!" : " ") + noCtrl);
        }
        // The next only exists because super.newLine() is private and so cannot be overloaded
        protected void newLine() { file.write("\n"); }
        @Override public void println()          { super.println();  newLine(); }
        @Override public void println(boolean x) { super.println(x); newLine(); }
        @Override public void println(char x)    { super.println(x); newLine(); }
        @Override public void println(int x)     { super.println(x); newLine(); }
        @Override public void println(long x)    { super.println(x); newLine(); }
        @Override public void println(float x)   { super.println(x); newLine(); }
        @Override public void println(double x)  { super.println(x); newLine(); }
        @Override public void println(char x[])  { super.println(x); newLine(); }
        @Override public void println(String x)  { super.println(x); newLine(); }
        @Override public void println(Object x)  { super.println(x); newLine(); }
    }

    private static LimitedSizeFile initConsoleOutput() {
        final LimitedSizeFile file = new LimitedSizeFile(Constants.OUTPUT_LOG_FILE, Constants.OUTPUT_LOG_SIZE);
        System.setOut(new ConsoleInterceptor(System.out, file, /*isErrors=*/false));
        System.setErr(new ConsoleInterceptor(System.err, file, /*isErrors=*/true));
        return file;
    }
}
