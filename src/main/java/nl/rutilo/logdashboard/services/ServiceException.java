package nl.rutilo.logdashboard.services;

public class ServiceException extends RuntimeException {
    public ServiceException(String reason, Exception cause) { super(reason, cause); }
}
