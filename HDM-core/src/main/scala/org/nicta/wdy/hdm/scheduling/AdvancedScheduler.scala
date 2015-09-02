package org.nicta.wdy.hdm.scheduling

import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.{ConcurrentHashMap, LinkedBlockingQueue, TimeUnit}
import java.util.concurrent.atomic.AtomicBoolean


import scala.collection.mutable.ListBuffer
import scala.concurrent.{Future, Promise, ExecutionContext}
import scala.reflect.ClassTag

import akka.actor.ActorSystem
import akka.util.Timeout
import akka.pattern._

import org.nicta.wdy.hdm.coordinator.ClusterExecutor
import org.nicta.wdy.hdm.executor._
import org.nicta.wdy.hdm.functions.{ParUnionFunc, ParallelFunction}
import org.nicta.wdy.hdm.io.Path
import org.nicta.wdy.hdm.message.AddTaskMsg
import org.nicta.wdy.hdm.model._
import org.nicta.wdy.hdm.storage.{Computed, HDMBlockManager}
import org.nicta.wdy.hdm.utils.Logging


/**
 * Created by tiantian on 1/09/15.
 */
class AdvancedScheduler(val blockManager:HDMBlockManager,
                        val promiseManager:PromiseManager,
                        val resourceManager: ResourceManager,
                        val actorSys:ActorSystem)(implicit val executorService:ExecutionContext) extends Scheduler with Logging{

  implicit val timeout = Timeout(5L, TimeUnit.MINUTES)

  //  private val workingSize = new Semaphore(0)

  private val isRunning = new AtomicBoolean(false)

  private val nonEmptyLock = new ReentrantLock()

  private val taskQueue = new LinkedBlockingQueue[Task[_, _]]()

  private val appBuffer: java.util.Map[String, ListBuffer[Task[_, _]]] = new ConcurrentHashMap[String, ListBuffer[Task[_, _]]]()

  val schedulingPolicy:SchedulingPolicy = new MinMinScheduling


  override def startup(): Unit = {
    isRunning.set(true)
    while (isRunning.get) {
      import scala.collection.JavaConversions._
      if(taskQueue.isEmpty)
        nonEmptyLock.wait()
      val candidates = Scheduler.getAllAvailableWorkers(resourceManager.getAllResources())
      val tasks = taskQueue.iterator().map { task =>
        val inputLocations = HDMBlockManager().getLocations(task.input.map(_.id))
        SchedulingTask(task.taskId, inputLocations, null, 0, task.dep)
      }.toSeq
      val plans = schedulingPolicy.plan(tasks, candidates, 1F, 10F ,20F)
      val scheduledTasks = taskQueue.filter(t => plans.contains(t.taskId)).map(t => t.taskId -> t).toMap[String,Task[_,_]]
      plans.foreach(tuple => {
        scheduledTasks.get(tuple._1) match {
          case Some(task) =>
            taskQueue.remove(task)
            scheduleTask(task, tuple._2)
          case None => //do nothing
        }
      })
    }
  }


  override def stop(): Unit = {
    isRunning.set(false)
  }

  override def init(): Unit = {
    isRunning.set(false)
    taskQueue.clear()
    nonEmptyLock.notifyAll()
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
    val blks = task.input.map(h => blockManager.getRef(h.id)).flatMap(_.blocks)

    if (task.func.isInstanceOf[ParUnionFunc[_]]) {
      //copy input blocks directly
      taskSucceeded(task.appId, task.taskId, task.func.toString, blks)
    } else {
      // run job, assign to remote or local node to execute this task
      val inputDDMs = blks.map(bl => blockManager.getRef(Path(bl).name))
      val updatedTask = task.copy(input = inputDDMs.asInstanceOf[Seq[HDM[_, I]]])
      resourceManager.require(1)
      resourceManager.decResource(workerPath, 1)
      log.info(s"Task has been assigned to: [$workerPath] [${task.taskId + "_" + task.func.toString}}] ")
      val future = if (Path.isLocal(workerPath)) ClusterExecutor.runTaskSynconized(updatedTask)
      else runRemoteTask(workerPath, updatedTask)

    }
    log.info(s"A task has been scheduled: [${task.taskId + "_" + task.func.toString}}] ")
    promise
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
            nonEmptyLock.notify()
            log.info(s"New tasks have has been triggered: [${tasks.map(t => (t.taskId, t.func)) mkString (",")}}] ")
          }
        }
      }
    }

  }
}
