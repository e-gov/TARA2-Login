package ee.ria.taraauthserver.authentication.mobileid;

import ee.ria.taraauthserver.BaseTest;
import ee.ria.taraauthserver.session.TaraAuthenticationState;
import ee.ria.taraauthserver.session.TaraSession;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;

import static ee.ria.taraauthserver.session.TaraSession.TARA_SESSION;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

class AuthMidPollCancelControllerTest extends BaseTest {

    @Autowired
    SessionRepository sessionRepository;

    @Test
    void authMidPoll_sessionMissing() {

        given()
                .when()
                .post("/auth/mid/poll/cancel")
                .then()
                .assertThat()
                .statusCode(400)
                .body("message", equalTo("Teie sessiooni ei leitud! Sessioon aegus või on küpsiste kasutamine Teie brauseris piiratud."))
                .body("error", equalTo("Bad Request"));

        assertErrorIsLogged("User exception: Session was not found");
    }

    @Test
    void authMidPoll_sessionIncorrectState() {
        Session session = createSessionInState(TaraAuthenticationState.COMPLETE);

        given()
                .when()
                .sessionId("SESSION", session.getId())
                .post("/auth/mid/poll/cancel")
                .then()
                .assertThat()
                .statusCode(400)
                .body("message", equalTo("Ebakorrektne päring."))
                .body("error", equalTo("Bad Request"));

        assertErrorIsLogged("User exception: Invalid authentication state: 'COMPLETE', expected: 'POLL_MID_STATUS'");
    }

    @Test
    void authMidPoll_ok() {
        Session session = createSessionInState(TaraAuthenticationState.POLL_MID_STATUS);

        given()
                .when()
                .sessionId("SESSION", session.getId())
                .post("/auth/mid/poll/cancel")
                .then()
                .assertThat()
                .statusCode(302);

        TaraSession taraSession = sessionRepository.findById(session.getId()).getAttribute(TARA_SESSION);
        assertEquals(TaraAuthenticationState.POLL_MID_STATUS_CANCELED, taraSession.getState());
        assertWarningIsLogged("Mobile ID authentication process with MID session id testSessionId has been canceled");
    }

    @NotNull
    private Session createSessionInState(TaraAuthenticationState state) {
        Session session = sessionRepository.createSession();
        TaraSession.LoginRequestInfo loginRequestInfo = new TaraSession.LoginRequestInfo();
        loginRequestInfo.setChallenge("123abc");
        TaraSession taraSession = new TaraSession();
        taraSession.setState(state);
        taraSession.setLoginRequestInfo(loginRequestInfo);
        TaraSession.MidAuthenticationResult authenticationResult = new TaraSession.MidAuthenticationResult();
        authenticationResult.setMidSessionId("testSessionId");
        taraSession.setAuthenticationResult(authenticationResult);
        session.setAttribute(TARA_SESSION, taraSession);
        sessionRepository.save(session);
        return session;
    }

}