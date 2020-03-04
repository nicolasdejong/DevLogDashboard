package nl.rutilo.logdashboard.services;

import nl.rutilo.logdashboard.Configuration;
import nl.rutilo.logdashboard.Constants;
import nl.rutilo.logdashboard.util.BTCache;
import nl.rutilo.logdashboard.util.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ServiceLogger {
    private final        LinkedList<LineInfo> lineBuffer            = new LinkedList<>();
    private final static int                  MAX_OUTPUT_LINE_COUNT = Configuration.MAX_OUTPUT_LINE_COUNT;
    private final        Logger               logger                = LoggerFactory.getLogger(this.getClass());
    private final        Object               msgSync               = new Object();
    private final static BTCache<String,Pattern> regexCache         = new BTCache<String,Pattern>()
                                                                        .setBuilder(Pattern::compile)
                                                                        .setMaxCount(100);

    protected      Service               service;
    private        SimpMessagingTemplate msgTemplate; // nullable
    private static int                   lineIndex  = 0;

    public enum LineType { WAITING, OUT, ERROR, INIT_ERROR, OTHER }

    public static class LineInfo {
        public final int index;
        public final long time;
        public final LineType type;
        public final String text;
        public final boolean replaces;
        public LineInfo(LineType type, String text) { this(type, text, false); }
        public LineInfo(LineType type, String text, boolean replacePrevious) {
            this.index = lineIndex++;
            this.time = System.currentTimeMillis();
            this.type = type;
            this.text = text;
            this.replaces = replacePrevious;
        }
    }
    private static class OutputInfo {
        public final LineInfo line;
        public final int suid;
        public final int logVelocity;
        public OutputInfo(Service service, LineInfo line) {
            this.line = line;
            this.suid = service.uid;
            this.logVelocity = service.getState().getLogVelocity();
        }
    }


    public ServiceLogger(Service service) {
        this.service = service;
    }
    public ServiceLogger setService(Service newService) { this.service = newService; return this; }

    public void clear() {
        synchronized(lineBuffer) { lineBuffer.clear(); }
    }

    public void setMessaging(SimpMessagingTemplate msgTemplate) {
        synchronized(msgSync) { this.msgTemplate = msgTemplate; }
    }

    private void limit() {
        synchronized(lineBuffer) {
           while(lineBuffer.size() > MAX_OUTPUT_LINE_COUNT) lineBuffer.removeFirst();
        }
    }


    private String cleanLine(String line) {
        if(!Util.or(service.getLogDeletes(), Collections.emptyList()).isEmpty()) {
            final String[] text = { line };
            service.getLogDeletes().forEach(rep -> text[0] = regexCache.get(rep).matcher(text[0]).replaceAll(""));
            return text[0];
        }
        return line;
    }

    public void log(String outText) { handleOutLine(outText); }
    public void logError(String outText) { handleErrorLine(outText); }
    public void logWaiting(String line) { addLine(new LineInfo(LineType.WAITING, line)); }
    public void logOther(String line) { addLine(new LineInfo(LineType.OTHER, line)); }

    private void addLine(LineInfo li) {
        if(!li.replaces) {
            synchronized(lineBuffer) {
                lineBuffer.add(li);
                limit();
            }
        }
        if(li.type != LineType.OTHER && li.type != LineType.WAITING) {
            service.getState().handleLine(li.type != LineType.OUT && li.type != LineType.INIT_ERROR,
                li.text, li.replaces);
        }
        synchronized(msgSync) {
            if(msgTemplate != null) {
                msgTemplate.convertAndSend(Constants.MSG_TOPIC_PROCESS_OUTPUT, new OutputInfo(service, li));
            }
        }
    }

    public List<LineInfo> getBuffer() {
        synchronized(lineBuffer) {
            return new ArrayList<>(lineBuffer);
        }
    }
    public void clearBuffer() {
        synchronized(lineBuffer) {
            lineBuffer.clear();
        }
        synchronized(msgSync) {
            if(msgTemplate != null) {
                msgTemplate.convertAndSend(Constants.MSG_TOPIC_CLEAR_PROCESS_OUTPUT, "update");
            }
        }
    }

    protected void handleLine(boolean isError, String line, boolean replacePrevious) {
        if(isError) handleErrorLine(line, replacePrevious);
        else        handleOutLine(line, replacePrevious);
    }

    protected void handleOutLine(String line) { handleOutLine(cleanLine(line), /*replacePrevious=*/false); }
    protected void handleOutLine(String line, boolean replacePrevious) {
        if (line.matches("^(\\w+\\.){1,}\\w+Exception: .*$")
         || line.matches("^\\tat \\w+\\..*$")
         || line.matches("^(Caused|Wrapped) by: (\\w+\\.){2,}.*$")
         || line.matches("^\\t... \\d+ more$")
         || line.startsWith("[ERROR]")) {
            addLine(new LineInfo(LineType.ERROR, cleanLine(line), replacePrevious));
        } else{
            addLine(new LineInfo(LineType.OUT, cleanLine(line), replacePrevious));
        }
    }
    protected void handleErrorLine(String line) { handleErrorLine(cleanLine(line), /*replacePrevious=*/false); }
    protected void handleErrorLine(String line, boolean replacePrevious) {
        addLine(new LineInfo(LineType.ERROR, cleanLine(line), replacePrevious));
    }

    void error(String errorText) { error(errorText, null); }
    void error(String errorText, Exception e) {
        if (e != null) e.printStackTrace();

        logger.error(errorText, e);

        addLine(new LineInfo(LineType.INIT_ERROR, errorText));
        if (e != null && e.getMessage() != null) {
            addLine(new LineInfo(LineType.INIT_ERROR, e.getMessage()));
        }
        service.getState().setInitFailed();
    }
}
