---
name: fix-pr-comments
description: Triage PR review comments and fix real bugs; when no bugs remain, update docs + version, merge PR, delete branch
disable-model-invocation: true
---

# Fix PR Comments

Fetch review comments from the current PR, fix confirmed bugs, then bring the PR to "done":

- **If bugs found:** triage, fix, react, resolve threads — wait for next review pass.
- **If no bugs remain:** check docs & version are up to date (write/bump if needed), then merge the PR and delete the branch. ROADMAP entry is committed straight to the base branch — no separate PR.

## Arguments

- `$1` — PR number (optional). If omitted, uses the current branch's open PR.

## Workflow

### Phase 1: Fetch comments

1. Determine the PR number:
   ```bash
   # If $1 provided, use it. Otherwise detect from current branch:
   gh pr view --json number --jq '.number'
   ```

2. Get repository info:
   ```bash
   gh repo view --json nameWithOwner --jq '.nameWithOwner'
   gh pr view {number} --json baseRefName,headRefName,title --jq '.'
   ```

3. Fetch both PR-level and review comments:
   ```bash
   gh api repos/{owner}/{repo}/issues/{number}/comments    # PR-level discussion
   gh api repos/{owner}/{repo}/pulls/{number}/comments     # inline review comments
   ```

4. Parse each comment extracting: `author`, `body`, `path`, `line`, `diff_hunk`, `node_id`.

### Phase 1.5: Quick verdict via reactions on the PR description

Codex signals its verdict on a review pass by **reacting to the PR body itself** (the description Mintflavored wrote when opening the PR). The reactions appear right under the PR description, NOT on a separate comment.

| Reaction | Meaning |
|----------|---------|
| 👍 (`+1`) | Pass — no bugs found |
| 👀 (`eyes`) | Issues found — see the inline comments below |
| anything else / no reaction | Review hasn't completed yet, or codex didn't react |

This gives us an O(1) verdict before parsing every inline comment.

1. Fetch reactions on the **PR description (issue body)**. Since PRs are issues underneath, use the `issues/{number}/reactions` endpoint, NOT `issues/comments/{id}/reactions`:
   ```bash
   gh api repos/{owner}/{repo}/issues/{number}/reactions --jq '[.[] | {content, user: .user.login}]'
   ```

2. Decide:

   - **👍 on the PR body** → fast-path: **no bugs**. Skip Phase 2 triage. Still run Phase 4 on any leftover non-bug threads (questions, etc), then go straight to Phase 6 — which will hand off to Phase 7 (docs/version) → Phase 8 (merge approval).
   - **👀 on the PR body** → codex found something. Proceed to full Phase 2 triage of the inline comments. **Do not assume "no bugs" just because the inline list looks short** — codex may have left a single high-severity comment.
   - **No reaction yet (or only the user's own reactions)** → review probably hasn't completed. Report this to the user and stop. Suggest: wait a few minutes and re-run `/fix-pr-comments`, or check that codex is configured for this repo.

3. **Sanity-check the fast-path.** Even on 👍, scan the inline-comments list (`pulls/{number}/comments`) for length. If it's not empty, log a warning and fall back to full Phase 2 — a 👍 on the PR body combined with inline bug comments is a contradiction we should investigate, not silently trust.

> Note: the `👀` icon shown in the GitHub UI's reaction picker is just the picker affordance — what matters is the actual reaction stored on the body. Read the API response, not screenshots, to decide.

### Phase 2: Triage

For each comment, classify it into one of these categories:

| Category | Action |
|----------|--------|
| **Bug / Security issue** | Read the referenced code, verify the issue exists, fix it |
| **Valid improvement** | Read the code, assess effort vs value, fix if quick (<20 lines change) |
| **False positive** | The comment describes an issue that doesn't actually exist or is already handled. Skip and note why. |
| **Style / opinion** | Subjective preference, not a bug. Skip. |
| **Question / discussion** | Not actionable as a code change. Skip. |
| **Already fixed** | The issue was already addressed. Skip and note. |

**Triage rules:**
- Always read the actual code at the referenced path and line before deciding. The diff_hunk in the comment may be outdated.
- Check if the issue was already fixed in a later commit on this branch.
- A comment is a **real bug** if it describes behavior that would cause incorrect results, data loss, security vulnerability, crash, deadlock, or resource leak under realistic conditions.
- A comment is a **false positive** if it describes a theoretical issue that can't happen given the surrounding code, or misunderstands the control flow.
- Prioritize by severity: security > data corruption > crash > incorrect behavior > performance > style.

### Phase 3: Fix (only if bugs/improvements were confirmed)

For each confirmed bug or valid improvement:

1. Read the full file (not just the diff hunk) to understand context.
2. Make the minimal fix — don't refactor surrounding code.
3. Verify the fix compiles. Pick the right command for the repo:
   ```bash
   # Go repo:
   go build ./...
   # Android repo:
   ./gradlew compileDebugKotlin
   ```
4. If there are tests for the affected package, run them.

### Phase 4: React & resolve threads

After triaging each comment:

1. **React with thumbs up/down** on each review comment:
   ```bash
   # 👍 for real bugs, valid improvements, and valid-but-deferred items
   gh api repos/{owner}/{repo}/pulls/comments/{comment_id}/reactions -f content="+1"

   # 👎 for false positives and style/opinion-only comments
   gh api repos/{owner}/{repo}/pulls/comments/{comment_id}/reactions -f content="-1"
   ```

   For PR-level (issue) comments, use the issues endpoint:
   ```bash
   gh api repos/{owner}/{repo}/issues/comments/{comment_id}/reactions -f content="+1"
   ```

2. **Resolve conversation** for comments that were fixed or confirmed as false positives:
   ```bash
   gh api graphql -f query='mutation {
     resolveReviewThread(input: {threadId: "<node_id>"}) {
       thread { isResolved }
     }
   }'
   ```

   - Fixed bugs → resolve
   - False positives → resolve
   - Already fixed → resolve
   - Valid but deferred → do NOT resolve (open as reminder)
   - Questions / discussion → do NOT resolve (needs human reply)

### Phase 5: Report intermediate state

After processing, print a summary table:

```
## PR Comment Triage

| # | File | Comment | Verdict | Reaction | Resolved | Action |
|---|------|---------|---------|----------|----------|--------|
| 1 | handlers/auth.go:106 | Device lockout | Bug — fixed | 👍 | ✅ | Evict oldest session |
| 2 | handlers/sub.go:136 | No HMAC check | Valid but deferred | 👍 | — | Needs YooKassa first |
| 3 | utils/parse.go:42 | Nil pointer | False positive | 👎 | ✅ | Validated on line 38 |
```

### Phase 6: Decide — bugs remaining or ready to ship?

**"Ready to ship"** = no comments classified as Bug or Valid-improvement on this run, AND no `Valid but deferred` threads still open from earlier rounds.

- **Bugs were found and fixed this run** → ask the user for approval to commit. After OK:
  1. Commit the fixes with a clear message referencing the issue ID and a short summary of what was fixed.
  2. Push to the PR branch.
  3. **Re-trigger codex review** by posting a top-level PR comment with the literal body `@codex review`:
     ```bash
     gh pr comment {number} --body "@codex review"
     ```
     This kicks off a fresh review pass on the new commit. Without it, codex won't know we pushed fixes and the PR will sit silent.

     ⚠️ **Trigger must start with `@codex`, not `/codex`.** A leading `/` on Git Bash (MSYS / mintty on Windows) triggers path conversion — `/codex review` gets posted as the literal string `C:/Program Files/Git/codex review`, which codex ignores and pollutes the PR with garbage. Always verify by reading the comment back:
     ```bash
     gh api repos/{owner}/{repo}/issues/comments/{id} --jq '.body'
     ```
  4. Then stop — wait for the next review pass. Do NOT proceed to docs/merge.

- **No bugs, but `Valid but deferred` threads still open** → stop and report. The PR is not ready; user has to handle the deferred items first.

- **No bugs, no open deferred threads** → continue to Phase 7. The PR is ready to ship.

> Even if Phase 2 found nothing to fix at all (e.g. the reviewer only asked questions, or had only false positives), if the PR has open Bug/Valid threads from earlier rounds, **stop here** — those need to be resolved first.
> If nothing changed (no fixes, no push), do NOT post `@codex review` — there's nothing new for codex to review and a no-op trigger is just noise.

### Phase 7: Docs & version sync (ready-to-ship path)

Goal: make sure README, ROADMAP and version reflect the changes shipping in this PR. Then merge.

1. **Identify the change** — what does this PR ship?
   ```bash
   gh pr view {number} --json title,body,files --jq '{title, body, files: [.files[].path]}'
   git log --oneline origin/{baseRefName}..HEAD
   git diff --stat origin/{baseRefName}..HEAD
   ```

2. **Find candidate doc files** — look for any of these in the repo root and any obvious docs dirs:
   - `README.md`, `README.*.md` (e.g. README.ru.md)
   - `docs/ROADMAP.md`, `ROADMAP.md`
   - `CHANGELOG.md`
   - Any other `*.md` near the changed files that documents the feature being modified

3. **Check whether each candidate already mentions the change.** Grep for the issue ID (e.g. `NER-46`), the user-visible feature name, or the version number. If a doc *does* mention it — leave it alone.

4. **Write entries for docs that don't mention it:**

   - **README** — only update if the PR materially changes the **Key Features** table, the **Project Structure**, **Build** instructions, the **Backend** section, or the **Version** line. Bug-fix-only PRs usually don't need a README update — skip unless the user-visible behavior actually changed.
   - **ROADMAP** — almost always append an entry for a meaningful round. Use the project's existing roadmap voice and format. ROADMAP entries are committed straight to the base branch in its own repo — they do NOT need their own PR.
   - **CHANGELOG** — if present, add an entry following whatever format is already there.

4.5. **Android "What's-new" release notes (nimbus-android repo only).** The in-app "What's new" modal is **hardcoded in the APK** — there is no changelog endpoint — so every round that ships a **user-visible** change MUST add a release entry, and it must follow the project rules exactly. Read the full memory `workflow_android_whats_new.md` before writing, and verify against it. Checklist:

   - **Two places, in sync:**
     1. `app/src/main/java/com/nwl/pow/ui/whatsnew/WhatsNewContent.kt` — add a new `WhatsNewRelease(versionCode = <N>, …)` at the **top** of `WhatsNewReleases` (newest-first; render is top-down). `versionCode` = the new round number.
     2. `app/src/main/res/values/strings.xml` **and** `values-ru/strings.xml` — add the matching `whats_new_v<N>_label` / `_<slug>_title` / `_<slug>_body` strings in **both** locales.
   - **Verify the entry already present** (e.g. when the round's main commit added it): grep `whats_new_v<N>` in both string files and check `WhatsNewContent.kt` has the `versionCode = <N>` block. If present, still **audit its copy against the rules below** — a malformed entry is worse than none.
   - **Copy rules (this is where it usually goes wrong — check every string):**
     - ❌ **No em/en dashes (`—`, `–`) anywhere.** This is the #1 AI-text marker the user flags. Replace with a regular hyphen `-`, parentheses `()`, a period, or reword. Grep both edited strings for `—`/`–` before committing.
     - **`values-ru` must contain NO English words** (brand `Nimbus` is the only exception): `relay` → «промежуточный/запасной сервер», `bypass` → «обход», etc. `values/` (EN) must contain no Russian.
     - **One short sentence per `body`**, friendly/human tone ("explain to a friend"), not a PR blurb, no jargon unless the bullet is literally about that tech. 3-5 bullets max per round.
   - **Never edit or renumber an existing release entry** — `versionCode` is the `WhatsNewPrefs.lastSeen` key; changing old entries silently rewrites history for users who already saw them.
   - **Skip the entry entirely for a no-user-visible-change round** (pure backend/refactor/crash-fix the user never saw) — do not invent a bullet.

5. **Version check:**

   - Locate the version-of-record file. Common spots: `app/build.gradle.kts` (`versionCode` + `versionName`), `package.json`, `Cargo.toml`, `pyproject.toml`, `version.go`.
   - Compare current version with what should ship. The project's convention is usually documented in CLAUDE.md / repo memory; respect it (e.g. for nimbus-android the scheme is `0.<phase>.<round>-beta`, `versionCode = round number`).
   - If unbumped AND this PR ships a user-visible change → bump it.
   - If already bumped or no version bump needed → leave it.

6. **Commit doc + version changes:**

   - **Code-repo files** (README, version, CHANGELOG that live next to the code): commit to the **PR's branch** with a clear message, then push.
     ```bash
     git add <files>
     git commit -m "Update README and bump version for <feature> ({issue-id})"
     git push
     ```
   - **ROADMAP that lives in a different repo** (e.g. nimbus-android's ROADMAP lives in `docs/ROADMAP.md` of the Go backend repo): commit + push **directly to the base branch** of that repo. **No PR for ROADMAP.**
     ```bash
     # In the ROADMAP repo, on the base branch:
     git pull --ff-only
     git add docs/ROADMAP.md
     git commit -m "docs(roadmap): <round summary> ({issue-id})"
     git push
     ```
   - **ROADMAP that lives in the same repo as the code**: include it in the same branch commit as README/version. Do NOT cut a separate PR.

7. **Re-verify build** after any code-bearing changes (version files often are):
   ```bash
   go build ./...                # Go repo
   ./gradlew compileDebugKotlin  # Android repo
   ```

### Phase 8: Ask for merge approval

Per memory rule (`workflow_linear_backlog.md` / `feedback_verify_before_commit.md` etc): **never merge without explicit user OK.**

Present a short summary:

```
## Ready to ship

- ✅ No bug comments remaining
- ✅ README updated (or: not applicable)
- ✅ ROADMAP entry committed to <base-branch> in <repo>
- ✅ Version bumped to <X.Y.Z> (or: no bump needed)
- ✅ Build green

Approve merge of PR #{number}?
```

If user says no — stop and wait. Do NOT auto-merge.

### Phase 9: Merge + delete branch (only after explicit approval)

```bash
# Merge with squash to keep the base branch history clean; check repo convention.
# Use --delete-branch so the head branch is removed on merge.
gh pr merge {number} --merge --delete-branch
# (Substitute --squash / --rebase if that's the repo convention.)
```

If the merge fails (conflicts, required check failing) — stop, report what blocked, do not retry blindly.

After merge, confirm:
```bash
gh pr view {number} --json state,mergedAt,headRefName
git fetch --prune origin
```

The head branch should be deleted (`--delete-branch`). If the local checkout is still on that branch, switch back to the base branch:
```bash
git checkout <baseRefName>
git pull --ff-only
```

### Phase 10: Final report

```
## Done

- PR #{number} merged to {baseRefName} ({mergedAt})
- Branch {headRefName} deleted (local + remote)
- ROADMAP entry pushed to <roadmap-repo>:<base-branch>
- Version: <X.Y.Z>
```

## Important / safety

- **Never fix style-only comments or subjective suggestions.**
- **Never merge without explicit user approval** — per project memory rules.
- **Never push directly to the base branch** for code/version/README. Only ROADMAP-in-other-repo is an exception (Phase 7.6).
- **Never force-push** the PR branch unless the user explicitly asks for it.
- **Never use `--no-verify`** to skip hooks.
- **Never use `git rebase -i`** — interactive editor blocks the harness.
- If a comment is about missing functionality (e.g. "add HMAC verification") that requires significant external integration work, classify it as "valid but deferred" — it counts as an open thread and blocks merge until the user decides.
- If multiple comments describe the same underlying issue, fix it once and mark the others as duplicates.
- If you accidentally land on a state where the branch is already merged or already deleted, stop and re-detect state with `gh pr view` — never re-run the merge command blindly.
