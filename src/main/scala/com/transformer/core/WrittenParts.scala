package com.transformer.core

/** What a partitioned write of a directory committed: the part files, by name
  * in partition order, and the rows they hold. A record names exactly these
  * files, never a listing of the directory, which can hold files the write
  * did not make. */
final case class WrittenParts(files: Vector[String], rows: Long)
