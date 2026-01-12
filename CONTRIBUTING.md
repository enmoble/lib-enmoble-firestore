# Contributing to lib-enmoble-firestore

Thank you for your interest in contributing to `lib-enmoble-firestore`! This document describes how to propose changes, set up the project locally, and submit PRs.

## Table of Contents

- [Code of Conduct](#code-of-conduct)
- [What this repo is](#what-this-repo-is)
- [Project structure](#project-structure)
- [Getting started](#getting-started)
- [Development setup](#development-setup)
- [Making changes](#making-changes)
- [Testing](#testing)
- [Documentation expectations](#documentation-expectations)
- [Code style](#code-style)
- [Submitting changes](#submitting-changes)
- [Reporting issues](#reporting-issues)
- [License](#license)

## Code of Conduct

Be respectful and constructive in all interactions. Assume good intent. Disagreements should stay technical and focused on improving the library.

## What this repo is

`lib-enmoble-firestore` is a Kotlin/Android library that provides reusable Firestore building blocks:

- Firestore initialization helpers (offline persistence + cache sizing)
- Generic CRUD helpers
- Batched write utilities (chunking/retries/optional write locking)
- A practical strategy for Firestore “non-existent ancestor documents” (explicit ancestor creation + tracked subcollections)
- Small reactive helpers around one-shot reads (Kotlin Flows)
- Generic utility helpers used in Firestore workflows (string/time conversions, content matching rules, small data structures)

The goal is to stay **schema-agnostic** (no app-specific Firestore paths/constants) and keep the public API stable.

## Project structure

This repo contains multiple Gradle modules:

- `:lib-enmoble-firestore` — the published Android library
- `:demo-app:app` — a demo Android app that runs real Firestore operations once Firebase config is added

## Getting started

1. Fork the repository on GitHub
2. Clone your fork locally:
   ```bash
   git clone https://github.com/YOUR-USERNAME/lib-enmoble-firestore.git
   cd lib-enmoble-firestore
   ```
3. Add the upstream repository as a remote:
   ```bash
   git remote add upstream https://github.com/ORIGINAL-OWNER/lib-enmoble-firestore.git
   ```

## Development setup

### Prerequisites

- Android Studio (latest stable)
- JDK 21+
- Kotlin (as configured by the repo)
- Gradle (via the included wrapper)

### Build the library and demo

From the repo root:

```bash
./gradlew :lib-enmoble-firestore:assembleDebug :demo-app:app:assembleDebug
```

### Firebase setup for the demo app (optional)

The demo app can run real write/read operations against your Firebase project.

1. Create a Firebase project
2. Add an Android app in Firebase Console
3. Download `google-services.json`
4. Place it at `demo-app/app/google-services.json`
5. Enable Firestore in Firebase Console

## Making changes

### Before you start

1. Check existing issues/PRs to avoid duplication
2. If the change is non-trivial, open an issue first describing:
   - what you want to change
   - why (use-case / bug / performance / API clarity)
   - proposed approach

### Development guidelines (library-first)

- Keep the library schema-agnostic (no app-specific document paths/collections)
- Prefer small, focused PRs
- Avoid breaking public API unless explicitly discussed/approved
- Add/adjust unit tests when behavior changes
- Update docs whenever user-visible behavior or capabilities change

## Testing

### Running unit tests

From repo root:

```bash
./gradlew :lib-enmoble-firestore:test
```

### Building the demo app

```bash
./gradlew :demo-app:app:assembleDebug
```

### Recommended “PR gate” command

```bash
./gradlew :lib-enmoble-firestore:test :demo-app:app:assembleDebug
```

## Documentation expectations

If you add or change any public API:

- Add/maintain KDoc for public classes/functions/constants
- Update `README.md` and/or the architecture document as appropriate so that together they cover the library’s capabilities
- Keep examples short, correct, and runnable (when possible)

## Code style

- Follow Kotlin coding conventions
- Prefer clear naming over cleverness
- Keep logging lightweight (especially inside Firestore listener callbacks)
- Use trailing commas in multi-line parameter lists where consistent with the existing codebase

### KDoc

All public APIs must have KDoc that explains:

- what it does
- key parameters and defaults
- behavior on errors
- any important caveats (threading, Firestore limits, quota implications)

## Submitting changes

### Branching

Create a feature branch from `main`:

```bash
git checkout -b feature/your-change
```

### Commit messages

Use a clear, consistent format:

```
type(scope): brief description

Longer explanation if needed.
Fixes #123
```

Common types: `feat`, `fix`, `docs`, `test`, `refactor`, `perf`, `chore`.

### Pull request checklist

Before submitting:

- [ ] Library builds successfully
- [ ] Unit tests pass
- [ ] Demo app assembles (if your change impacts it)
- [ ] Public APIs have KDoc (if applicable)
- [ ] README / architecture docs updated (if applicable)
- [ ] No unrelated formatting-only noise

## Reporting issues

### Bug reports should include

- clear description
- steps to reproduce
- expected vs actual behavior
- environment details (Android version/device if relevant, library version, Firestore SDK version)
- logs/stacktraces (sanitized)

### Feature requests should include

- use-case and motivation
- proposed API shape (if possible)
- alternatives considered
- any constraints (backwards compatibility, quota/perf expectations)

## License

By contributing, you agree that your contributions will be licensed under the repository’s LICENSE.