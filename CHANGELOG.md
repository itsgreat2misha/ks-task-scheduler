# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.1] - 2026-11-12

### Fixed

- The README's Installation section now states that the
  plugin is listed in Community Plugins.

## [1.0.0] - 2026-11-11

### Added

- **Tasks**: commands you choose, each with its own executable,
  arguments, per-task timeout (1–30 s) and enabled toggle, run
  through a per-task backend — **Auto** (root when available,
  otherwise Shizuku), **Root** (`su`) or **Shizuku**.
- **Per-task schedules**: **On boot** (once per boot), **On Kiosk
  start** (every Kiosk Satellite start), **Interval**, **Daily**,
  **Weekly**, and **Manual** (never runs on its own — only when
  you run it). Scheduled timing is best-effort while Kiosk
  Satellite is running (see the README's Limitations).
- **Missed-run policy** for scheduled tasks: **Catch up once**
  (default) runs a missed task once when the plugin next can;
  **Skip** moves past missed runs. Optional per-task **jitter**
  (0–120 minutes) and per-task **Delay** (0–300 s, automatic
  runs only) stagger tasks that would otherwise fire together.
- **Run tasks now** button and the **Run all enabled tasks now**
  action run every enabled task on demand, whatever its schedule;
  each task row also has a one-shot **Run now** toggle. A manual
  run does not mark a boot task as run for this boot and does not
  move a scheduled task's next run. Disabled tasks never run.
- **Clear scheduler state** button: clears Once per boot tracking
  and the record of scheduled tasks' past runs, so boot tasks run
  again on the next Kiosk start and scheduled tasks start their
  schedules fresh.
- **Backends that start late are picked up automatically**: a
  root stack or Shizuku that starts after Kiosk Satellite, or
  comes back after a crash, is used without restarting Kiosk
  Satellite.
- **Logging level** setting (**Informational** / **Verbose**) in
  the **Advanced** section. **Verbose** adds the scheduling
  decisions behind each pass to the plugin log — why each task
  ran, waited or was skipped — for troubleshooting. It changes
  only the log, never what runs.
- Text sensors for plugin status, last run, and next scheduled
  run, published to Home Assistant.
- Clock icon. Original artwork created for this project,
  AI-assisted at the author's direction and approved by the
  author; not a third-party logo or copied icon.

[1.0.1]: https://github.com/itsgreat2misha/ks-task-scheduler/releases/tag/v1.0.1
[1.0.0]: https://github.com/itsgreat2misha/ks-task-scheduler/releases/tag/v1.0.0
