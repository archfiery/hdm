package org.nicta.wdy.hdm.scheduling

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.{ConcurrentHashMap, LinkedBlockingQueue, TimeUnit}

import akka.actor.ActorSystem
import akka.pattern.ask
import akka.util.Timeout
import org.nicta.wdy.hdm.coordinator.ClusterExecutor
import org.nicta.wdy.hdm.executor._
import org.nicta.wdy.hdm.functions.{ParUnionFunc, ParallelFunction}
import org.nicta.wdy.hdm.io.Path
import org.nicta.wdy.hdm.message.AddTaskMsg
import org.nicta.wdy.hdm.model._
import org.nicta.wdy.hdm.storage.{Computed, HDMBlockManager}
import org.nicta.wdy.hdm.utils.Logging

import scala.collection.mutable.ListBuffer
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.reflect.ClassTag

/**
 * Created by tiantian on 24/08/15.
 */
class DefScheduler(val blockManager:HDMBlockManager,
                   val promiseManager:PromiseManager,
                   val resourceManager: ResourceManager,
                   val actorSys:ActorSystem)(implicit val executorService:ExecutionContext) extends Scheduler with Logging{

  implicit val timeout = Timeout(5L, TimeUnit.MINUTES)

//  private val workingSize = new Semaphore(0)

  private val isRunning = new AtomicBoolean(false)

  private val taskQueue = new LinkedBlockingQueue[Task[_, _]]()

  private val appBuffer: java.util.Map[String, ListBuffer[Task[_, _]]] = new ConcurrentHashMap[String, ListBuffer[Task[_, _]]]()



  override def startup(): Unit = {
    isRunning.set(true)
    while (isRunning.get) {
      val task = taskQueue.take()
      log.info(s"A task has been scheduling: [${task.taskId + "_" + task.func.toString}}] ")
      schedule(task)
    }
  }


  private def schedule[I: ClassTag, R: ClassTag](task: Task[I, R]): Promise[HDM[I, R]] = {
    val promise = promiseManager.getPromise(task.taskId).asInstanceOf[Promise[HDM[I, R]]]
    val blks = task.input.map(h => blockManager.getRef(h.id)).flatMap(_.blocks)

    if (task.func.isInstanceOf[ParUnionFunc[_]]) {
      //copy input blocks directly
      taskSucceeded(task.appId, task.taskId, task.func.toString, blks)
    } else {
      // run job, assign to remote or local node to execute this task
      val inputDDMs = blks.map(bl => blockManager.getRef(Path(bl).name))
      val updatedTask = task.copy(input = inputDDMs.asInstanceOf[Seq[HDM[_, I]]])
      resourceManager.require(1)
      var workerPath = findPreferredWorker(updatedTask)
      while (workerPath == null || workerPath == ""){ // wait for available workers
        log.info(s"no worker available for task[${task.taskId + "_" + task.func.toString}}] ")
        workerPath = findPreferredWorker(updatedTask)
        Thread.sleep(50)
      }
      log.info(s"Task has been assigned to: [$workerPath] [${task.taskId + "_" + task.func.toString}}] ")
      scheduleTask(updatedTask, workerPath)
    }
    log.info(s"A task has been scheduled: [${task.taskId + "_" + task.func.toString}}] ")
    promise
  }

  override def stop(): Unit = {
    isRunning.set(false)
  }

  override def init(): Unit = {
    isRunning.set(false)
    taskQueue.clear()
    val totalSlots = resourceManager.getAllResources().map(_._2.get()).sum
    resourceManager.release(totalSlots)
  }

  override def addTask[I, R](task: Task[I, R]): Promise[HDM[I, R]] = {
    val promise = promiseManager.createPromise[HDM[I, R]](task.taskId)
    if (!appBuffer.containsKey(task.appId))
      appBuffer.put(task.appId, new ListBuffer[Task[_, _]])
    val lst = appBuffer.get(task.appId)
    lst += task
    triggerTasks(task.appId) //todo replace with planner.nextPlanning
    promise
  }

  override def submitJob(appId: String, hdms: Seq[HDM[_, _]]): Future[HDM[_, _]] = {
    hdms.map { h =>
      blockManager.addRef(h)
      val task = Task(appId = appId,
        taskId = h.id,
        input = h.children.asInstanceOf[Seq[HDM[_, h.inType.type]]],
        func = h.func.asInstanceOf[ParallelFunction[h.inType.type, h.outType.type]],
        dep = h.dependency,
        partitioner = h.partitioner.asInstanceOf[Partitioner[h.outType.type ]])
      addTask(task)
    }.last.future
  }


  override def taskSucceeded(appId: String, taskId: String, func: String, blks: Seq[String]): Unit = {
    val ref = blockManager.getRef(taskId) match {
      case dfm: DFM[_ , _] => dfm.copy(blocks = blks, state = Computed)
      case ddm: DDM[_ , _] => ddm.copy(state = Computed)
    }
    blockManager.addRef(ref)
    HDMContext.declareHdm(Seq(ref))
    log.info(s"A task is succeeded : [${taskId + "_" + func}}] ")
    val promise = promiseManager.removePromise(taskId).asInstanceOf[Promise[HDM[_, _]]]
    if (promise != null && !promise.isCompleted ){
      promise.success(ref.asInstanceOf[HDM[_, _]])
      log.info(s"A promise is triggered for : [${taskId + "_" + func}}] ")
    }
    else if (promise eq null) {
      log.warn(s"no matched promise found: ${taskId}")
    }
    triggerTasks(appId)
  }


  override protected def scheduleTask[I: ClassTag, R: ClassTag](task: Task[I, R], workerPath:String): Promise[HDM[I, R]] = {
    val promise = promiseManager.getPromise(task.taskId).asInstanceOf[Promise[HDM[I, R]]]
    log.info(s"Task has been assigned to: [$workerPath] [${task.taskId + "__" + task.func.toString}}] ")
    val future = if (Path.isLocal(workerPath)) ClusterExecutor.runTaskSynconized(task)
    else runRemoteTask(workerPath.toString, task)

    log.info(s"A task has been scheduled: [${task.taskId + "_" + task.func.toString}}] ")
    promise
  }

  //todo wrap as scheduling policy
  private def findPreferredWorker(task: Task[_, _]): String = try {

    //    val inputLocations = task.input.flatMap(hdm => HDMBlockManager().getRef(hdm.id).blocks).map(Path(_))
    val inputLocations = task.input.flatMap { hdm =>
      val nhdm = HDMBlockManager().getRef(hdm.id)
      if (nhdm.preferLocation == null)
        nhdm.blocks.map(Path(_))
      else Seq(nhdm.preferLocation)
    }
    log.info(s"Block prefered input locations:${inputLocations.mkString(",")}")
    val candidates =
      if (task.dep == OneToN || task.dep == OneToOne) Scheduler.getAllAvailableWorkers(resourceManager.getAllResources()) // for parallel tasks
      else Scheduler.getFreestWorkers(resourceManager.getAllResources()) // for shuffle tasks

    //find closest worker from candidates
    if (candidates.size > 0) {
      val workerPath = Path.findClosestLocation(candidates, inputLocations).toString
      resourceManager.decResource(workerPath, 1) // reduce slots of worker
      workerPath
    } else ""
  } catch {
    case e: Throwable => log.error(s"failed to find worker for task:${task.taskId}"); ""
  }

  private def runRemoteTask[I: ClassTag, R: ClassTag](workerPath: String, task: Task[I, R]): Future[Seq[String]] = {
    val future = (actorSys.actorSelection(workerPath) ? AddTaskMsg(task)).mapTo[Seq[String]]
    future
  }



  /**
   * find next tasks which are available to be executed
   * @param appId
   */
  private def triggerTasks(appId: String) = { //todo replace with planner.findNextTask
    if (appBuffer.containsKey(appId)) {
      val seq = appBuffer.get(appId)
      synchronized {
        if (!seq.isEmpty) {
          //find tasks that all inputs have been computed
          val tasks = seq.filter(t =>
            if (t.input eq null) false
            else try {
              t.input.forall{in =>
                val hdm = HDMBlockManager().getRef(in.id)
                if(hdm ne null)
                  hdm.state.eq(Computed)
                else false
              }
            } catch {
              case ex: Throwable => log.error(s"Got exception on ${t}"); false
            }
          )
          if ((tasks ne null) && !tasks.isEmpty) {
            seq --= tasks
            tasks.foreach(taskQueue.put(_))
            log.info(s"New tasks have has been triggered: [${tasks.map(t => (t.taskId, t.func)) mkString (",")}}] ")
          }
        }
      }
    }

  }
}