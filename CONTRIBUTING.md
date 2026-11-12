# Contributing

Thanks for helping. This project is a Kiosk Satellite plugin written
in Java for Android; the build is driven by Python scripts in
`tools/`, not Gradle (see the README's Building section).

## Before you start

- Run the tests with `python3 tools/test.py` and make sure the full
  build compiles: `python3 tools/build.py --android-platform 35`
- CI runs the tests and a build check (`Build and test`) on pushes
  to `main` and on pull requests; release builds are built by
  GitHub Actions from a version tag.

## Compatibility rules

These are load-bearing for existing users; changing them needs a
migration story in the PR:

- The plugin ID (`ks-task-scheduler`), the manifest file
  (`kiosk-satellite-plugin.json`), and the settings keys, command
  IDs, and sensor IDs declared in it.
- Command behaviour: the backend options (`Auto`, `Root`,
  `Shizuku`), the schedules (`On boot`, `On Kiosk start`, `Interval`, `Daily`, `Weekly`, `Manual`), and
  the per-task timeout range (1–30 seconds).
- The plugin `version` in `kiosk-satellite-plugin.json` = git tag =
  release name; it only ever moves forward.

## Contributing a fix or feature

1. Open an issue first for anything larger than a small fix, so the
   approach can be agreed before code is written.
2. Keep the change scoped: plugin code lives in `src/taskscheduler/`,
   the bundled Kiosk Satellite SDK lives in `sdk/`, and build/test
   tooling lives in `tools/`. Do not edit the SDK copy to work
   around a plugin bug.
3. Add or update tests in `tests/` (run via `tools/test.py`) for
   behaviour changes, and note how you tested on a real kiosk if
   the change depends on root (`su`) or Shizuku behaviour that the
   JVM tests cannot cover.
4. Update the README if behaviour, settings, or setup changed, and
   add a `CHANGELOG.md` entry under `Unreleased` (or the release
   being prepared).

## Style

- No new dependencies without discussion: the plugin is
  dependency-free Java on purpose, built against the bundled SDK
  and the Android platform only.
- Comments should explain the current design timelessly; incident
  history belongs in the changelog / git log, not in dated comment
  diaries.

## Pull requests

- Branch, open a PR, wait for CI (`Build and test`) to go green,
  then squash-merge. Direct pushes to `main` are blocked by the
  repository rulesets.
- PR titles use Conventional Commits, since the squash-merge title
  becomes the commit and the changelog is distilled from history.

## AI involvement

This project's development uses AI-agent assistance. Every change is reviewed by the repository owner, who remains the responsible human for the project.
