# Security Policy

## Supported versions

Only the latest release is supported. Fixes ship in a new release;
there are no backports to older versions.

## Reporting a vulnerability

Please do not open a public issue for a security problem. Use
GitHub's private vulnerability reporting for this repository
(**Security > Report a vulnerability**) so the details stay private
until a fix is available.

Include what you can: the plugin version, your Kiosk Satellite
version, the backend involved (root or Shizuku), and steps to
reproduce. Remove any secrets from logs or screenshots before
sending them.

## Scope notes

This plugin runs user-entered commands as root or as Shizuku's user
by design — that is its function, and the README's Security section
describes the trust model. Reports that a command you entered ran
with the backend you chose are not vulnerabilities. Problems in
Kiosk Satellite itself should be reported to the Kiosk Satellite
project instead.
