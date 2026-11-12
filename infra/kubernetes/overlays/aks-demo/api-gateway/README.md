# AKS API gateway after Cloudflare Pages cutover

The public `tcgstore-demo-2026-11.malaysiawest.cloudapp.azure.com` endpoint must
keep HTTPS and the `/api` reverse proxy after the frontend moves to Cloudflare
Pages. This overlay replaces the current frontend container with plain Caddy.
It serves no frontend files: every path outside `/api` returns HTTP 404.

The Deployment, Service, and PVC keep their existing `tcg-store-frontend` names
to preserve the Azure DNS label, public load balancer, and ACME certificate
storage. Those names are legacy only; the running image is an API gateway.
This overlay is deliberately separate from the backend app overlay, so a normal
backend rollout cannot switch off the frontend before Pages is ready.

## Cutover order

1. Create the Cloudflare Pages project from the frontend GitHub repository on
   `main`. Set `VITE_API_BASE_URL=/api`, `VITE_ENABLE_MOCK_API=false`, and the
   runtime variable `API_ORIGIN=https://tcgstore-demo-2026-11.malaysiawest.cloudapp.azure.com`.
2. Add the actual `https://<project>.pages.dev` origin to
   `APP_CORS_ALLOWED_ORIGINS` in the parent `aks-demo/configmap.yaml`, apply the
   backend overlay, and restart the backend Deployment so its environment
   reads the new ConfigMap. Keep any active custom domain origins in the list.
3. Verify the Pages homepage, `/api/health`, login or another POST request, and
   the live auction WebSocket on the `pages.dev` hostname. Keep the old AKS
   frontend until all checks pass.
4. Switch the stable AKS endpoint to API-only Caddy:

   ```powershell
   kubectl apply --dry-run=server -k infra/kubernetes/overlays/aks-demo/api-gateway
   kubectl apply -k infra/kubernetes/overlays/aks-demo/api-gateway
   kubectl rollout status -n tcg-store-aks-demo deployment/tcg-store-frontend --timeout=5m
   ```

5. Check that the AKS `/api/health` still returns `UP`, its `/` returns 404,
   and the Pages site and API remain usable. Record the Pages URL as the sole
   frontend URL in the project handoff.

If the gateway rollout fails, use
`kubectl rollout undo -n tcg-store-aks-demo deployment/tcg-store-frontend`.
The previous image contains the original Caddy configuration and frontend
assets. Recheck the public API before trying the cutover again.
