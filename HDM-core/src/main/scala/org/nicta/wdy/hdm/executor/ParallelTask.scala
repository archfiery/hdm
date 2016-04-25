package org.nicta.wdy.hdm.executor

import java.util.concurrent.Callable

import org.nicta.wdy.hdm._
import org.nicta.wdy.hdm.functions.SerializableFunction
import org.nicta.wdy.hdm.model.{HDM, DDM, ParHDM$, DataDependency}
import org.nicta.wdy.hdm.utils.Logging

import scala.reflect.ClassTag

/**
 * Created by tiantian on 23/11/15.
 */
abstract class ParallelTask [R : ClassTag] extends Serializable with Callable[Seq[DDM[_, R]]] with Logging {

  val appId: String

  val version: String

  val exeId:String

  val taskId: String

  def input:Seq[HDM[_]]

  val dep: DataDependency

  val keepPartition: Boolean

  val func:SerializableFunction[_, Arr[R]]

  val partitioner:Partitioner[R]

  val createTime: Long = System.currentTimeMillis()

}
