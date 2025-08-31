#!/usr/bin/env sh
if echo "$1" | grep -qiE 'username'; then
  echo "x-access-token"
elif echo "$1" | grep -qiE 'password'; then
  echo "$GITHUB_TOKEN"
else
  echo ""
fi
