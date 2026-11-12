#!/bin/bash
# Runs the backend build and tests inside a throwaway Maven + JDK 21 container, isolated from
# this machine's Java, Maven and Testcontainers settings (for example a ~/.testcontainers.properties
# written by another project). The source is mounted read-only and copied inside the container,
# so nothing on the host changes; Maven downloads are cached in the Docker volume luma-m2-cache.
#
#   scripts/backend-test-docker.sh                         # mvn verify
#   scripts/backend-test-docker.sh test -Dtest=Zoom*       # any Maven arguments
#
# Testcontainers in the container starts PostgreSQL through the host's Docker and reaches it
# at host.docker.internal.
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
IMAGE=maven:3.9.9-eclipse-temurin-21
ARGS=("$@"); [ ${#ARGS[@]} -eq 0 ] && ARGS=(verify)
exec docker run --rm --name "luma-backend-tests-$$" \
  -v "$ROOT/backend:/src:ro" -v luma-m2-cache:/root/.m2 -v /var/run/docker.sock:/var/run/docker.sock \
  --add-host host.docker.internal:host-gateway \
  -e DOCKER_HOST=unix:///var/run/docker.sock -e TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
  -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
  "$IMAGE" bash -c 'mkdir /work && tar --exclude=./target -C /src -cf - . | tar -C /work -xf - && cd /work && mvn -B -ntp "$@"' \
  luma-tests "${ARGS[@]}"
