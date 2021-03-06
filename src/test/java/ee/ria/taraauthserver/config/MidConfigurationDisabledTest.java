package ee.ria.taraauthserver.config;

import ee.ria.taraauthserver.authentication.mobileid.AuthMidController;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

@TestPropertySource(
        locations = "classpath:application.yml",
        properties = {"tara.auth-methods.mobile-id.enabled=false"})
@SpringBootTest(webEnvironment = RANDOM_PORT)
public class MidConfigurationDisabledTest extends DisabledConfigurationTest {

    @Test
    @Tag(value = "MID_ENABLED")
    public void whenLegalPersonDisabledThenBeansNotLoaded() {
        assertBeanNotInitiated(MidConfiguration.class);
        assertBeanNotInitiated(AuthMidController.class);
    }
}
