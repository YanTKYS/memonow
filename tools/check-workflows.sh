#!/usr/bin/env bash
set -euo pipefail
check_dir=$(mktemp -d)
trap 'rm -rf "$check_dir"' EXIT
curl --fail --silent --show-error --location https://github.com/rhysd/actionlint/releases/download/v1.7.12/actionlint_1.7.12_linux_amd64.tar.gz --output "$check_dir/actionlint.tar.gz"
printf '%s  %s\n' 8aca8db96f1b94770f1b0d72b6dddcb1ebb8123cb3712530b08cc387b349a3d8 "$check_dir/actionlint.tar.gz" | sha256sum --check
tar -xzf "$check_dir/actionlint.tar.gz" -C "$check_dir" actionlint
"$check_dir/actionlint" .github/workflows/*.yml
