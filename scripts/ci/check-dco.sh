#!/usr/bin/env bash
set -euo pipefail

base_sha="${1:?base SHA is required}"
failed=0
while IFS= read -r commit; do
  if ! git show -s --format=%B "$commit" | grep -Eiq '^Signed-off-by: .+ <.+>$'; then
    echo "Missing DCO sign-off: $commit" >&2
    failed=1
  fi
done < <(git rev-list "$base_sha"..HEAD)

exit "$failed"
