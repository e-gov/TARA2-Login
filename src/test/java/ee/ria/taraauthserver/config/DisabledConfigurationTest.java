package ee.ria.taraauthserver.config;


import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

public abstract class DisabledConfigurationTest {

    @Autowired
    protected ApplicationContext applicationContext;

    @BeforeAll
    static void setUpAll() {
        System.setProperty("IGNITE_QUIET", "false");
        System.setProperty("IGNITE_HOME", System.getProperty("java.io.tmpdir"));
        System.setProperty("java.net.preferIPv4Stack", "true");
    }

    protected void assertBeanNotInitiated(Class<?> clazz) {
        try {
            applicationContext.getBean(clazz);
            Assertions.fail("Bean <" + clazz + "> should not be initiated!");
        } catch (NoSuchBeanDefinitionException e) {
        }
    }

    protected void assertBeanNotInitiated(String name) {
        try {
            applicationContext.getBean(name);
            Assertions.fail("Bean <" + name + "> should not be initiated!");
        } catch (NoSuchBeanDefinitionException e) {
        }
    }

    protected void assertBeanInitiated(Class<?> clazz) {
        try {
            applicationContext.getBean(clazz);
        } catch (NoSuchBeanDefinitionException e) {
            Assertions.fail("Bean <" + clazz + "> is not initiated!");
        }
    }
}
