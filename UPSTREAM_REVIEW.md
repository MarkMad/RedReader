# Upstream integration

Reviewed QuantumBadger/RedReader through
`6a220e02697b9865c4ff5a946437f4067cf1a690` on 2026-09-15.
Fork base: `cf7a96a3`.

Selected code and tests for local integration:

- `72a7e929`: account database contention and token value equality.
- `d27f1e32`: duplicate notification permission prompts after login.
- `00474b37`: accessibility actions disappearing after view rebinding.
- `5b074030`: asynchronous cookie clearing and login popup session preservation.
- `d2c00d31`: dialog recreation crashes and bounded error state serialization.
- `47c8b8fb`: replying before the parent post has downloaded.

Upstream release changelogs are excluded because their version identifiers overlap
with this fork's releases. Application ID, version, updater, TTS and earcons are
preserved.

Deferred: parent-comment focus changes (require adapting the fork's TTS caller),
accessibility announcements (need playback interaction checks), image preview
refactoring, scroll-to-mark-read and dependency updates (broader validation).
Safe Browsing opt-out is excluded because it changes browser protection behavior.
Unrelated ignore rules and upstream-only changelog entries are also excluded.

## 2026-09-26 review

Reviewed `upstream/master` through `f36a36b528fdab25fb9038c066875875d5fd6dbc`
(12 commits since the prior review). Integrated the two focused tablet fixes from
`7273dd6c` and `f36a36b5` into `MainActivity`: preserve the main menu when returning
from comments, and lay out panes before preference migrations can trigger a refresh.

Deferred `2d9613d0` (inbox read-state and layout overhaul) because it spans API,
data model, and UI behavior, including the locally edited OAuth login flow. Deferred
`7f8932f4` and `a8615e5e` (edge-to-edge and image viewer insets) as one coupled
layout change requiring device checks. Deferred `2e027a8f` (bezel toolbar under
gesture navigation) for a gesture navigation check, `d3959533` (popup theme) for
theme checks, and `e65e92c8`/`73dfbdf3` (locked-post tag and upgrade default) as
optional presentation changes. `9675cfae` removes the inline image question prompt;
its preference impact needs a separate UX review. Upstream changelog-only commits
`48280251` and `92791e5f` are omitted because fork release versions differ.

Validation: `assembleDebug pmd checkstyle lint test` passed; lint found no errors
or warnings, and `git diff --check` passed. Tablet update and back-navigation flows
still need device verification. No upstream changes were pushed or released.

Validation: `assembleDebug assembleRelease pmd checkstyle lint test` passed.
All 111 JVM tests passed; lint reported no errors or warnings. `git diff --check`
passed. Device-level login, dialog recreation and accessibility checks were not
run. Changes are local and have not been pushed or released.
