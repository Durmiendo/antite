# Contributing to Antite

Thank you for wanting to help. Bug reports, ideas and pull requests are welcome.

## Before you start

- For anything larger than a small fix, open an issue first and describe what you want to change, so the work does not
  clash with plans already made.
- Change only what the pull request is about. Do not reformat or refactor code around it.
- Code, identifiers, comments and commit messages are in English. Commit messages are plain ASCII.
- New interface strings go to both `l10n/ru` and `l10n/en`; `FluentTest` checks that the two sets match.
- Run the JVM tests (`./gradlew :app:testDebugUnitTest`) before sending a pull request. If the change touches
  something a test cannot reach, say in the pull request how you checked it.
- Examples and test pages use `example.com` or other reserved names, never real third-party sites.

## Work you do not own

If a pull request contains anything you did not write yourself (code, text, images, data), submit it separately from
your own work, name its source and its license in the pull request, and mark it "Submitted on behalf of a third-party:
[name]". It is then not covered by the assignment, and it is accepted only if its license fits the project.

## Contributor agreement

Contributions to the official repository are accepted only under the [contributor agreement](CLA.md), the standard
Harmony Individual Contributor Assignment Agreement with the blanks filled in. In short: you assign the copyright in your
contribution to the project owner (or, where the law does not allow that, give them an exclusive license), they may
release it under any license but always also under the project's license at that time, and you keep a license to use
your own contribution as you like. The full text in [CLA.md](CLA.md) is what applies.

You agree to it by checking the box in the pull request description.
