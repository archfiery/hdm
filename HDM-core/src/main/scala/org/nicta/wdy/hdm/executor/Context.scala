package org.nicta.wdy.hdm.executor

import java.util.UUID
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}

import com.baidu.bpit.akka.server.SmsSystem
import com.typesafe.config.{Config, ConfigFactory}
import org.nicta.wdy.hdm.Arr
import org.nicta.wdy.hdm.functions.SerializableFunction
import org.nicta.wdy.hdm.io.netty.NettyConnectionManager
import org.nicta.wdy.hdm.io.{CompressionCodec, SnappyCompressionCodec}
import org.nicta.wdy.hdm.message._
import org.nicta.wdy.hdm.model.{HDM, GroupedSeqHDM, ParHDM, KvHDM}
import org.nicta.wdy.hdm.planing.StaticPlaner
import org.nicta.wdy.hdm.scheduling.{AdvancedScheduler, SchedulingPolicy}
import org.nicta.wdy.hdm.serializer.{SerializableByteBuffer, JavaSerializer, SerializerInstance}
import org.nicta.wdy.hdm.server.{ProvenanceManager, DependencyManager}
import org.nicta.wdy.hdm.storage.{Block, HDMBlockManager}
import org.nicta.wdy.hdm.utils.Logging

import scala.concurrent.{Future, Promise}
import scala.reflect.ClassTag
import scala.util.Try

/**
 * Created by Tiantian on 2014/11/4.
 */
trait Context {

  def findHDM(id:String): ParHDM[_, _] = ???

  def sendFunc[T,R](target:ParHDM[_,T], func:SerializableFunction[T,R]): Future[ParHDM[T,R]] = ???

  def receiveFunc[T,R](target:ParHDM[_, T], func:SerializableFunction[T,R]): Future[ParHDM[T,R]] = ???

  def runTask[T,R](target:ParHDM[_, T], func:SerializableFunction[T,R]): Future[ParHDM[T,R]] = ???
}

object HDMContext extends  Context with Logging{

  implicit lazy val executionContext = ClusterExecutorContext((CORES * parallelismFactor).toInt)

  lazy val defaultConf = ConfigFactory.load("hdm-core.conf")

  lazy val DEFAULT_DEPENDENCY_BASE_PATH = Try {defaultConf.getString("hdm.dep.base.path")} getOrElse ("target/repo/hdm")

  lazy val parallelismFactor = Try {defaultConf.getDouble("hdm.executor.parallelism.factor")} getOrElse (1.0D)

  lazy val PLANER_PARALLEL_CPU_FACTOR = Try {defaultConf.getInt("hdm.planner.parallelism.cpu.factor")} getOrElse (CORES)

  lazy val PLANER_PARALLEL_NETWORK_FACTOR = Try {defaultConf.getInt("hdm.planner.parallelism.network.factor")} getOrElse (CORES)

  lazy val DEFAULT_BLOCK_ID_LENGTH = defaultSerializer.serialize(newLocalId()).array().length

  val defaultSerializer:SerializerInstance = new JavaSerializer(defaultConf).newInstance()

  val defaultSerializerFactory = new JavaSerializer(defaultConf)

  val DEFAULT_COMPRESSOR = new SnappyCompressionCodec(defaultConf)

  val BLOCK_COMPRESS_IN_TRANSPORTATION = Try {defaultConf.getBoolean("hdm.io.network.block.compress")} getOrElse (true)

  val BLOCK_SERVER_PROTOCOL = Try {defaultConf.getString("hdm.io.network.protocol")} getOrElse ("netty")

  var NETTY_BLOCK_SERVER_PORT = Try {defaultConf.getInt("hdm.io.netty.server.port")} getOrElse (9091)

  val BLOCK_SERVER_INIT = Try {defaultConf.getBoolean("hdm.io.netty.server.init")} getOrElse (true)

  val NETTY_BLOCK_SERVER_THREADS = Try {defaultConf.getInt("hdm.io.netty.server.threads")} getOrElse(CORES)

  val NETTY_BLOCK_CLIENT_THREADS = Try {defaultConf.getInt("hdm.io.netty.client.threads")} getOrElse(CORES)

  val NETTY_CLIENT_CONNECTIONS_PER_PEER = Try {defaultConf.getInt("hdm.io.netty.client.connection-per-peer")} getOrElse(CORES)

  val CORES = Runtime.getRuntime.availableProcessors

  val MAX_MEM_GC_SIZE = Try {defaultConf.getInt("hdm.memory.gc.max.byte")} getOrElse(1024 * 1024 * 1024) // about 256MB

  val SCHEDULING_POLICY_CLASS = Try {defaultConf.getString("hdm.scheduling.policy.class")} getOrElse ("org.nicta.wdy.hdm.scheduling.MinMinScheduling")

  val SCHEDULING_FACTOR_CPU = Try {defaultConf.getInt("hdm.scheduling.policy.factor.cpu")} getOrElse (1)

  val SCHEDULING_FACTOR_IO = Try {defaultConf.getInt("hdm.scheduling.policy.factor.io")} getOrElse (10)

  val SCHEDULING_FACTOR_NETWORK = Try {defaultConf.getInt("hdm.scheduling.policy.factor.network")} getOrElse (20)

  val slot = new AtomicInteger(1)

  val isLinux = System.getProperty("os.name").toLowerCase().contains("linux")

  val blockManager = HDMBlockManager()

  val planer = StaticPlaner

  private var hdmBackEnd:HDMServerBackend = null

  lazy val schedulingPolicy = Class.forName(SCHEDULING_POLICY_CLASS).newInstance().asInstanceOf[SchedulingPolicy]

//  val scheduler = new SimpleFIFOScheduler

  val leaderPath: AtomicReference[String] = new AtomicReference[String]()

  val CLUSTER_EXECUTOR_NAME:String =  "ClusterExecutor"

  val BLOCK_MANAGER_NAME:String =  "BlockManager"

  val JOB_RESULT_DISPATCHER:String = "ResultDispatcher"

  var appName = "defaultApp"

  var version = "0.0.1"

  def clusterBlockPath = leaderPath.get() + "/" + BLOCK_MANAGER_NAME

  def localBlockPath = {
    BLOCK_SERVER_PROTOCOL match {
      case "akka" => SmsSystem.physicalRootPath + "/" + BLOCK_MANAGER_NAME
      case "netty" => s"netty://${NettyConnectionManager.localHost}:$NETTY_BLOCK_SERVER_PORT"
    }
  }

  def startAsMaster(port:Int = 8999, conf: Config = defaultConf, slots:Int = 0){
    SmsSystem.startAsMaster(port, isLinux, conf)
//    SmsSystem.addActor(CLUSTER_EXECUTOR_NAME, "localhost","org.nicta.wdy.hdm.coordinator.ClusterExecutorLeader", slots)
    SmsSystem.addActor(CLUSTER_EXECUTOR_NAME, "localhost","org.nicta.wdy.hdm.coordinator.HDMClusterLeaderActor", slots)
    SmsSystem.addActor(BLOCK_MANAGER_NAME, "localhost","org.nicta.wdy.hdm.coordinator.BlockManagerLeader", null)
    SmsSystem.addActor(JOB_RESULT_DISPATCHER, "localhost","org.nicta.wdy.hdm.coordinator.ResultHandler", null)
    leaderPath.set(SmsSystem.rootPath)
  }

  def startAsSlave(masterPath:String, port:Int = 10010, blockPort:Int = 9091, conf: Config = defaultConf, slots:Int = CORES){
    this.slot.set(slots)
    this.NETTY_BLOCK_SERVER_PORT = blockPort
    SmsSystem.startAsSlave(masterPath, port, isLinux, conf)
    SmsSystem.addActor(CLUSTER_EXECUTOR_NAME, "localhost","org.nicta.wdy.hdm.coordinator.HDMClusterWorkerActor", masterPath+"/"+CLUSTER_EXECUTOR_NAME)
    SmsSystem.addActor(BLOCK_MANAGER_NAME, "localhost","org.nicta.wdy.hdm.coordinator.BlockManagerFollower", masterPath+"/"+BLOCK_MANAGER_NAME)
    SmsSystem.addActor(JOB_RESULT_DISPATCHER, "localhost","org.nicta.wdy.hdm.coordinator.ResultHandler", null)
    leaderPath.set(masterPath)
    if(BLOCK_SERVER_INIT) HDMBlockManager.initBlockServer()
  }

  def startAsClient(masterPath:String, port:Int = 20010, blockPort:Int = 9092, conf: Config = defaultConf, localExecution:Boolean = false){
    SmsSystem.startAsSlave(masterPath, port, isLinux, conf)
    SmsSystem.addActor(BLOCK_MANAGER_NAME, "localhost","org.nicta.wdy.hdm.coordinator.BlockManagerFollower", masterPath+"/"+BLOCK_MANAGER_NAME)
    SmsSystem.addActor(JOB_RESULT_DISPATCHER, "localhost","org.nicta.wdy.hdm.coordinator.ResultHandler", null)
    leaderPath.set(masterPath)
    if(localExecution){
      this.slot.set(CORES)
      this.NETTY_BLOCK_SERVER_PORT = blockPort
      if(BLOCK_SERVER_INIT) HDMBlockManager.initBlockServer()
      SmsSystem.addActor(CLUSTER_EXECUTOR_NAME, "localhost","org.nicta.wdy.hdm.coordinator.HDMClusterWorkerActor", masterPath+"/"+CLUSTER_EXECUTOR_NAME)
    }

  }

  def init(leader:String = "localhost", slots:Int = CORES) {

    if(leader == "localhost") {
      startAsMaster(slots = slots)
    } else {
      if(slots > 0)
        startAsClient(masterPath = leader, localExecution = true)
      else
        startAsClient(masterPath = leader, localExecution = false)
    }
//    scheduler.start()
  }


  def shutdown(){
    SmsSystem.shutDown()
  }


  def getServerBackend():HDMServerBackend = {
    if(hdmBackEnd == null) {
      val appManager = new AppManager
      val blockManager = HDMBlockManager()
      val promiseManager = new DefPromiseManager
      val resourceManager = new DefResourceManager
      val schedulingPolicy = Class.forName(SCHEDULING_POLICY_CLASS).newInstance().asInstanceOf[SchedulingPolicy]
      //    val scheduler = new DefScheduler(blockManager, promiseManager, resourceManager, SmsSystem.system)
      val scheduler = new AdvancedScheduler(blockManager, promiseManager, resourceManager, ProvenanceManager(), SmsSystem.system, schedulingPolicy)
      hdmBackEnd = new HDMServerBackend(blockManager, scheduler, planer, resourceManager, promiseManager, DependencyManager())
      log.warn(s"created new HDMServerBackend.")
    }
    hdmBackEnd
  }

  def getCompressor(): CompressionCodec ={
    if(BLOCK_COMPRESS_IN_TRANSPORTATION) DEFAULT_COMPRESSOR
    else null
  }

  def explain(hdm:HDM[_],parallelism:Int) = {
//    val hdmOpt = new FunctionFusion().optimize(hdm)
//    planer.plan(hdmOpt, parallelism)
    planer.plan(hdm, parallelism)
  }

  def compute(hdm:HDM[_], parallelism:Int):Future[HDM[_]] = {
//    addJob(hdm.id, explain(hdm, parallelism))
    submitJob(appName, version, hdm, parallelism)
  }

  def declareHdm(hdms:Seq[ParHDM[_,_]], declare:Boolean = true) = {
    SmsSystem.forwardLocalMsg(BLOCK_MANAGER_NAME, AddRefMsg(hdms, declare))
  }

  def addBlock(block:Block[_], declare:Boolean) = {
    SmsSystem.forwardLocalMsg(BLOCK_MANAGER_NAME, AddBlockMsg(block, declare))
  }

  def queryBlock(id:String, location:String) = {
    SmsSystem.forwardLocalMsg(BLOCK_MANAGER_NAME, QueryBlockMsg(Seq(id), location))
  }

  def removeBlock(id:String): Unit = {
    SmsSystem.forwardLocalMsg(BLOCK_MANAGER_NAME, RemoveBlockMsg(id))
  }

  def removeRef(id:String): Unit = {
    SmsSystem.forwardLocalMsg(BLOCK_MANAGER_NAME, RemoveRefMsg(id))
  }

  def addTask(task:Task[_,_]) = {
    SmsSystem.askAsync(leaderPath.get()+ "/"+CLUSTER_EXECUTOR_NAME, AddTaskMsg(task))
  }

  @Deprecated
  def submitTasks(appId:String, hdms:Seq[ParHDM[_,_]]): Future[ParHDM[_,_]] = {
    val rootPath =  SmsSystem.rootPath
    HDMContext.declareHdm(hdms)
    val promise = SmsSystem.askLocalMsg(JOB_RESULT_DISPATCHER, AddHDMsMsg(appId, hdms, rootPath + "/"+JOB_RESULT_DISPATCHER)) match {
      case Some(promise) => promise.asInstanceOf[Promise[ParHDM[_,_]]]
      case none => null
    }
    SmsSystem.askAsync(leaderPath.get()+ "/"+CLUSTER_EXECUTOR_NAME, AddHDMsMsg(appId, hdms, rootPath + "/"+JOB_RESULT_DISPATCHER))

    if(promise ne null) promise.future
    else throw new Exception("add job dispatcher failed.")
  }

  def submitJob(appName:String, version:String, hdm:HDM[_], parallel:Int): Future[HDM[_]] = {
    val rootPath =  SmsSystem.rootPath
//    HDMContext.declareHdm(Seq(hdm))
    val promise = SmsSystem.askLocalMsg(JOB_RESULT_DISPATCHER, RegisterPromiseMsg(appName, version, rootPath + "/"+JOB_RESULT_DISPATCHER)) match {
      case Some(promise) => promise.asInstanceOf[Promise[HDM[_]]]
      case none => null
    }
    //    val jobMsg = SubmitJobMsg(appId, hdm, rootPath + "/"+JOB_RESULT_DISPATCHER, parallel)
    val jobBytes = HDMContext.defaultSerializer.serialize(hdm).array
    val jobMsg = new SerializedJobMsg(appName, version, jobBytes, rootPath + "/"+JOB_RESULT_DISPATCHER, parallel)
    SmsSystem.askAsync(leaderPath.get()+ "/"+CLUSTER_EXECUTOR_NAME, jobMsg)
    if(promise ne null) promise.future
    else throw new Exception("add job dispatcher failed.")
  }

  def clean(appId:String): Unit = {
    //todo clean all the resources used by this application
  }


  def newClusterId():String = {
    UUID.randomUUID().toString
  }

  def newLocalId():String = {
    UUID.randomUUID().toString
  }

  /**
   *
   * @param hdm
   * @tparam K
   * @tparam V
   * @return
   */
 implicit def hdmToKVHDM[T:ClassTag, K:ClassTag, V: ClassTag](hdm:ParHDM[T, (K,V)] ): KvHDM[T,K,V] = {
    new KvHDM(hdm)
  }

  implicit def hdmToGroupedSeqHDM[K:ClassTag, V: ClassTag](hdm:ParHDM[_, (K, Iterable[V])] ): GroupedSeqHDM[K,V] = {
    new GroupedSeqHDM[K,V](hdm)
  }

}
