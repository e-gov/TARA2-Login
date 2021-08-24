package ee.ria.taraauthserver.authentication;

import ee.ria.taraauthserver.config.properties.AuthConfigurationProperties;
import ee.ria.taraauthserver.error.exceptions.BadRequestException;
import ee.ria.taraauthserver.logging.StatisticsLogger;
import ee.ria.taraauthserver.session.SessionUtils;
import ee.ria.taraauthserver.session.TaraSession;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.SessionAttribute;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.view.RedirectView;

import javax.validation.constraints.Pattern;
import java.util.HashMap;
import java.util.Map;

import static ee.ria.taraauthserver.error.ErrorCode.SESSION_NOT_FOUND;
import static ee.ria.taraauthserver.session.TaraAuthenticationState.AUTHENTICATION_CANCELED;
import static ee.ria.taraauthserver.session.TaraSession.TARA_SESSION;
import static net.logstash.logback.argument.StructuredArguments.value;
import static org.springframework.web.bind.annotation.RequestMethod.GET;
import static org.springframework.web.bind.annotation.RequestMethod.POST;

@Slf4j
@Validated
@Controller
public class AuthRejectController {

    @Autowired
    private AuthConfigurationProperties configurationProperties;

    @Autowired
    private RestTemplate hydraService;

    @Autowired
    private StatisticsLogger statisticsLogger;

    @RequestMapping(value = "/auth/reject", method = {GET, POST})
    public RedirectView authReject(@RequestParam(name = "error_code") @Pattern(regexp = "user_cancel", message = "the only supported value is: 'user_cancel'") String errorCode,
                                   @SessionAttribute(value = TARA_SESSION, required = false) TaraSession taraSession) {
        if (taraSession == null) {
            throw new BadRequestException(SESSION_NOT_FOUND, "Invalid session");
        }

        String url = getRequestUrl(taraSession.getLoginRequestInfo().getChallenge());
        log.info("OIDC login reject request: {}", value("url.full", url));
        var response = hydraService.exchange(
                url,
                HttpMethod.PUT,
                createRequestBody(errorCode),
                Map.class);

        if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null && response.getBody().get("redirect_to") != null) {
            taraSession.setState(AUTHENTICATION_CANCELED);
            statisticsLogger.log(taraSession);
            SessionUtils.invalidateSession();
            return new RedirectView(response.getBody().get("redirect_to").toString());
        } else {
            throw new IllegalStateException("Invalid OIDC server response. Redirect URL missing from response.");
        }
    }

    @NotNull
    private String getRequestUrl(String loginChallenge) {
        return configurationProperties.getHydraService().getRejectLoginUrl() + "?login_challenge=" + loginChallenge;
    }

    @NotNull
    private HttpEntity<Map<String, String>> createRequestBody(String errorCode) {
        Map<String, String> map = new HashMap<>();
        map.put("error", errorCode);
        map.put("error_debug", "User canceled the authentication process.");
        map.put("error_description", "User canceled the authentication process.");
        return new HttpEntity<>(map);
    }

}
