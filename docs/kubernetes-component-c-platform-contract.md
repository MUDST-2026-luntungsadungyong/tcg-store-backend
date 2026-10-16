# Component C platform contract

This contract covers the reusable edge and network Kustomize Components. Environment overlays include them only for environments that have the required platform services. These manifests do not install or configure a Kubernetes distribution, Traefik, Traefik CRDs, cert-manager, a CNI, DNS, a certificate issuer, or external Secret delivery.

## Edge prerequisites and contract

- Provide Traefik v3 with the Kubernetes Ingress and Kubernetes CRD providers enabled, the `traefik` IngressClass, and its CRDs installed before applying the edge component. The controller must run in `kube-system` and have the label `app.kubernetes.io/name=traefik` for the network policy to admit it.
- Provide a namespaced cert-manager `Issuer` named `tcg-store-issuer` in each application namespace, or adapt the issuer annotation in that environment's edge overlay. The issuer must issue a certificate for `api.replace.invalid` only as a placeholder; replace the host in both Ingress rule and TLS host before use.
- Provide the `tcg-store-backend-tls` TLS Secret through cert-manager or an out-of-Git certificate process. Its certificate must cover the configured API hostname and renew successfully.
- The general Ingress routes host `api.replace.invalid` and path `/api` with `Prefix` semantics to `tcg-store-backend:80`. A second, higher-priority Ingress routes `/api/auth` to the same Service with its own rate-limit chain. Neither route exposes `/actuator` or another management path. The backend Service remains ClusterIP and targets pod TCP 8080.
- The general edge chain buffers request bodies up to 8,388,608 bytes, uses a 2 MiB in-memory buffering threshold, and applies a baseline rate limit of 100 requests per second with burst 50. The more specific auth chain reuses the body cap and limits `/api/auth` to 5 requests per second with burst 10. Traefik's rate counters are per Traefik instance unless distributed rate limiting is configured, so these are not cluster-wide quotas. Seller/upload-specific limits need the confirmed route paths and product limits before they can be safely added.
- The ServersTransport bounds dial time to 10 seconds, response-header wait to 30 seconds, and idle backend connections to 90 seconds. Validate these values against application and upload timeouts before production use.
- Both Ingresses bind to Traefik's `websecure` entrypoint and enable router TLS. The auth router has explicit priority `100` so `/api/auth` takes precedence over the general `/api` route. HTTP-to-HTTPS redirection is a controller-wide `web` entrypoint configuration prerequisite; this component does not create an HTTP route or controller configuration. Verify that global redirect before declaring HTTP behavior accepted.
- No forwarded-header trust settings are configured. Configure trusted proxy IPs only after the platform owner confirms the complete proxy chain and source ranges.
- The Middleware reference is set on the Ingress, and the ServersTransport reference is patched onto the backend Service, as required by [Traefik's Kubernetes Ingress provider](https://doc.traefik.io/traefik/v3.5/reference/routing-configuration/kubernetes/ingress/#on-service). Kustomize evaluates a Component's replacements before the parent overlay assigns its namespace, so namespace substitution belongs in every environment overlay that includes this component. Add a replacement sourced from the Ingress `metadata.namespace` to both annotations, using `delimiter: '-'` and `index: 0`. The component's `placeholder-...` values intentionally remain until this overlay handshake is present. Confirm the rendered annotations contain `<namespace>-tcg-edge-chain@kubernetescrd` on the Ingress and `<namespace>-tcg-backend-transport@kubernetescrd` on the Service.

## Network policy contract

- Include the network component only in a dedicated application namespace whose CNI enforces Kubernetes NetworkPolicy. Its default-deny policy selects every pod in that namespace, including any co-located controller or operational pod. Keep Traefik and DNS outside the application namespace.
- API ingress is allowed from pods labeled `app.kubernetes.io/name=traefik` in namespace `kube-system`, and from private Prometheus pods labeled `app.kubernetes.io/name=prometheus` in namespace `monitoring`, only on TCP 8080. Each rule requires the namespace selector AND pod selector in the same peer; selectors must match the platform installation. The monitoring policy does not expose Actuator through public Ingress and does not replace monitoring RBAC or CNI enforcement checks.
- API egress allows DNS to `kube-system` pods labeled `k8s-app=kube-dns` on TCP/UDP 53, PostgreSQL TCP 5432, and HTTPS TCP 443. Migration pods (component label `database-migration`) get DNS and PostgreSQL TCP 5432 only. They do not receive ingress allowances.
- The external database and object-storage rules use `0.0.0.0/0` with private, link-local, loopback, shared-address, documentation, benchmarking, multicast, and reserved IPv4 ranges excluded. This is a broad, port-limited public IPv4 egress exception, not FQDN-based security. It cannot restrict the rules to Neon or the chosen object-storage provider, and provider destinations that resolve inside excluded ranges will not work. Use a platform FQDN policy or egress gateway when available; otherwise document and accept the destination risk before production.
- No provider CIDRs are guessed. These rules do not grant arbitrary egress, but allow any reachable public IPv4 destination on the listed ports. IPv6 egress is not granted by these IPv4 rules; confirm cluster/CNI behavior if workloads can use IPv6.
- The policy selectors depend on existing workload labels: API `app.kubernetes.io/name=tcg-store-backend, app.kubernetes.io/component=api`; migration `app.kubernetes.io/name=tcg-store-backend, app.kubernetes.io/component=database-migration`. The Service selector alone is insufficient because it also matches migration pods.

## Live acceptance commands

Render the environment overlays and inspect the generated Ingress and policies before applying. After the platform and environment are available, set the non-secret values below in your shell; do not put credentials in command arguments or logs:

```sh
kubectl config current-context
kubectl -n "$NS" get ingress tcg-store-backend -o yaml
kubectl -n "$NS" describe certificate tcg-store-backend-tls
kubectl -n "$NS" get secret tcg-store-backend-tls -o jsonpath='{.type}{"\n"}'
kubectl -n "$NS" get service tcg-store-backend -o jsonpath='{.spec.type}{"\n"}'
kubectl -n "$NS" get networkpolicy
```

From a network location that resolves the configured API hostname, verify HTTPS certificate/hostname and HTTP redirect behavior without credentials:

```sh
curl --fail --silent --show-error --head "https://$API_HOST/api"
curl --silent --show-error --head "http://$API_HOST/api"
```

The first request may return an application-level 401/404 and still establish TLS; inspect the certificate with the local TLS client as part of acceptance. Confirm the HTTP response redirects to HTTPS. Exercise an upload at the supported maximum and one above the 8 MiB edge limit using a non-sensitive fixture, and verify exact CORS behavior in the browser or an HTTP client. Test allowed and denied pod connectivity from temporary, non-credentialed diagnostic pods on the same production-equivalent CNI. A successful render or API-server acceptance does not prove NetworkPolicy enforcement.

## Environment substitutions

The current edge host is intentionally `api.replace.invalid` to make incomplete environment configuration visible. Each staging/production overlay must replace it with the approved hostname and use the environment's issuer. Local development defaults to port-forward; add the edge component locally only when a local Traefik/cert-manager setup exists. Keep the TLS Secret out of Git. The application Secret `tcg-store-backend-secrets` is also delivered out of Git and is separate from the TLS Secret.

Each overlay that includes the edge component must declare this Kustomize replacement at the overlay level. It runs after that overlay's namespace transformer and avoids hard-coding staging or production namespace names into the reusable component:

```yaml
replacements:
  - source:
      kind: Ingress
      name: tcg-store-backend
      fieldPath: metadata.namespace
    targets:
      - select:
          kind: Ingress
          name: tcg-store-backend
        fieldPaths:
          - metadata.annotations.[traefik.ingress.kubernetes.io/router.middlewares]
        options:
          delimiter: '-'
          index: 0
      - select:
          kind: Ingress
          name: tcg-store-backend-auth
        fieldPaths:
          - metadata.annotations.[traefik.ingress.kubernetes.io/router.middlewares]
        options:
          delimiter: '-'
          index: 0
      - select:
          group: ""
          version: v1
          kind: Service
          name: tcg-store-backend
        fieldPaths:
          - metadata.annotations.[traefik.ingress.kubernetes.io/service.serverstransport]
        options:
          delimiter: '-'
          index: 0
```

Manifest validation must expect both `tcg-store-backend` and `tcg-store-backend-auth` Ingresses, verify the `/api` and higher-priority `/api/auth` paths and their corresponding middleware chains, and reject either rendered Ingress if `spec.ingressClassName` is not `traefik` while this component is selected. Supporting another class requires adapting the controller annotations, Middleware/ServersTransport API and routing semantics as a component change; changing only the class field is not a supported configuration.
