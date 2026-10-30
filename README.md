# Buddy

Buddy is a proposed Android AI assistant that operates apps on a background virtual display while the user continues using the phone. It uses a built-in local wireless ADB connection to start its own privileged helper, with no external privilege-manager dependency.

The complete product requirements, Android architecture and API research, modern UI/UX design, security model, acceptance criteria, and phased implementation plan are in **[spec.md](spec.md)**.

This repository currently contains planning documents. It does not yet contain an Android implementation or an installable APK.

The initial design targets Android 13+, recommends Android 14+, and plans signed direct-APK distribution for the full agent. Platform compatibility and release requirements are defined in the specification.
