#!/usr/bin/env bash
set -Eeuo pipefail

case "$ZOO_MY_ID" in
    1|2|3)
        ;;
    *)
        printf 'ZOO_MY_ID must be 1, 2, or 3\n' >&2
        exit 64
        ;;
esac

mkdir -p "$ZOO_DATA_DIR"
printf '%s\n' "$ZOO_MY_ID" > "$ZOO_DATA_DIR/myid"

# QuorumPeerConfig resolves every server address during startup. Wait until
# Docker has registered all Compose service names before launching that JVM.
for peer in zk1 zk2 zk3; do
    attempts=0
    until getent hosts "$peer" >/dev/null 2>&1; do
        attempts=$((attempts + 1))
        if (( attempts >= 60 )); then
            printf 'Timed out waiting for Docker DNS entry: %s\n' "$peer" >&2
            exit 69
        fi
        sleep 1
    done
done

exec java \
    -cp "/opt/zookeeper/conf:/opt/zookeeper/lib/*" \
    org.apache.zookeeper.server.quorum.QuorumPeerMain \
    /opt/zookeeper/conf/zoo.cfg
