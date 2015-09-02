package org.nicta.wdy.hdm.planing

/**
 * Created by tiantian on 9/04/15.
 */

import org.nicta.wdy.hdm.model.DDM

import scala.collection.mutable.Buffer
import org.nicta.wdy.hdm.io.Path

object PlanningUtils {

  def simpleGrouping[T](elem:Seq[T], groupNum:Int):Seq[Seq[T]]= {
    elem.grouped(elem.size/groupNum).toSeq
  }

  def orderKeepingGroup[T](elem:Seq[T], groupNum:Int):Seq[Buffer[T]]= {
    val elemBuffer = elem.toBuffer
    val groupBuffer = Array.fill(groupNum){Buffer.empty[T]}
    val elemSize = elem.size/groupNum
    for (g <- 0 until groupNum){
      groupBuffer(g) = elemBuffer.take(elemSize)
      elemBuffer.remove(0, elemSize)
    }
    for (elem <- elemBuffer){
      val idx = elemBuffer.indexOf(elem) % groupNum
      groupBuffer(idx) += elem
    }
    groupBuffer.toSeq
  }

  def weightedGroup[T](elems:Seq[T], weights:Array[Float], groupNum:Int):Seq[Buffer[T]]= {
    require(elems.length == weights.length)
    val groupBuffer = Array.fill(groupNum){Buffer.empty[T]}
    val totalWeights = weights.sum
    val weightPerGroup = totalWeights / groupNum
    var accumulatedWeights = 0F
    var groupIdx = 0
    for(idx <- 0 until elems.length){
      val cur = elems(idx)
      if(accumulatedWeights < weightPerGroup){
        groupBuffer(idx) += cur
        accumulatedWeights += weights(idx)
      } else {
        accumulatedWeights = 0
        groupIdx += 1
      }
    }
    groupBuffer.toSeq
  }


  def seqSlide[T](elem:Seq[T], slideLen:Int):Seq[T] ={
    val (pre, post) = elem.splitAt(slideLen)
    post ++ pre
  }

  def groupPathBySimilarity(paths:Seq[Path], n:Int) = {
    val avg = paths.size/n
    paths.sortWith( (p1,p2) => Path.path2Int(p1) < Path.path2Int(p2)).grouped(avg.toInt).toSeq
  }

  def groupDDMByLocation(ddms:Seq[DDM[String,String]], n:Int) = {
    //    val avg = ddms.size/n
    //    ddms.sortWith( (p1,p2) => path2Int(p1.preferLocation) < path2Int(p2.preferLocation)).grouped(avg.toInt).toSeq
    val ddmMap = ddms.map(d => (d.preferLocation -> d)).toMap
    val paths = ddms.map(_.preferLocation)
    val grouped = groupPathBySimilarity(paths, n)
    grouped.map{seq =>
      seq.map(p => ddmMap(p))
    }
  }

  def groupPathByBoundary(paths:Seq[Path], n:Int) = {
    val sorted = paths.sortWith( (p1,p2) => Path.path2Int(p1) < Path.path2Int(p2)).iterator
    val boundary = 256 << 8 + 256
    val ddmBuffer = Buffer.empty[Buffer[Path]]
    var buffer = Buffer.empty[Path]
    val total = paths.size.toFloat

    if(sorted.hasNext){
      var cur = sorted.next()
      buffer += cur
      while (sorted.hasNext) {
        val next = sorted.next()
        if ((Path.path2Int(next) - Path.path2Int(cur)) >= boundary ){
          ddmBuffer += buffer
          buffer = Buffer.empty[Path] += next
        } else {
          buffer += next
        }
        cur = next
      }
      ddmBuffer += buffer
    }
    // subGrouping in each bounded group
    val distribution = ddmBuffer.map(seq => Math.round( (seq.size/total) * n))
    val finalRes = Buffer.empty[Buffer[Path]]
    for{
      i <- 0 until ddmBuffer.size
    }{
      val seq = ddmBuffer(i)
      val groupSize = distribution(i)
      finalRes ++= (PlanningUtils.orderKeepingGroup(seq, groupSize))
    }
    finalRes
  }

  def groupDDMByBoundary(ddms:Seq[DDM[String,String]], n:Int) ={
    val ddmMap = ddms.map(d => (d.preferLocation -> d)).toMap
    val paths = ddms.map(_.preferLocation)
    val grouped = groupPathByBoundary(paths, n)
    grouped.map{seq =>
      seq.map(p => ddmMap(p))
    }
  }
}