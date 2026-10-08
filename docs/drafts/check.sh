#!/bin/bash
# Verifies every draft PR branch on its own, so a reviewer does not have to trust the
# integration branch:
#
#   1. detaches each branch in one scratch worktree (the main working tree is untouched, and the
#      Gradle build stays warm, so each branch only costs a recompile)
#   2. runs ktlint on app/src (ignoring hits upstream itself has) and compiles it with the Android
#      toolchain
#   3. reports which branches would conflict with each other when both get merged
#
# Usage: bash docs/drafts/check.sh [branch ...]      (default: all reviewable fix/* and feature/*)
#
# Environment (all optional):
#   UPSTREAM     commit every draft sits on          (default 51eb708, the commit after upstream 2.5)
#   SKIP         branches to leave out               (default: the obsolete ones, see README)
#   GRADLE_CMD   compile command, run in the worktree (default: ./gradlew, or gradlew.bat from WSL)
#   KTLINT       ktlint binary                       (default /tmp/ktlint-1.8.0/.../bin/ktlint)
#   WORKDIR      scratch directory                   (default /mnt/d/repos/squeezeclient-drafts)
#
# Results land in check-report.txt next to this script, per-branch build output in /tmp/drafts-check/.
set -u

HERE=$(cd "$(dirname "$0")" && pwd)
cd "$HERE/../.." || exit 1
UPSTREAM=${UPSTREAM:-51eb708}
WORKDIR=${WORKDIR:-/mnt/d/repos/squeezeclient-drafts}
SCRATCH=${SCRATCH:-$WORKDIR/scratch}
LOGDIR=${LOGDIR:-/tmp/drafts-check}
KTLINT=${KTLINT:-/tmp/ktlint-1.8.0/ktlint-1.8.0/bin/ktlint}
SKIP=${SKIP:-"fix/favorites-duplicate-entries fix/media-session-lifetime
fix/media-session-state-stability fix/flac-resume-after-pause fix/cometd-utf8-decoding
fix/volume-follow-device-changes"}
REPORT=$HERE/check-report.txt
WIN_SCRATCH=$(echo "$SCRATCH" | sed 's|^/mnt/\([a-z]\)/|\U\1:\\|; s|/|\\|g')

if [ -z "${GRADLE_CMD:-}" ]; then
    if command -v cmd.exe >/dev/null 2>&1; then
        GRADLE_CMD="cmd.exe /c \"cd /d $WIN_SCRATCH && gradlew.bat --quiet :app:compileFossDebugKotlin\""
    else
        GRADLE_CMD="./gradlew --quiet :app:compileFossDebugKotlin"
    fi
fi

mkdir -p "$WORKDIR" "$LOGDIR"
: > "$REPORT"

if [ $# -gt 0 ]; then
    BRANCHES="$*"
else
    BRANCHES=$(git for-each-ref --format='%(refname:short)' refs/heads | grep -E '^(fix|feature)/')
fi
for skip in $SKIP; do
    BRANCHES=$(echo "$BRANCHES" | grep -v "^$skip\$" || true)
done

# One scratch worktree for all branches: Gradle keeps its caches, so only the first branch is slow.
git worktree remove --force "$SCRATCH" >/dev/null 2>&1
if ! git worktree add --force --detach "$SCRATCH" "$UPSTREAM" >/dev/null 2>&1; then
    echo "could not create scratch worktree at $SCRATCH" >&2
    exit 1
fi
cp -f local.properties "$SCRATCH/local.properties" 2>/dev/null

# ktlint baseline: upstream itself need not be clean, and a draft must not be blamed for that
BASE_WT="$WORKDIR/base"
git worktree remove --force "$BASE_WT" >/dev/null 2>&1
BASELINE_LINT=""
if git worktree add --force --detach "$BASE_WT" "$UPSTREAM" >/dev/null 2>&1; then
    cp -f local.properties "$BASE_WT/local.properties" 2>/dev/null
    BASELINE_LINT=$(cd "$BASE_WT" && sh "$KTLINT" "app/src/**/*.kt" 2>&1 | grep -E "\.kt:[0-9]+:[0-9]+:" | sed "s|$BASE_WT/||" | sort)
    git worktree remove --force "$BASE_WT" >/dev/null 2>&1
fi

echo "== draft check $(date -Is), upstream $UPSTREAM ==" | tee -a "$REPORT"
echo "   gradle: $GRADLE_CMD" | tee -a "$REPORT"
FAILED=""

for branch in $BRANCHES; do
    log="$LOGDIR/${branch//\//-}.log"
    echo "--- $branch" | tee -a "$REPORT"
    if ! git -C "$SCRATCH" checkout --detach "$branch" >/dev/null 2>&1; then
        echo "    checkout: FAILED (branch missing)" | tee -a "$REPORT"
        FAILED="$FAILED $branch"
        continue
    fi

    behind=$(git rev-list --count "$branch".."$UPSTREAM")
    ahead=$(git rev-list --count "$UPSTREAM".."$branch")
    size=$(git diff --numstat "$UPSTREAM" "$branch" 2>/dev/null | awk '{a+=$1; d+=$2} END {printf "+%d/-%d files=%d", a, d, NR}')
    echo "    base: $behind behind / $ahead ahead of upstream; diff: $size" | tee -a "$REPORT"

    ktlint_out=$(cd "$SCRATCH" && sh "$KTLINT" "app/src/**/*.kt" 2>&1 | grep -E "\.kt:[0-9]+:[0-9]+:" | sed "s|$SCRATCH/||" | sort)
    if [ -z "$ktlint_out" ]; then
        echo "    ktlint: clean" | tee -a "$REPORT"
    elif [ -n "$BASELINE_LINT" ] && [ "$ktlint_out" = "$BASELINE_LINT" ]; then
        echo "    ktlint: upstream's pre-existing hits only" | tee -a "$REPORT"
    else
        echo "    ktlint: issues ($log)" | tee -a "$REPORT"
        printf '%s\n' "$ktlint_out" | grep -vxF -f <(printf '%s\n' "$BASELINE_LINT") | head -3 \
            | sed 's/^/        /' | tee -a "$REPORT"
    fi

    if (cd "$SCRATCH" && eval "$GRADLE_CMD") > "$log" 2>&1; then
        echo "    compile: OK" | tee -a "$REPORT"
    else
        echo "    compile: FAILED ($log)" | tee -a "$REPORT"
        grep -E "^e: " "$log" | head -3 | sed 's/^/        /' | tee -a "$REPORT"
        FAILED="$FAILED $branch"
    fi
done

echo | tee -a "$REPORT"
echo "== merge conflicts between drafts (every pair) ==" | tee -a "$REPORT"
CONFLICTS=0
list=$(echo "$BRANCHES" | tr ' ' '\n' | grep -v '^$')
for a in $list; do
    for b in $list; do
        [ "$a" \< "$b" ] || continue
        out=$(git merge-tree --write-tree --name-only "$a" "$b" 2>&1) || {
            CONFLICTS=$((CONFLICTS + 1))
            files=$(echo "$out" | tail -n +2 | head -4 | tr '\n' ' ')
            echo "    $a + $b -> CONFLICT in ${files:-?}" | tee -a "$REPORT"
        }
    done
done
[ "$CONFLICTS" -eq 0 ] && echo "    none" | tee -a "$REPORT"

echo | tee -a "$REPORT"
if [ -n "$FAILED" ]; then
    echo "== branches that do NOT build on their own:$FAILED" | tee -a "$REPORT"
else
    echo "== all branches build on their own ==" | tee -a "$REPORT"
fi
git worktree remove --force "$SCRATCH" >/dev/null 2>&1
echo "report: $REPORT"
