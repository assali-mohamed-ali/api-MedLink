FROM caddy:2.11.9-builder-alpine AS build
RUN xcaddy build v2.11.9 --with github.com/corazawaf/coraza-caddy/v2@v2.6.2

FROM caddy:2.11.9-alpine
COPY --from=build /usr/bin/caddy /usr/bin/caddy
