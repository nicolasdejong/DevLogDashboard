package nl.rutilo.logdashboard.services;

import nl.rutilo.logdashboard.Application;
import nl.rutilo.logdashboard.Constants;
import nl.rutilo.logdashboard.util.Listeners;
import nl.rutilo.logdashboard.util.Util;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static nl.rutilo.logdashboard.services.Service.LocationType.*;


public class Services {
    private static Optional<SimpMessagingTemplate> msgTemplate      = Optional.empty();
    private static final List<Service>             services         = ServicesLoader.getConfiguration().map(cfg -> cfg.services).orElseGet(ArrayList::new);
    private static final ServicesStateHistory  servicesStateHistory = ServicesStateHistory
                                                                          .forServices(services)
                                                                          .withDuration(Duration.ofHours(24))
                                                                          .andResolution(Duration.ofMinutes(5));
    private static final Set<Service>             servicesToStart     = new LinkedHashSet<>();
    private static final Consumer<ServiceState>   stateChangeListener = state -> {
        msgTemplate.ifPresent(mt -> {
            servicesStateHistory.update();
            mt.convertAndSend(Constants.MSG_TOPIC_STATE_CHANGE, state.getService());
            mt.convertAndSend(Constants.MSG_TOPIC_LAST_STATE_HISTORY, servicesStateHistory.getAsLastTimeToString());
            callStateChangeListeners(state);
            checkRuns();
        });
    };
    private static final Listeners<ServiceState>      listeners           = new Listeners<ServiceState>().debounced();
    private static final Listeners<Void>              newCfgListeners     = new Listeners<>();
    private static       boolean                      startParallel       = false;
    private static       boolean                      startIgnoreDeps     = false;
    private static final ScheduledExecutorService     scheduler           = Executors.newScheduledThreadPool(1);
    private static final ServicesConfigurationChecker configChecker       = new ServicesConfigurationChecker().whenChanged(Services::updateForNewConfiguration);
    public static        boolean                      noAutoStart         = false;
    public static        boolean                      autoStartAll        = false; // only use this for the initial configuration load

    public static void reload() {
        updateForNewConfiguration();
    }

    public static void startProcesses() {
        scheduler.scheduleAtFixedRate(configChecker::check,         /*initialDelay*/5, /*delay*/1, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(Services::sendLogVelocities,  /*initialDelay*/5, /*delay*/10, TimeUnit.SECONDS);

        ServicesLoader.setFileLocations(services);
        services.forEach(Services::initForLoadedService);
    }

    public static void setWebsocketMessaging(SimpMessagingTemplate smt) {
        msgTemplate = Optional.ofNullable(smt);
    }

    public static void addStateChangeListener(Consumer<ServiceState> listener) { listeners.add(listener); }
    public static void removeStateChangeListener(Consumer<ServiceState> listener) { listeners.remove(listener); }
    private static void callStateChangeListeners(ServiceState changedState) { listeners.call(changedState); }

    public static void addConfigurationChangeListener(Consumer<Void> listener) { newCfgListeners.add(listener); }
    public static void removeConfigurationChangeListener(Consumer<Void> listener) { newCfgListeners.remove(listener); }
    private static void callConfigurationChangeListeners() { newCfgListeners.call(null); }

    public static List<Service> get() { return services; }
    private static void initForLoadedService(Service s) {
        s.state.addChangeListener(stateChangeListener);
        if(s.getLocationType() == URL || s.getLocationType() == PORT || s.getLocationType() == LOG) s.getRunner().start();
        else if((s.isStart() || autoStartAll) && !noAutoStart) start(s);
    }

    private static void sendLogVelocities() {
        if (msgTemplate == null) return;
        msgTemplate.ifPresent(mt -> mt.convertAndSend(Constants.MSG_TOPIC_LOG_VELOCITIES,
            services.stream().mapToInt(service -> service.getState().getLogVelocity())
        ));
    }
    private static void updateForNewConfiguration() {
        //Application.log("Configuration change detected -- updating services");
        final int oldPort = ServicesLoader.getConfiguration().map(cfg -> cfg.port).orElse(Constants.DEFAULT_PORT);

        // This method was called because an update was detected on the cfg file. Here read it.
        ServicesLoader.load();

        final List<Service> oldServices = new ArrayList<>(services);
        final List<Service> cfgServices = ServicesLoader.getConfiguration().map(cfg -> cfg.services).orElseGet(ArrayList::new);
        if(cfgServices.isEmpty()) {
            Application.log("Failed to read updated configuration -- ignoring (reload client to show error)");
            msgTemplate.ifPresent(mt -> mt.convertAndSend(Constants.MSG_TOPIC_SERVICES_RELOADED, "update"));
            return;
        }

        // A new list of services is loaded.
        // Try to keep running services running, unless they were removed
        handleServiceConfigurationUpdate(oldServices, cfgServices);

        callConfigurationChangeListeners();

        final int newPort = ServicesLoader.getConfiguration().map(cfg -> cfg.port).orElse(Constants.DEFAULT_PORT);
        if(oldPort != newPort) {
            restartServerForNewPort(oldPort, newPort);
        } else {
            msgTemplate.ifPresent(mt -> mt.convertAndSend(Constants.MSG_TOPIC_SERVICES_RELOADED, "update"));
        }
    }
    private static void handleServiceConfigurationUpdate(List<Service> oldServices, List<Service> cfgServices) {
        if(cfgServices == services) {
            Application.log("Unexpected configuration update: services == cfgServices");
            return;
        }
        final Map<Service,Service> oldToNewServices = new HashMap<>();
        synchronized(servicesToStart) {
            cfgServices.forEach(cfgService -> {
                final Optional<Service> likeOpt = getServiceLike(cfgService);
                likeOpt.ifPresent(oldService -> {
                    oldServices.remove(oldService);

                    cfgService.setState(oldService.getState());

                    final boolean locationChanged = !Util.or(cfgService.getLocation(), "").equals(Util.or(oldService.getLocation(), ""));
                    final boolean logFileChanged = !cfgService.getLogFile().equals(oldService.getLogFile());

                    if(locationChanged || logFileChanged) {
                        cfgService.setFileLocation(null);
                        ServicesLoader.setFileLocationOf(cfgService);
                        oldService.getRunner().stop();
                        if (cfgService.getLocationType() != JAR) cfgService.getRunner().start();
                    } else {
                        // keep the old runner, so running process is not terminated when location/jar does not change
                        oldToNewServices.put(oldService, cfgService);
                        cfgService.copyFromOld(oldService);
                    }

                    if (servicesToStart.remove(oldService)) servicesToStart.add(cfgService);
                });
                if (!likeOpt.isPresent()) {
                    ServicesLoader.setFileLocationOf(cfgService);
                    initForLoadedService(cfgService);
                }
            });

            oldServices.forEach(service -> {
                servicesToStart.remove(service);
                service.getRunner().stop();
                service.getState().removeChangeListener(stateChangeListener);
            });

            services.clear();
            services.addAll(cfgServices);
            services.forEach(service -> callStateChangeListeners(service.getState())); // updates tray
            servicesStateHistory.servicesWereUpdated(services, oldToNewServices);
        }
    }
    private static void restartServerForNewPort(int oldPort, int newPort) {
        msgTemplate.ifPresent(mt -> mt.convertAndSend(Constants.MSG_TOPIC_PORT_CHANGED, new HashMap<String,Integer>(){{put("port",newPort);}}));
        Application.log("Server port changed from " + oldPort + " to " + newPort + " -- restarting server");
        Application.restart();
    }

    public static void reset() {
        services.forEach(s -> s.state.removeChangeListener(stateChangeListener));
        services.clear();
    }
    public static Optional<Service> get(String serviceName) {
        return get().stream().filter(s -> Util.or(s.getName(), "").equals(serviceName)).findFirst();
    }
    public static Service getOrThrow(String serviceName) {
        return Services.get(serviceName).orElseThrow(() -> new RuntimeException("Unknown service requested: " + serviceName));
    }
    public static List<Service> getGroupOf(Service s0) {
        return getServices(s -> s0 == s || Util.or(s0.getGroup(), "none").equals(s.getGroup()));
    }

    public static ServicesStateHistory getServicesStateHistory() {
        return servicesStateHistory;
    }

    public static void setStartParallel(boolean set) { startParallel = set; }
    public static void setStartIgnoreDeps(boolean set) { startIgnoreDeps = set; }

    public static boolean getStartParallel() { return startParallel; }
    public static boolean getStartIgnoreDeps() { return startIgnoreDeps; }

    public static void start(Service service) {
        start(service, Optional.empty());
    }
    public static void start(Service service, Optional<String> job) {
        if(!service.getState().isRunning()) service.getState().setOff();
        // jobs have no dependencies and start running immediately
        if(job.isPresent()) {
            service.getRunner().start(job.map(jobName -> service.getJobs().get(jobName)).orElse(""));
        } else {
            synchronized(servicesToStart) { servicesToStart.add(service); }
            checkRuns();
        }
    }
    public static void stop(Service service) { servicesToStart.remove(service); service.runner.stop(); checkRuns(); }
    public static void startAll() {
        Application.log("Start all");
        synchronized(servicesToStart) {
            services.stream()
                .filter(s -> !s.isExcludeFromStartAll())
                .filter(s -> !servicesToStart.contains(s))
                .forEach(servicesToStart::add);
            servicesToStart.forEach(s -> {
                if (!s.getState().isRunning()) s.getState().setOff();
            });
        }
        checkRuns();
    }
    public static void stopAll() {
        Application.log("Stop all");
        clearStart();
        get().forEach(s -> s.runner.stop());
    }
    public static void clearStart() {
        synchronized (servicesToStart) {
            servicesToStart.clear();
            services.forEach(s -> s.state.resetState());
        }
    }

    private static Optional<Service> getServiceLike(Service like) {
        Optional<Service> found = services.stream()
            .filter(service -> service.getName().equals(like.getName()))
            .findFirst();

        if(!found.isPresent()) found = services.stream()
            .filter(service -> service.getLocationType() == JAR
                            && like.getLocationType() == JAR
                            && Util.or(service.getLocation(),"").equals(Util.or(like.getLocation(), ""))
            )
            .findFirst();

        return found;
    }
    private static List<Service> getDepServicesOf(Service service) {
        if(startIgnoreDeps) return new ArrayList<>();
        return service.getDependsOn()
            .stream()
            .map(depName -> {
                final Optional<Service> dep = Services.get(depName);
                if (!dep.isPresent() && !service.state.isWaiting()) service.logger.logError("Unknown dependency: " + depName);
                return dep;
            })
            .filter(Optional::isPresent)
            .map(Optional::get)
            .collect(Collectors.toList())
            ;
    }

    private static final Predicate<Service> canBeStarted   = s -> s.getRunner().canBeStarted();
    private static final Predicate<Service> starting       = s -> s.getState().isStarting();
    private static final Predicate<Service> notStarting    = s -> !starting.test(s);
    private static final Predicate<Service> runningOk      = s -> s.getState().isRunningOk();
    private static final Predicate<Service> running        = s -> s.getState().isRunning();
    private static final Predicate<Service> runStartedOk   = s -> runningOk.test(s) && !starting.test(s);
    private static final Predicate<Service> notRunning     = s -> !running.test(s);
    private static final Predicate<Service> waiting        = s -> s.getState().isWaiting();
    private static final Predicate<Service> notWaiting     = s -> !waiting.test(s);
    private static final Predicate<Service> initError      = s -> s.getState().isInitError();
    private static final Predicate<Service> notInitError   = s -> !s.getState().isInitError();
    private static final Predicate<Service> exitError      = s -> s.getState().isExitError();
    private static final Predicate<Service> hasDepsRunning = s -> getDepServicesOf(s).stream().allMatch(runStartedOk);

    private static List<Service> getServices(Predicate<Service> predicate) {
        return new ArrayList<>(services).stream().filter(predicate).collect(Collectors.toList());
    }

    private static void updateWaitingStateOf(Service s) {
        final List<Service> deps = getDepServicesOf(s); // returns empty when startIgnoreDeps
        if (deps.isEmpty()) {
            if (!startParallel) s.logger.logWaiting(" - no other service is starting (no parallel)");
            if (startIgnoreDeps) {
                s.logger.logWaiting("Ignoring dependencies");
            }
        } else {
            s.logger.logWaiting("Waiting for dependencies:");
            deps.forEach(dep -> s.logger.log(" - " + dep.getName()));
            if (!startParallel) s.logger.log(" - no other service is starting (no parallel)");
        }
        s.state.setWaiting();
    }

    public static void checkRuns() {
        synchronized(servicesToStart) {
            servicesToStart.removeIf(running);
            servicesToStart.removeIf(initError);
            servicesToStart.removeIf(exitError);
            if (servicesToStart.isEmpty()) return;

            servicesToStart.stream()
                .filter(notRunning)
                .filter(notStarting)
                .filter(notWaiting)
                .filter(notInitError)
                .forEach(Services::updateWaitingStateOf);

            if (!startParallel && services.stream().anyMatch(starting)) return;

            servicesToStart.stream()
                .filter(notRunning)
                .filter(canBeStarted)
                .filter(notStarting)
                .filter(notInitError)
                .filter(waiting)
                .filter(hasDepsRunning)
                .limit(startParallel ? 999 : 1)
                .forEach(service -> service.runner.start());
        }
    }
}
