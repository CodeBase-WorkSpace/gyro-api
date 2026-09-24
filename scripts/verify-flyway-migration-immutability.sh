#!/usr/bin/env bash
set -Eeuo pipefail

# Versioned Flyway migrations are part of the deployed database history. Editing
# one changes its checksum and can leave already-migrated databases inconsistent.
readonly SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly API_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"
readonly MIGRATIONS_DIR="src/main/resources/db/migration"

cd "$API_ROOT"

# A source archive has no Git history to compare. Keep the check active in
# normal checkouts, while allowing standalone archive builds before Git init.
if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    if [[ -n "${FLYWAY_MIGRATION_BASE_REF:-}${GITHUB_BASE_REF:-}" ]]; then
        echo "Flyway migration base ref was set, but this source tree has no Git history." >&2
        exit 1
    fi
    echo "Skipping Flyway migration immutability check: source tree has no Git history."
    exit 0
fi

if [[ -n "${FLYWAY_MIGRATION_BASE_REF:-}" ]]; then
    base_ref="$FLYWAY_MIGRATION_BASE_REF"
elif [[ -n "${GITHUB_BASE_REF:-}" ]]; then
    base_ref="origin/$GITHUB_BASE_REF"
elif git rev-parse --verify --quiet origin/main >/dev/null; then
    base_ref="origin/main"
elif git rev-parse --verify --quiet main >/dev/null; then
    base_ref="main"
else
    echo "Unable to find a base branch for Flyway migration immutability verification." >&2
    echo "Set FLYWAY_MIGRATION_BASE_REF to the branch or commit this change is based on." >&2
    exit 1
fi

base_commit="$(git merge-base "$base_ref" HEAD)"
git_prefix="$(git rev-parse --show-prefix)"
violations=()

while IFS= read -r path; do
    repo_path="${git_prefix}${path}"

    # A migration absent from the target branch is new for this change and may
    # still be revised before merge. Looking only at its first commit is wrong:
    # an in-branch version rename (for example V56 -> V57 after a rebase) makes
    # the introducing commit appear to have no file at the current path.
    if ! git cat-file -e "$base_commit:$repo_path" 2>/dev/null; then
        continue
    fi

    # Existing target-branch migrations must remain byte-for-byte unchanged.
    # Comparing with the original adding commit as a fallback also permits a
    # one-time restoration when the target branch contains a known accidental
    # edit.
    if [[ -f "$path" ]] && git diff --quiet "$base_commit" -- "$path"; then
        continue
    fi

    introduced_by="$(git log --follow --diff-filter=A --format=%H -- "$path" | tail -n 1)"
    if [[ ! -f "$path" ]] || [[ -z "$introduced_by" ]] || ! git diff --quiet "$introduced_by" -- "$path"; then
        violations+=("$path")
    fi
done < <(git diff --relative --name-only "$base_commit" -- "$MIGRATIONS_DIR")

if ((${#violations[@]} > 0)); then
    echo "Versioned Flyway migrations must be immutable after commit." >&2
    echo "Restore each file and create a new V<version>__*.sql migration instead:" >&2
    printf '  %s\n' "${violations[@]}" >&2
    exit 1
fi

echo "Flyway migration immutability check passed against $base_ref."
