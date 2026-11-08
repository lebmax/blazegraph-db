#!/usr/bin/env bash
set -Eeuo pipefail

exec 3<>/dev/tcp/127.0.0.1/2181
printf 'stat' >&3

mode=''
while IFS= read -r -t 2 -u 3 line; do
    if [[ "$line" == Mode:* ]]; then
        mode="${line#Mode: }"
        break
    fi
done

exec 3>&-

if [[ "$mode" == 'leader' || "$mode" == 'follower' ]]; then
    exit 0
fi

printf 'ZooKeeper quorum is not ready (Mode: %s)\n' "${mode:-missing}" >&2
exit 1
