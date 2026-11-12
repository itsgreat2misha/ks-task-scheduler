#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Compile the Task Scheduler plugin against the SDK and run its unit tests."""
import os
import subprocess
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from android_sdk import android_platform

root = Path(__file__).resolve().parents[1]
java_home = os.environ.get("JAVA_HOME")


def tool(name):
    return str(Path(java_home) / "bin" / name) if java_home else name


sdk_root = Path(os.environ.get("ANDROID_HOME", os.environ.get("ANDROID_SDK_ROOT", str(Path.home() / "android-sdk"))))
try:
    platform = str(android_platform(sdk_root))
except ValueError as error:
    raise SystemExit(str(error)) from error

sources = [
    *sorted((root / "sdk/src").rglob("*.java")),
    *sorted((root / "src").rglob("*.java")),
    *sorted((root / "tests").rglob("*.java")),
]
with tempfile.TemporaryDirectory(prefix="ks-task-scheduler-test-") as directory:
    subprocess.run(
        [tool("javac"), "--release", "8", "-cp", platform, "-d", directory, *map(str, sources)],
        check=True,
    )
    subprocess.run(
        [tool("java"), "-ea", "-cp", os.pathsep.join([directory, platform]), "taskscheduler.TaskSchedulerTest"],
        check=True,
    )

subprocess.run([sys.executable, str(root / "tools/test_plugin_manifest.py")], check=True)
subprocess.run([sys.executable, str(root / "tools/test_plugin_assets.py")], check=True)
subprocess.run([sys.executable, str(root / "tools/test_android_sdk.py")], check=True)
