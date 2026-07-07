# Two-stage build: bake the k8store provider into a pre-built (--optimized) Keycloak nightly image.
# Build context must be the repo root. The provider is the thin jar plus its runtime
# dependency jars, all staged by the core build into core/target/providers/ (no shading,
# fabric8 with the JDK HTTP client).
FROM quay.io/keycloak/keycloak:nightly AS builder

COPY core/target/providers/*.jar /opt/keycloak/providers/

# --features-disabled=organization: with the default areas (no 'organization' area, groups in
# CRs) the organizations feature must stay off - the built-in JPA organization store cannot
# reference CR-backed groups and the k8store boot validation rejects that combination. To use
# organizations, enable the area (e.g. areas=all) AND the feature AND disable the infinispan
# organization cache (--spi-organization--infinispan--enabled=false); the deployment manifest's
# non-optimized start makes those runtime args authoritative.
RUN /opt/keycloak/bin/kc.sh build \
      --features=stateless \
      --db=postgres \
      --spi-datastore--provider=k8store \
      --spi-realm--jpa--enabled=false \
      --spi-realm-cache--default--enabled=false \
      --spi-authorization-cache--default--enabled=false \
      --features-disabled=organization \
      --health-enabled=true \
      --metrics-enabled=true

FROM quay.io/keycloak/keycloak:nightly
COPY --from=builder /opt/keycloak/ /opt/keycloak/
ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
