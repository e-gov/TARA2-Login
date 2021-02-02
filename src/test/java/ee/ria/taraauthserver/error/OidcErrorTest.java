package ee.ria.taraauthserver.error;

import ee.ria.taraauthserver.BaseTest;
import org.junit.jupiter.api.Test;

import static ee.ria.taraauthserver.session.MockSessionFilter.withoutTaraSession;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

public class OidcErrorTest extends BaseTest {

    @Test
    void oidc_knownErrorParam() {
        given()
                .filter(withoutTaraSession().sessionRepository(sessionRepository).build())
                .queryParam("error", "invalid_client")
                .when()
                .post("/auth/error")
                .then()
                .assertThat()
                .statusCode(404)
                .body("message", equalTo("kliendi autentimine ebaõnnestus (näiteks: tundmatu klient, kliendi autentimist pole kaasatud, või toetamata autentimismeetod)"));
    }

    @Test
    void oidc_unKnownErrorParam() {
        given()
                .filter(withoutTaraSession().sessionRepository(sessionRepository).build())
                .queryParam("error", "unknown_param")
                .when()
                .post("/auth/error")
                .then()
                .assertThat()
                .statusCode(404)
                .body("message", equalTo("Autentimine ebaõnnestus teenuse tehnilise vea tõttu. Palun proovige mõne aja pärast uuesti."));
    }

    @Test
    void oidc_missingErrorParam() {
        given()
                .filter(withoutTaraSession().sessionRepository(sessionRepository).build())
                .when()
                .post("/auth/error")
                .then()
                .assertThat()
                .statusCode(404)
                .body("message", equalTo("No message available"));
    }

}
