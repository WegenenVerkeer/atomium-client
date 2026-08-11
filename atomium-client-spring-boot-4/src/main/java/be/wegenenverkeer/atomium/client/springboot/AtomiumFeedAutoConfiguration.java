package be.wegenenverkeer.atomium.client.springboot;

import be.wegenenverkeer.atomium.client.handler.FeedEventListener;
import be.wegenenverkeer.atomium.client.handler.FeedHandler;
import be.wegenenverkeer.atomium.client.handler.FeedPointerRepository;
import be.wegenenverkeer.atomium.client.handler.FeedRuntime;
import be.wegenenverkeer.atomium.client.handler.Feeds;
import be.wegenenverkeer.atomium.client.handler.LoggingFeedEventListener;
import be.wegenenverkeer.atomium.client.handler.PerFeedThreadExecutors;

import be.wegenenverkeer.atomium.client.springboot.admin.AtomiumAdminAuthorization;
import be.wegenenverkeer.atomium.client.springboot.admin.AtomiumAdminEndpoint;
import be.wegenenverkeer.atomium.client.springboot.admin.AtomiumAdminService;
import org.jspecify.annotations.Nullable;
import org.springframework.core.annotation.Order;
import org.springframework.core.Ordered;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The generic auto-configuration: it discovers all {@link FeedHandler} beans and registers a {@link Feeds} with
 * a {@link FeedRuntime} per handler, plus the scheduler and (conditionally) the {@link AtomiumAdminService}. Requires a
 * {@link JdbcClient} and a {@link PlatformTransactionManager} (the application provides the {@code DataSource} and the
 * table {@code atomium_feed_pointer_v1}).
 *
 * <p>The environment-specific {@link FeedRestClientBuilders} bean (which HTTP client and authentication to use) is supplied by the
 * application. Without a {@link FeedRestClientBuilders} bean the app does not start — that is the deliberate, required
 * (narrow) seam; content mapper, executor and backoff are provided by this module as defaults.
 */
@AutoConfiguration
@EnableConfigurationProperties(AtomiumProperties.class)
public class AtomiumFeedAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public FeedPointerRepository feedPointerRepository(JdbcClient jdbcClient) {
        return new JdbcFeedPointerRepository(jdbcClient);
    }

    /**
     * The bundled logging listener (DEBUG/INFO). Replaceable; an app can also register its own listeners
     * alongside it. Ordered first, so that the log line for an event (e.g. "feed pointer committed")
     * precedes whatever the app listeners do in reaction to that same event.
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @ConditionalOnMissingBean(LoggingFeedEventListener.class)
    public LoggingFeedEventListener loggingFeedEventListener() {
        return new LoggingFeedEventListener();
    }

    /**
     * The per-feed daemon-thread executors: framework default behind {@link FeedConfiguration#getExecutor()}. As a bean
     * so that the threads are torn down cleanly at context shutdown ({@code destroyMethod = "shutdown"}).
     */
    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean
    public PerFeedThreadExecutors perFeedThreadExecutors() {
        return new PerFeedThreadExecutors();
    }

    @Bean
    @ConditionalOnMissingBean
    public FeedFactory feedFactory(
            FeedRestClientBuilders feedRestClientBuilders,
            ObjectProvider<JsonMapper> contentMapper,
            PerFeedThreadExecutors perFeedThreadExecutors,
            ObjectProvider<FeedCustomizer> feedCustomizers,
            ObjectProvider<FeedEventListener> feedEventListeners,
            AtomiumProperties atomiumProperties,
            FeedPointerRepository feedPointerRepository,
            PlatformTransactionManager transactionManager) {
        // default content mapper = the app JsonMapper (Boot Jackson provides one); fallback just in case
        JsonMapper mapper = contentMapper.getIfAvailable(() -> JsonMapper.builder().build());
        return new FeedFactory(feedRestClientBuilders, mapper, perFeedThreadExecutors,
                feedCustomizers.orderedStream().toList(), feedEventListeners.orderedStream().toList(),
                atomiumProperties, feedPointerRepository,
                new TransactionTemplateFeedTransactions(new TransactionTemplate(transactionManager)));
    }

    /** At context shutdown {@link Feeds#close()} deactivates all feeds, so that running runs stop cleanly. */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public Feeds feeds(FeedFactory factory, ObjectProvider<FeedHandler<?>> handlers,
                       AtomiumProperties atomiumProperties) {
        List<FeedRuntime> feeds = handlers.orderedStream().map(factory::create).toList();
        verifyEveryConfigHasAHandler(feeds, atomiumProperties);
        return new Feeds(feeds);
    }

    /**
     * Typo detection (fail fast): an {@code atomium.feeds.<id>} block without a matching {@link FeedHandler} bean
     * is almost certainly a typo in the feedId. We fail immediately at startup instead of silently ignoring config.
     */
    private static void verifyEveryConfigHasAHandler(List<FeedRuntime> feeds, AtomiumProperties atomiumProperties) {
        Set<String> feedIdsWithHandler = feeds.stream().map(FeedRuntime::feedId).collect(Collectors.toSet());
        verifyConfigAgainstHandlers(atomiumProperties.feeds().keySet(), feedIdsWithHandler);
    }

    /** The pure check (tested on its own): every configured feedId must have a handler bean. */
    static void verifyConfigAgainstHandlers(Set<String> configuredFeedIds, Set<String> feedIdsWithHandler) {
        List<String> configWithoutHandler = configuredFeedIds.stream()
                .filter(configId -> !feedIdsWithHandler.contains(configId))
                .sorted()
                .toList();
        if (!configWithoutHandler.isEmpty()) {
            throw new IllegalStateException(
                    "configured feeds without a matching FeedHandler bean (typo in the feedId?): %s. "
                            .formatted(configWithoutHandler)
                            + "Known handler feedIds: %s".formatted(feedIdsWithHandler.stream().sorted().toList()));
        }
    }

    @Bean
    @ConditionalOnMissingBean
    public FeedScheduler feedScheduler(Feeds feeds) {
        return new FeedScheduler(feeds);
    }

    /**
     * The admin/diagnostics service + the {@code @RestController}. Only in a web application and when explicitly
     * {@code atomium.admin.enabled=true}. The endpoint then requires an {@link AtomiumAdminAuthorization} bean from the
     * application (fail fast at startup when it is missing); the application additionally provides its own
     * security filter chain for {@code /rest/atomium/**}.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnWebApplication
    @ConditionalOnProperty(name = "atomium.admin.enabled", havingValue = "true")
    public AtomiumAdminService atomiumAdminService(Feeds feeds, FeedPointerRepository feedPointerRepository,
                                                   AtomiumProperties atomiumProperties) {
        return new AtomiumAdminService(feeds, feedPointerRepository, atomiumProperties);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnWebApplication
    @ConditionalOnProperty(name = "atomium.admin.enabled", havingValue = "true")
    public AtomiumAdminEndpoint atomiumAdminEndpoint(
            AtomiumAdminService atomiumAdminService,
            ObjectProvider<AtomiumAdminAuthorization> authorization,
            ObjectProvider<JsonMapper> jsonMapper,
            AtomiumProperties atomiumProperties) {
        JsonMapper mapper = jsonMapper.getIfAvailable(() -> JsonMapper.builder().build());
        return new AtomiumAdminEndpoint(atomiumAdminService, requiredAuthorization(authorization.getIfAvailable()),
                mapper, atomiumProperties.admin().prettyPrint());
    }

    /** Fail fast with a clear message instead of a generic missing-bean failure. */
    static AtomiumAdminAuthorization requiredAuthorization(@Nullable AtomiumAdminAuthorization authorization) {
        if (authorization == null) {
            throw new IllegalStateException("'atomium.admin.enabled=true' requires an AtomiumAdminAuthorization bean "
                    + "that determines who has read/write permission on /rest/atomium/** "
                    + "(e.g. new HasAuthorityAtomiumAdminAuthorization(\"...\"))");
        }
        return authorization;
    }
}
