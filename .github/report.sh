#!/bin/bash
# Temporary, while ios.yml is being brought up: this repository's runs can only be read with a token,
# so a run leaves its state and the end of its log on the branch ios-ipa-log, readable over ssh.
set -u
state="$1"
out=$(mktemp -d)
log=build/xcodebuild.log
{
  echo "state=$state"
  echo "sha=$GITHUB_SHA"
  echo "run=$GITHUB_RUN_NUMBER"
  echo "url=$GITHUB_SERVER_URL/$GITHUB_REPOSITORY/actions/runs/$GITHUB_RUN_ID"
  echo "steps=${STEPS:-}"
  ls -la build/ipa/*.ipa 2>/dev/null
} > "$out/status.txt"
if [ -f "$log" ]; then
  grep -nE 'error:|^e: |FAILED|What went wrong|Undefined symbol|^ld: |Could not|Execution failed|Caused by' "$log" | tail -n 300 > "$out/errors.txt"
  tail -n 1500 "$log" > "$out/log-tail.txt"
fi
cd "$out"
git init -q
git add .
git -c user.name='github-actions[bot]' -c user.email='41898282+github-actions[bot]@users.noreply.github.com' commit -qm "ios run $GITHUB_RUN_NUMBER: $state"
git push -q --force "https://x-access-token:$TOKEN@github.com/$GITHUB_REPOSITORY.git" HEAD:refs/heads/ios-ipa-log || echo "::warning::the report was not pushed"
