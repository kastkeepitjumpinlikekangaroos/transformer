package com.transformer.core

import java.util.Locale

/** Every setting the engine reads from outside the program, and the one place
  * each is read. A setting is a system property, `transformer.<name>`, or its
  * environment variable twin, `TRANSFORMER_<NAME>`: the property's name
  * upper-cased, its dots underscores. The property wins over the variable,
  * and a value that is blank once trimmed counts as unset. A whole number
  * that does not parse is the default, as is a flag set to anything but
  * `true` or `1` (in any case); a chaos seed or a fault that does not parse is
  * an error.
  *
  * Each owner reads its setting when it is first asked, mostly once per JVM
  * (`ColumnarBatch.DefaultCapacity`, `Scheduler.parallelism`), so a test that
  * varies one runs in a JVM of its own: a target's `jvm_flags`, or a
  * `.bazelrc` config for the whole suite (docs/testing.md, "Whole-suite
  * configurations").
  *
  * README's "Settings" table says what each one sets, with its twin and its
  * default, and `core:settings_test` holds the table to [[All]]. Rule R8 of
  * `bytecode:bytecode_rules_test` holds that no other class of the engine
  * reads a system property or an environment variable, or names a setting. */
object Settings {

  /** A setting: its system property, its default as README's table writes
    * it, and how its value reads: `parse` is given the value set, or None,
    * and gives the default for None. */
  final class Setting[T](val property: String, val default: String, val parse: Option[String] => T) {

    /** `TRANSFORMER_` and the rest of the property's name, upper-cased, its
      * dots underscores. */
    val environmentVariable: String = property.toUpperCase(Locale.ROOT).replace('.', '_')

    /** The value set: the property's, else the environment variable's; None
      * when neither holds more than blanks. */
    def value: Option[String] = valueIn(System.getProperty(_), System.getenv(_))

    /** [[value]], reading the properties and the environment through the two
      * functions given, each null for a name that is not set. */
    def valueIn(properties: String => String, environment: String => String): Option[String] =
      present(properties(property)).orElse(present(environment(environmentVariable)))

    /** The setting: its value parsed, or its default. */
    def get: T = parse(value)

    override def toString: String = property
  }

  private def present(v: String): Option[String] = Option(v).map(_.trim).filter(_.nonEmpty)

  /** On when set to `true` or `1`, in any case, as a task's `options.spill`
    * and `options.metrics` read. */
  private def flag(v: Option[String]): Boolean = v.exists { s =>
    val t = s.toLowerCase(Locale.ROOT)
    t == "true" || t == "1"
  }

  /** The rows a batch holds, a whole number of 1 or more
    * (`ColumnarBatch.DefaultCapacity`). */
  val BatchCapacity: Setting[Int] = new Setting[Int]("transformer.batch_capacity", "8192",
    _.flatMap(_.toIntOption).filter(_ >= 1).getOrElse(8192))

  /** The threads of the pool every query and job runs on, at least one
    * (`Scheduler.parallelism`). */
  val Parallelism: Setting[Int] = new Setting[Int]("transformer.scheduler.parallelism", "twice the processors",
    v => math.max(1, v.flatMap(_.toIntOption).getOrElse(2 * math.max(1, Runtime.getRuntime.availableProcessors))))

  /** The shards an exchange routes rows to, at least one
    * (`ExchangeExec.defaultNumShards`). */
  val ShardCount: Setting[Int] = new Setting[Int]("transformer.scheduler.shard_count", "the parallelism",
    v => math.max(1, v.flatMap(_.toIntOption).getOrElse(Scheduler.parallelism)))

  /** The estimated input rows from which a GROUP BY or a DISTINCT is sharded
    * (`LogicalPlanCardinality.MinShardableSize`); by default none is. */
  val ShardMinSize: Setting[Long] = new Setting[Long]("transformer.scheduler.shard_min_size", "never",
    _.flatMap(_.toLongOption).getOrElse(Long.MaxValue))

  /** The estimated rows of a hash join's smaller side below which it is built
    * once rather than sharded (`LogicalPlanCardinality.BroadcastBuildThreshold`). */
  val BroadcastThreshold: Setting[Long] = new Setting[Long]("transformer.scheduler.broadcast_threshold", "1000000",
    _.flatMap(_.toLongOption).getOrElse(1000000L))

  /** Per-operator metrics for every task whose `output.json` does not say
    * (`metrics.MetricsCollector.globalDefault`). */
  val MetricsEnabled: Setting[Boolean] = new Setting[Boolean]("transformer.metrics.enabled", "false", flag)

  /** Spill on for every task (`DataJob.forceSpill`), as the macro bench
    * captures its spill-on baseline. */
  val ForceSpill: Setting[Boolean] = new Setting[Boolean]("transformer.macro.force_spill", "false", flag)

  /** The directory each JVM's spill directory is made in (`Spill.baseDir`). */
  val SpillDir: Setting[String] = new Setting[String]("transformer.spill.dir", "`<java.io.tmpdir>/transformer-spill`",
    _.getOrElse(System.getProperty("java.io.tmpdir") + "/transformer-spill"))

  /** Every key hash 0 (`KeyHash.zero`, `--config=hashzero`). */
  val HashZero: Setting[Boolean] = new Setting[Boolean]("transformer.hash_zero", "false", flag)

  /** Every batch every operator emits checked against its schema
    * (`BatchInvariants.enabled`, `--config=verify`). */
  val Verify: Setting[Boolean] = new Setting[Boolean]("transformer.verify", "false", flag)

  /** What a JVM leaves open at its exit, reported, failing it
    * (`LeakAudit.enabled`, `--config=leaks`). */
  val AuditLeaks: Setting[Boolean] = new Setting[Boolean]("transformer.audit_leaks", "false", flag)

  /** The seed of the perturbation at every handoff between threads
    * (`Chaos.seed`, `--config=chaos`), a whole number; off when unset. */
  val ChaosSeed: Setting[Option[Long]] = new Setting[Option[Long]]("transformer.chaos", "off",
    _.map(s => s.toLongOption.getOrElse(throw new IllegalArgumentException(
      s"transformer.chaos must be a whole number, a seed, not '$s'"))))

  /** A fault armed where a run changes the disk, `<point>:<n>:<action>`,
    * which `Faults` reads (docs/testing.md, "Fault points"). */
  val Fault: Setting[Option[String]] = new Setting[Option[String]]("transformer.fault", "none", identity)

  /** Every setting, in README's order. */
  val All: Seq[Setting[_]] = Seq(BatchCapacity, Parallelism, ShardCount, ShardMinSize, BroadcastThreshold,
    MetricsEnabled, ForceSpill, SpillDir, HashZero, Verify, AuditLeaks, ChaosSeed, Fault)
}
