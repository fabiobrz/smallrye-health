package io.smallrye.health;

import static io.smallrye.health.SmallRyeHealthReporter.HealthType.HEALTH;
import static io.smallrye.health.SmallRyeHealthReporter.HealthType.LIVENESS;
import static io.smallrye.health.SmallRyeHealthReporter.HealthType.READINESS;
import static io.smallrye.health.SmallRyeHealthReporter.HealthType.WELLNESS;

import java.io.OutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Any;
import javax.enterprise.inject.Instance;
import javax.enterprise.inject.spi.Bean;
import javax.enterprise.inject.spi.BeanManager;
import javax.inject.Inject;
import javax.json.JsonArray;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.json.JsonWriter;
import javax.json.JsonWriterFactory;
import javax.json.spi.JsonProvider;
import javax.json.stream.JsonGenerator;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.health.Health;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Liveness;
import org.eclipse.microprofile.health.Readiness;

import io.smallrye.common.annotation.Experimental;
import io.smallrye.health.api.AsyncHealthCheck;
import io.smallrye.health.api.HealthGroup;
import io.smallrye.health.api.Wellness;
import io.smallrye.health.registry.AbstractHealthRegistry;
import io.smallrye.health.registry.LivenessHealthRegistry;
import io.smallrye.health.registry.ReadinessHealthRegistry;
import io.smallrye.mutiny.Uni;

@ApplicationScoped
public class SmallRyeHealthReporter {

    private static final Map<String, ?> JSON_CONFIG = Collections.singletonMap(JsonGenerator.PRETTY_PRINTING, true);

    /**
     * can be {@code null} if SmallRyeHealthReporter is used in a non-CDI environment
     */
    @Inject
    @Health
    Instance<HealthCheck> healthChecks;

    @Inject
    @Liveness
    Instance<HealthCheck> livenessChecks;

    @Inject
    @Readiness
    Instance<HealthCheck> readinessChecks;

    @Inject
    @Wellness
    Instance<HealthCheck> wellnessChecks;

    @Inject
    @Any
    Instance<HealthCheck> allHealthChecks;

    @Inject
    @Health
    Instance<AsyncHealthCheck> asyncHealthChecks;

    @Inject
    @Liveness
    Instance<AsyncHealthCheck> asyncLivenessChecks;

    @Inject
    @Readiness
    Instance<AsyncHealthCheck> asyncReadinessChecks;

    @Inject
    @Wellness
    Instance<AsyncHealthCheck> asyncWellnessChecks;

    @Inject
    @Any
    Instance<AsyncHealthCheck> allAsyncHealthChecks;

    @Inject
    BeanManager beanManager;

    @Inject
    @Liveness
    LivenessHealthRegistry livenessHealthRegistry;

    @Inject
    @Readiness
    ReadinessHealthRegistry readinessHealthRegistry;

    @Inject
    @ConfigProperty(name = "io.smallrye.health.emptyChecksOutcome", defaultValue = "UP")
    String emptyChecksOutcome;

    @Inject
    @ConfigProperty(name = "io.smallrye.health.timeout.seconds", defaultValue = "60")
    int timeoutSeconds;

    @Inject
    AsyncHealthCheckFactory asyncHealthCheckFactory;

    private final Map<String, Uni<HealthCheckResponse>> additionalChecks = new HashMap<>();

    private final JsonProvider jsonProvider = JsonProvider.provider();

    private Uni<SmallRyeHealth> smallRyeHealthUni = null;
    private Uni<SmallRyeHealth> smallRyeLivenessUni = null;
    private Uni<SmallRyeHealth> smallRyeReadinessUni = null;
    private Uni<SmallRyeHealth> smallryeWellnessUni = null;
    private boolean additionalListsChanged = false;

    private List<Uni<HealthCheckResponse>> healthUnis = new ArrayList<>();
    private List<Uni<HealthCheckResponse>> livenessUnis = new ArrayList<>();
    private List<Uni<HealthCheckResponse>> readinessUnis = new ArrayList<>();
    private List<Uni<HealthCheckResponse>> wellnessUnis = new ArrayList<>();

    @PostConstruct
    public void initChecks() {
        initUnis(healthUnis, healthChecks, asyncHealthChecks);
        initUnis(livenessUnis, livenessChecks, asyncLivenessChecks, livenessHealthRegistry);
        initUnis(readinessUnis, readinessChecks, asyncReadinessChecks, readinessHealthRegistry);
        initUnis(wellnessUnis, wellnessChecks, asyncWellnessChecks);
    }

    private void initUnis(List<Uni<HealthCheckResponse>> list, Iterable<HealthCheck> checks,
            Iterable<AsyncHealthCheck> asyncChecks) {
        initUnis(list, checks, asyncChecks, null);
    }

    private void initUnis(List<Uni<HealthCheckResponse>> list, Iterable<HealthCheck> checks,
            Iterable<AsyncHealthCheck> asyncChecks, AbstractHealthRegistry registry) {
        for (HealthCheck check : checks) {
            list.add(asyncHealthCheckFactory.callSync(check));
        }

        for (AsyncHealthCheck asyncCheck : asyncChecks) {
            list.add(asyncHealthCheckFactory.callAsync(asyncCheck));
        }

        if (registry != null) {
            list.addAll(registry.getChecks());
        }
    }

    void setEmptyChecksOutcome(String emptyChecksOutcome) {
        if (emptyChecksOutcome != null) {
            this.emptyChecksOutcome = emptyChecksOutcome;
        }
    }

    public void reportHealth(OutputStream out, SmallRyeHealth health) {
        if (health.isDown() && HealthLogging.log.isInfoEnabled()) {
            // Log reason, as not reported by container orchestrators, yet container may get killed.
            HealthLogging.log.healthDownStatus(health.getPayload().toString());
        }

        JsonWriterFactory factory = jsonProvider.createWriterFactory(JSON_CONFIG);
        JsonWriter writer = factory.createWriter(out);

        writer.writeObject(health.getPayload());
        writer.close();
    }

    public SmallRyeHealth getHealth() {
        return getHealth(smallRyeHealthUni, HEALTH, LIVENESS, READINESS, WELLNESS);
    }

    public SmallRyeHealth getLiveness() {
        return getHealth(smallRyeLivenessUni, LIVENESS);
    }

    public SmallRyeHealth getReadiness() {
        return getHealth(smallRyeReadinessUni, READINESS);
    }

    @Experimental("Wellness experimental checks")
    public SmallRyeHealth getWellness() {
        return getHealth(smallryeWellnessUni, WELLNESS);
    }

    public SmallRyeHealth getHealthGroup(String groupName) {
        return getHealthGroupAsync(groupName).await().atMost(Duration.ofSeconds(timeoutSeconds));
    }

    public SmallRyeHealth getHealthGroups() {
        return getHealthGroupsAsync().await().atMost(Duration.ofSeconds(timeoutSeconds));
    }

    @Experimental("Asynchronous Health Check procedures")
    public Uni<SmallRyeHealth> getHealthAsync() {
        return getHealthAsync(smallRyeHealthUni, HEALTH, LIVENESS, READINESS, WELLNESS);
    }

    @Experimental("Asynchronous Health Check procedures")
    public Uni<SmallRyeHealth> getLivenessAsync() {
        return getHealthAsync(smallRyeLivenessUni, LIVENESS);
    }

    @Experimental("Asynchronous Health Check procedures")
    public Uni<SmallRyeHealth> getReadinessAsync() {
        return getHealthAsync(smallRyeReadinessUni, READINESS);
    }

    @Experimental("Asynchronous Health Check procedures & wellness experimental checks")
    public Uni<SmallRyeHealth> getWellnessAsync() {
        return getHealthAsync(smallryeWellnessUni, WELLNESS);
    }

    @Experimental("Asynchronous Health Check procedures and Health Groups")
    public Uni<SmallRyeHealth> getHealthGroupAsync(String groupName) {
        List<Uni<HealthCheckResponse>> checks = new ArrayList<>();
        initUnis(checks, allHealthChecks.select(HealthGroup.Literal.of(groupName)),
                allAsyncHealthChecks.select(HealthGroup.Literal.of(groupName)));

        return getHealthAsync(checks);
    }

    @Experimental("Asynchronous Health Check procedures and Health Groups")
    public Uni<SmallRyeHealth> getHealthGroupsAsync() {
        List<Uni<HealthCheckResponse>> checks = new ArrayList<>();
        initUnis(checks, getHealthGroupsChecks(HealthCheck.class), getHealthGroupsChecks(AsyncHealthCheck.class));

        return getHealthAsync(checks);
    }

    @SuppressWarnings("unchecked")
    private <T> List<T> getHealthGroupsChecks(Class<T> checkClass) {
        Iterator<Bean<?>> iterator = beanManager.getBeans(checkClass, Any.Literal.INSTANCE).iterator();

        List<T> groupHealthChecks = new ArrayList<>();

        while (iterator.hasNext()) {
            Bean<?> bean = iterator.next();
            if (bean.getQualifiers().stream().anyMatch(annotation -> annotation.annotationType().equals(HealthGroup.class))) {
                groupHealthChecks.add((T) beanManager.getReference(bean, bean.getBeanClass(),
                        beanManager.createCreationalContext(bean)));
            }
        }

        return groupHealthChecks;
    }

    private SmallRyeHealth getHealth(Uni<SmallRyeHealth> cachedHealth, HealthType... types) {
        return getHealthAsync(cachedHealth, types).await().atMost(Duration.ofSeconds(timeoutSeconds));
    }

    private Uni<SmallRyeHealth> getHealthAsync(Uni<SmallRyeHealth> cachedHealth, HealthType... types) {
        if (cachedHealth == null || additionalListsChanged(types)) {
            additionalListsChanged = false;
            cachedHealth = computeHealth(types);
        }

        return cachedHealth;
    }

    private boolean additionalListsChanged(HealthType... types) {
        boolean needRecompute = false;
        for (HealthType type : types) {
            switch (type) {
                case LIVENESS:
                    if (livenessHealthRegistry.checksChanged()) {
                        needRecompute = true;
                    }
                    break;
                case READINESS:
                    if (readinessHealthRegistry.checksChanged()) {
                        needRecompute = true;
                    }
                    break;
            }
        }

        return needRecompute;
    }

    private Uni<SmallRyeHealth> computeHealth(HealthType[] types) {
        List<Uni<HealthCheckResponse>> checks = new ArrayList<>();

        for (HealthType type : types) {
            switch (type) {
                case HEALTH:
                    checks.addAll(healthUnis);
                    break;
                case LIVENESS:
                    checks.addAll(livenessUnis);
                    checks.addAll(livenessHealthRegistry.getChecks());
                    break;
                case READINESS:
                    checks.addAll(readinessUnis);
                    checks.addAll(readinessHealthRegistry.getChecks());
                    break;
                case WELLNESS:
                    checks.addAll(wellnessUnis);
                    break;
            }
        }

        return getHealthAsync(checks);
    }

    private Uni<SmallRyeHealth> getHealthAsync(Collection<Uni<HealthCheckResponse>> checks) {
        List<Uni<HealthCheckResponse>> healthCheckUnis = new ArrayList<>();

        if (checks != null) {
            healthCheckUnis.addAll(checks);
        }

        if (!additionalChecks.isEmpty()) {
            healthCheckUnis.addAll(additionalChecks.values());
        }

        if (healthCheckUnis.isEmpty()) {
            return Uni.createFrom().item(createEmptySmallRyeHealth());
        }

        return Uni.combine().all().unis(healthCheckUnis)
                .combinedWith(responses -> {
                    JsonArrayBuilder results = jsonProvider.createArrayBuilder();
                    HealthCheckResponse.State status = HealthCheckResponse.State.UP;

                    for (Object o : responses) {
                        HealthCheckResponse response = (HealthCheckResponse) o;
                        status = handleResponse(response, results, status);
                    }

                    return createSmallRyeHealth(results, status);
                });
    }

    private SmallRyeHealth createEmptySmallRyeHealth() {
        return createSmallRyeHealth(jsonProvider.createArrayBuilder(), null);
    }

    private SmallRyeHealth createSmallRyeHealth(JsonArrayBuilder results, HealthCheckResponse.State status) {
        JsonObjectBuilder builder = jsonProvider.createObjectBuilder();
        JsonArray checkResults = results.build();

        builder.add("status", checkResults.isEmpty() ? emptyChecksOutcome : status.toString());
        builder.add("checks", checkResults);

        return new SmallRyeHealth(builder.build());
    }

    private HealthCheckResponse.State handleResponse(HealthCheckResponse response, JsonArrayBuilder results,
            HealthCheckResponse.State globalOutcome) {
        JsonObject responseJson = jsonObject(response);
        results.add(responseJson);

        if (globalOutcome == HealthCheckResponse.State.UP) {
            String status = responseJson.getString("status");
            if (status.equals("DOWN")) {
                return HealthCheckResponse.State.DOWN;
            }
        }

        return globalOutcome;
    }

    private JsonObject jsonObject(HealthCheckResponse response) {
        JsonObjectBuilder builder = jsonProvider.createObjectBuilder();
        builder.add("name", response.getName());
        builder.add("status", response.getState().toString());
        response.getData().ifPresent(d -> {
            JsonObjectBuilder data = jsonProvider.createObjectBuilder();
            for (Map.Entry<String, Object> entry : d.entrySet()) {
                Object value = entry.getValue();
                if (value instanceof String) {
                    data.add(entry.getKey(), (String) value);
                } else if (value instanceof Long) {
                    data.add(entry.getKey(), (Long) value);
                } else if (value instanceof Boolean) {
                    data.add(entry.getKey(), (Boolean) value);
                }
            }
            builder.add("data", data.build());
        });

        return builder.build();
    }

    public void addHealthCheck(HealthCheck check) {
        if (check != null) {
            additionalChecks.put(check.getClass().getName(), asyncHealthCheckFactory.callSync(check));
            additionalListsChanged = true;
        }
    }

    public void addHealthCheck(AsyncHealthCheck check) {
        if (check != null) {
            additionalChecks.put(check.getClass().getName(), asyncHealthCheckFactory.callAsync(check));
            additionalListsChanged = true;
        }
    }

    public void removeHealthCheck(HealthCheck check) {
        additionalChecks.remove(check.getClass().getName());
        additionalListsChanged = true;
    }

    public void removeHealthCheck(AsyncHealthCheck check) {
        additionalChecks.remove(check.getClass().getName());
        additionalListsChanged = true;
    }

    enum HealthType {
        HEALTH,
        LIVENESS,
        READINESS,
        WELLNESS
    }
}
