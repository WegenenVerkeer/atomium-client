package be.wegenenverkeer.atomium.client.springboot.admin;

import be.wegenenverkeer.atomium.client.springboot.AbstractAtomiumFeedIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Without {@code atomium.admin.enabled=true} the admin endpoint is not registered. This test inherits the
 * context of {@link AbstractAtomiumFeedIT} (profiles {@code test}+{@code wiremock}, i.e. without the property).
 */
class AtomiumAdminDisabledTest extends AbstractAtomiumFeedIT {

    @Autowired
    private ApplicationContext ctx;

    @Test
    void noAdminBeansWithoutTheProperty() {
        assertThat(ctx.getBeanNamesForType(AtomiumAdminEndpoint.class)).isEmpty();
        assertThat(ctx.getBeanNamesForType(AtomiumAdminService.class)).isEmpty();
    }
}
