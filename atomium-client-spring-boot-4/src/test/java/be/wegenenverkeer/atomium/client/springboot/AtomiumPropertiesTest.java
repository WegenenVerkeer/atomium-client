package be.wegenenverkeer.atomium.client.springboot;

import be.wegenenverkeer.atomium.client.handler.FeedHandler;
import be.wegenenverkeer.atomium.client.springboot.admin.HasAuthorityAtomiumAdminAuthorization;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * The fail-fast typo detection on the config: an {@code atomium.feeds.<id>} block without a corresponding
 * {@link FeedHandler} bean fails startup ({@link AtomiumFeedAutoConfiguration#verifyConfigAgainstHandlers}).
 */
class AtomiumPropertiesTest {

    @Test
    void configWithoutHandlerFailsImmediately() {
        assertThatIllegalStateException().isThrownBy(() ->
                        AtomiumFeedAutoConfiguration.verifyConfigAgainstHandlers(
                                Set.of("a-feed-id", "typo-feed"), Set.of("a-feed-id")))
                .withMessageContaining("typo-feed");
    }

    @Test
    void everyConfigHavingAHandlerIsOk() {
        AtomiumFeedAutoConfiguration.verifyConfigAgainstHandlers(
                Set.of("a-feed-id"), Set.of("a-feed-id", "extra-handler-without-config"));
    }

    /**
     * {@code atomium.admin.enabled=true} without an {@code AtomiumAdminAuthorization} bean fails startup with a
     * clear message ({@link AtomiumFeedAutoConfiguration#requiredAuthorization}) — an admin endpoint that is
     * unintentionally left open is thus impossible.
     */
    @Test
    void adminWithoutAuthorizationBeanFailsFast() {
        assertThatIllegalStateException()
                .isThrownBy(() -> AtomiumFeedAutoConfiguration.requiredAuthorization(null))
                .withMessageContaining("atomium.admin.enabled")
                .withMessageContaining("AtomiumAdminAuthorization");
    }

    @Test
    void adminWithAuthorizationBeanIsOk() {
        var authorization = new HasAuthorityAtomiumAdminAuthorization("x");

        assertThat(AtomiumFeedAutoConfiguration.requiredAuthorization(authorization)).isSameAs(authorization);
    }
}
