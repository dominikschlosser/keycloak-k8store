# Two-stage build: bake the k8store provider into a pre-built (--optimized) Keycloak 26.7.0 image.
# Build context must be the repo root. The provider is the thin jar plus its runtime
# dependency jars, all staged by the core build into core/target/providers/ (no shading,
# fabric8 with the JDK HTTP client).
FROM quay.io/keycloak/keycloak:26.7.0 AS builder

COPY core/target/providers/*.jar /opt/keycloak/providers/

# Selecting the k8store datastore is the opt-in. From there k8store self-configures. Its
# K8sConfigDefaultsSourceFactory disables the SPI providers that would otherwise shadow the
# CR-backed stores. Those are the built-in JPA realm provider and the realm, authorization, and
# organization infinispan caches. Any explicit --spi-* here or at runtime still wins.
#
# --features-disabled=organization is needed with the default areas. There is no 'organization'
# area and groups live in CRs, so the organizations feature must stay off. The built-in JPA
# organization store cannot reference CR-backed groups, and the k8store boot validation rejects
# that combination. To use organizations, enable the area (for example areas=all, or
# areas=organization which auto-pulls in group, identity-provider, and realm) and the feature.
RUN /opt/keycloak/bin/kc.sh build \
      --features=stateless \
      --db=postgres \
      --spi-datastore--provider=k8store \
      --features-disabled=organization \
      --health-enabled=true \
      --metrics-enabled=true

FROM quay.io/keycloak/keycloak:26.7.0
COPY --from=builder /opt/keycloak/ /opt/keycloak/
ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
