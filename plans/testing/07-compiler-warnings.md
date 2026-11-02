# Plan 07: the compiler warns, and a warning fails the build

> Status: **not started** · from the 2026-11-01 test survey, category 9 of 9
> (part 1; part 2 is [19](19-mutation-testing.md)) · Effort: 2-3 days ·
> Risk: low (no behaviour changes but the defects the warnings find) ·
> Depends on: none; land it early, so the plans after it are held to it

## Why

No target sets a compiler option. There is no `scalacopts` in any BUILD file,
in MODULE.bazel or in `.bazelrc`, and the Scala toolchain is the default one
rules_scala 7.0.0 generates
(`@rules_scala_toolchains//scala:BUILD`: `setup_scala_toolchain(...,
use_argument_file_in_runner = True)`, no options). Today a build reports only
what scalac reports by default: deprecations, as a summary line, and a match on
a sealed type that misses a case. Bazel also prints a warning only when the
target compiles. A cached target prints nothing, so a warning that does not
fail the build is seen once, by whoever changed that file, if they look.

Measured 2026-11-01 at HEAD `f309205`, outside Bazel. scalac 2.13.16 was taken
from the output base and run on a `git archive` of `src`, `examples`,
`benchmarks` and `tools` in the scratchpad. The classpath held 167 jars: the
maven repository's and Scala's own. Main code compiled in 27 s and the tests in
two groups, because `SortExecTest` and `ExchangeExecTest` each define a private
top-level `InMemoryPartitionedPlan` in one package, which Bazel compiles as
separate targets. Every warning flag was on, with `-Wconf:any:wv` to print each
warning's category. With today's flags the main code gives four summary lines
for 6 deprecations and nothing else. With every flag on there are 2,217
warnings:

| category (flag) | main | test | examples, benchmarks, tools |
|---|---|---|---|
| `w-flag-tostring-interpolated` (`-Wtostring-interpolated`) | 140 | 578 | 25 |
| `other-pure-statement` (`-Wnonunit-statement`) | 61 | 405 | 6 |
| `w-flag-numeric-widen` (`-Wnumeric-widen`) | 19 | 421 | 0 |
| `lint-performance` (`-Wperformance`, captured vars) | 56 | 85 | 3 |
| `lint-infer-any` | 0 | 117 | 0 |
| `w-flag-value-discard` (`-Wvalue-discard`) | 39 | 62 | 0 |
| `deprecation` | 2 | 46 | 4 |
| `lint-nonlocal-return` | 46 | 2 | 0 |
| `lint-universal-methods` | 0 | 43 | 0 |
| `unused-imports`, `-privates`, `-params`, `-locals`, `-pat-vars` | 14 | 31 | 2 |
| `other-match-analysis` (`-Xlint:strict-unsealed-patmat`) | 6 | 2 | 0 |
| `other-shadowing` (`-Xlint:pattern-shadow`) | 1 | 0 | 0 |
| `lint-missing-interpolator` | 0 | 1 | 0 |

Of the 384 in main code, 279 are in the engine and job modules and 105 in the
desktop ones (`gui`, `viewer`). Reading the high-signal ones one by one found:

- **A defect, through `-Wnonunit-statement`** ("unused value of type
  Boolean"). `ResultPersister.persist` (gui/ResultPersister.scala:39) ignores
  `TaskRunRecord.clearIfMarked` returning false. It then writes its parts into
  a directory that holds part files no record names, and records every
  `part-*` file in the directory as its output (:80). Plan 29's refusal of
  stray part files lives in `DataJob.writeOutput` alone. Reproduced against
  HEAD's classes: in a directory holding four unrecorded one-row
  `part-0000N.csv` files, persist a one-row result as CSV. `part-00000.csv` is
  overwritten, the other three stay, and `_run.json` lists all four with
  `rowsProduced` 1. A reader of the directory reads four rows.
- **A message that prints its own template, through
  `-Xlint:missing-interpolator`.** FrontendFuzzTest.scala:292 builds a
  coverage-guard problem from a plain string holding `${entry.example.get}`.
  That failure shows the placeholder, not the example.
- **Matches with no catch-all on a type that is not sealed**, through
  `-Xlint:strict-unsealed-patmat`, each a `MatchError` on the input it misses:
  Row.scala:14, 18, 22, 26 (`values(i) match` over `Any`), CsvRowParser.scala:87
  (the parser's state, an `Int`, matched against its four constants),
  Funcs.scala:108, and in tests WindowFrames.scala:41 and 55.
- **Deprecations:** DataJob.scala:402 calls `Thread.getId` (deprecated since
  19; `threadId()` replaces it) and TaskDag.scala:218 JSqlParser's
  `TablesNamesFinder.getTableList`. In tests, most of the 46 are HashKeysTest's
  implicit conversion of an `Array` to an immutable `IndexedSeq`, deprecated
  since Scala 2.13.0.
- **Unused code:** 14 sites in main code (SortExec.scala:198 `nKeys`,
  PhysicalPlanner.scala:436 `rightWidth`, WindowExec.scala:691 `childSchema`,
  HashKeys.scala:622's `var` never reassigned, ...) and 31 in tests, among
  them dead helpers in seven test classes.
- **A pattern that reads as a match on an outer name**, through
  `-Xlint:pattern-shadow`. At LogicalBuilder.scala:1104,
  `case Some(e: StringValue)` sits inside `bindExprWithAggs(e, ...)`. It binds
  a new `e`, as meant, but a reader has to check.

The rest is noise for this code base: `-Wtostring-interpolated` (743, mostly
numbers in messages), `-Wnumeric-widen` (440, a `Char` passed to `indexOf`, an
`Int` to JUnit's `assertEquals(long, long)`), `-Wperformance` (144, a `var` a
closure captures), `-Xlint:infer-any` (117, all in tests, JUnit's
`assertEquals(Object, Object)`), `-Wvalue-discard` (101, JavaFX's `add` and
`Files.move`), and `-Xlint:nonlocal-return` (48, `getOrElse(return)` in GUI
handlers; the five in job code sit in no handler that would catch the
`NonLocalReturnControl` they throw).

docs/extending.md:959 (at HEAD) tells the author of a new optimizer pass that
"Scala's exhaustivity check will flag missed cases". It flags them as a
warning, which a cached build never prints again. As an error, the check does
what the doc says.

## What exists now

- **Versions.** rules_scala 7.0.0 (MODULE.bazel:5) and Scala 2.13.16
  (MODULE.bazel:82), through the `scala_deps` extension (`.scala()`,
  `.junit()`).
- **A dead extension.** MODULE.bazel:91-95 also names an extension,
  `@rules_scala//scala/extensions:toolchains.bzl`, that rules_scala 7.0.0 does
  not have: its `scala/extensions/` holds `config.bzl`, `deps.bzl` and
  `protoc.bzl`. No `use_repo` asks for a repository of it, so Bazel never
  evaluates it, and MODULE.bazel.lock has no entry for it.
- **Dependency checking.** The default toolchain has
  `dependency_mode = "direct"`. Under it, rules_scala's
  `_compute_strict_deps_mode` (scala/scala_toolchain.bzl:9-17 in the external
  repository) turns strict deps `"off"`. The compile classpath holds only
  direct dependencies, and that alone gives the "Symbol ... is missing from the
  classpath" error docs/conventions.md:55 calls strict-deps.
  `unused_dependency_checker_mode` is `"off"`, so a BUILD dependency no source
  uses goes unreported.
- **Where options apply.** rules_scala's `phase_scalacopts` passes
  `toolchain.scalacopts + ctx.attr.scalacopts`: a global list from the
  toolchain, then a target's own additions after it. The last matching
  `-Wconf` wins, so a target can only add to or override the global ones.
- **Changing the options.** Define a `scala_toolchain` (from
  `@rules_scala//scala:scala_toolchain.bzl`) and a `toolchain(toolchain_type =
  "@rules_scala//scala:toolchain_type")` in a BUILD file, and register it in
  MODULE.bazel with `register_toolchains`. The root module's registrations are
  tried before those rules_scala makes (rules_scala's docs/scala_toolchain.md,
  "B) Defining your own").
- **Silencing one site.** `@nowarn("cat=...")` (`scala.annotation.nowarn`)
  silences a warning there, and `-Wunused:nowarn` reports one that silences
  nothing.

## What to build

**W1. Measure through Bazel.**

1. In a git worktree outside the repo (the scratchpad), with its own Bazel
   output base, add the toolchain to the root BUILD.bazel. It sets every
   candidate flag, as warnings, with `-Wconf:any:wv`, and
   `unused_dependency_checker_mode = "warn"`. Register it in MODULE.bazel, and
   remove the dead `scala_toolchains` stanza.
2. Run `bazel build //... --keep_going`. A fresh output base compiles every
   target, so every warning prints.
3. Count by category and by target, and compare with the table above. Bazel
   compiles each target against direct dependencies only, so a few numbers may
   move.
4. Check that the toolchain resolves to ours (`--toolchain_resolution_debug`),
   how a `-Wconf:src=` filter sees a path under Bazel's sandbox, and how many
   BUILD dependencies the unused-dependency checker reports.

Done when the table above is re-measured through Bazel, per target, and the
`src=` form that matches test sources is known.

**W2. Clear the categories decision 1 chooses**, one commit per category with
its tests and docs:

- The unused code goes, and deprecated calls get their replacements
  (`threadId()`; JSqlParser's replacement for `getTableList`;
  `ArraySeq.unsafeWrapArray` or `.toIndexedSeq` in HashKeysTest).
- Each unsealed match gets a catch-all that fails naming the value, or a
  sealed type; each discarded value becomes explicit (`val _ = ...`, or a
  `: Unit` helper); the missing interpolator gets its `s`; the shadowing
  pattern gets another name.
- The ResultPersister defect gets a regression test that fails at HEAD, and
  the fix plan 29's rule implies: a directory holding part files no record
  names is refused before anything is written. If [06](06-a-failed-run-leaves-no-partial-output.md)
  lands first and moves ResultPersister onto its commit protocol, its tests
  cover this; check that one does.

A site whose warning is deliberate keeps it, with
`@nowarn("cat=<category>")` and a comment saying why.

Done when the chosen categories are at zero, measured as in W1.

**W3. Make them errors.** The toolchain in the repo's root BUILD.bazel gets
`-Wconf:cat=<each chosen category>:e` and turns the noisy categories off.
Bazel prints a warning only when its target compiles, so every category
ends up either an error or off, never left a warning. `-Werror` comes last,
once nothing else warns.

Done when `bazel test //...` and the jaffle-shop job pass with the toolchain
in place. A deliberate offence in a scratch worktree must fail the build: an
unused import, a non-exhaustive match on a sealed trait, an ignored Boolean
in main code. Record those in this plan's Outcome; they are not committed.

**W4 (decision 3). Unused BUILD dependencies.** Remove the ones W1 lists, then
set the checker to `"error"`. Fewer dependencies mean fewer rebuilds, which
[19](19-mutation-testing.md)'s mutant runs pay for many times over.

## Decisions for the user

1. **Which warnings fail the build.**
   - **(a) The high-signal set (recommended):** deprecations; unused
     imports, privates, locals, pattern variables, implicits and `@nowarn`;
     exhaustivity, `strict-unsealed-patmat` included; `missing-interpolator`
     and `pattern-shadow`; and `-Wnonunit-statement` in main code. About 90
     sites in main code, the 61 discarded values among them. They found the
     defect and the broken message above, and they make extending.md's
     exhaustivity promise true.
   - **(b) All of `-Xlint` and the `-W` flags:** 2,217 warnings, two thirds of
     them `toString` interpolation and numeric widening, which say nothing
     about this code.
   - **(c) Only deprecations, unused code and exhaustivity:** cheaper, but
     leaves out the flag that found a defect.
2. **Test code.**
   - **(a) The same set (recommended)**, but for `-Wnonunit-statement`
     (405 in tests, mostly fixtures and drained iterators) and `infer-any`
     (117, JUnit's `Object` overloads), which are off in tests through a
     `-Wconf:src=` filter. The broken coverage-guard message was in a test.
   - **(b) Tests get no new errors.**
   - **(c) Every category the same everywhere.**
3. **Unused BUILD dependencies.**
   - **(a) Error, after W1 measures and W4 removes them (recommended).**
   - **(b) Leave the checker off.**
4. **`-Wunused:params`** (4 in main code: `Ops.apply`'s `lt` and `rt` are part
   of a call shape, and two are leftovers).
   - **(a) Off (recommended):** an unused parameter of an API shape needs an
     annotation to say so, and the leftovers are found by reading W1's list
     once.
   - **(b) On, with `@unused` where meant.**

## Decisions already made

- Scala 2.13.16 and JDK 21 stay pinned (CLAUDE.md). A patch upgrade adds
  lints, so the categories are re-measured then.
- No new dependency (CLAUDE.md, plans/testing/README.md:11 and :94): scalac's
  own flags only, no WartRemover or Scalafix.
- No backwards-compatibility shims (CLAUDE.md): a deprecated call is replaced,
  not wrapped.
- Comments describe current behaviour (the readability memory note): a
  `@nowarn` comment says why the site is deliberate, not which plan added it.
- Docs land in the same commit as the code (CLAUDE.md).
- A regression test fails at the base commit, checked in a worktree there
  (plans/robustness/09:144, 11:163).

## Tests

- **The build.** With W3 in place, `bazel build //...` is the test of every
  rule; the deliberate offences in W3 prove each fatal category fires.
- **Regressions.** The ResultPersister regression in `gui:result_persister_test`
  must fail at HEAD. So must a regression for each unsealed match that could
  meet its missing case (`Row.getInt` of a value of another type is reachable
  only from code, so a unit test).
- **Whole-suite configurations.** The configs (`--config=batch1`, `sharded`,
  `zone`, `hashzero`) are unaffected: compile options do not change
  behaviour. Run the default suite and jaffle-shop only.

## Docs

- `docs/conventions.md`: a bullet saying the compiler's warnings are errors.
  It gives the categories, how to silence one site (`@nowarn("cat=...")` with
  a reason) and which flags are off and why. Fix line 55's mechanism:
  `dependency_mode = "direct"` gives the classpath error, strict deps are off,
  and the unused-dependency checker is on (decision 3).
- `docs/extending.md:959`: a missed `LogicalPlan` case is a compile error.
- `docs/testing.md`, "Build / test commands": how to see the full warning set
  (W1's command), and the direct-scalac count as a quick measure.
- `docs/gotchas.md`: Bazel prints a warning only when its target compiles;
  what `-Wconf:src=` matches under Bazel; the root module's toolchain wins
  resolution (`--toolchain_resolution_debug` shows it).
- `README.md`, Development: a line saying warnings fail the build. No change
  to `site/`, `docs/architecture.md` or `docs/code-map.md`.

## Risks

- **Peer sessions.** An uncommitted change in another session may not compile
  once W3 lands. On 2026-11-01 a peer had uncommitted work in MODULE.bazel,
  Cli.scala, CsvWriter.scala and PhysicalPlan.scala. Land W3 when `git status`
  is clean, after a `ListAgents` check, and say in its commit message how to
  silence a site.
- **The wrong toolchain.** If rules_scala's default toolchain wins resolution,
  the flags do nothing and nothing fails. W1's resolution check and W3's
  deliberate offences are the guard.
- **`src=` paths.** A `-Wconf:src=` filter that matches no path under Bazel
  turns a test-only exemption into a main-code error, or the reverse. W1
  settles the form before W3 depends on it.
- **The ResultPersister fix changes behaviour.** Persisting into a directory
  that holds stray part files fails where it used to adopt them. That is
  plan 29's rule, already true of jobs; the GUI shows the error the job runner
  gives.

## Launch prompt

```
Read plans/testing/07-compiler-warnings.md and implement it.

Follow CLAUDE.md's required workflow for every commit: bazel test //... green,
the jaffle-shop job 15/15 Succeeded, and every stale doc updated in the same
commit. One commit per category in W2, each with its tests.

Before editing, run ListAgents and git status: if another session has
uncommitted work in this tree, develop in a git worktree OUTSIDE the repo (see
the memory notes on concurrent sessions and on agent worktrees breaking
bazel //...), and land W3 only when the tree is clean.

Do W1 first, in a scratch worktree outside the repo with its own output base,
and report its counts per category and per target before changing code.

Stop and ask before: (a) any category beyond the ones decision 1 chose,
(b) changing a public signature to silence a warning, (c) silencing more than
a handful of sites with @nowarn in one category, (d) anything that changes
behaviour beyond the ResultPersister fix.

Report at the end: the warning counts before and after per category, each
defect a warning found (with its regression test), the deliberate offences
that proved W3's errors fire, and the unused BUILD dependencies removed.
```
