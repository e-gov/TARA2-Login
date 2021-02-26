package ee.ria.taraauthserver.error;

import ee.ria.taraauthserver.error.exceptions.BadRequestException;
import ee.ria.taraauthserver.error.exceptions.NotFoundException;
import ee.ria.taraauthserver.error.exceptions.ServiceNotAvailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.client.HttpClientErrorException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.validation.ConstraintViolationException;
import java.io.IOException;

import static ee.ria.taraauthserver.session.TaraSession.TARA_SESSION;
import static net.logstash.logback.marker.Markers.append;

@Slf4j
@ControllerAdvice
public class ErrorHandler {

    private void invalidateSessionAndSendError(HttpServletRequest request, HttpServletResponse response, int status) throws IOException {
        HttpSession session = request.getSession(false);
        if (session != null) {
            Object taraSession = session.getAttribute(TARA_SESSION);
            session.invalidate();
            log.warn(append(TARA_SESSION, taraSession), "Session has been invalidated: {}", session.getId());
        }
        response.sendError(status);
    }

    @ExceptionHandler({BadRequestException.class, BindException.class, ConstraintViolationException.class, MissingServletRequestParameterException.class})
    public void handleBindException(Exception ex, HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (log.isDebugEnabled())
            log.error("User exception: {}", ex.getMessage(), ex);
        else
            log.error("User exception: {}", ex.getMessage());
        invalidateSessionAndSendError(request, response, HttpServletResponse.SC_BAD_REQUEST);
    }

    @ExceptionHandler({HttpClientErrorException.class})
    public void handleHttpClientErrorException(HttpClientErrorException ex, HttpServletRequest request, HttpServletResponse response) throws IOException {
        log.error("HTTP client exception: {}", ex.getMessage(), ex);
        invalidateSessionAndSendError(request, response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
    }

    @ExceptionHandler({ServiceNotAvailableException.class})
    public void handleDownstreamServiceErrors(Exception ex, HttpServletRequest request, HttpServletResponse response) throws IOException {
        log.error("Service not available: {}", ex.getMessage(), ex);
        invalidateSessionAndSendError(request, response, HttpServletResponse.SC_BAD_GATEWAY);
    }

    @ExceptionHandler({NotFoundException.class})
    public void handleNotFound(Exception ex, HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (log.isDebugEnabled())
            log.error("Results not found: {}", ex.getMessage(), ex);
        else
            log.error("Results not found: {}", ex.getMessage());
        invalidateSessionAndSendError(request, response, HttpServletResponse.SC_NOT_FOUND);
    }

    @ExceptionHandler({Exception.class})
    public void handleAll(Exception ex, HttpServletRequest request, HttpServletResponse response) throws IOException {
        log.error("Server encountered an unexpected error: {}", ex.getMessage(), ex);
        invalidateSessionAndSendError(request, response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
    }
}
