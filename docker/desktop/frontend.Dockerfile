# Desktop build of the separately checked-out frontend. Its current development
# dependencies require Node >=22; browser-test binaries are unnecessary to serve it.
FROM node:22-alpine AS build
WORKDIR /app
ARG VITE_API_BASE_URL=/api
ENV CYPRESS_INSTALL_BINARY=0
ENV VITE_ENABLE_MOCK_API=false
COPY package.json package-lock.json ./
RUN npm ci --legacy-peer-deps --no-audit --no-fund
COPY . .
RUN npm run build

FROM nginx:alpine AS runtime
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
