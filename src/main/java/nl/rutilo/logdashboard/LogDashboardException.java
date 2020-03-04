package nl.rutilo.logdashboard;

public class LogDashboardException extends RuntimeException{
    public LogDashboardException(String msg) { super(msg); }
    public LogDashboardException(String msg, Throwable cause) { super(msg, cause); }
}
