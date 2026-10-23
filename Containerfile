# Chequer container image.
#
#   podman build --format docker -t chequer .
#   podman run -d -p 3000:3000 -v chequer-data:/data chequer
#
# The default config listens on 0.0.0.0:3000, stores the database in /data and
# runs in single-user mode. Mount your own config over /etc/chequer/chequer.ini
# to change it; see doc/operations.md.

ARG DEBIAN_VERSION=trixie


## Build stage: compile the stand-alone binary with Jolt.

FROM docker.io/library/debian:${DEBIAN_VERSION}-slim AS build

ARG DEBIAN_FRONTEND=noninteractive
ARG JOLT_VERSION=v0.8.23
ARG JOLT_SHA256=90031be43241e61d0788a2f1bbd3da56d8f4324ae9fa87271f9637b43a6071f2

RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      ca-certificates curl git liblz4-1 zlib1g libtinfo6 libuuid1 libsqlite3-0 \
 && rm -rf /var/lib/apt/lists/*

RUN curl -fsSL https://raw.githubusercontent.com/jolt-lang/jolt/main/install \
      | bash -s -- --dir /usr/local/bin --version "$JOLT_VERSION" --checksum "$JOLT_SHA256"

WORKDIR /src

# Fetch dependencies in their own layer, so source changes don't refetch them.
COPY deps.edn ./
RUN jolt path > /dev/null

COPY bin ./bin
COPY resources ./resources
COPY src ./src
RUN bin/build release


## Runtime stage: the binary plus the shared libraries it loads at runtime.

FROM docker.io/library/debian:${DEBIAN_VERSION}-slim

ARG DEBIAN_FRONTEND=noninteractive

RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      ca-certificates curl libsqlite3-0 libssl3t64 libuuid1 tzdata zlib1g \
 && rm -rf /var/lib/apt/lists/*

RUN useradd --user-group --uid 10001 --home-dir /data --no-create-home chequer \
 && mkdir -p /data /etc/chequer \
 && chown chequer:chequer /data \
 && printf '%s\n' \
      '[server]' \
      'listen = 0.0.0.0:3000' \
      'base-url = http://localhost:3000' \
      '' \
      '[database]' \
      'path = /data/chequer.db' \
      > /etc/chequer/chequer.ini

COPY --from=build /src/target/release/chequer /usr/local/bin/chequer

ENV CHEQUER_CONFIG=/etc/chequer/chequer.ini
USER chequer
WORKDIR /data
VOLUME /data
EXPOSE 3000

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s \
  CMD curl -fsS -o /dev/null http://127.0.0.1:3000/healthz || exit 1

ENTRYPOINT ["/usr/local/bin/chequer"]
CMD ["server"]
