package nl.rutilo.logdashboard;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import nl.rutilo.logdashboard.services.Service;
import nl.rutilo.logdashboard.services.ServiceLogger;
import nl.rutilo.logdashboard.services.Services;
import nl.rutilo.logdashboard.util.ManifestUtil;
import nl.rutilo.logdashboard.util.Util;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static javax.ws.rs.core.MediaType.APPLICATION_JSON;
import static javax.ws.rs.core.MediaType.TEXT_PLAIN;


@RestController
public class WebController {
	private final  SimpMessagingTemplate msgTemplate;
	private        long                  lastModified = -1;
	private static boolean               upgrading = false;

	public WebController(SimpMessagingTemplate msgTemplate) {
	    this.msgTemplate = msgTemplate;
  	    Services.setWebsocketMessaging(msgTemplate);
    }

    // @Scheduled calls multiple times consecutively when the machine was paused for a while
    private static class Recent {
	    private final long recentDelta;
	    private long lastUpdate;
	    public Recent(int recentDelta) { this.recentDelta = recentDelta; }
        private long now() { return System.currentTimeMillis(); }
	    public void update() { lastUpdate = now(); }
	    public boolean isRecent() {
	        if(now() - lastUpdate >= recentDelta) { update(); return true; }
	        return false;
	    }
    }

    final Recent recentCheckChangedFiles = new Recent(1000);
    final Recent recentSetLastStateHistory = new Recent(10_000);
    final Recent recentCheckForLatestVersion = new Recent(100_000);

    @Scheduled(fixedRate=2_000)   private void checkChangedFiles() {
        if(!recentCheckChangedFiles.isRecent()) return;
	    if(!ManifestUtil.RUNNING_FROM_JAR) { // only when developing
            final long newLastModified = Util.lastModified(Constants.DEV_STATIC_DIR);
            if (lastModified > 0 && newLastModified > lastModified) {
                msgTemplate.convertAndSend(Constants.MSG_TOPIC_SCRIPTS_CHANGED, "update");
            }
            lastModified = newLastModified;
        }
    }

    @Scheduled(fixedRate=60_000)  private void setLastStateHistory() {
        if(!recentSetLastStateHistory.isRecent()) return;
        if (msgTemplate == null) return;
        msgTemplate.convertAndSend(Constants.MSG_TOPIC_LAST_STATE_HISTORY,
            Services.getServicesStateHistory().getAsLastTimeToString());
    }

    @Scheduled(initialDelay=10_000, fixedRate=3600_000) private void checkForLatestVersion() {
        if(!recentCheckForLatestVersion.isRecent()) return;
	    final int previousLatestVersion = Upgrader.latestVersion;
	    Upgrader.checkForLatestVersion();
        if(Upgrader.latestVersion != previousLatestVersion) {
            Application.log("Send to client: latest version changed");
            msgTemplate.convertAndSend(Constants.MSG_TOPIC_PORT_CAN_UPGRADE, "" + Upgrader.latestVersion);
        }
    }

    public static boolean isUpgrading() {
	    return upgrading;
    }

    @GetMapping("/serverInfo")
    public ServerInfo getServerInfo() {
        return ServerInfo.get();
    }

    @GetMapping(value="/serverInfoJson", produces = APPLICATION_JSON)
    public String getServerInfoJson() throws JsonProcessingException {
        final String json = new ObjectMapper().writeValueAsString(ServerInfo.get());
        return "window.serverInfo = " + json + ";";
    }

    @GetMapping(value="/services")
    public List<Service> getServices() {
        return Services.get();
    }

    @Data
    public static class StartInfo {
	    String serviceName;
	    String job;
    }

    @PostMapping(value="/start")
    public void start(@RequestBody StartInfo startInfo) {
        final Service service = Services.getOrThrow(startInfo.serviceName);
        Services.start(service, Optional.ofNullable(startInfo.job));
    }

    @PostMapping(value="/stop")
    public void stop(@RequestBody String serviceName) {
        final Service service = Services.getOrThrow(serviceName);
        Services.stop(service);
        service.state.stopped();
    }

    @PostMapping(value="/setFlags")
    public void setFlags(@RequestParam(name="startParallel") Optional<Boolean> startParallel,
                         @RequestParam(name="startIgnoreDeps") Optional<Boolean> startIgnoreDeps) {
        startParallel.ifPresent(Services::setStartParallel);
        startIgnoreDeps.ifPresent(Services::setStartIgnoreDeps);
        Services.checkRuns();
    }

    @PostMapping(value="/startAll")
    public void startAll() {
	    Services.startAll();
    }

    @PostMapping(value="/stopAll")
    public void stopAll() {
	    Services.stopAll();
    }

    private static class ServiceOutput {
	    public final List<ServiceLogger.LineInfo> log;
	    public final int logVelocity;
	    private ServiceOutput() {
	        log = new ArrayList<>();
	        logVelocity = 0;
        }
	    private ServiceOutput(Service s) {
            log = s.logger.getBuffer();
            logVelocity = s.getState().getLogVelocity();
        }
    }

    @PostMapping(value="/sendOutputOfService", produces=APPLICATION_JSON)
    public ServiceOutput sendOutputOfService(@RequestBody String serviceName) {
        final Optional<Service> service = Services.get(serviceName);

        Services.get().forEach(s -> s.logger.setMessaging(null));
        service.ifPresent(s -> s.logger.setMessaging(msgTemplate));
        return service.map(ServiceOutput::new).orElse(new ServiceOutput());
    }

    @PostMapping(value="/clearLog")
    public void clearLog(@RequestBody String serviceName) {
        final Service service = Services.getOrThrow(serviceName);
        service.logger.clearBuffer();
        service.logger.logOther("Log cleared");
    }

    @PostMapping(value="/stateHistory", produces=APPLICATION_JSON)
    public Map<Long,String> getStateHistory() {
	    return Services.getServicesStateHistory().getAsTimeToString();
    }

    @GetMapping(value="/getLatestVersion", produces=TEXT_PLAIN)
    public String getLatestVersion() {
	    return "" + Upgrader.latestVersion;
    }

    @GetMapping(value="/getLatestReleaseNotes", produces=TEXT_PLAIN)
    public String getLatestReleaseNotes() {
        return Upgrader.getLatestReleaseNotes();
    }

    @PostMapping(value="/upgrade", produces="text/plain")
    public String upgrade() {
	    if(upgrading) return "already upgrading";
	    upgrading = true;
	    try {
	        final String result = Upgrader.upgrade();
	        Application.log("Upgrade result:", result);
	        return result;
        } finally {
	        upgrading = false;
        }
    }
}
