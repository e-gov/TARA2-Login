package ee.ria.taraauthserver.authentication.mobileid;

import ee.ria.taraauthserver.error.ErrorCode;
import ee.ria.taraauthserver.error.exceptions.BadRequestException;
import ee.ria.taraauthserver.error.exceptions.ServiceNotAvailableException;
import ee.ria.taraauthserver.session.SessionUtils;
import ee.ria.taraauthserver.session.TaraAuthenticationState;
import ee.ria.taraauthserver.session.TaraSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.EnumSet;
import java.util.Map;

import static ee.ria.taraauthserver.session.TaraAuthenticationState.AUTHENTICATION_FAILED;
import static ee.ria.taraauthserver.session.TaraAuthenticationState.INIT_MID;
import static ee.ria.taraauthserver.session.TaraAuthenticationState.NATURAL_PERSON_AUTHENTICATION_COMPLETED;
import static ee.ria.taraauthserver.session.TaraAuthenticationState.POLL_MID_STATUS;
import static java.util.Map.of;
import static net.logstash.logback.marker.Markers.append;

@Slf4j
@RestController
public class AuthMidPollController {
    private static final EnumSet<TaraAuthenticationState> ALLOWED_STATES = EnumSet.of(INIT_MID, POLL_MID_STATUS, AUTHENTICATION_FAILED, NATURAL_PERSON_AUTHENTICATION_COMPLETED);

    @GetMapping(value = "/auth/mid/poll")
    public Map<String, String> authMidPoll() {
        TaraSession taraSession = SessionUtils.getAuthSession();
        SessionUtils.assertSessionInState(taraSession, ALLOWED_STATES);

        log.info(append("tara.session.state", taraSession.getState()),
                "Polling Mobile-ID authentication process");

        if (taraSession.getState() == NATURAL_PERSON_AUTHENTICATION_COMPLETED) {
            return of("status", "COMPLETED");
        } else if (taraSession.getState() == AUTHENTICATION_FAILED) {
            ErrorCode errorCode = taraSession.getAuthenticationResult().getErrorCode();
            if (errorCode.equals(ErrorCode.ERROR_GENERAL))
                throw new IllegalStateException(errorCode.getMessage());
            else if (errorCode.equals(ErrorCode.MID_INTERNAL_ERROR))
                throw new ServiceNotAvailableException(errorCode, "Mobile-ID poll failed", null);
            else
                throw new BadRequestException(taraSession.getAuthenticationResult().getErrorCode(), "Mobile-ID poll failed");
        } else
            return of("status", "PENDING");
    }
}
