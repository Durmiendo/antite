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

## Contributor agreement

By sending a pull request, a patch or any other contribution to the official Antite repository
(https://github.com/Durmiendo/antite), you agree to the following terms for that contribution. If you do not agree,
please do not send the contribution.

"Maintainer" means the owner of the official Antite repository (GitHub user Durmiendo) and anyone the Maintainer
transfers the project to. "Contribution" means any code, text, image or other material you submit to the official
repository, including later changes to it.

1. **Assignment.** To the extent the law that applies to you allows it, you assign to the Maintainer all copyright and
   other rights in your Contribution, worldwide and for the whole term of those rights. After it is accepted, the
   Contribution belongs to the project, not to you.

2. **License where assignment is not possible.** Where the law does not allow such an assignment, or as far as it is
   not effective, you grant the Maintainer a perpetual, worldwide, irrevocable, royalty-free license to use, copy,
   modify, publish, distribute, sublicense and relicense your Contribution in any form and under any license, including
   licenses other than the one the project uses today, without asking you again or paying you.

3. **Patents.** You grant the Maintainer and everyone who receives Antite a perpetual, worldwide, irrevocable,
   royalty-free patent license for any patent claims you can license that your Contribution, alone or combined with
   Antite, would infringe.

4. **Moral rights.** To the extent the law allows it, you waive, or agree not to assert, moral rights in your
   Contribution, such as the right to object to changes to it. Credit for your work stays in the history of the
   repository.

5. **Your right to contribute.** You confirm that the Contribution is your own original work, or that you have the
   right to submit it under these terms (for example, your employer agreed, if it may own work you create). If any
   part of it comes from someone else, you say so in the pull request and name its source and license.

6. **No obligation.** The Maintainer does not have to accept, use or keep any Contribution.

7. **No warranty.** You give the Contribution as it is, without warranties of any kind, except the confirmation in
   point 5.

The project keeps being released under the [Mozilla Public License 2.0](LICENSE). These terms only decide who holds
the rights in what you contribute, so that the project can stay under one owner and change its license if it ever
needs to.
