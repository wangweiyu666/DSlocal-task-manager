#!/usr/bin/env bash
set -euo pipefail

if git ls-files | grep -E '\.(jks|keystore|p12|pfx)$|(^|/)(keystore|signing)[^/]*\.properties$'; then
  echo 'Release key material must not be tracked.' >&2
  exit 1
fi

if git grep -I -n -E -- '-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----|gh[pousr]_[A-Za-z0-9_]{30,}|github_pat_[A-Za-z0-9_]{40,}'; then
  echo 'Possible private key or GitHub token found.' >&2
  exit 1
fi

echo 'No tracked release keys or known token patterns found.'
