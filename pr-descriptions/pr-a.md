Version bumps only, in `linux.yml`, `windows.yml` and `windows-ci.yml` (15 lines changed, nothing else).
`build.yml`, `release.yml` and `gradle/actions` are not touched (gradle/actions v6 changed its licence, so v5 stays).

Why its own PR: these files are part of the release pipeline, so Louis wants to do a test release with them before the next real one.

How it was checked: the same bumps have been running green on my fork (Windows, Linux, windows-ci).

🤖 Generated with [Claude Code](https://claude.com/claude-code)
