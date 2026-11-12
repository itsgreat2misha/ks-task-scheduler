# Task Scheduler for Kiosk Satellite

Run commands you choose on boot, when Kiosk Satellite starts, or on a
schedule — every so often, daily, or weekly — through root (`su`) or
Shizuku.

Use boot and start tasks for anything that needs re-applying after a
reboot: a setting a device drops on reboot, launching an app or
service, or granting a permission. Use scheduled tasks for periodic
housekeeping: restarting a flaky service, running a health check, or
writing a heartbeat file overnight.

## Installation

> **Before you install:** this plugin runs commands you enter as
> root or through Shizuku, with Kiosk Satellite's privileges — read
> [Security](#security) first. Tasks also need one of the backends
> from [Requirements](#requirements): a rooted device with a silent
> `su` grant for Kiosk Satellite, or Shizuku installed, running and
> granted to Kiosk Satellite.

Use the button below to open this plugin in your Kiosk Satellite,
where you review it and choose **Trust and install**:

[![Install in Kiosk Satellite](https://kiosksatellite.com/img/badges/plugin-add.svg)](https://kiosksatellite.com/redirect/plugin/?repository=itsgreat2misha/ks-task-scheduler)

Then enable **Task Scheduler** from its entry row, open its settings
and add tasks.

### Manual

1. Open **Settings > Plugin Manager** in Kiosk Satellite and turn on
   **Enable Plugins**.
2. Choose **Add plugin**, paste
   `https://github.com/itsgreat2misha/ks-task-scheduler` and choose
   **Preview**.
3. Review the plugin and choose **Trust and install**.
4. Enable **Task Scheduler** from its entry row, then open its
   settings and add tasks.

Task Scheduler is listed in Community Plugins — you can
also install it by searching **Browse plugins** in Plugin
Manager.

## Uninstalling

Uninstalling the plugin deletes its settings, including all saved
tasks, so copy anything you want to keep before you uninstall.
Uninstalling does not undo anything tasks have already done: it
does not revert settings they changed or stop services they
started.

## Settings

- **Tasks** — a list of up to 32 tasks. Each task has a label, an
  executable (an absolute path, for example `/system/bin/settings`),
  its arguments (one string, split quote-aware, for example
  `put system user_rotation 1`), a schedule and the fields for that
  schedule (below), a backend, a timeout in seconds (1–30), and an
  enabled toggle. A disabled task never runs.

A new task starts with the **On boot** schedule, the **Auto**
backend, an interval of 60 minutes and a timeout of 10 seconds.
The task editor shows only the fields that apply to the schedule
you picked: an **On boot** task, for example, has no interval,
time of day or weekday fields, and a **Manual** task shows none of
the schedule fields at all. In the task list, each task shows its
schedule type under its name (for example `Weekly` or `On boot`).

Each task's **backend** is one of:

- **Auto** (the default) — root when root is available on this
  kiosk, otherwise Shizuku.
- **Root** — root only.
- **Shizuku** — Shizuku only.

If the backend a task uses is unavailable, or the run through it
fails, the task has failed: it is not retried through the other
backend, and a command never runs twice.

Each task's **schedule** is one of:

- **On boot** — run once per boot; a Kiosk restart in the same boot
  does not repeat it.
- **On Kiosk start** — run at every Kiosk app start.
- **Interval** — run every **interval** minutes after the previous
  run (5 minutes to one week). A new interval task runs soon after
  it is added, then counts from that run.
- **Daily** — run at the **time of day** (`HH:MM`, local time).
- **Weekly** — run on the chosen **weekday** at the **time of day**.
- **Manual** — never runs on its own. Run it with **Run tasks
  now**, the **Run all enabled tasks now** action, or its per-row
  **Run now** toggle.

Scheduled (Interval/Daily/Weekly) tasks also have:

- **If a scheduled run is missed** — `Catch up once` (the default)
  runs the task once at the next opportunity if its time passed
  while it could not run — Kiosk was not running, the device was
  asleep, or the task was disabled; it never replays more than one
  missed run. `Skip` abandons a run that is well past its time
  (more than 15 minutes for daily/weekly, a full interval for
  interval tasks); a run that is late but still inside that window
  runs late.
- **Jitter** — optionally delay each scheduled run by a
  per-occurrence amount between 0 and this many minutes (0–120),
  so a fleet of kiosks does not all run the same task at the same
  moment. The amount is fixed for a given occurrence, and the
  next-run time the plugin reports already includes it.

Every task except a **Manual** one also has a **Delay**: an
automatic run waits this many seconds (0–300) immediately before
running the task, so tasks that fire at the same moment can be
staggered. Manual runs ignore the delay.

To run a single task on its own, open its row, turn **Run now** on
and save: the task runs once, ignoring its schedule, and the
toggle switches itself back off. A disabled task does not run.

The **Advanced** section holds:

- **Logging level** — `Informational` (the default) keeps the
  plugin log to run results. `Verbose` adds why each task ran,
  waited or was skipped, for troubleshooting. It changes only the
  log, never what runs.
- **Run tasks now** (button) — runs all enabled tasks now,
  whatever their schedule. A manual run does not mark a boot task
  as run for this boot and does not move a scheduled task's next
  run, so testing a task does not disturb its schedule.
- **Clear scheduler state** (button) — clears Once per boot
  tracking and the record of scheduled tasks' past runs, so boot
  tasks run again on the next Kiosk start and scheduled tasks
  start their schedules fresh.

**Run all enabled tasks now** is also available as an action you
can assign to a gesture, the kiosk drawer or Home Assistant. It
does the same as the **Run tasks now** button.
## How it works

Tasks run one after another, in the order they appear in the
task list. A task that hangs, crashes or
fails cannot stall the scheduler or crash the plugin, and a
failed task does not stop later tasks — but that isolation
covers the task process, not its effects: a command running
with root or Shizuku privileges can itself stop apps or Kiosk
Satellite, or reboot the device, exactly as the trust model in
[Security](#security) describes. Every result is logged, summarised in
the plugin status and published as the `Task Scheduler status`,
`Task Scheduler last run` and `Task Scheduler next run` text
sensors.

A result is one line per task: `ok`, or `failed` with the reason —
the exit code (for example `exit 1`), `timed out`, or why no
backend was available. On a failure, the log line also carries the
first part of the task's output (up to 200 characters); a
successful task's output is not recorded, so if you need a task's
output, have the task write it to a file itself.

Start and boot tasks run when the Kiosk app starts. Enabling the
plugin alone does not run them; they run on the next Kiosk app
start. Only successful start/boot runs are marked, so a failed boot
task retries on the next Kiosk start.

It remembers each
scheduled task's last accounted run — when it last ran
successfully, or when a `Skip` resync moved it past a missed run —
so due times survive Kiosk and plugin restarts. An interval
task's next run is due one interval after its previous run
finished; a failed scheduled run does not move its next run,
and the task waits for its next scheduled occurrence instead of
retrying straight away. Editing
a task's schedule (type, interval, time or weekday) **or its
executable or arguments** starts that task's schedule fresh;
other edits (label, backend, timeout, delay, jitter, missed-run
policy, the enabled toggle) keep its place.

A scheduled task that has never run is due at its next
occurrence. For a daily or weekly task that means its most
recent scheduled time — if that time has already passed, the
missed-run policy decides, exactly as for any other missed
run. The same applies when a task is
re-enabled: its place was kept while it was disabled, so any runs
it missed are handled by the same policy.

Task Scheduler does not start Shizuku, and it does not substitute
`su` when a task is set to Shizuku only. Shizuku must already be
running when a Shizuku task runs — after a reboot, something else
has to start Shizuku first. An automatic Shizuku task that runs
before Shizuku is up fails for that pass, and the plugin retries
it when Kiosk reports Shizuku ready.

Check a task with **Run tasks now** before relying on it, and
prefer idempotent tasks: settings, grants and launches are; toggles
are not, because they would flip again on every run.

## Examples

On boot / on Kiosk start:

| What | Schedule | Executable | Arguments |
| --- | --- | --- | --- |
| Re-apply rotation | On boot | `/system/bin/settings` | `put system user_rotation 1` |
| Launch an app | On Kiosk start | `/system/bin/am` | `start -n com.example/.MainActivity` |
| Grant a permission | On boot | `/system/bin/pm` | `grant com.example android.permission.POST_NOTIFICATIONS` |

Scheduled:

| What | Schedule | Executable | Arguments |
| --- | --- | --- | --- |
| Restart a service every 6 hours | Interval, 360 min | `/system/bin/am` | `startservice -n com.example/.MyService` |
| Write a nightly heartbeat file | Daily, 03:15 | `/system/bin/sh` | `-c "date > /data/local/tmp/heartbeat"` |
| Weekly health check | Weekly, Monday 08:00 | `/system/bin/sh` | `-c "echo ok > /data/local/tmp/health"` |

Replace the package and class names with the app you actually mean.

## Security

This plugin runs inside Kiosk Satellite with its privileges. A task
set to Root runs as root, and a task set to Shizuku runs as
Shizuku's shell or root user. Only install and enable it if you trust
the tasks you enter and anyone else who can edit this plugin's
settings. The plugin itself sends no data anywhere: it has no
network code and no telemetry; it runs local tasks and reports
only to Kiosk Satellite's log, status and text sensors (which
Kiosk can surface in Home Assistant). A task you enter can of
course send data itself — that is under your control, not the
plugin's.

Do not put secrets in tasks. Task executables and arguments
are stored as plain plugin settings, not as secrets, so a token or
password written into an argument would sit there in plain text
and show up in the settings UI and the log. If a
task needs a credential, keep it out of the task itself:
put it in a script file on the device with tight permissions and
have the task call that script instead.

## Requirements

Kiosk Satellite 2026.10.40 or newer, Android 7.0 (API 24) or newer.
Tasks need either root or Shizuku: root tasks need a rooted
device with a silent `su` grant for Kiosk Satellite, and Shizuku
tasks need Shizuku installed, running, and granted to Kiosk
Satellite (**Settings > Device > Shizuku**).

## Troubleshooting

- **A task did not run at startup.** Start/boot tasks run on
  the next Kiosk app start after the plugin is enabled (see
  How it works). Test the task first with **Run tasks now**.
- **A scheduled task did not run at its time.** See the
  best-effort timing limitation below. Check the
  `Task Scheduler next run` sensor for when the plugin thinks the
  next run is, and check that the task's time of day is in
  24-hour `HH:MM` form — a daily or weekly task with an invalid
  time never runs.
- **A backend is unavailable.** The plugin status at the top of
  the settings page reports whether root (`su`) and Shizuku are
  available on this kiosk. A backend that starts after Kiosk
  Satellite, or comes back after a crash, is picked up
  automatically — you do not need to restart Kiosk Satellite.
  For Shizuku tasks, make sure
  Shizuku is installed, running, and granted to Kiosk Satellite
  (**Settings > Device > Shizuku**) — see Limitations below.
- **An `On boot` task was skipped.** Only successful
  automatic runs are marked as run for this boot, so a failed
  task retries on the next Kiosk start. To run a skipped task
  again in the same boot, use **Clear scheduler state**, or run it
  manually — manual runs ignore Once per boot tracking.
- **Shell syntax (pipes, redirection, `&&`) did not work.**
  Tasks run directly, without a shell. Use `/system/bin/sh`
  with `-c` as the executable when shell syntax is genuinely
  needed.
- **A task failed but later tasks still ran.** That is
  expected: a failed task does not stop later tasks. Every
  result is logged and summarised in the plugin status.
- **You need more detail about why a task did or did not run.**
  Set **Logging level** to **Verbose** in the **Advanced**
  section and reproduce the run: the plugin's entries in Kiosk
  Satellite's log then show the decision behind each task in
  each pass. Set it back to **Informational** when you are done.

## Limitations

- Tasks need either root or Shizuku (see Requirements); a
  task set to `Root` or `Shizuku` only uses that backend.
- The plugin does not start Shizuku itself (see How it works).
- **Scheduled runs are best-effort, not exact-time alarms.** The
  scheduler runs inside Kiosk Satellite's process, and plugins
  cannot use Android's alarm or job-scheduling APIs or hold the
  device awake. If the device is asleep or Kiosk Satellite is not
  running at a task's due time, the run happens late — when Kiosk
  next runs and notices — or, with the `Skip` missed-run policy,
  not at all. Do not use scheduled tasks for anything where
  running late (or, with `Skip`, not running) would be a problem,
  such as alarms or time-critical actions.
- On the day daylight saving time ends, an ambiguous daily or
  weekly time (one that occurs twice) runs at its second,
  standard-time occurrence; a time inside the spring-forward gap
  runs shifted forward by the gap.
- Tasks run directly, without a shell: each task is a single
  executable (absolute path) plus one arguments string. Use
  `/system/bin/sh` with `-c` for shell syntax.
- At most 32 tasks, each with a timeout of 1–30 seconds. This is
  a scheduler for short commands, not for long-running jobs.
- Shizuku tasks are also limited by the Shizuku interface the
  plugin calls: at most 32 arguments, 4096 characters per
  argument and 16384 characters in total. A task whose arguments
  exceed those limits fails.
- Task executables and arguments are stored as plain plugin
  settings, not as secrets — do not put secrets in tasks (see
  Security above).

## Building

```sh
python3 tools/test.py
python3 tools/build.py --android-platform 35
```

The ZIP appears in `dist/`. To release: update the CHANGELOG
and push a `v*` tag (for example `v1.0.1`); the Release
workflow tests and builds the plugin and creates the GitHub
release for the tag.

For local testing only, **Plugin Manager > Developer Tools > Install
from ZIP** accepts the ZIP built by `python3 tools/build.py`.

## License

Apache-2.0.
