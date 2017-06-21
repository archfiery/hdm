package org.hdm.core.executor

import org.hdm.akka.messages.Query
import org.hdm.akka.server.SmsSystem
import org.hdm.core.io.Path
import org.hdm.core.model.HDM
import org.hdm.core.planing.FunctionFusion
import org.junit.{After, Ignore, Test}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent._
import scala.util.{Failure, Success}

/**
 * Created by Tiantian on 2014/12/19.
 */
class HDMLeaderTest extends ClusterTestSuite {


  val text =
    """
        this is a word count text
        this is line 2
        this is line 3
    """.split("\\s+")

  val text2 =
    """
        this is a word count text
        this is line 4
        this is line 5
        this is line 6
        this is line 7
    """.split("\\s+")


  def testForDebugging {
    hDMContext.startAsMaster(port = 8999, conf = testMasterConf)
    val rootPath = SmsSystem.rootPath
    println(rootPath)
    //    val addmsg1 = AddMsg(CLUSTER_EXECUTOR_NAME, "localhost","org.hdm.core.coordinator.BlockManagerLeader", null)
    //    val res1 = SmsSystem.askMsg("akka.tcp://masterSys@127.0.0.1:8999/user/smsMaster", addmsg1).getOrElse("no response")
    //    println(res1)

    //    SmsSystem.addActor(CLUSTER_EXECUTOR_NAME, "akka.tcp://masterSys@127.0.0.1:8999/user/smsMaster","org.hdm.core.coordinator.BlockManagerLeader", null)
    //    SmsSystem.addActor(BLOCK_MANAGER_NAME, "localhost","org.hdm.core.coordinator.ClusterExecutorLeader", null)

    val res = SmsSystem.askSync("akka.tcp://masterSys@127.0.0.1:8999/user/smsMaster", Query("smsSystem/allActors", "", "")).getOrElse("no response")

    println(res)
  }


  /**
   *
   */
  @Test
  def testLeaderStart() {
    hDMContext.startAsMaster(port = 8999, conf = testMasterConf)
    val rootPath = SmsSystem.rootPath
    println(rootPath)

    Thread.sleep(1500)
  }

  @Test
  def testLocalExecution() {
    hDMContext.clusterExecution.set(false)
    hDMContext.init(leader = "localhost", slots = 4)
    appContext.setMasterPath("akka.tcp://masterSys@127.0.1.1:8999/user/smsMaster")
    Thread.sleep(1000)
    val hdm = HDM.horizontal(appContext, hDMContext, text, text2)
    val wordCount = hdm.map(w => (w, 1))
      //.groupReduce(_._1, (t1, t2) => (t1._1, t1._2 + t2._2))

    val future = wordCount.compute(1)
    future onComplete {
      case Success(hdm) =>
        println("Job completed and received response:" + hdm)
//        hdm.asInstanceOf[HDM[_, _]].sample(10).foreach(println(_))
      case Failure(t) =>
        println("Job failed because of: " + t)
        t.printStackTrace()
    }
    Await.ready(future, maxWaitResponseTime)
  }

  @Ignore("require hdfs installed locally.")
  @Test
  def testHDFSExecution(): Unit = {
    hDMContext.init(leader = "localhost", slots = 4)

    val path = Path("hdfs://127.0.0.1:9001/user/spark/benchmark/micro/rankings")
    val hdm = HDM(path)

    val wordCount = hdm.map{ w =>
      val as = w.split(",");
      (as(0).substring(0,3), as(1).toInt)
    }.groupReduce(_._1, (t1,t2) => (t1._1, t1._2 + t2._2))

    // val wordCount = hdm.map(d => (d.substring(0,3), 1)).groupBy(_._1)
    //.groupReduce(d => d._1, (t1,t2) => (t1._1, t1._2 + t2._2))
    val wordCountOpt = new FunctionFusion().optimize(wordCount)

    val res = wordCountOpt.compute(4)
    res onComplete {
      case Success(hdm) =>
        println("Job completed and received response:" + hdm)
//        hdm.asInstanceOf[HDM[_, _]].sample(10).foreach(println(_))
      case Failure(t) =>
        println("Job failed because of: " + t)
        t.printStackTrace()
    }

    Await.ready(res, maxWaitResponseTime)
  }


  @After
  def after() {
    hDMContext.shutdown(appContext)
  }
}


