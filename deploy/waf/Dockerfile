FROM caddy:2.11.9-builder-alpine AS build
RUN xcaddy build v2.11.9 --with github.com/corazawaf/coraza-caddy/v2@v2.6.2

FROM caddy:2.11.9-alpine
COPY --from=build /usr/bin/caddy /usr/bin/caddy
RUN addgroup -S -g 10001 caddy \
    && adduser -S -D -H -u 10001 -G caddy caddy \
    && chown -R caddy:caddy /config /data \
    && setcap cap_net_bind_service=+ep /usr/bin/caddy
USER 10001:10001
