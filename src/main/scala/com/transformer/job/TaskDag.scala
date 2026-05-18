package com.transformer.job

import com.transformer.sql.parse.SqlParser
import com.transformer.temporal.{TemplateRenderer, TemporalVariables}

import net.sf.jsqlparser.util.TablesNamesFinder

import scala.collection.mutable
import scala.jdk.CollectionConverters._

/** A single node in the SQLTask dependency graph.
  *
  * `deps` is the set of declared-indices of upstream tasks this task reads from (i.e.,
  * other tasks whose `viewName` is referenced in this task's main SQL or any of its
  * validation SQLs). References to Phase 1 input views are NOT deps — those are
  * pre-loaded into the catalog before any task runs.
  *
  * Public so external surfaces (e.g. the GUI module) can render the DAG without
  * having to re-implement the analyzer.
  */
final case class TaskDagNode(
    index: Int,
    task: SQLTask,
    renderedMainSql: String,
    renderedValidationSqls: Seq[String],
    deps: Set[Int]
)

/** A fully-validated DAG of [[SQLTask]]s ready to be executed by the scheduler.
  *
  * Public for the same reason as [[TaskDagNode]] — see scaladoc there.
  */
final case class TaskDag(
    nodes: Vector[TaskDagNode],
    dependents: Vector[Set[Int]]
)

object TaskDag {

  /** Build a DAG over `tasks`, validating uniqueness/references/cycles upfront.
    *
    * @param tasks          the job's SQLTasks, in declared order
    * @param inputViewNames lowercased viewNames already registered by Phase 1
    * @param vars           temporal variables; SQL is rendered against these so that
    *                       template-injected table names (e.g. `FROM events_{{ today }}`)
    *                       resolve correctly
    *
    * Throws [[IllegalArgumentException]] on any violation. The runner only ever sees a
    * fully-consistent DAG.
    */
  def build(
      tasks: Seq[SQLTask],
      inputViewNames: Set[String],
      vars: TemporalVariables
  ): TaskDag = {
    if (tasks.isEmpty) return TaskDag(Vector.empty, Vector.empty)

    val viewNameToTaskIdx = mutable.LinkedHashMap.empty[String, Int]
    var idx = 0
    while (idx < tasks.size) {
      val t = tasks(idx)
      t.viewName.foreach { vn =>
        val key = vn.toLowerCase
        if (inputViewNames.contains(key)) {
          throw new IllegalArgumentException(
            s"Task '${t.displayName}' viewName '$vn' collides with an input view of the same name"
          )
        }
        viewNameToTaskIdx.get(key) match {
          case Some(prev) =>
            throw new IllegalArgumentException(
              s"Duplicate task viewName '$vn': index $prev ('${tasks(prev).displayName}') and index $idx ('${t.displayName}')"
            )
          case None => viewNameToTaskIdx(key) = idx
        }
      }
      idx += 1
    }

    val renderedMains = tasks.map(t => TemplateRenderer.render(t.loadSql(), vars))
    val renderedValidations = tasks.map(t =>
      t.validations.map(v => TemplateRenderer.render(v.loadSql(), vars))
    )

    def extract(sql: String): Set[String] = {
      val stmt = SqlParser.parse(sql)
      new TablesNamesFinder().getTableList(stmt).asScala.iterator.map(_.toLowerCase).toSet
    }

    val mainRefs: IndexedSeq[Set[String]] = tasks.indices.map(i => extract(renderedMains(i)))
    val validationRefs: IndexedSeq[Set[String]] =
      tasks.indices.map(i => renderedValidations(i).iterator.flatMap(extract).toSet)

    tasks.indices.foreach { i =>
      val selfView = tasks(i).viewName.map(_.toLowerCase)
      // Self-reference in main SQL is impossible: the task can't read its own output
      // before it has been written. (Self-reference in a validation IS expected — the
      // validation queries the just-materialized view by name.)
      selfView.foreach { sv =>
        if (mainRefs(i).contains(sv)) {
          throw new IllegalArgumentException(
            s"Task '${tasks(i).displayName}' main SQL references its own viewName '$sv' (self-cycle)"
          )
        }
      }
      val allRefs = mainRefs(i) ++ validationRefs(i)
      allRefs.foreach { ref =>
        val known = inputViewNames.contains(ref) || viewNameToTaskIdx.contains(ref)
        if (!known) {
          throw new IllegalArgumentException(
            s"Task '${tasks(i).displayName}' references unknown view '$ref'. " +
              s"Known inputs: [${inputViewNames.toSeq.sorted.mkString(", ")}]; " +
              s"task views: [${viewNameToTaskIdx.keys.toSeq.sorted.mkString(", ")}]"
          )
        }
      }
    }

    val pathToTaskIdx = mutable.LinkedHashMap.empty[String, Int]
    tasks.iterator.zipWithIndex.foreach { case (t, i) =>
      t.outputFile.foreach { ofp =>
        val rendered = TemplateRenderer.render(ofp.path, vars)
        pathToTaskIdx.get(rendered) match {
          case Some(prev) =>
            throw new IllegalArgumentException(
              s"Tasks at index $prev ('${tasks(prev).displayName}') and index $i " +
                s"('${t.displayName}') both write to '$rendered'"
            )
          case None => pathToTaskIdx(rendered) = i
        }
      }
    }

    val deps: IndexedSeq[Set[Int]] = tasks.indices.map { i =>
      val refs = mainRefs(i) ++ validationRefs(i)
      refs.iterator.flatMap(viewNameToTaskIdx.get).filter(_ != i).toSet
    }

    val inDeg = Array.tabulate(tasks.size)(i => deps(i).size)
    val dependentsAdj: IndexedSeq[mutable.Set[Int]] =
      tasks.indices.map(_ => mutable.Set.empty[Int])
    tasks.indices.foreach { i =>
      deps(i).foreach(d => dependentsAdj(d) += i)
    }
    val queue = mutable.Queue.empty[Int]
    tasks.indices.foreach(i => if (inDeg(i) == 0) queue.enqueue(i))
    var peeled = 0
    while (queue.nonEmpty) {
      val i = queue.dequeue()
      peeled += 1
      dependentsAdj(i).foreach { j =>
        inDeg(j) -= 1
        if (inDeg(j) == 0) queue.enqueue(j)
      }
    }
    if (peeled < tasks.size) {
      val remaining = tasks.indices.filter(i => inDeg(i) > 0).toSet
      val cycle = findCycle(remaining, deps)
      val pretty = cycle.map(i => s"$i:${tasks(i).displayName}").mkString(" -> ")
      throw new IllegalArgumentException(s"Cycle detected in SQLTask DAG: $pretty")
    }

    val nodes = tasks.indices.map { i =>
      TaskDagNode(
        index = i,
        task = tasks(i),
        renderedMainSql = renderedMains(i),
        renderedValidationSqls = renderedValidations(i),
        deps = deps(i)
      )
    }.toVector

    TaskDag(nodes = nodes, dependents = dependentsAdj.map(_.toSet).toVector)
  }

  private def findCycle(remaining: Set[Int], deps: IndexedSeq[Set[Int]]): Seq[Int] = {
    val color = mutable.Map.empty[Int, Int] // 0=white, 1=gray, 2=black
    val parent = mutable.Map.empty[Int, Int]
    var cycleStart = -1
    var cycleEnd = -1
    def dfs(u: Int): Boolean = {
      color(u) = 1
      val it = deps(u).iterator
      while (it.hasNext) {
        val v = it.next()
        if (remaining.contains(v)) color.getOrElse(v, 0) match {
          case 0 =>
            parent(v) = u
            if (dfs(v)) return true
          case 1 =>
            cycleStart = v
            cycleEnd = u
            return true
          case _ =>
        }
      }
      color(u) = 2
      false
    }
    val start = remaining.min
    dfs(start)
    if (cycleStart < 0) Seq.empty
    else {
      val path = mutable.ArrayBuffer[Int]()
      var cur = cycleEnd
      path += cur
      while (cur != cycleStart) {
        cur = parent(cur)
        path += cur
      }
      (path.reverse :+ cycleStart).toSeq
    }
  }
}
