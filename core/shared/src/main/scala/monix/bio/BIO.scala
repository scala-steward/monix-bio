/*
 * Copyright (c) 2019-2019 by The Monix Project Developers.
 * See the project homepage at: https://monix.io
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package monix.bio

import cats.Parallel
import cats.effect.{CancelToken, Clock, ContextShift, ExitCase, Timer, Fiber => _}
import monix.bio.compat.internal.newBuilder
import monix.bio.instances._
import monix.bio.internal.TaskRunLoop.WrappedException
import monix.bio.internal._
import monix.execution.ExecutionModel.AlwaysAsyncExecution
import monix.execution.annotations.{UnsafeBecauseBlocking, UnsafeBecauseImpure}
import monix.execution.compat.BuildFrom
import monix.execution.internal.Platform
import monix.execution.internal.Platform.fusionMaxStackDepth
import monix.execution.misc.Local
import monix.execution.schedulers.{CanBlock, TracingScheduler, TrampolinedRunnable}
import monix.execution.{Callback, Scheduler, _}

import scala.annotation.unchecked.{uncheckedVariance => uV}
import scala.concurrent.duration.{Duration, FiniteDuration, TimeUnit}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

sealed abstract class BIO[+E, +A] extends Serializable {
  import BIO._

  /** Triggers the asynchronous execution, returning a cancelable
    * [[monix.execution.CancelableFuture CancelableFuture]] that can
    * be awaited for the final result or canceled.
    *
    * Note that without invoking `runAsync` on a `Task`, nothing
    * gets evaluated, as a `Task` has lazy behavior.
    *
    * {{{
    *   import scala.concurrent.duration._
    *   // A Scheduler is needed for executing tasks via `runAsync`
    *   import monix.execution.Scheduler.Implicits.global
    *
    *   // Nothing executes yet
    *   val task: Task[String] =
    *     for {
    *       _ <- Task.sleep(3.seconds)
    *       r <- Task { println("Executing..."); "Hello!" }
    *     } yield r
    *
    *
    *   // Triggering the task's execution:
    *   val f = task.runToFuture
    *
    *   // Or in case we change our mind
    *   f.cancel()
    * }}}
    *
    * $unsafeRun
    *
    * BAD CODE:
    * {{{
    *   import monix.execution.CancelableFuture
    *   import scala.concurrent.Await
    *
    *   // ANTI-PATTERN 1: Unnecessary side effects
    *   def increment1(sample: Task[Int]): CancelableFuture[Int] = {
    *     // No reason to trigger `runAsync` for this operation
    *     sample.runToFuture.map(_ + 1)
    *   }
    *
    *   // ANTI-PATTERN 2: blocking threads makes it worse than (1)
    *   def increment2(sample: Task[Int]): Int = {
    *     // Blocking threads is totally unnecessary
    *     val x = Await.result(sample.runToFuture, 5.seconds)
    *     x + 1
    *   }
    *
    *   // ANTI-PATTERN 3: this is even WORSE than (2)!
    *   def increment3(sample: Task[Int]): Task[Int] = {
    *     // Triggering side-effects, but misleading users/readers
    *     // into thinking this function is pure via the return type
    *     Task.fromFuture(sample.runToFuture.map(_ + 1))
    *   }
    * }}}
    *
    * Instead prefer the pure versions. `Task` has its own [[map]],
    * [[flatMap]], [[onErrorHandleWith]] or [[bracketCase]], which
    * are really powerful and can allow you to operate on a task
    * in however way you like without escaping Task's context and
    * triggering unwanted side-effects.
    *
    * @param s $schedulerDesc
    * @return $runAsyncToFutureReturn
    */
  @UnsafeBecauseImpure
  final def runToFuture[E1 >: E](implicit s: Scheduler): CancelableFuture[Either[E1, A]] =
    runToFutureOpt(s, BIO.defaultOptions)

  /** Triggers the asynchronous execution, much like normal [[runToFuture]],
    * but includes the ability to specify [[monix.bio.BIO.Options Options]]
    * that can modify the behavior of the run-loop.
    *
    * This is the configurable version of [[runToFuture]].
    * It allows you to specify options such as:
    *
    *  - enabling support for [[TaskLocal]]
    *  - disabling auto-cancelable run-loops
    *
    * See [[BIO.Options]]. Example:
    *
    * {{{
    *   import monix.execution.Scheduler.Implicits.global
    *
    *   val task =
    *     for {
    *       local <- TaskLocal(0)
    *       _     <- local.write(100)
    *       _     <- Task.shift
    *       value <- local.read
    *     } yield value
    *
    *   // We need to activate support of TaskLocal via:
    *   implicit val opts = Task.defaultOptions.enableLocalContextPropagation
    *   // Actual execution that depends on these custom options:
    *   task.runToFutureOpt
    * }}}
    *
    * $unsafeRun
    *
    * PLEASE READ the advice on anti-patterns at [[runToFuture]].
    *
    * @param s $schedulerDesc
    * @param opts $optionsDesc
    * @return $runAsyncToFutureReturn
    */
  @UnsafeBecauseImpure
  def runToFutureOpt[E1 >: E](implicit s: Scheduler, opts: Options): CancelableFuture[Either[E1, A]] = {
    val opts2 = opts.withSchedulerFeatures
    Local
      .bindCurrentIf(opts2.localContextPropagation) {
        TaskRunLoop.startFuture(this, s, opts2)
      }
  }

  /** Triggers the asynchronous execution, with a provided callback
    * that's going to be called at some point in the future with
    * the final result.
    *
    * Note that without invoking `runAsync` on a `Task`, nothing
    * gets evaluated, as a `Task` has lazy behavior.
    *
    * {{{
    *   import scala.concurrent.duration._
    *   // A Scheduler is needed for executing tasks via `runAsync`
    *   import monix.execution.Scheduler.Implicits.global
    *
    *   // Nothing executes yet
    *   val task: Task[String] =
    *     for {
    *       _ <- Task.sleep(3.seconds)
    *       r <- Task { println("Executing..."); "Hello!" }
    *     } yield r
    *
    *
    *   // Triggering the task's execution:
    *   val f = task.runAsync {
    *     case Right(str: String) =>
    *       println(s"Received: $$str")
    *     case Left(e) =>
    *       global.reportFailure(e)
    *   }
    *
    *   // Or in case we change our mind
    *   f.cancel()
    * }}}
    *
    * $callbackDesc
    *
    * Example, equivalent to the above:
    *
    * {{{
    *   import monix.execution.Callback
    *
    *   task.runAsync(new Callback[Throwable, String] {
    *     def onSuccess(str: String) =
    *       println(s"Received: $$str")
    *     def onError(e: Throwable) =
    *       global.reportFailure(e)
    *   })
    * }}}
    *
    * Example equivalent with [[runAsyncAndForget]]:
    *
    * {{{
    *   task.runAsync(Callback.empty)
    * }}}
    *
    * Completing a [[scala.concurrent.Promise]]:
    *
    * {{{
    *   import scala.concurrent.Promise
    *
    *   val p = Promise[String]()
    *   task.runAsync(Callback.fromPromise(p))
    * }}}
    *
    * $unsafeRun
    *
    * @param cb $callbackParamDesc
    * @param s $schedulerDesc
    * @return $cancelableDesc
    */
  @UnsafeBecauseImpure
  final def runAsync(cb: Either[Either[Throwable, E], A] => Unit)(implicit s: Scheduler): Cancelable =
    runAsyncOpt(cb)(s, BIO.defaultOptions)

  /** Triggers the asynchronous execution, much like normal [[runAsync]], but
    * includes the ability to specify [[monix.bio.BIO.Options BIO.Options]]
    * that can modify the behavior of the run-loop.
    *
    * This allows you to specify options such as:
    *
    *  - enabling support for [[TaskLocal]]
    *  - disabling auto-cancelable run-loops
    *
    * Example:
    * {{{
    *   import monix.execution.Scheduler.Implicits.global
    *
    *   val task =
    *     for {
    *       local <- TaskLocal(0)
    *       _     <- local.write(100)
    *       _     <- Task.shift
    *       value <- local.read
    *     } yield value
    *
    *   // We need to activate support of TaskLocal via:
    *   implicit val opts = Task.defaultOptions.enableLocalContextPropagation
    *
    *   // Actual execution that depends on these custom options:
    *   task.runAsyncOpt {
    *     case Right(value) =>
    *       println(s"Received: $$value")
    *     case Left(e) =>
    *       global.reportFailure(e)
    *   }
    * }}}
    *
    * See [[BIO.Options]].
    *
    * $callbackDesc
    *
    * $unsafeRun
    *
    * @param cb $callbackParamDesc
    * @param s $schedulerDesc
    * @param opts $optionsDesc
    * @return $cancelableDesc
    */
  @UnsafeBecauseImpure
  def runAsyncOpt(cb: Either[Either[Throwable, E], A] => Unit)(implicit s: Scheduler, opts: Options): Cancelable = {
    val opts2 = opts.withSchedulerFeatures
    Local.bindCurrentIf(opts2.localContextPropagation) {
      UnsafeCancelUtils.taskToCancelable(runAsyncOptF(cb)(s, opts2))
    }
  }

  /** Triggers the asynchronous execution, returning a `Task[Unit]`
    * (aliased to `CancelToken[Task]` in Cats-Effect) which can
    * cancel the running computation.
    *
    * This is the more potent version of [[runAsync]],
    * because the returned cancelation token is a `Task[Unit]` that
    * can be used to back-pressure on the result of the cancellation
    * token, in case the finalizers are specified as asynchronous
    * actions that are expensive to complete.
    *
    * Example:
    * {{{
    *   import scala.concurrent.duration._
    *
    *   val task = Task("Hello!").bracketCase { str =>
    *     Task(println(str))
    *   } { (_, exitCode) =>
    *     // Finalization
    *     Task(println(s"Finished via exit code: $$exitCode"))
    *       .delayExecution(3.seconds)
    *   }
    * }}}
    *
    * In this example we have a task with a registered finalizer
    * (via [[bracketCase]]) that takes 3 whole seconds to finish.
    * Via normal `runAsync` the returned cancelation token has no
    * capability to wait for its completion.
    *
    * {{{
    *   import monix.execution.Callback
    *   import monix.execution.Scheduler.Implicits.global
    *
    *   val cancel = task.runAsyncF(Callback.empty)
    *
    *   // Triggering `cancel` and we can wait for its completion
    *   for (_ <- cancel.runToFuture) {
    *     // Takes 3 seconds to print
    *     println("Resources were released!")
    *   }
    * }}}
    *
    * WARN: back-pressuring on the completion of finalizers is not
    * always a good idea. Avoid it if you can.
    *
    * $callbackDesc
    *
    * $unsafeRun
    *
    * NOTE: the `F` suffix comes from `F[_]`, highlighting our usage
    * of `CancelToken[F]` to return a `Task[Unit]`, instead of a
    * plain and side effectful `Cancelable` object.
    *
    * @param cb $callbackParamDesc
    * @param s $schedulerDesc
    * @return $cancelTokenDesc
    */
  @UnsafeBecauseImpure
  final def runAsyncF[E1 >: E](cb: Either[Either[Throwable, E1], A] => Unit)(
    implicit s: Scheduler): CancelToken[BIO[E1, ?]] =
    runAsyncOptF(cb)(s, BIO.defaultOptions)

  /** Triggers the asynchronous execution, much like normal [[runAsyncF]], but
    * includes the ability to specify [[monix.bio.Task.Options Task.Options]]
    * that can modify the behavior of the run-loop.
    *
    * This allows you to specify options such as:
    *
    *  - enabling support for [[TaskLocal]]
    *  - disabling auto-cancelable run-loops
    *
    * See the description of [[runToFutureOpt]] for an example.
    *
    * The returned cancelation token is a `Task[Unit]` that
    * can be used to back-pressure on the result of the cancellation
    * token, in case the finalizers are specified as asynchronous
    * actions that are expensive to complete.
    *
    * See the description of [[runAsyncF]] for an example.
    *
    * WARN: back-pressuring on the completion of finalizers is not
    * always a good idea. Avoid it if you can.
    *
    * $callbackDesc
    *
    * $unsafeRun
    *
    * NOTE: the `F` suffix comes from `F[_]`, highlighting our usage
    * of `CancelToken[F]` to return a `Task[Unit]`, instead of a
    * plain and side effectful `Cancelable` object.
    *
    * @param cb $callbackParamDesc
    * @param s $schedulerDesc
    * @param opts $optionsDesc
    * @return $cancelTokenDesc
    */
  @UnsafeBecauseImpure
  def runAsyncOptF[E1 >: E](
    cb: Either[Either[Throwable, E1], A] => Unit)(implicit s: Scheduler, opts: Options): CancelToken[BIO[E1, ?]] = {
    val opts2 = opts.withSchedulerFeatures
    Local.bindCurrentIf(opts2.localContextPropagation) {
      TaskRunLoop
        .startLight(this, s, opts2, BiCallback.fromAttempt(cb).asInstanceOf[BiCallback[Any, A]]) // TODO: should it be E,A?
    }
  }

  /** Returns a failed projection of this task.
    *
    * The failed projection is a `Task` holding a value of type `Throwable`,
    * emitting the error yielded by the source, in case the source fails,
    * otherwise if the source succeeds the result will fail with a
    * `NoSuchElementException`.
    */
  final def failed: UIO[E] =
    FlatMap(this, BIO.Failed.asInstanceOf[StackFrame[E, A, UIO[E]]])

  /** Creates a new Task by applying a function to the successful result
    * of the source Task, and returns a task equivalent to the result
    * of the function.
    */
  final def flatMap[E1 >: E, B](f: A => BIO[E1, B]): BIO[E1, B] =
    FlatMap(this, f)

  /** Given a source Task that emits another Task, this function
    * flattens the result, returning a Task equivalent to the emitted
    * Task by the source.
    */
  final def flatten[E1 >: E, B](implicit ev: A <:< BIO[E1, B]): BIO[E1, B] =
    flatMap(a => a)

  /** Returns a new task that upon evaluation will execute the given
    * function for the generated element, transforming the source into
    * a `Task[Unit]`.
    *
    * Similar in spirit with normal [[foreach]], but lazy, as
    * obviously nothing gets executed at this point.
    */
  final def foreachL(f: A => Unit): BIO[E, Unit] =
    this.map { a =>
      f(a); ()
    }

  /** Triggers the evaluation of the source, executing the given
    * function for the generated element.
    *
    * The application of this function has strict behavior, as the
    * task is immediately executed.
    *
    * Exceptions in `f` are reported using provided (implicit) Scheduler
    */
  @UnsafeBecauseImpure
  final def foreach(f: Either[E, A] => Unit)(implicit s: Scheduler): Unit =
    runToFuture.foreach(f)

  /** Returns a new `Task` that repeatedly executes the source as long
    * as it continues to succeed. It never produces a terminal value.
    *
    * Example:
    *
    * {{{
    *   import scala.concurrent.duration._
    *
    *   Task.eval(println("Tick!"))
    *     .delayExecution(1.second)
    *     .loopForever
    * }}}
    *
    */
  final def loopForever: BIO[E, Nothing] =
    flatMap(_ => this.loopForever)

  /** Returns a new `Task` that applies the mapping function to
    * the element emitted by the source.
    *
    * Can be used for specifying a (lazy) transformation to the result
    * of the source.
    *
    * This equivalence with [[flatMap]] always holds:
    *
    * `fa.map(f) <-> fa.flatMap(x => Task.pure(f(x)))`
    */
  final def map[B](f: A => B): BIO[E, B] =
    this match {
      case Map(source, g, index) =>
        // Allowed to do a fixed number of map operations fused before
        // resetting the counter in order to avoid stack overflows;
        // See `monix.execution.internal.Platform` for details.
        if (index != fusionMaxStackDepth) Map(source, g.andThen(f), index + 1)
        else Map(this, f, 0)
      case _ =>
        Map(this, f, 0)
    }

  /** Mirrors the given source `Task`, but upon execution ensure
    * that evaluation forks into a separate (logical) thread.
    *
    * The [[monix.execution.Scheduler Scheduler]] used will be
    * the one that is used to start the run-loop in
    * [[Task.runAsync]] or [[Task.runToFuture]].
    *
    * This operation is equivalent with:
    *
    * {{{
    *   Task.shift.flatMap(_ => Task(1 + 1))
    *
    *   // ... or ...
    *
    *   import cats.syntax.all._
    *
    *   Task.shift *> Task(1 + 1)
    * }}}
    *
    * The [[monix.execution.Scheduler Scheduler]] used for scheduling
    * the async boundary will be the default, meaning the one used to
    * start the run-loop in `runAsync`.
    */
  final def executeAsync: BIO[E, A] =
    BIO.shift.flatMap(_ => this)

  /** Returns a new task that will execute the source with a different
    * [[monix.execution.ExecutionModel ExecutionModel]].
    *
    * This allows fine-tuning the options injected by the scheduler
    * locally. Example:
    *
    * {{{
    *   import monix.execution.ExecutionModel.AlwaysAsyncExecution
    *   Task(1 + 1).executeWithModel(AlwaysAsyncExecution)
    * }}}
    *
    * @param em is the
    *        [[monix.execution.ExecutionModel ExecutionModel]]
    *        with which the source will get evaluated on `runAsync`
    */
  final def executeWithModel(em: ExecutionModel): BIO[E, A] =
    TaskExecuteWithModel(this, em)

  /** Returns a new task that will execute the source with a different
    * set of [[BIO.Options Options]].
    *
    * This allows fine-tuning the default options. Example:
    *
    * {{{
    *   Task(1 + 1).executeWithOptions(_.enableAutoCancelableRunLoops)
    * }}}
    *
    * @param f is a function that takes the source's current set of
    *          [[BIO.Options options]] and returns a modified set of
    *          options that will be used to execute the source
    *          upon `runAsync`
    */
  final def executeWithOptions(f: Options => Options): BIO[E, A] =
    TaskExecuteWithOptions(this, f)

  /** Triggers the asynchronous execution of the source task
    * in a "fire and forget" fashion.
    *
    * Starts the execution of the task, but discards any result
    * generated asynchronously and doesn't return any cancelable
    * tokens either. This affords some optimizations — for example
    * the underlying run-loop doesn't need to worry about
    * cancelation. Also the call-site is more clear in intent.
    *
    * Example:
    * {{{
    *   import monix.execution.Scheduler.Implicits.global
    *
    *   val task = Task(println("Hello!"))
    *
    *   // We don't care about the result, we don't care about the
    *   // cancellation token, we just want this thing to run:
    *   task.runAsyncAndForget
    * }}}
    *
    * $unsafeRun
    *
    * @param s $schedulerDesc
    */
  @UnsafeBecauseImpure
  final def runAsyncAndForget(implicit s: Scheduler): Unit =
    runAsyncAndForgetOpt(s, BIO.defaultOptions)

  /** Triggers the asynchronous execution in a "fire and forget"
    * fashion, like normal [[runAsyncAndForget]], but includes the
    * ability to specify [[monix.bio.BIO.Options TasWRYYY.Options]] that
    * can modify the behavior of the run-loop.
    *
    * This allows you to specify options such as:
    *
    *  - enabling support for [[TaskLocal]]
    *  - disabling auto-cancelable run-loops
    *
    * See the description of [[runAsyncOpt]] for an example of customizing the
    * default [[BIO.Options]].
    *
    * See the description of [[runAsyncAndForget]] for an example
    * of running as a "fire and forget".
    *
    * $unsafeRun
    *
    * @param s $schedulerDesc
    * @param opts $optionsDesc
    */
  @UnsafeBecauseImpure
  def runAsyncAndForgetOpt(implicit s: Scheduler, opts: BIO.Options): Unit =
    runAsyncUncancelableOpt(BiCallback.empty)(s, opts)

  /** Triggers the asynchronous execution of the source task,
    * but runs it in uncancelable mode.
    *
    * This is an optimization over plain [[runAsync]] or [[runAsyncF]] that
    * doesn't give you a cancellation token for cancelling the task. The runtime
    * can thus not worry about keeping state related to cancellation when
    * evaluating it.
    *
    * {{{
    *   import scala.concurrent.duration._
    *   import monix.execution.Scheduler.Implicits.global
    *
    *   val task: Task[String] =
    *     for {
    *       _ <- Task.sleep(3.seconds)
    *       r <- Task { println("Executing..."); "Hello!" }
    *     } yield r
    *
    *   // Triggering the task's execution, without receiving any
    *   // cancelation tokens
    *   task.runAsyncUncancelable {
    *     case Right(str) =>
    *       println(s"Received: $$str")
    *     case Left(e) =>
    *       global.reportFailure(e)
    *   }
    * }}}
    *
    * $callbackDesc
    *
    * $unsafeRun
    *
    * @param s $schedulerDesc
    */
  @UnsafeBecauseImpure
  final def runAsyncUncancelable(cb: Either[Either[Throwable, E], A] => Unit)(implicit s: Scheduler): Unit =
    runAsyncUncancelableOpt(cb)(s, BIO.defaultOptions)

  /** Triggers the asynchronous execution in uncancelable mode,
    * like [[runAsyncUncancelable]], but includes the ability to
    * specify [[monix.bio.BIO.Options BIO.Options]] that can modify
    * the behavior of the run-loop.
    *
    * This allows you to specify options such as:
    *
    *  - enabling support for [[TaskLocal]]
    *  - disabling auto-cancelable run-loops
    *
    * See the description of [[runAsyncOpt]] for an example of customizing the
    * default [[BIO.Options]].
    *
    * This is an optimization over plain [[runAsyncOpt]] or
    * [[runAsyncOptF]] that doesn't give you a cancellation token for
    * cancelling the task. The runtime can thus not worry about
    * keeping state related to cancellation when evaluating it.
    *
    * $callbackDesc
    *
    * @param s $schedulerDesc
    * @param opts $optionsDesc
    */
  @UnsafeBecauseImpure
  def runAsyncUncancelableOpt(
    cb: Either[Either[Throwable, E], A] => Unit)(implicit s: Scheduler, opts: BIO.Options): Unit = {
    val opts2 = opts.withSchedulerFeatures
    Local.bindCurrentIf(opts2.localContextPropagation) {
      TaskRunLoop
        .startLight(this, s, opts2, BiCallback.fromAttempt(cb).asInstanceOf[BiCallback[Any, A]], isCancelable = false)
    }
  }

  /** Executes the source until completion, or until the first async
    * boundary, whichever comes first.
    *
    * This operation is mean to be compliant with
    * `cats.effect.Effect.runSyncStep`, but without suspending the
    * evaluation in `IO`.
    *
    * WARNING: This method is a partial function, throwing exceptions
    * in case errors happen immediately (synchronously).
    *
    * Usage sample:
    * {{{
    *   import monix.execution.Scheduler.Implicits.global
    *   import scala.util._
    *   import scala.util.control.NonFatal
    *
    *   try Task(42).runSyncStep match {
    *     case Right(a) => println("Success: " + a)
    *     case Left(task) =>
    *       task.runToFuture.onComplete {
    *         case Success(a) => println("Async success: " + a)
    *         case Failure(e) => println("Async error: " + e)
    *       }
    *   } catch {
    *     case NonFatal(e) =>
    *       println("Error: " + e)
    *   }
    * }}}
    *
    * Obviously the purpose of this method is to be used for
    * optimizations.
    *
    * $unsafeRun
    *
    * @see [[runSyncUnsafe]], the blocking execution mode that can
    *      only work on top of the JVM.
    *
    * @param s $schedulerDesc
    * @return $runSyncStepReturn
    */
  @UnsafeBecauseImpure
  final def runSyncStep(implicit s: Scheduler): Either[BIO[E, A], A] =
    runSyncStepOpt(s, defaultOptions)

  /** A variant of [[runSyncStep]] that takes an implicit
    * [[BIO.Options]] from the current scope.
    *
    * This helps in tuning the evaluation model of task.
    *
    * $unsafeRun
    *
    * @see [[runSyncStep]]
    * @param s $schedulerDesc
    * @param opts $optionsDesc
    * @return $runSyncStepReturn
    */
  @UnsafeBecauseImpure
  final def runSyncStepOpt(implicit s: Scheduler, opts: Options): Either[BIO[E, A], A] = {
    val opts2 = opts.withSchedulerFeatures
    Local.bindCurrentIf(opts2.localContextPropagation) {
      TaskRunLoop.startStep(this, s, opts2)
    }
  }

  /** Evaluates the source task synchronously and returns the result
    * immediately or blocks the underlying thread until the result is
    * ready.
    *
    * '''WARNING:''' blocking operations are unsafe and incredibly
    * error prone on top of the JVM. It's a good practice to not block
    * any threads and use the asynchronous `runAsync` methods instead.
    *
    * In general prefer to use the asynchronous [[BIO.runAsync]] or
    * [[BIO.runToFuture]] and to structure your logic around asynchronous
    * actions in a non-blocking way. But in case you're blocking only once, in
    * `main`, at the "edge of the world" so to speak, then it's OK.
    *
    * Sample:
    * {{{
    *   import monix.execution.Scheduler.Implicits.global
    *   import scala.concurrent.duration._
    *
    *   Task(42).runSyncUnsafe(3.seconds)
    * }}}
    *
    * This is equivalent with:
    * {{{
    *   import scala.concurrent.Await
    *
    *   Await.result[Int](Task(42).runToFuture, 3.seconds)
    * }}}
    *
    * Some implementation details:
    *
    *  - blocking the underlying thread is done by triggering Scala's
    *    `BlockingContext` (`scala.concurrent.blocking`), just like
    *    Scala's `Await.result`
    *  - the `timeout` is mandatory, just like when using Scala's
    *    `Await.result`, in order to make the caller aware that the
    *    operation is dangerous and that setting a `timeout` is good
    *    practice
    *  - the loop starts in an execution mode that ignores
    *    [[monix.execution.ExecutionModel.BatchedExecution BatchedExecution]] or
    *    [[monix.execution.ExecutionModel.AlwaysAsyncExecution AlwaysAsyncExecution]],
    *    until the first asynchronous boundary. This is because we want to block
    *    the underlying thread for the result, in which case preserving
    *    fairness by forcing (batched) async boundaries doesn't do us any good,
    *    quite the contrary, the underlying thread being stuck until the result
    *    is available or until the timeout exception gets triggered.
    *
    * Not supported on top of JavaScript engines and trying to use it
    * with Scala.js will trigger a compile time error.
    *
    * For optimizations on top of JavaScript you can use
    * [[runSyncStep]] instead.
    *
    * $unsafeRun
    *
    * @param timeout $runSyncUnsafeTimeout
    * @param s $schedulerDesc
    * @param permit $runSyncUnsafePermit
    */
  @UnsafeBecauseImpure
  @UnsafeBecauseBlocking
  final def runSyncUnsafe(timeout: Duration = Duration.Inf)(implicit s: Scheduler, permit: CanBlock): A =
    runSyncUnsafeOpt(timeout)(s, defaultOptions, permit)

  /** Variant of [[runSyncUnsafe]] that takes a [[BIO.Options]]
    * implicitly from the scope in order to tune the evaluation model
    * of the task.
    *
    * This allows you to specify options such as:
    *
    *  - enabling support for [[TaskLocal]]
    *  - disabling auto-cancelable run-loops
    *
    * See the description of [[runAsyncOpt]] for an example of
    * customizing the default [[BIO.Options]].
    *
    * $unsafeRun
    *
    * @see [[runSyncUnsafe]]
    * @param timeout $runSyncUnsafeTimeout
    * @param s $schedulerDesc
    * @param opts $optionsDesc
    * @param permit $runSyncUnsafePermit
    */
  @UnsafeBecauseImpure
  @UnsafeBecauseBlocking
  final def runSyncUnsafeOpt(timeout: Duration = Duration.Inf)(
    implicit s: Scheduler,
    opts: Options,
    permit: CanBlock
  ): A = {
    /*_*/
    val opts2 = opts.withSchedulerFeatures
    Local.bindCurrentIf(opts2.localContextPropagation) {
      TaskRunSyncUnsafe(this, timeout, s, opts2)
    }
    /*_*/
  }

  /** Creates a new [[Task]] that will expose any triggered error
    * from the source.
    */
  final def attempt: UIO[Either[E, A]] =
    FlatMap(this, AttemptTask.asInstanceOf[A => UIO[Either[E, A]]])

  /** Runs this task first and then, when successful, the given task.
    * Returns the result of the given task.
    *
    * Example:
    * {{{
    *   val combined = Task{println("first"); "first"} >> Task{println("second"); "second"}
    *   // Prints "first" and then "second"
    *   // Result value will be "second"
    * }}}
    */
  final def >>[E1 >: E, B](tb: => BIO[E1, B]): BIO[E1, B] =
    this.flatMap(_ => tb)

  /** Introduces an asynchronous boundary at the current stage in the
    * asynchronous processing pipeline.
    *
    * Consider the following example:
    *
    * {{{
    *   import monix.execution.Scheduler
    *   val io = Scheduler.io()
    *
    *   val source = Task(1).executeOn(io).map(_ + 1)
    * }}}
    *
    * That task is being forced to execute on the `io` scheduler,
    * including the `map` transformation that follows after
    * `executeOn`. But what if we want to jump with the execution
    * run-loop on the default scheduler for the following
    * transformations?
    *
    * Then we can do:
    *
    * {{{
    *   source.asyncBoundary.map(_ + 2)
    * }}}
    *
    * In this sample, whatever gets evaluated by the `source` will
    * happen on the `io` scheduler, however the `asyncBoundary` call
    * will make all subsequent operations to happen on the default
    * scheduler.
    */
  final def asyncBoundary: BIO[E, A] =
    flatMap(a => BIO.shift.map(_ => a))

  /** Introduces an asynchronous boundary at the current stage in the
    * asynchronous processing pipeline, making processing to jump on
    * the given [[monix.execution.Scheduler Scheduler]] (until the
    * next async boundary).
    *
    * Consider the following example:
    * {{{
    *   import monix.execution.Scheduler
    *   val io = Scheduler.io()
    *
    *   val source = Task(1).executeOn(io).map(_ + 1)
    * }}}
    *
    * That task is being forced to execute on the `io` scheduler,
    * including the `map` transformation that follows after
    * `executeOn`. But what if we want to jump with the execution
    * run-loop on another scheduler for the following transformations?
    *
    * Then we can do:
    * {{{
    *   import monix.execution.Scheduler.global
    *
    *   source.asyncBoundary(global).map(_ + 2)
    * }}}
    *
    * In this sample, whatever gets evaluated by the `source` will
    * happen on the `io` scheduler, however the `asyncBoundary` call
    * will make all subsequent operations to happen on the specified
    * `global` scheduler.
    *
    * @param s is the scheduler triggering the asynchronous boundary
    */
  final def asyncBoundary(s: Scheduler): BIO[E, A] =
    flatMap(a => BIO.shift(s).map(_ => a))

  /** Returns a task that treats the source task as the acquisition of a resource,
    * which is then exploited by the `use` function and then `released`.
    *
    * The `bracket` operation is the equivalent of the
    * `try {} catch {} finally {}` statements from mainstream languages.
    *
    * The `bracket` operation installs the necessary exception handler to release
    * the resource in the event of an exception being raised during the computation,
    * or in case of cancellation.
    *
    * If an exception is raised, then `bracket` will re-raise the exception
    * ''after'' performing the `release`. If the resulting task gets cancelled,
    * then `bracket` will still perform the `release`, but the yielded task
    * will be non-terminating (equivalent with [[Task.never]]).
    *
    * Example:
    *
    * {{{
    *   import java.io._
    *
    *   def readFile(file: File): Task[String] = {
    *     // Opening a file handle for reading text
    *     val acquire = Task.eval(new BufferedReader(
    *       new InputStreamReader(new FileInputStream(file), "utf-8")
    *     ))
    *
    *     acquire.bracket { in =>
    *       // Usage part
    *       Task.eval {
    *         // Yes, ugly Java, non-FP loop;
    *         // side-effects are suspended though
    *         var line: String = null
    *         val buff = new StringBuilder()
    *         do {
    *           line = in.readLine()
    *           if (line != null) buff.append(line)
    *         } while (line != null)
    *         buff.toString()
    *       }
    *     } { in =>
    *       // The release part
    *       Task.eval(in.close())
    *     }
    *   }
    * }}}
    *
    * Note that in case of cancellation the underlying implementation cannot
    * guarantee that the computation described by `use` doesn't end up
    * executed concurrently with the computation from `release`. In the example
    * above that ugly Java loop might end up reading from a `BufferedReader`
    * that is already closed due to the task being cancelled, thus triggering
    * an error in the background with nowhere to go but in
    * [[monix.execution.Scheduler.reportFailure Scheduler.reportFailure]].
    *
    * In this particular example, given that we are just reading from a file,
    * it doesn't matter. But in other cases it might matter, as concurrency
    * on top of the JVM when dealing with I/O might lead to corrupted data.
    *
    * For those cases you might want to do synchronization (e.g. usage of
    * locks and semaphores) and you might want to use [[bracketE]], the
    * version that allows you to differentiate between normal termination
    * and cancellation.
    *
    * $bracketErrorNote
    *
    * @see [[bracketCase]] and [[bracketE]]
    *
    * @param use is a function that evaluates the resource yielded by the source,
    *        yielding a result that will get generated by the task returned
    *        by this `bracket` function
    *
    * @param release is a function that gets called after `use` terminates,
    *        either normally or in error, or if it gets cancelled, receiving
    *        as input the resource that needs to be released
    */
  final def bracket[E1 >: E, B](use: A => BIO[E1, B])(release: A => UIO[Unit]): BIO[E1, B] =
    bracketCase(use)((a, _) => release(a))

  /** Returns a new task that treats the source task as the
    * acquisition of a resource, which is then exploited by the `use`
    * function and then `released`, with the possibility of
    * distinguishing between normal termination and cancelation, such
    * that an appropriate release of resources can be executed.
    *
    * The `bracketCase` operation is the equivalent of
    * `try {} catch {} finally {}` statements from mainstream languages
    * when used for the acquisition and release of resources.
    *
    * The `bracketCase` operation installs the necessary exception handler
    * to release the resource in the event of an exception being raised
    * during the computation, or in case of cancelation.
    *
    * In comparison with the simpler [[bracket]] version, this one
    * allows the caller to differentiate between normal termination,
    * termination in error and cancelation via an `ExitCase`
    * parameter.
    *
    * @see [[bracket]] and [[bracketE]]
    *
    * @param use is a function that evaluates the resource yielded by
    *        the source, yielding a result that will get generated by
    *        this function on evaluation
    *
    * @param release is a function that gets called after `use`
    *        terminates, either normally or in error, or if it gets
    *        canceled, receiving as input the resource that needs that
    *        needs release, along with the result of `use`
    *        (cancelation, error or successful result)
    */
  final def bracketCase[E1 >: E, B](use: A => BIO[E1, B])(
    release: (A, ExitCase[Either[Throwable, E1]]) => UIO[Unit]): BIO[E1, B] =
    TaskBracket.exitCase(this, use, release)

  /** Returns a task that treats the source task as the acquisition of a resource,
    * which is then exploited by the `use` function and then `released`, with
    * the possibility of distinguishing between normal termination and cancellation,
    * such that an appropriate release of resources can be executed.
    *
    * The `bracketE` operation is the equivalent of `try {} catch {} finally {}`
    * statements from mainstream languages.
    *
    * The `bracketE` operation installs the necessary exception handler to release
    * the resource in the event of an exception being raised during the computation,
    * or in case of cancellation.
    *
    * In comparison with the simpler [[bracket]] version, this one allows the
    * caller to differentiate between normal termination and cancellation.
    *
    * The `release` function receives as input:
    *
    *  - `Left(None)` in case of cancellation
    *  - `Left(Some(error))` in case `use` terminated with an error
    *  - `Right(b)` in case of success
    *
    * $bracketErrorNote
    *
    * @see [[bracket]] and [[bracketCase]]
    *
    * @param use is a function that evaluates the resource yielded by the source,
    *        yielding a result that will get generated by this function on
    *        evaluation
    *
    * @param release is a function that gets called after `use` terminates,
    *        either normally or in error, or if it gets cancelled, receiving
    *        as input the resource that needs that needs release, along with
    *        the result of `use` (cancellation, error or successful result)
    */
  final def bracketE[E1 >: E, B](use: A => BIO[E1, B])(
    release: (A, Either[Option[Either[Throwable, E1]], B]) => UIO[Unit]): BIO[E1, B] =
    TaskBracket.either(this, use, release)

  /**
    * Executes the given `finalizer` when the source is finished,
    * either in success or in error, or if canceled.
    *
    * This variant of [[guaranteeCase]] evaluates the given `finalizer`
    * regardless of how the source gets terminated:
    *
    *  - normal completion
    *  - completion in error
    *  - cancellation
    *
    * As best practice, it's not a good idea to release resources
    * via `guaranteeCase` in polymorphic code. Prefer [[bracket]]
    * for the acquisition and release of resources.
    *
    * @see [[guaranteeCase]] for the version that can discriminate
    *      between termination conditions
    *
    * @see [[bracket]] for the more general operation
    */
  final def guarantee(finalizer: UIO[Unit]): BIO[E, A] =
    guaranteeCase(_ => finalizer)

  /**
    * Executes the given `finalizer` when the source is finished,
    * either in success or in error, or if canceled, allowing
    * for differentiating between exit conditions.
    *
    * This variant of [[guarantee]] injects an ExitCase in
    * the provided function, allowing one to make a difference
    * between:
    *
    *  - normal completion
    *  - completion in error
    *  - cancellation
    *
    * As best practice, it's not a good idea to release resources
    * via `guaranteeCase` in polymorphic code. Prefer [[bracketCase]]
    * for the acquisition and release of resources.
    *
    * @see [[guarantee]] for the simpler version
    *
    * @see [[bracketCase]] for the more general operation
    */
  final def guaranteeCase(finalizer: ExitCase[Either[Throwable, E]] => UIO[Unit]): BIO[E, A] =
    TaskBracket.guaranteeCase(this, finalizer)

  /** Returns a task that waits for the specified `timespan` before
    * executing and mirroring the result of the source.
    *
    * In this example we're printing to standard output, but before
    * doing that we're introducing a 3 seconds delay:
    *
    * {{{
    *   import scala.concurrent.duration._
    *
    *   Task(println("Hello!"))
    *     .delayExecution(3.seconds)
    * }}}
    *
    * This operation is also equivalent with:
    *
    * {{{
    *   Task.sleep(3.seconds).flatMap(_ => Task(println("Hello!")))
    * }}}
    *
    * See [[BIO.sleep]] for the operation that describes the effect
    * and [[BIO.delayResult]] for the version that evaluates the
    * task on time, but delays the signaling of the result.
    *
    * @param timespan is the time span to wait before triggering
    *        the evaluation of the task
    */
  final def delayExecution(timespan: FiniteDuration): BIO[E, A] =
    BIO.sleep(timespan).flatMap(_ => this)

  /** Returns a task that executes the source immediately on `runAsync`,
    * but before emitting the `onSuccess` result for the specified
    * duration.
    *
    * Note that if an error happens, then it is streamed immediately
    * with no delay.
    *
    * See [[delayExecution]] for delaying the evaluation of the
    * task with the specified duration. The [[delayResult]] operation
    * is effectively equivalent with:
    *
    * {{{
    *   import scala.concurrent.duration._
    *
    *   Task(1 + 1)
    *     .flatMap(a => Task.now(a).delayExecution(3.seconds))
    * }}}
    *
    * Or if we are to use the [[BIO.sleep]] describing just the
    * effect, this operation is equivalent with:
    *
    * {{{
    *   Task(1 + 1).flatMap(a => Task.sleep(3.seconds).map(_ => a))
    * }}}
    *
    * Thus in this example 3 seconds will pass before the result
    * is being generated by the source, plus another 5 seconds
    * before it is finally emitted:
    *
    * {{{
    *   Task(1 + 1)
    *     .delayExecution(3.seconds)
    *     .delayResult(5.seconds)
    * }}}
    *
    * @param timespan is the time span to sleep before signaling
    *        the result, but after the evaluation of the source
    */
  final def delayResult(timespan: FiniteDuration): BIO[E, A] =
    flatMap(a => BIO.sleep(timespan).map(_ => a))

  /** Overrides the default [[monix.execution.Scheduler Scheduler]],
    * possibly forcing an asynchronous boundary before execution
    * (if `forceAsync` is set to `true`, the default).
    *
    * When a `Task` is executed with [[Task.runAsync]] or [[Task.runToFuture]],
    * it needs a `Scheduler`, which is going to be injected in all
    * asynchronous tasks processed within the `flatMap` chain,
    * a `Scheduler` that is used to manage asynchronous boundaries
    * and delayed execution.
    *
    * This scheduler passed in `runAsync` is said to be the "default"
    * and `executeOn` overrides that default.
    *
    * {{{
    *   import monix.execution.Scheduler
    *   import java.io.{BufferedReader, FileInputStream, InputStreamReader}
    *
    *   /** Reads the contents of a file using blocking I/O. */
    *   def readFile(path: String): Task[String] = Task.eval {
    *     val in = new BufferedReader(
    *       new InputStreamReader(new FileInputStream(path), "utf-8"))
    *
    *     val buffer = new StringBuffer()
    *     var line: String = null
    *     do {
    *       line = in.readLine()
    *       if (line != null) buffer.append(line)
    *     } while (line != null)
    *
    *     buffer.toString
    *   }
    *
    *   // Building a Scheduler meant for blocking I/O
    *   val io = Scheduler.io()
    *
    *   // Building the Task reference, specifying that `io` should be
    *   // injected as the Scheduler for managing async boundaries
    *   readFile("path/to/file").executeOn(io, forceAsync = true)
    * }}}
    *
    * In this example we are using [[Task.eval]], which executes the
    * given `thunk` immediately (on the current thread and call stack).
    *
    * By calling `executeOn(io)`, we are ensuring that the used
    * `Scheduler` (injected in [[Task.cancelable0[A](register* async tasks]])
    * will be `io`, a `Scheduler` that we intend to use for blocking
    * I/O actions. And we are also forcing an asynchronous boundary
    * right before execution, by passing the `forceAsync` parameter as
    * `true` (which happens to be the default value).
    *
    * Thus, for our described function that reads files using Java's
    * blocking I/O APIs, we are ensuring that execution is entirely
    * managed by an `io` scheduler, executing that logic on a thread
    * pool meant for blocking I/O actions.
    *
    * Note that in case `forceAsync = false`, then the invocation will
    * not introduce any async boundaries of its own and will not
    * ensure that execution will actually happen on the given
    * `Scheduler`, that depending of the implementation of the `Task`.
    * For example:
    *
    * {{{
    *   Task.eval("Hello, " + "World!")
    *     .executeOn(io, forceAsync = false)
    * }}}
    *
    * The evaluation of this task will probably happen immediately
    * (depending on the configured
    * [[monix.execution.ExecutionModel ExecutionModel]]) and the
    * given scheduler will probably not be used at all.
    *
    * However in case we would use [[Task.apply]], which ensures
    * that execution of the provided thunk will be async, then
    * by using `executeOn` we'll indeed get a logical fork on
    * the `io` scheduler:
    *
    * {{{
    *   Task("Hello, " + "World!").executeOn(io, forceAsync = false)
    * }}}
    *
    * Also note that overriding the "default" scheduler can only
    * happen once, because it's only the "default" that can be
    * overridden.
    *
    * Something like this won't have the desired effect:
    *
    * {{{
    *   val io1 = Scheduler.io()
    *   val io2 = Scheduler.io()
    *
    *   Task(1 + 1).executeOn(io1).executeOn(io2)
    * }}}
    *
    * In this example the implementation of `task` will receive
    * the reference to `io1` and will use it on evaluation, while
    * the second invocation of `executeOn` will create an unnecessary
    * async boundary (if `forceAsync = true`) or be basically a
    * costly no-op. This might be confusing but consider the
    * equivalence to these functions:
    *
    * {{{
    *   import scala.concurrent.ExecutionContext
    *
    *   val io11 = Scheduler.io()
    *   val io22 = Scheduler.io()
    *
    *   def sayHello(ec: ExecutionContext): Unit =
    *     ec.execute(new Runnable {
    *       def run() = println("Hello!")
    *     })
    *
    *   def sayHello2(ec: ExecutionContext): Unit =
    *     // Overriding the default `ec`!
    *     sayHello(io11)
    *
    *   def sayHello3(ec: ExecutionContext): Unit =
    *     // Overriding the default no longer has the desired effect
    *     // because sayHello2 is ignoring it!
    *     sayHello2(io22)
    * }}}
    *
    * @param s is the [[monix.execution.Scheduler Scheduler]] to use
    *        for overriding the default scheduler and for forcing
    *        an asynchronous boundary if `forceAsync` is `true`
    * @param forceAsync indicates whether an asynchronous boundary
    *        should be forced right before the evaluation of the
    *        `Task`, managed by the provided `Scheduler`
    * @return a new `Task` that mirrors the source on evaluation,
    *         but that uses the provided scheduler for overriding
    *         the default and possibly force an extra asynchronous
    *         boundary on execution
    */
  final def executeOn(s: Scheduler, forceAsync: Boolean = true): BIO[E, A] =
    TaskExecuteOn(this, s, forceAsync)

  /** Returns a new `Task` that will mirror the source, but that will
    * execute the given `callback` if the task gets canceled before
    * completion.
    *
    * This only works for premature cancellation. See [[doOnFinish]]
    * for triggering callbacks when the source finishes.
    *
    * @param callback is the callback to execute if the task gets
    *        canceled prematurely
    */
  final def doOnCancel(callback: UIO[Unit]): BIO[E, A] =
    TaskDoOnCancel(this, callback)

  /** Creates a new [[Task]] that will expose any triggered error from
    * the source.
    */
  final def materialize: UIO[Try[A]] =
    FlatMap(this, MaterializeTask.asInstanceOf[A => UIO[Try[A]]])

  /** Dematerializes the source's result from a `Try`. */
  final def dematerialize[B](implicit evE: E <:< Nothing, evA: A <:< Try[B]): Task[B] =
    this.asInstanceOf[UIO[Try[B]]].flatMap(fromTry)

  /** Returns a new task that mirrors the source task for normal termination,
    * but that triggers the given error on cancellation.
    *
    * Normally tasks that are cancelled become non-terminating.
    * Here's an example of a cancelable task:
    *
    * {{{
    *   import monix.execution.Scheduler.Implicits.global
    *   import scala.concurrent.duration._
    *
    *   val tenSecs = Task.sleep(10.seconds)
    *   val task1 = tenSecs.start.flatMap { fa =>
    *     // Triggering pure cancellation, then trying to get its result
    *     fa.cancel.flatMap(_ => tenSecs)
    *   }
    *
    *   task1.timeout(10.seconds).runToFuture
    *   //=> throws TimeoutException
    * }}}
    *
    * In general you can expect cancelable tasks to become non-terminating on
    * cancellation.
    *
    * This `onCancelRaiseError` operator transforms a task that would yield
    * [[Task.never]] on cancellation into one that yields [[Task.raiseError]].
    *
    * Example:
    * {{{
    *   import java.util.concurrent.CancellationException
    *
    *   val anotherTenSecs = Task.sleep(10.seconds)
    *     .onCancelRaiseError(new CancellationException)
    *
    *   val task2 = anotherTenSecs.start.flatMap { fa =>
    *     // Triggering pure cancellation, then trying to get its result
    *     fa.cancel.flatMap(_ => anotherTenSecs)
    *   }
    *
    *   task2.runToFuture
    *   // => CancellationException
    * }}}
    */
  final def onCancelRaiseError[E1 >: E](e: E1): BIO[E1, A] =
    TaskCancellation.raiseError(this, e)

  /** Creates a new task that will try recovering from an error by
    * matching it with another task using the given partial function.
    *
    * See [[onErrorHandleWith]] for the version that takes a total function.
    */
  final def onErrorRecoverWith[E1 >: E, B >: A](pf: PartialFunction[E, BIO[E1, B]]): BIO[E1, B] =
    onErrorHandleWith(ex => pf.applyOrElse(ex, raiseConstructor[E]))

  /** Creates a new task that will handle any matching throwable that
    * this task might emit by executing another task.
    *
    * See [[onErrorRecoverWith]] for the version that takes a partial function.
    */
  final def onErrorHandleWith[E1, B >: A](f: E => BIO[E1, B]): BIO[E1, B] =
    FlatMap(this, new StackFrame.ErrorHandler(f, nowConstructor))

  /** Creates a new task that in case of error will fallback to the
    * given backup task.
    */
  final def onErrorFallbackTo[E1, B >: A](that: BIO[E1, B]): BIO[E1, B] =
    onErrorHandleWith(_ => that)

  /** Creates a new task that will handle any matching throwable that
    * this task might emit.
    *
    * See [[onErrorRecover]] for the version that takes a partial function.
    */
  final def onErrorHandle[U >: A](f: E => U): UIO[U] =
    onErrorHandleWith(f.andThen(nowConstructor))

  /** Creates a new task that on error will try to map the error
    * to another value using the provided partial function.
    *
    * See [[onErrorHandle]] for the version that takes a total function.
    */
  final def onErrorRecover[E1 >: E, U >: A](pf: PartialFunction[E, U]): BIO[E1, U] =
    onErrorRecoverWith(pf.andThen(nowConstructor))

  /** Start execution of the source suspended in the `Task` context.
    *
    * This can be used for non-deterministic / concurrent execution.
    * The following code is more or less equivalent with
    * [[Task.parMap2]] (minus the behavior on error handling and
    * cancellation):
    *
    * {{{
    *   def par2[A, B](ta: Task[A], tb: Task[B]): Task[(A, B)] =
    *     for {
    *       fa <- ta.start
    *       fb <- tb.start
    *        a <- fa.join
    *        b <- fb.join
    *     } yield (a, b)
    * }}}
    *
    * Note in such a case usage of [[Task.parMap2 parMap2]]
    * (and [[Task.parMap3 parMap3]], etc.) is still recommended
    * because of behavior on error and cancellation — consider that
    * in the example above, if the first task finishes in error,
    * the second task doesn't get cancelled.
    *
    * This operation forces an asynchronous boundary before execution
    */
  final def start: UIO[Fiber[E @uV, A @uV]] =
    TaskStart.forked(this)

  /** Returns a string representation of this task meant for
    * debugging purposes only.
    */
  override def toString: String = this match {
    case Now(a) => s"BIO.Now($a)"
    case Error(e) => s"BIO.Error($e)"
    case _ =>
      val n = this.getClass.getName.replaceFirst("^monix\\.bio\\.BIO[$.]", "")
      s"BIO.$n$$${System.identityHashCode(this)}"
  }

  /** Returns a new value that transforms the result of the source,
    * given the `recover` or `map` functions, which get executed depending
    * on whether the result is successful or if it ends in error.
    *
    * This is an optimization on usage of [[attempt]] and [[map]],
    * this equivalence being true:
    *
    * `task.redeem(recover, map) <-> task.attempt.map(_.fold(recover, map))`
    *
    * Usage of `redeem` subsumes [[onErrorHandle]] because:
    *
    * `task.redeem(fe, id) <-> task.onErrorHandle(fe)`
    *
    * @param recover is a function used for error recover in case the
    *        source ends in error
    * @param map is a function used for mapping the result of the source
    *        in case it ends in success
    */
  def redeem[B](recover: E => B, map: A => B): UIO[B] =
    BIO.FlatMap(this, new BIO.Redeem(recover, map))

  /** Returns a new value that transforms the result of the source,
    * given the `recover` or `bind` functions, which get executed depending
    * on whether the result is successful or if it ends in error.
    *
    * This is an optimization on usage of [[attempt]] and [[flatMap]],
    * this equivalence being available:
    *
    * `task.redeemWith(recover, bind) <-> task.attempt.flatMap(_.fold(recover, bind))`
    *
    * Usage of `redeemWith` subsumes [[onErrorHandleWith]] because:
    *
    * `task.redeemWith(fe, F.pure) <-> task.onErrorHandleWith(fe)`
    *
    * Usage of `redeemWith` also subsumes [[flatMap]] because:
    *
    * `task.redeemWith(Task.raiseError, fs) <-> task.flatMap(fs)`
    *
    * @param recover is the function that gets called to recover the source
    *        in case of error
    * @param bind is the function that gets to transform the source
    *        in case of success
    */
  def redeemWith[E1, B](recover: E => BIO[E1, B], bind: A => BIO[E1, B]): BIO[E1, B] =
    BIO.FlatMap(this, new StackFrame.RedeemWith(recover, bind))

  /** Makes the source `Task` uninterruptible such that a `cancel` signal
    * (e.g. [[Fiber.cancel]]) has no effect.
    *
    * {{{
    *   import monix.execution.Scheduler.Implicits.global
    *   import scala.concurrent.duration._
    *
    *   val uncancelable = Task
    *     .eval(println("Hello!"))
    *     .delayExecution(10.seconds)
    *     .uncancelable
    *     .runToFuture
    *
    *   // No longer works
    *   uncancelable.cancel()
    *
    *   // After 10 seconds
    *   // => Hello!
    * }}}
    */
  final def uncancelable: BIO[E, A] =
    TaskCancellation.uncancelable(this)

  // TODO: scaladoc, name
  final def hideErrors(implicit E: E <:< Throwable): UIO[A] =
    onErrorHandleWith(ex => BIO.raiseFatalError(E(ex)))

  // TODO: scaladoc, name
  final def redeemFatal[B](recover: Throwable => B, map: A => B): UIO[B] =
    BIO.FlatMap(this, new BIO.RedeemFatal(recover, map))

  // TODO: scaladoc, name
  final def redeemFatalWith[E1, B](recover: Throwable => BIO[E1, B], bind: A => BIO[E1, B]): BIO[E1, B] =
    BIO.FlatMap(this, new StackFrame.RedeemFatalWith(recover, bind))
}

object BIO extends TaskInstancesLevel0 {

  /** Lifts the given thunk in the `BIO` context, processing it synchronously
    * when the task gets evaluated.
    *
    * This is an alias for:
    *
    * {{{
    *   val thunk = () => 42
    *   BIO.eval(thunk())
    * }}}
    *
    * WARN: behavior of `BIO.apply` has changed since 3.0.0-RC2.
    * Before the change (during Monix 2.x series), this operation was forcing
    * a fork, being equivalent to the new [[BIO.evalAsync]].
    *
    * Switch to [[BIO.evalAsync]] if you wish the old behavior, or combine
    * [[BIO.eval]] with [[BIO.executeAsync]].
    */
  def apply[A](a: => A): Task[A] =
    eval(a)

  /** Returns a `BIO` that on execution is always successful, emitting
    * the given strict value.
    */
  def now[A](a: A): UIO[A] =
    BIO.Now(a)

  /** Lifts a value into the task context. Alias for [[now]]. */
  def pure[A](a: A): UIO[A] = now(a)

  /** Returns a task that on execution is always finishing in error
    * emitting the specified exception.
    */
  def raiseError[E](ex: E): BIO[E, Nothing] =
    Error(ex)

  def raiseFatalError(ex: Throwable): UIO[Nothing] =
    FatalError(ex)

  /** Promote a non-strict value representing a Task to a Task of the
    * same type.
    */
  def defer[E, A](fa: => BIO[E, A]): BIO[E, A] =
    Suspend(fa _)

  /** Defers the creation of a `Task` by using the provided
    * function, which has the ability to inject a needed
    * [[monix.execution.Scheduler Scheduler]].
    *
    * Example:
    * {{{
    *   import scala.concurrent.duration.MILLISECONDS
    *
    *   def measureLatency[A](source: Task[A]): Task[(A, Long)] =
    *     Task.deferAction { implicit s =>
    *       // We have our Scheduler, which can inject time, we
    *       // can use it for side-effectful operations
    *       val start = s.clockRealTime(MILLISECONDS)
    *
    *       source.map { a =>
    *         val finish = s.clockRealTime(MILLISECONDS)
    *         (a, finish - start)
    *       }
    *     }
    * }}}
    *
    * @param f is the function that's going to be called when the
    *        resulting `Task` gets evaluated
    */
  def deferAction[E, A](f: Scheduler => BIO[E, A]): BIO[E, A] =
    TaskDeferAction(f)

  /** Promote a non-strict Scala `Future` to a `Task` of the same type.
    *
    * The equivalent of doing:
    * {{{
    *   import scala.concurrent.Future
    *   def mkFuture = Future.successful(27)
    *
    *   Task.defer(Task.fromFuture(mkFuture))
    * }}}
    */
  def deferFuture[A](fa: => Future[A]): Task[A] =
    defer(fromFuture(fa))

  /** Wraps calls that generate `Future` results into [[Task]], provided
    * a callback with an injected [[monix.execution.Scheduler Scheduler]]
    * to act as the necessary `ExecutionContext`.
    *
    * This builder helps with wrapping `Future`-enabled APIs that need
    * an implicit `ExecutionContext` to work. Consider this example:
    *
    * {{{
    *   import scala.concurrent.{ExecutionContext, Future}
    *
    *   def sumFuture(list: Seq[Int])(implicit ec: ExecutionContext): Future[Int] =
    *     Future(list.sum)
    * }}}
    *
    * We'd like to wrap this function into one that returns a lazy
    * `Task` that evaluates this sum every time it is called, because
    * that's how tasks work best. However in order to invoke this
    * function an `ExecutionContext` is needed:
    *
    * {{{
    *   def sumTask(list: Seq[Int])(implicit ec: ExecutionContext): Task[Int] =
    *     Task.deferFuture(sumFuture(list))
    * }}}
    *
    * But this is not only superfluous, but against the best practices
    * of using `Task`. The difference is that `Task` takes a
    * [[monix.execution.Scheduler Scheduler]] (inheriting from
    * `ExecutionContext`) only when [[Task.runAsync runAsync]] happens.
    * But with `deferFutureAction` we get to have an injected
    * `Scheduler` in the passed callback:
    *
    * {{{
    *   def sumTask2(list: Seq[Int]): Task[Int] =
    *     Task.deferFutureAction { implicit scheduler =>
    *       sumFuture(list)
    *     }
    * }}}
    *
    * @param f is the function that's going to be executed when the task
    *        gets evaluated, generating the wrapped `Future`
    */
  def deferFutureAction[A](f: Scheduler => Future[A]): Task[A] =
    TaskFromFuture.deferAction(f)

  /** Alias for [[defer]]. */
  def suspend[E, A](fa: => BIO[E, A]): BIO[E, A] =
    Suspend(fa _)

  /** Promote a non-strict value, a thunk, to a `Task`, catching exceptions
    * in the process.
    *
    * Note that since `Task` is not memoized or strict, this will recompute the
    * value each time the `Task` is executed, behaving like a function.
    *
    * @param a is the thunk to process on evaluation
    */
  def eval[A](a: => A): Task[A] =
    Eval(a _)

  /** Lifts a non-strict value, a thunk, to a `Task` that will trigger a logical
    * fork before evaluation.
    *
    * Like [[eval]], but the provided `thunk` will not be evaluated immediately.
    * Equivalence:
    *
    * `Task.evalAsync(a) <-> Task.eval(a).executeAsync`
    *
    * @param a is the thunk to process on evaluation
    */
  def evalAsync[A](a: => A): Task[A] =
    TaskEvalAsync(a _)

  /** Alias for [[eval]]. */
  def delay[A](a: => A): Task[A] = eval(a)

  /** A [[Task]] instance that upon evaluation will never complete. */
  def never[A]: UIO[A] = neverRef

  /** Builds a [[Task]] instance out of a Scala `Try`. */
  def fromTry[A](a: Try[A]): Task[A] =
    a match {
      case Success(v) => Now(v)
      case Failure(ex) => Error(ex)
    }

  /** Builds a [[Task]] instance out of a Scala `Either`. */
  def fromEither[E, A](a: Either[E, A]): BIO[E, A] =
    a match {
      case Right(v) => Now(v)
      case Left(ex) => Error(ex)
    }

  /** Keeps calling `f` until it returns a `Right` result.
    *
    * Based on Phil Freeman's
    * [[http://functorial.com/stack-safety-for-free/index.pdf Stack Safety for Free]].
    */
  def tailRecM[E, A, B](a: A)(f: A => BIO[E, Either[A, B]]): BIO[E, B] =
    BIO.defer(f(a)).flatMap {
      case Left(continueA) => tailRecM(continueA)(f)
      case Right(b) => BIO.now(b)
    }

  /** A `Task[Unit]` provided for convenience. */
  val unit: UIO[Unit] = Now(())

  /** Create a non-cancelable `Task` from an asynchronous computation,
    * which takes the form of a function with which we can register a
    * callback to execute upon completion.
    *
    * This operation is the implementation for `cats.effect.Async` and
    * is thus yielding non-cancelable tasks, being the simplified
    * version of [[BIO.cancelable[A](register* Task.cancelable]].
    * This can be used to translate from a callback-based API to pure
    * `Task` values that cannot be canceled.
    *
    * See the the documentation for
    * [[https://typelevel.org/cats-effect/typeclasses/async.html cats.effect.Async]].
    *
    * For example, in case we wouldn't have [[BIO.deferFuture]]
    * already defined, we could do this:
    *
    * {{{
    *   import scala.concurrent.{Future, ExecutionContext}
    *   import scala.util._
    *
    *   def deferFuture[A](f: => Future[A])(implicit ec: ExecutionContext): Task[A] =
    *     Task.async { cb =>
    *       // N.B. we could do `f.onComplete(cb)` directly ;-)
    *       f.onComplete {
    *         case Success(a) => cb.onSuccess(a)
    *         case Failure(e) => cb.onError(e)
    *       }
    *     }
    * }}}
    *
    * Note that this function needs an explicit `ExecutionContext` in order
    * to trigger `Future#complete`, however Monix's `Task` can inject
    * a [[monix.execution.Scheduler Scheduler]] for you, thus allowing you
    * to get rid of these pesky execution contexts being passed around explicitly.
    * See [[BIO.async0]].
    *
    * CONTRACT for `register`:
    *
    *  - the provided function is executed when the `Task` will be evaluated
    *    (via `runAsync` or when its turn comes in the `flatMap` chain, not before)
    *  - the injected [[monix.execution.Callback Callback]] can be
    *    called at most once, either with a successful result, or with
    *    an error; calling it more than once is a contract violation
    *  - the injected callback is thread-safe and in case it gets called
    *    multiple times it will throw a
    *    [[monix.execution.exceptions.CallbackCalledMultipleTimesException]];
    *    also see [[monix.execution.Callback.tryOnSuccess Callback.tryOnSuccess]]
    *    and [[monix.execution.Callback.tryOnError Callback.tryOnError]]
    *
    * @see [[BIO.async0]] for a variant that also injects a
    *      [[monix.execution.Scheduler Scheduler]] into the provided callback,
    *      useful for forking, or delaying tasks or managing async boundaries
    * @see [[BIO.cancelable[A](register* Task.cancelable]] and [[BIO.cancelable0]]
    *      for creating cancelable tasks
    * @see [[BIO.create]] for the builder that does it all
    */
  def async[E, A](register: Callback[E, A] => Unit): BIO[E, A] =
    TaskCreate.async(register)

  /** Create a non-cancelable `Task` from an asynchronous computation,
    * which takes the form of a function with which we can register a
    * callback to execute upon completion, a function that also injects a
    * [[monix.execution.Scheduler Scheduler]] for managing async boundaries.
    *
    * This operation is the implementation for `cats.effect.Async` and
    * is thus yielding non-cancelable tasks, being the simplified
    * version of [[BIO.cancelable0]]. It can be used to translate from a
    * callback-based API to pure `Task` values that cannot be canceled.
    *
    * See the the documentation for
    * [[https://typelevel.org/cats-effect/typeclasses/async.html cats.effect.Async]].
    *
    * For example, in case we wouldn't have [[BIO.deferFuture]]
    * already defined, we could do this:
    *
    * {{{
    *   import scala.concurrent.Future
    *   import scala.util._
    *
    *   def deferFuture[A](f: => Future[A]): Task[A] =
    *     Task.async0 { (scheduler, cb) =>
    *       // We are being given an ExecutionContext ;-)
    *       implicit val ec = scheduler
    *
    *       // N.B. we could do `f.onComplete(cb)` directly ;-)
    *       f.onComplete {
    *         case Success(a) => cb.onSuccess(a)
    *         case Failure(e) => cb.onError(e)
    *       }
    *     }
    * }}}
    *
    * Note that this function doesn't need an implicit `ExecutionContext`.
    * Compared with usage of [[BIO.async[A](register* Task.async]], this
    * function injects a [[monix.execution.Scheduler Scheduler]] for us to
    * use for managing async boundaries.
    *
    * CONTRACT for `register`:
    *
    *  - the provided function is executed when the `Task` will be evaluated
    *    (via `runAsync` or when its turn comes in the `flatMap` chain, not before)
    *  - the injected [[monix.execution.Callback]] can be called at
    *    most once, either with a successful result, or with an error;
    *    calling it more than once is a contract violation
    *  - the injected callback is thread-safe and in case it gets called
    *    multiple times it will throw a
    *    [[monix.execution.exceptions.CallbackCalledMultipleTimesException]];
    *    also see [[monix.execution.Callback.tryOnSuccess Callback.tryOnSuccess]]
    *    and [[monix.execution.Callback.tryOnError Callback.tryOnError]]
    *
    * NOTES on the naming:
    *
    *  - `async` comes from `cats.effect.Async#async`
    *  - the `0` suffix is about overloading the simpler
    *    [[BIO.async[A](register* Task.async]] builder
    *
    * @see [[BIO.async]] for a simpler variant that doesn't inject a
    *      `Scheduler`, in case you don't need one
    * @see [[BIO.cancelable[A](register* Task.cancelable]] and [[BIO.cancelable0]]
    *      for creating cancelable tasks
    * @see [[BIO.create]] for the builder that does it all
    */
  def async0[E, A](register: (Scheduler, Callback[E, A]) => Unit): BIO[E, A] =
    TaskCreate.async0(register)

  /** Suspends an asynchronous side effect in `Task`, this being a
    * variant of [[async]] that takes a pure registration function.
    *
    * Implements `cats.effect.Async.asyncF`.
    *
    * The difference versus [[async]] is that this variant can suspend
    * side-effects via the provided function parameter. It's more relevant
    * in polymorphic code making use of the `cats.effect.Async`
    * type class, as it alleviates the need for `cats.effect.Effect`.
    *
    * Contract for the returned `Task[Unit]` in the provided function:
    *
    *  - can be asynchronous
    *  - can be cancelable, in which case it hooks into IO's cancelation
    *    mechanism such that the resulting task is cancelable
    *  - it should not end in error, because the provided callback
    *    is the only way to signal the final result and it can only
    *    be called once, so invoking it twice would be a contract
    *    violation; so on errors thrown in `Task`, the task can become
    *    non-terminating, with the error being printed via
    *    [[monix.execution.Scheduler.reportFailure Scheduler.reportFailure]]
    *
    * @see [[BIO.async]] and [[BIO.async0]] for a simpler variants
    * @see [[BIO.cancelable[A](register* Task.cancelable]] and
    *      [[BIO.cancelable0]] for creating cancelable tasks
    */
  def asyncF[E, A](register: Callback[E, A] => BIO[E, Unit]): BIO[E, A] =
    TaskCreate.asyncF(register)

  /** Create a cancelable `Task` from an asynchronous computation that
    * can be canceled, taking the form of a function with which we can
    * register a callback to execute upon completion.
    *
    * This operation is the implementation for
    * `cats.effect.Concurrent#cancelable` and is thus yielding
    * cancelable tasks. It can be used to translate from a callback-based
    * API to pure `Task` values that can be canceled.
    *
    * See the the documentation for
    * [[https://typelevel.org/cats-effect/typeclasses/concurrent.html cats.effect.Concurrent]].
    *
    * For example, in case we wouldn't have [[BIO.delayExecution]]
    * already defined and we wanted to delay evaluation using a Java
    * [[https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/ScheduledExecutorService.html ScheduledExecutorService]]
    * (no need for that because we've got [[monix.execution.Scheduler Scheduler]],
    * but lets say for didactic purposes):
    *
    * {{{
    *   import java.util.concurrent.ScheduledExecutorService
    *   import scala.concurrent.ExecutionContext
    *   import scala.concurrent.duration._
    *   import scala.util.control.NonFatal
    *
    *   def delayed[A](sc: ScheduledExecutorService, timespan: FiniteDuration)
    *     (thunk: => A)
    *     (implicit ec: ExecutionContext): Task[A] = {
    *
    *     Task.cancelable { cb =>
    *       val future = sc.schedule(new Runnable { // scheduling delay
    *         def run() = ec.execute(new Runnable { // scheduling thunk execution
    *           def run() =
    *             try
    *               cb.onSuccess(thunk)
    *             catch { case NonFatal(e) =>
    *               cb.onError(e)
    *             }
    *           })
    *         },
    *         timespan.length,
    *         timespan.unit)
    *
    *       // Returning the cancelation token that is able to cancel the
    *       // scheduling in case the active computation hasn't finished yet
    *       Task(future.cancel(false))
    *     }
    *   }
    * }}}
    *
    * Note in this sample we are passing an implicit `ExecutionContext`
    * in order to do the actual processing, the `ScheduledExecutorService`
    * being in charge just of scheduling. We don't need to do that, as `Task`
    * affords to have a [[monix.execution.Scheduler Scheduler]] injected
    * instead via [[BIO.cancelable0]].
    *
    * CONTRACT for `register`:
    *
    *  - the provided function is executed when the `Task` will be evaluated
    *    (via `runAsync` or when its turn comes in the `flatMap` chain, not before)
    *  - the injected [[monix.execution.Callback Callback]] can be
    *    called at most once, either with a successful result, or with
    *    an error; calling it more than once is a contract violation
    *  - the injected callback is thread-safe and in case it gets called
    *    multiple times it will throw a
    *    [[monix.execution.exceptions.CallbackCalledMultipleTimesException]];
    *    also see [[monix.execution.Callback.tryOnSuccess Callback.tryOnSuccess]]
    *    and [[monix.execution.Callback.tryOnError Callback.tryOnError]]
    *
    * @see [[BIO.cancelable0]] for the version that also injects a
    *      [[monix.execution.Scheduler Scheduler]] in that callback
    * @see [[BIO.async0]] and [[BIO.async[A](register* Task.async]] for the
    *      simpler versions of this builder that create non-cancelable tasks
    *      from callback-based APIs
    * @see [[BIO.create]] for the builder that does it all
    * @param register $registerParamDesc
    */
  def cancelable[E, A](register: Callback[E, A] => CancelToken[BIO[E, ?]]): BIO[E, A] =
    cancelable0[E, A]((_, cb) => register(cb))

  /** Create a cancelable `Task` from an asynchronous computation,
    * which takes the form of a function with which we can register a
    * callback to execute upon completion, a function that also injects a
    * [[monix.execution.Scheduler Scheduler]] for managing async boundaries.
    *
    * This operation is the implementation for
    * `cats.effect.Concurrent#cancelable` and is thus yielding
    * cancelable tasks. It can be used to translate from a callback-based API
    * to pure `Task` values that can be canceled.
    *
    * See the the documentation for
    * [[https://typelevel.org/cats-effect/typeclasses/concurrent.html cats.effect.Concurrent]].
    *
    * For example, in case we wouldn't have [[Task.delayExecution]]
    * already defined and we wanted to delay evaluation using a Java
    * [[https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/ScheduledExecutorService.html ScheduledExecutorService]]
    * (no need for that because we've got [[monix.execution.Scheduler Scheduler]],
    * but lets say for didactic purposes):
    *
    * {{{
    *   import java.util.concurrent.ScheduledExecutorService
    *   import scala.concurrent.duration._
    *   import scala.util.control.NonFatal
    *
    *   def delayed1[A](sc: ScheduledExecutorService, timespan: FiniteDuration)
    *     (thunk: => A): Task[A] = {
    *
    *     Task.cancelable0 { (scheduler, cb) =>
    *       val future = sc.schedule(new Runnable { // scheduling delay
    *         def run = scheduler.execute(new Runnable { // scheduling thunk execution
    *           def run() =
    *             try
    *               cb.onSuccess(thunk)
    *             catch { case NonFatal(e) =>
    *               cb.onError(e)
    *             }
    *           })
    *         },
    *         timespan.length,
    *         timespan.unit)
    *
    *       // Returning the cancel token that is able to cancel the
    *       // scheduling in case the active computation hasn't finished yet
    *       Task(future.cancel(false))
    *     }
    *   }
    * }}}
    *
    * As can be seen, the passed function needs to pass a
    * [[monix.execution.Cancelable Cancelable]] in order to specify cancelation
    * logic.
    *
    * This is a sample given for didactic purposes. Our `cancelable0` is
    * being injected a [[monix.execution.Scheduler Scheduler]] and it is
    * perfectly capable of doing such delayed execution without help from
    * Java's standard library:
    *
    * {{{
    *   def delayed2[A](timespan: FiniteDuration)(thunk: => A): Task[A] =
    *     Task.cancelable0 { (scheduler, cb) =>
    *       // N.B. this already returns the Cancelable that we need!
    *       val cancelable = scheduler.scheduleOnce(timespan) {
    *         try cb.onSuccess(thunk)
    *         catch { case NonFatal(e) => cb.onError(e) }
    *       }
    *       // `scheduleOnce` above returns a Cancelable, which
    *       // has to be converted into a Task[Unit]
    *       Task(cancelable.cancel())
    *     }
    * }}}
    *
    * CONTRACT for `register`:
    *
    *  - the provided function is executed when the `Task` will be evaluated
    *    (via `runAsync` or when its turn comes in the `flatMap` chain, not before)
    *  - the injected [[monix.execution.Callback Callback]] can be
    *    called at most once, either with a successful result, or with
    *    an error; calling it more than once is a contract violation
    *  - the injected callback is thread-safe and in case it gets called
    *    multiple times it will throw a
    *    [[monix.execution.exceptions.CallbackCalledMultipleTimesException]];
    *    also see [[monix.execution.Callback.tryOnSuccess Callback.tryOnSuccess]]
    *    and [[monix.execution.Callback.tryOnError Callback.tryOnError]]
    *
    * NOTES on the naming:
    *
    *  - `cancelable` comes from `cats.effect.Concurrent#cancelable`
    *  - the `0` suffix is about overloading the simpler
    *    [[BIO.cancelable[A](register* Task.cancelable]] builder
    *
    * @see [[BIO.cancelable[A](register* Task.cancelable]] for the simpler
    *      variant that doesn't inject the `Scheduler` in that callback
    * @see [[BIO.async0]] and [[BIO.async[A](register* Task.async]] for the
    *      simpler versions of this builder that create non-cancelable tasks
    *      from callback-based APIs
    * @see [[BIO.create]] for the builder that does it all
    * @param register $registerParamDesc
    */
  def cancelable0[E, A](register: (Scheduler, Callback[E, A]) => CancelToken[BIO[E, ?]]): BIO[E, A] =
    TaskCreate.cancelable0(register)

  /** Returns a cancelable boundary — a `Task` that checks for the
    * cancellation status of the run-loop and does not allow for the
    * bind continuation to keep executing in case cancellation happened.
    *
    * This operation is very similar to `Task.shift`, as it can be dropped
    * in `flatMap` chains in order to make loops cancelable.
    *
    * Example:
    *
    * {{{
    *
    *  import cats.syntax.all._
    *
    *  def fib(n: Int, a: Long, b: Long): Task[Long] =
    *    Task.suspend {
    *      if (n <= 0) Task.pure(a) else {
    *        val next = fib(n - 1, b, a + b)
    *
    *        // Every 100-th cycle, check cancellation status
    *        if (n % 100 == 0)
    *          Task.cancelBoundary *> next
    *        else
    *          next
    *      }
    *    }
    * }}}
    *
    * NOTE: that by default `Task` is configured to be auto-cancelable
    * (see [[BIO.Options]]), so this isn't strictly needed, unless you
    * want to fine tune the cancelation boundaries.
    */
  val cancelBoundary: UIO[Unit] =
    BIO.Async[Nothing, Unit] { (ctx, cb) =>
      if (!ctx.connection.isCanceled) cb.onSuccess(())
    }

  /** Polymorphic `Task` builder that is able to describe asynchronous
    * tasks depending on the type of the given callback.
    *
    * Note that this function uses the
    * [[https://typelevel.org/cats/guidelines.html#partially-applied-type-params Partially-Applied Type technique]].
    *
    * Calling `create` with a callback that returns `Unit` is
    * equivalent with [[Task.async0]]:
    *
    * `Task.async0(f) <-> Task.create(f)`
    *
    * Example:
    *
    * {{{
    *   import scala.concurrent.Future
    *
    *   def deferFuture[A](f: => Future[A]): Task[A] =
    *     Task.create { (scheduler, cb) =>
    *       f.onComplete(cb(_))(scheduler)
    *     }
    * }}}
    *
    * We could return a [[monix.execution.Cancelable Cancelable]]
    * reference and thus make a cancelable task. Example:
    *
    * {{{
    *   import monix.execution.Cancelable
    *   import scala.concurrent.duration.FiniteDuration
    *   import scala.util.Try
    *
    *   def delayResult1[A](timespan: FiniteDuration)(thunk: => A): Task[A] =
    *     Task.create { (scheduler, cb) =>
    *       val c = scheduler.scheduleOnce(timespan)(cb(Try(thunk)))
    *       // We can simply return `c`, but doing this for didactic purposes!
    *       Cancelable(() => c.cancel())
    *     }
    * }}}
    *
    * Passed function can also return `IO[Unit]` as a task that
    * describes a cancelation action:
    *
    * {{{
    *   import cats.effect.IO
    *
    *   def delayResult2[A](timespan: FiniteDuration)(thunk: => A): Task[A] =
    *     Task.create { (scheduler, cb) =>
    *       val c = scheduler.scheduleOnce(timespan)(cb(Try(thunk)))
    *       // We can simply return `c`, but doing this for didactic purposes!
    *       IO(c.cancel())
    *     }
    * }}}
    *
    * Passed function can also return `Task[Unit]` as a task that
    * describes a cancelation action, thus for an `f` that can be
    * passed to [[Task.cancelable0]], and this equivalence holds:
    *
    * `Task.cancelable(f) <-> Task.create(f)`
    *
    * {{{
    *   def delayResult3[A](timespan: FiniteDuration)(thunk: => A): Task[A] =
    *     Task.create { (scheduler, cb) =>
    *       val c = scheduler.scheduleOnce(timespan)(cb(Try(thunk)))
    *       // We can simply return `c`, but doing this for didactic purposes!
    *       Task(c.cancel())
    *     }
    * }}}
    *
    * Passed function can also return `Coeval[Unit]` as a task that
    * describes a cancelation action:
    *
    * {{{
    *   def delayResult4[A](timespan: FiniteDuration)(thunk: => A): Task[A] =
    *     Task.create { (scheduler, cb) =>
    *       val c = scheduler.scheduleOnce(timespan)(cb(Try(thunk)))
    *       // We can simply return `c`, but doing this for didactic purposes!
    *       Coeval(c.cancel())
    *     }
    * }}}
    *
    * The supported types for the cancelation tokens are:
    *
    *  - `Unit`, yielding non-cancelable tasks
    *  - [[monix.execution.Cancelable Cancelable]], the Monix standard
    *  - [[monix.bio.Task Task[Unit]]]
    *  - [[monix.eval.Coeval Coeval[Unit]]]
    *  - `cats.effect.IO[Unit]`, see
    *    [[https://typelevel.org/cats-effect/datatypes/io.html IO docs]]
    *
    * Support for more might be added in the future.
    */
  def create[E, A]: AsyncBuilder.CreatePartiallyApplied[E, A] = new AsyncBuilder.CreatePartiallyApplied[E, A]

  /** Converts the given Scala `Future` into a `Task`.
    * There is an async boundary inserted at the end to guarantee
    * that we stay on the main Scheduler.
    *
    * NOTE: if you want to defer the creation of the future, use
    * in combination with [[defer]].
    */
  def fromFuture[A](f: Future[A]): Task[A] =
    TaskFromFuture.strict(f)

  /** Wraps a [[monix.execution.CancelablePromise]] into `Task`. */
  def fromCancelablePromise[A](p: CancelablePromise[A]): Task[A] =
    TaskFromFuture.fromCancelablePromise(p)

  /** Run two `Task` actions concurrently, and return the first to
    * finish, either in success or error. The loser of the race is
    * cancelled.
    *
    * The two tasks are executed in parallel, the winner being the
    * first that signals a result.
    *
    * As an example, this would be equivalent with [[Task.timeout]]:
    * {{{
    *   import scala.concurrent.duration._
    *   import scala.concurrent.TimeoutException
    *
    *   // some long running task
    *   val myTask = Task(42)
    *
    *   val timeoutError = Task
    *     .raiseError(new TimeoutException)
    *     .delayExecution(5.seconds)
    *
    *   Task.race(myTask, timeoutError)
    * }}}
    *
    * Similarly [[Task.timeoutTo]] is expressed in terms of `race`.
    *
    * $parallelismNote
    *
    * @see [[racePair]] for a version that does not cancel
    *     the loser automatically on successful results and [[raceMany]]
    *     for a version that races a whole list of tasks.
    */
  def race[E, A, B](fa: BIO[E, A], fb: BIO[E, B]): BIO[E, Either[A, B]] =
    TaskRace(fa, fb)

  /** Run two `Task` actions concurrently, and returns a pair
    * containing both the winner's successful value and the loser
    * represented as a still-unfinished task.
    *
    * If the first task completes in error, then the result will
    * complete in error, the other task being cancelled.
    *
    * On usage the user has the option of cancelling the losing task,
    * this being equivalent with plain [[race]]:
    *
    * {{{
    *   import scala.concurrent.duration._
    *
    *   val ta = Task.sleep(2.seconds).map(_ => "a")
    *   val tb = Task.sleep(3.seconds).map(_ => "b")
    *
    *   // `tb` is going to be cancelled as it returns 1 second after `ta`
    *   Task.racePair(ta, tb).flatMap {
    *     case Left((a, taskB)) =>
    *       taskB.cancel.map(_ => a)
    *     case Right((taskA, b)) =>
    *       taskA.cancel.map(_ => b)
    *   }
    * }}}
    *
    * $parallelismNote
    *
    * @see [[race]] for a simpler version that cancels the loser
    *      immediately or [[raceMany]] that races collections of tasks.
    */
  def racePair[E, A, B](fa: BIO[E, A], fb: BIO[E, B]): BIO[E, Either[(A, Fiber[E, B]), (Fiber[E, A], B)]] =
    TaskRacePair(fa, fb)

  /** Asynchronous boundary described as an effectful `Task` that
    * can be used in `flatMap` chains to "shift" the continuation
    * of the run-loop to another thread or call stack, managed by
    * the default [[monix.execution.Scheduler Scheduler]].
    *
    * This is the equivalent of `IO.shift`, except that Monix's `Task`
    * gets executed with an injected `Scheduler` in [[BIO.runAsync]] or
    * in [[BIO.runToFuture]] and that's going to be the `Scheduler`
    * responsible for the "shift".
    *
    * $shiftDesc
    */
  val shift: UIO[Unit] =
    shift(null)

  /** Asynchronous boundary described as an effectful `Task` that
    * can be used in `flatMap` chains to "shift" the continuation
    * of the run-loop to another call stack or thread, managed by
    * the given execution context.
    *
    * This is the equivalent of `IO.shift`.
    *
    * $shiftDesc
    */
  def shift(ec: ExecutionContext): UIO[Unit] =
    TaskShift(ec)

  /** Creates a new `Task` that will sleep for the given duration,
    * emitting a tick when that time span is over.
    *
    * As an example on evaluation this will print "Hello!" after
    * 3 seconds:
    *
    * {{{
    *   import scala.concurrent.duration._
    *
    *   Task.sleep(3.seconds).flatMap { _ =>
    *     Task.eval(println("Hello!"))
    *   }
    * }}}
    *
    * See [[BIO.delayExecution]] for this operation described as
    * a method on `Task` references or [[BIO.delayResult]] for the
    * helper that triggers the evaluation of the source on time, but
    * then delays the result.
    */
  def sleep(timespan: FiniteDuration): UIO[Unit] =
    TaskSleep.apply(timespan)

  /** Given a `Iterable` of tasks, transforms it to a task signaling
    * the collection, executing the tasks one by one and gathering their
    * results in the same collection.
    *
    * This operation will execute the tasks one by one, in order, which means that
    * both effects and results will be ordered. See [[gather]] and [[gatherUnordered]]
    * for unordered results or effects, and thus potential of running in parallel.
    *
    *  It's a simple version of [[traverse]].
    */
  def sequence[E, A, M[X] <: Iterable[X]](in: M[BIO[E, A]])(
    implicit bf: BuildFrom[M[BIO[E, A]], A, M[A]]): BIO[E, M[A]] =
    TaskSequence.list(in)(bf)

  /** Given a `Iterable[A]` and a function `A => Task[B]`, sequentially
    * apply the function to each element of the collection and gather their
    * results in the same collection.
    *
    *  It's a generalized version of [[sequence]].
    */
  def traverse[E, A, B, M[X] <: Iterable[X]](in: M[A])(f: A => BIO[E, B])(
    implicit bf: BuildFrom[M[A], B, M[B]]): BIO[E, M[B]] =
    TaskSequence.traverse(in, f)(bf)

  /** Executes the given sequence of tasks in parallel, non-deterministically
    * gathering their results, returning a task that will signal the sequence
    * of results once all tasks are finished.
    *
    * This function is the nondeterministic analogue of `sequence` and should
    * behave identically to `sequence` so long as there is no interaction between
    * the effects being gathered. However, unlike `sequence`, which decides on
    * a total order of effects, the effects in a `gather` are unordered with
    * respect to each other, the tasks being execute in parallel, not in sequence.
    *
    * Although the effects are unordered, we ensure the order of results
    * matches the order of the input sequence. Also see [[gatherUnordered]]
    * for the more efficient alternative.
    *
    * Example:
    * {{{
    *   val tasks = List(Task(1 + 1), Task(2 + 2), Task(3 + 3))
    *
    *   // Yields 2, 4, 6
    *   Task.gather(tasks)
    * }}}
    *
    * $parallelismAdvice
    *
    * $parallelismNote
    *
    * @see [[gatherN]] for a version that limits parallelism.
    */
  def gather[E, A, M[X] <: Iterable[X]](in: M[BIO[E, A]])(implicit bf: BuildFrom[M[BIO[E, A]], A, M[A]]): BIO[E, M[A]] =
    TaskGather[E, A, M](in, () => newBuilder(bf, in))

  /** Executes the given sequence of tasks in parallel, non-deterministically
    * gathering their results, returning a task that will signal the sequence
    * of results once all tasks are finished.
    *
    * Implementation ensure there are at most `n` (= `parallelism` parameter) tasks
    * running concurrently and the results are returned in order.
    *
    * Example:
    * {{{
    *   import scala.concurrent.duration._
    *
    *   val tasks = List(
    *     Task(1 + 1).delayExecution(1.second),
    *     Task(2 + 2).delayExecution(2.second),
    *     Task(3 + 3).delayExecution(3.second),
    *     Task(4 + 4).delayExecution(4.second)
    *    )
    *
    *   // Yields 2, 4, 6, 8 after around 6 seconds
    *   Task.gatherN(2)(tasks)
    * }}}
    *
    * $parallelismAdvice
    *
    * $parallelismNote
    *
    * @see [[gather]] for a version that does not limit parallelism.
    */
  def gatherN[E, A](parallelism: Int)(in: Iterable[BIO[E, A]]): BIO[E, List[A]] =
    TaskGatherN[E, A](parallelism, in)

  /** Processes the given collection of tasks in parallel and
    * nondeterministically gather the results without keeping the original
    * ordering of the given tasks.
    *
    * This function is similar to [[gather]], but neither the effects nor the
    * results will be ordered. Useful when you don't need ordering because:
    *
    *  - it has non-blocking behavior (but not wait-free)
    *  - it can be more efficient (compared with [[gather]]), but not
    *    necessarily (if you care about performance, then test)
    *
    * Example:
    * {{{
    *   val tasks = List(Task(1 + 1), Task(2 + 2), Task(3 + 3))
    *
    *   // Yields 2, 4, 6 (but order is NOT guaranteed)
    *   Task.gatherUnordered(tasks)
    * }}}
    *
    * $parallelismAdvice
    *
    * $parallelismNote
    *
    * @param in is a list of tasks to execute
    */
  def gatherUnordered[E, A](in: Iterable[BIO[E, A]]): BIO[E, List[A]] =
    TaskGatherUnordered(in)

  /** Yields a task that on evaluation will process the given tasks
    * in parallel, then apply the given mapping function on their results.
    *
    * Example:
    * {{{
    *   val task1 = Task(1 + 1)
    *   val task2 = Task(2 + 2)
    *
    *   // Yields 6
    *   Task.mapBoth(task1, task2)((a, b) => a + b)
    * }}}
    *
    * $parallelismAdvice
    *
    * $parallelismNote
    */
  def mapBoth[E, A1, A2, R](fa1: BIO[E, A1], fa2: BIO[E, A2])(f: (A1, A2) => R): BIO[E, R] =
    TaskMapBoth(fa1, fa2)(f)

  /** Returns the current [[BIO.Options]] configuration, which determine the
    * task's run-loop behavior.
    *
    * @see [[BIO.executeWithOptions]]
    */
  val readOptions: UIO[Options] =
    BIO
      .Async[Nothing, Options]((ctx, cb) => cb.onSuccess(ctx.options), trampolineBefore = false, trampolineAfter = true)

  /** Set of options for customizing the task's behavior.
    *
    * See [[BIO.defaultOptions]] for the default `Options` instance
    * used by [[BIO.runAsync]] or [[BIO.runToFuture]].
    *
    * @param autoCancelableRunLoops  should be set to `true` in
    *                                case you want `flatMap` driven loops to be
    *                                auto-cancelable. Defaults to `true`.
    * @param localContextPropagation should be set to `true` in
    *                                case you want the [[monix.execution.misc.Local Local]]
    *                                variables to be propagated on async boundaries.
    *                                Defaults to `false`.
    */
  final case class Options(
    autoCancelableRunLoops: Boolean,
    localContextPropagation: Boolean
  ) {

    /** Creates a new set of options from the source, but with
      * the [[autoCancelableRunLoops]] value set to `true`.
      */
    def enableAutoCancelableRunLoops: Options =
      copy(autoCancelableRunLoops = true)

    /** Creates a new set of options from the source, but with
      * the [[autoCancelableRunLoops]] value set to `false`.
      */
    def disableAutoCancelableRunLoops: Options =
      copy(autoCancelableRunLoops = false)

    /** Creates a new set of options from the source, but with
      * the [[localContextPropagation]] value set to `true`.
      */
    def enableLocalContextPropagation: Options =
      copy(localContextPropagation = true)

    /** Creates a new set of options from the source, but with
      * the [[localContextPropagation]] value set to `false`.
      */
    def disableLocalContextPropagation: Options =
      copy(localContextPropagation = false)

    /**
      * Enhances the options set with the features of the underlying
      * [[monix.execution.Scheduler Scheduler]].
      *
      * This enables for example the [[Options.localContextPropagation]]
      * in case the `Scheduler` is a
      * [[monix.execution.schedulers.TracingScheduler TracingScheduler]].
      */
    def withSchedulerFeatures(implicit s: Scheduler): Options = {
      val wLocals = s.features.contains(Scheduler.TRACING)
      if (wLocals == localContextPropagation)
        this
      else
        copy(localContextPropagation = wLocals || localContextPropagation)
    }
  }

  /** Default [[Options]] to use for [[BIO]] evaluation,
    * thus:
    *
    *  - `autoCancelableRunLoops` is `true` by default
    *  - `localContextPropagation` is `false` by default
    *
    * On top of the JVM the default can be overridden by
    * setting the following system properties:
    *
    *  - `monix.environment.autoCancelableRunLoops`
    *    (`false`, `no` or `0` for disabling)
    *
    *  - `monix.environment.localContextPropagation`
    *    (`true`, `yes` or `1` for enabling)
    *
    * @see [[BIO.Options]]
    */
  val defaultOptions: Options =
    Options(
      autoCancelableRunLoops = Platform.autoCancelableRunLoops,
      localContextPropagation = Platform.localContextPropagation
    )

  /** The `AsyncBuilder` is a type used by the [[BIO.create]] builder,
    * in order to change its behavior based on the type of the
    * cancelation token.
    *
    * In combination with the
    * [[https://typelevel.org/cats/guidelines.html#partially-applied-type-params Partially-Applied Type technique]],
    * this ends up providing a polymorphic [[BIO.create]] that can
    * support multiple cancelation tokens optimally, i.e. without
    * implicit conversions and that can be optimized depending on
    * the `CancelToken` used - for example if `Unit` is returned,
    * then the yielded task will not be cancelable and the internal
    * implementation will not have to worry about managing it, thus
    * increasing performance.
    */
  abstract class AsyncBuilder[CancelationToken] {
    def create[E, A](register: (Scheduler, Callback[E, A]) => CancelationToken): BIO[E, A]
  }

  object AsyncBuilder extends AsyncBuilder0 {

    /** Returns the implicit `AsyncBuilder` available in scope for the
      * given `CancelToken` type.
      */
    def apply[CancelationToken](implicit ref: AsyncBuilder[CancelationToken]): AsyncBuilder[CancelationToken] = ref

    /** For partial application of type parameters in [[BIO.create]].
      *
      * Read about the
      * [[https://typelevel.org/cats/guidelines.html#partially-applied-type-params Partially-Applied Type Technique]].
      */
    private[bio] final class CreatePartiallyApplied[E, A](val dummy: Boolean = true) extends AnyVal {

      def apply[CancelationToken](register: (Scheduler, Callback[E, A]) => CancelationToken)(
        implicit B: AsyncBuilder[CancelationToken]): BIO[E, A] =
        B.create(register)
    }

    /** Implicit `AsyncBuilder` for non-cancelable tasks. */
    implicit val forUnit: AsyncBuilder[Unit] =
      new AsyncBuilder[Unit] {

        def create[E, A](register: (Scheduler, Callback[E, A]) => Unit): BIO[E, A] =
          TaskCreate.async0(register)
      }

    // TODO: implement create forIO
    //    /** Implicit `AsyncBuilder` for cancelable tasks, using
    //      * `cats.effect.IO` values for specifying cancelation actions,
    //      * see [[https://typelevel.org/cats-effect/ Cats Effect]].
    //      */
    //    implicit val forIO: AsyncBuilder[IO[Unit]] =
    //      new AsyncBuilder[IO[Unit]] {
    //        def create[A](register: (Scheduler, Callback[E, A]) => CancelToken[IO]): Task[A] =
    //          TaskCreate.cancelableIO(register)
    //      }

    // TODO: Task.create
    //    /** Implicit `AsyncBuilder` for cancelable tasks, using
    //      * [[Task]] values for specifying cancelation actions.
    //      */
    //    implicit def forTask[E1]: AsyncBuilder[BIO[E1, Unit]] =
    //      new AsyncBuilder[BIO[E1, Unit]] {
    //
    //        override def create[E, A](register: (Scheduler, Callback[E, A]) => BIO[E1, Unit]): BIO[E, A] =
    //          TaskCreate.cancelable0(register)
    //      }

    /** Implicit `AsyncBuilder` for non-cancelable tasks built by a function
      * returning a [[monix.execution.Cancelable.Empty Cancelable.Empty]].
      *
      * This is a case of applying a compile-time optimization trick,
      * completely ignoring the provided cancelable value, since we've got
      * a guarantee that it doesn't do anything.
      */
    implicit def forCancelableDummy[T <: Cancelable.Empty]: AsyncBuilder[T] =
      forCancelableDummyRef.asInstanceOf[AsyncBuilder[T]]

    private[this] val forCancelableDummyRef: AsyncBuilder[Cancelable.Empty] =
      new AsyncBuilder[Cancelable.Empty] {

        def create[E, A](register: (Scheduler, Callback[E, A]) => Cancelable.Empty): BIO[E, A] =
          TaskCreate.async0(register)
      }
  }

  private[BIO] abstract class AsyncBuilder0 {

    /**
      * Implicit `AsyncBuilder` for cancelable tasks, using
      * [[monix.execution.Cancelable Cancelable]] values for
      * specifying cancelation actions.
      */
    implicit def forCancelable[T <: Cancelable]: AsyncBuilder[T] =
      forCancelableRef.asInstanceOf[AsyncBuilder[T]]

    private[this] val forCancelableRef =
      new AsyncBuilder[Cancelable] {

        def create[E, A](register: (Scheduler, Callback[E, A]) => Cancelable): BIO[E, A] =
          TaskCreate.cancelableCancelable(register)
      }
  }

  /** Internal API — The `Context` under which [[Task]] is supposed to be executed.
    *
    * This has been hidden in version 3.0.0-RC2, becoming an internal
    * implementation detail. Soon to be removed or changed completely.
    */
  private[bio] final case class Context[E](
    private val schedulerRef: Scheduler,
    options: Options,
    connection: TaskConnection[E],
    frameRef: FrameIndexRef) {

    val scheduler: Scheduler = {
      if (options.localContextPropagation && !schedulerRef.features.contains(Scheduler.TRACING))
        TracingScheduler(schedulerRef)
      else
        schedulerRef
    }

    def shouldCancel: Boolean =
      options.autoCancelableRunLoops &&
        connection.isCanceled

    def executionModel: ExecutionModel =
      schedulerRef.executionModel

    def withScheduler(s: Scheduler): Context[E] =
      new Context(s, options, connection, frameRef)

    def withExecutionModel(em: ExecutionModel): Context[E] =
      new Context(schedulerRef.withExecutionModel(em), options, connection, frameRef)

    def withOptions(opts: Options): Context[E] =
      new Context(schedulerRef, opts, connection, frameRef)

    def withConnection[E1 >: E](conn: TaskConnection[E1]): Context[E1] =
      new Context(schedulerRef, options, conn, frameRef)
  }

  private[bio] object Context {

    def apply[E](scheduler: Scheduler, options: Options): Context[E] =
      apply(scheduler, options, TaskConnection[E]())

    def apply[E](scheduler: Scheduler, options: Options, connection: TaskConnection[E]): Context[E] = {
      val em = scheduler.executionModel
      val frameRef = FrameIndexRef(em)
      new Context(scheduler, options, connection, frameRef)
    }
  }

  /** [[Task]] state describing an immediate synchronous value. */
  private[bio] final case class Now[+A](value: A) extends BIO[Nothing, A] {

    // Optimization to avoid the run-loop
    override def runAsyncOptF[E](
      cb: Either[Either[Throwable, E], A] => Unit)(implicit s: Scheduler, opts: BIO.Options): CancelToken[BIO[E, ?]] = {
      if (s.executionModel != AlwaysAsyncExecution) {
        BiCallback.callSuccess(cb, value)
        BIO.unit
      } else {
        super.runAsyncOptF(cb)(s, opts)
      }
    }

    // Optimization to avoid the run-loop
    override def runToFutureOpt[E](implicit s: Scheduler, opts: Options): CancelableFuture[Either[E, A]] = {
      CancelableFuture.successful(Right(value))
    }

    // Optimization to avoid the run-loop
    override def runAsyncOpt(
      cb: Either[Either[Throwable, Nothing], A] => Unit)(implicit s: Scheduler, opts: Options): Cancelable = {
      if (s.executionModel != AlwaysAsyncExecution) {
        BiCallback.callSuccess(cb, value)
        Cancelable.empty
      } else {
        super.runAsyncOpt(cb)(s, opts)
      }
    }

    // Optimization to avoid the run-loop
    override def runAsyncUncancelableOpt(cb: Either[Either[Throwable, Nothing], A] => Unit)(
      implicit s: Scheduler,
      opts: Options
    ): Unit = {
      if (s.executionModel != AlwaysAsyncExecution)
        BiCallback.callSuccess(cb, value)
      else
        super.runAsyncUncancelableOpt(cb)(s, opts)
    }

    // Optimization to avoid the run-loop
    override def runAsyncAndForgetOpt(implicit s: Scheduler, opts: Options): Unit =
      ()
  }

  /** [[Task]] state describing an immediate exception. */
  private[bio] final case class Error[E](e: E) extends BIO[E, Nothing] {

    // Optimization to avoid the run-loop
    override def runAsyncOptF[E1 >: E](cb: Either[Either[Throwable, E1], Nothing] => Unit)(
      implicit s: Scheduler,
      opts: BIO.Options): CancelToken[BIO[E1, ?]] = {
      if (s.executionModel != AlwaysAsyncExecution) {
        BiCallback.callError(cb, e)
        BIO.unit
      } else {
        super.runAsyncOptF(cb)(s, opts)
      }
    }

    // Optimization to avoid the run-loop
    override def runToFutureOpt[E1 >: E](
      implicit s: Scheduler,
      opts: Options): CancelableFuture[Either[E1, Nothing]] = {
      CancelableFuture.successful(Left(e))
    }

    // Optimization to avoid the run-loop
    override def runAsyncOpt(
      cb: Either[Either[Throwable, E], Nothing] => Unit)(implicit s: Scheduler, opts: Options): Cancelable = {
      if (s.executionModel != AlwaysAsyncExecution) {
        BiCallback.callError(cb, e)
        Cancelable.empty
      } else {
        super.runAsyncOpt(cb)(s, opts)
      }
    }

    // Optimization to avoid the run-loop
    override def runAsyncAndForgetOpt(implicit s: Scheduler, opts: Options): Unit = {
      e match {
        case th: Throwable => s.reportFailure(th)
        case _ => s.reportFailure(WrappedException(e))
      }
    }

    // Optimization to avoid the run-loop
    override def runAsyncUncancelableOpt(cb: Either[Either[Throwable, E], Nothing] => Unit)(
      implicit s: Scheduler,
      opts: Options
    ): Unit = {
      if (s.executionModel != AlwaysAsyncExecution)
        BiCallback.callError(cb, e)
      else
        super.runAsyncUncancelableOpt(cb)(s, opts)
    }
  }

  // TODO: revise FatalError optimizations

  /** [[Task]] state describing an immediate exception. */
  private[bio] final case class FatalError(e: Throwable) extends BIO[Nothing, Nothing] {

    // Optimization to avoid the run-loop
    override def runToFutureOpt[E1 >: Nothing](
      implicit s: Scheduler,
      opts: Options): CancelableFuture[Either[Nothing, Nothing]] =
      CancelableFuture.failed(e)

    // Optimization to avoid the run-loop
    override def runAsyncAndForgetOpt(implicit s: Scheduler, opts: Options): Unit =
      s.reportFailure(e)
  }

  /** [[BIO]] state describing an non-strict synchronous value. */
  private[bio] final case class Eval[+E, +A](thunk: () => A) extends BIO[E, A]

  /** Internal state, the result of [[BIO.defer]] */
  private[bio] final case class Suspend[+E, +A](thunk: () => BIO[E, A]) extends BIO[E, A]

  /** Internal [[BIO]] state that is the result of applying `flatMap`. */
  private[bio] final case class FlatMap[E, E1, A, +B](source: BIO[E, A], f: A => BIO[E1, B]) extends BIO[E1, B]

  /** Internal [[BIO]] state that is the result of applying `map`. */
  private[bio] final case class Map[S, +E, +A](source: BIO[E, S], f: S => A, index: Int)
      extends BIO[E, A] with (S => BIO[E, A]) {

    def apply(value: S): BIO[E, A] =
      new Now[A](f(value))

    override def toString: String =
      super[BIO].toString
  }

  /** Constructs a lazy [[BIO]] instance whose result will
    * be computed asynchronously.
    *
    * Unsafe to build directly, only use if you know what you're doing.
    * For building `Async` instances safely, see [[cancelable0]].
    *
    * @param register is the side-effecting, callback-enabled function
    *        that starts the asynchronous computation and registers
    *        the callback to be called on completion
    * @param trampolineBefore is an optimization that instructs the
    *        run-loop to insert a trampolined async boundary before
    *        evaluating the `register` function
    */
  private[monix] final case class Async[E, +A](
    register: (Context[E], BiCallback[E, A]) => Unit,
    trampolineBefore: Boolean = false,
    trampolineAfter: Boolean = true,
    restoreLocals: Boolean = true)
      extends BIO[E, A]

  /** For changing the context for the rest of the run-loop.
    *
    * WARNING: this is entirely internal API and shouldn't be exposed.
    */
  private[monix] final case class ContextSwitch[E, A](
    source: BIO[E, A],
    modify: Context[E] => Context[E],
    restore: (A, E, Context[E], Context[E]) => Context[E])
      extends BIO[E, A]

  /**
    * Internal API — starts the execution of a Task with a guaranteed
    * asynchronous boundary.
    */
  private[monix] def unsafeStartAsync[E, A](source: BIO[E, A], context: Context[E], cb: BiCallback[E, A]): Unit =
    TaskRunLoop.restartAsync(source, context, cb, null, null, null)

  /** Internal API — a variant of [[unsafeStartAsync]] that tries to
    * detect if the `source` is known to fork and in such a case it
    * avoids creating an extraneous async boundary.
    */
  private[monix] def unsafeStartEnsureAsync[E, A](
    source: BIO[E, A],
    context: Context[E],
    cb: BiCallback[E, A]): Unit = {
    if (ForkedRegister.detect(source))
      unsafeStartNow(source, context, cb)
    else
      unsafeStartAsync(source, context, cb)
  }

  /**
    * Internal API — starts the execution of a Task with a guaranteed
    * trampolined async boundary.
    */
  private[monix] def unsafeStartTrampolined[E, A](source: BIO[E, A], context: Context[E], cb: BiCallback[E, A]): Unit =
    context.scheduler.execute(new TrampolinedRunnable {

      def run(): Unit =
        TaskRunLoop.startFull(source, context, cb, null, null, null, context.frameRef())
    })

  /**
    * Internal API - starts the immediate execution of a Task.
    */
  private[monix] def unsafeStartNow[E, A](source: BIO[E, A], context: Context[E], cb: BiCallback[E, A]): Unit =
    TaskRunLoop.startFull(source, context, cb, null, null, null, context.frameRef())

  /** Internal, reusable reference. */
  private[this] val neverRef: Async[Nothing, Nothing] =
    Async[Nothing, Nothing]((_, _) => (), trampolineBefore = false, trampolineAfter = false)

  /** Internal, reusable reference. */
  private val nowConstructor: Any => UIO[Nothing] =
    ((a: Any) => new Now(a)).asInstanceOf[Any => UIO[Nothing]]

  private def raiseConstructor[E]: E => BIO[E, Nothing] =
    raiseConstructorRef.asInstanceOf[E => BIO[E, Nothing]]

  /** Internal, reusable reference. */
  private val raiseConstructorRef: Any => BIO[Any, Nothing] =
    e => new Error(e)

  /** Used as optimization by [[BIO.failed]]. */
  private object Failed extends StackFrame[Any, Any, UIO[Any]] {

    def apply(a: Any): UIO[Any] =
      FatalError(new NoSuchElementException("failed"))

    def recover(e: Any): UIO[Any] =
      Now(e)
  }

  /** Used as optimization by [[BIO.redeem]]. */
  private final class Redeem[E, A, B](fe: E => B, fs: A => B) extends StackFrame[E, A, UIO[B]] {
    def apply(a: A): UIO[B] = new Now(fs(a))
    def recover(e: E): UIO[B] = new Now(fe(e))
  }

  /** Used as optimization by [[BIO.redeemFatal]]. */
  private final class RedeemFatal[A, B](fe: Throwable => B, fs: A => B)
      extends StackFrame.FatalStackFrame[Throwable, A, UIO[B]] {
    override def apply(a: A): UIO[B] = new Now(fs(a))
    override def recover(e: Throwable): UIO[B] = new Now(fe(e))
    override def recoverFatal(e: Throwable): UIO[B] = new Now(fe(e))
  }

  /** Used as optimization by [[BIO.attempt]]. */
  private object AttemptTask extends StackFrame[Any, Any, UIO[Either[Any, Any]]] {

    override def apply(a: Any): UIO[Either[Any, Any]] =
      new Now(new Right(a))

    override def recover(e: Any): UIO[Either[Any, Any]] =
      new Now(new Left(e))
  }

  /** Used as optimization by [[BIO.materialize]]. */
  private object MaterializeTask extends StackFrame[Throwable, Any, UIO[Try[Any]]] {

    override def apply(a: Any): UIO[Try[Any]] =
      new Now(new Success(a))

    override def recover(e: Throwable): UIO[Try[Any]] =
      new Now(new Failure(e))
  }
}

private[bio] abstract class TaskInstancesLevel0 extends TaskInstancesLevel1 {

  /** Global instance for `cats.effect.Async` and for `cats.effect.Concurrent`.
    *
    * Implied are also `cats.CoflatMap`, `cats.Applicative`, `cats.Monad`,
    * `cats.MonadError` and `cats.effect.Sync`.
    *
    * As trivia, it's named "catsAsync" and not "catsConcurrent" because
    * it represents the `cats.effect.Async` lineage, up until
    * `cats.effect.Effect`, which imposes extra restrictions, in our case
    * the need for a `Scheduler` to be in scope (see [[BIO.catsEffect]]).
    * So by naming the lineage, not the concrete sub-type implemented, we avoid
    * breaking compatibility whenever a new type class (that we can implement)
    * gets added into Cats.
    *
    * Seek more info about Cats, the standard library for FP, at:
    *
    *  - [[https://typelevel.org/cats/ typelevel/cats]]
    *  - [[https://github.com/typelevel/cats-effect typelevel/cats-effect]]
    */
  implicit def catsAsync: CatsConcurrentForTask =
    CatsConcurrentForTask

  /** Global instance for `cats.Parallel`.
    *
    * The `Parallel` type class is useful for processing
    * things in parallel in a generic way, usable with
    * Cats' utils and syntax:
    *
    * {{{
    *   import cats.syntax.all._
    *   import scala.concurrent.duration._
    *
    *   val taskA = Task.sleep(1.seconds).map(_ => "a")
    *   val taskB = Task.sleep(2.seconds).map(_ => "b")
    *   val taskC = Task.sleep(3.seconds).map(_ => "c")
    *
    *   // Returns "abc" after 3 seconds
    *   (taskA, taskB, taskC).parMapN { (a, b, c) =>
    *     a + b + c
    *   }
    * }}}
    *
    * Seek more info about Cats, the standard library for FP, at:
    *
    *  - [[https://typelevel.org/cats/ typelevel/cats]]
    *  - [[https://github.com/typelevel/cats-effect typelevel/cats-effect]]
    */
  implicit def catsParallel[E]: Parallel.Aux[BIO[E, ?], BIO.Par[E, ?]] =
    new CatsParallelForTask[E]

  // TODO: implement CatsMonoid
}

private[bio] abstract class TaskInstancesLevel1 extends TaskInstancesLevel2 {

  /** Global instance for `cats.effect.Effect` and for
    * `cats.effect.ConcurrentEffect`.
    *
    * Implied are `cats.CoflatMap`, `cats.Applicative`, `cats.Monad`,
    * `cats.MonadError`, `cats.effect.Sync` and `cats.effect.Async`.
    *
    * Note this is different from
    * [[monix.bio.BIO.catsAsync TWRYYYsk.catsAsync]] because we need an
    * implicit [[monix.execution.Scheduler Scheduler]] in scope in
    * order to trigger the execution of a `Task`. It's also lower
    * priority in order to not trigger conflicts, because
    * `Effect <: Async` and `ConcurrentEffect <: Concurrent with Effect`.
    *
    * As trivia, it's named "catsEffect" and not "catsConcurrentEffect"
    * because it represents the `cats.effect.Effect` lineage, as in the
    * minimum that this value will support in the future. So by naming the
    * lineage, not the concrete sub-type implemented, we avoid breaking
    * compatibility whenever a new type class (that we can implement)
    * gets added into Cats.
    *
    * Seek more info about Cats, the standard library for FP, at:
    *
    *  - [[https://typelevel.org/cats/ typelevel/cats]]
    *  - [[https://github.com/typelevel/cats-effect typelevel/cats-effect]]
    *
    * @param s is a [[monix.execution.Scheduler Scheduler]] that needs
    *        to be available in scope
    */
  implicit def catsEffect(
    implicit s: Scheduler,
    opts: BIO.Options = BIO.defaultOptions): CatsConcurrentEffectForTask = {
    new CatsConcurrentEffectForTask
  }

  // TODO: implement catsSemigroup
}

private[bio] abstract class TaskInstancesLevel2 extends TaskParallelNewtype {

  implicit def monadError[E]: CatsBaseForTask[E] =
    new CatsBaseForTask[E]
}

private[bio] abstract class TaskParallelNewtype extends TaskContextShift {

  /** Newtype encoding for a `Task` data type that has a [[cats.Applicative]]
    * capable of doing parallel processing in `ap` and `map2`, needed
    * for implementing `cats.Parallel`.
    *
    * Helpers are provided for converting back and forth in `Par.apply`
    * for wrapping any `Task` value and `Par.unwrap` for unwrapping.
    *
    * The encoding is based on the "newtypes" project by
    * Alexander Konovalov, chosen because it's devoid of boxing issues and
    * a good choice until opaque types will land in Scala.
    */
  type Par[+E, +A] = Par.Type[E, A]

  /** Newtype encoding, see the [[BIO.Par]] type alias
    * for more details.
    */
  object Par extends Newtype2[BIO]
}

private[bio] abstract class TaskContextShift extends TaskTimers {

  /**
    * Default, pure, globally visible `cats.effect.ContextShift`
    * implementation that shifts the evaluation to `Task`'s default
    * [[monix.execution.Scheduler Scheduler]]
    * (that's being injected in [[BIO.runToFuture]]).
    */
  implicit def contextShift[E]: ContextShift[BIO[E, ?]] =
    contextShiftAny.asInstanceOf[ContextShift[BIO[E, ?]]]

  private[this] final val contextShiftAny: ContextShift[BIO[Any, ?]] =
    new ContextShift[BIO[Any, ?]] {

      override def shift: BIO[Any, Unit] =
        BIO.shift

      override def evalOn[A](ec: ExecutionContext)(fa: BIO[Any, A]): BIO[Any, A] =
        ec match {
          case ref: Scheduler => fa.executeOn(ref, forceAsync = true)
          case _ => fa.executeOn(Scheduler(ec), forceAsync = true)
        }

    }

  /** Builds a `cats.effect.ContextShift` instance, given a
    * [[monix.execution.Scheduler Scheduler]] reference.
    */
  def contextShift(s: Scheduler): ContextShift[BIO[Any, ?]] =
    new ContextShift[BIO[Any, ?]] {

      override def shift: BIO[Any, Unit] =
        BIO.shift(s)

      override def evalOn[A](ec: ExecutionContext)(fa: BIO[Any, A]): BIO[Any, A] =
        ec match {
          case ref: Scheduler => fa.executeOn(ref, forceAsync = true)
          case _ => fa.executeOn(Scheduler(ec), forceAsync = true)
        }

    }
}

private[bio] abstract class TaskTimers extends TaskClocks {

  /**
    * Default, pure, globally visible `cats.effect.Timer`
    * implementation that defers the evaluation to `Task`'s default
    * [[monix.execution.Scheduler Scheduler]]
    * (that's being injected in [[BIO.runToFuture]]).
    */
  implicit def timer[E]: Timer[BIO[E, ?]] =
    timerAny.asInstanceOf[Timer[BIO[E, ?]]]

  private[this] final val timerAny: Timer[BIO[Any, ?]] =
    new Timer[BIO[Any, ?]] {

      override def sleep(duration: FiniteDuration): BIO[Any, Unit] =
        BIO.sleep(duration)

      override def clock: Clock[BIO[Any, ?]] =
        BIO.clock
    }

  /** Builds a `cats.effect.Timer` instance, given a
    * [[monix.execution.Scheduler Scheduler]] reference.
    */
  def timer(s: Scheduler): Timer[BIO[Any, ?]] =
    new Timer[BIO[Any, ?]] {

      override def sleep(duration: FiniteDuration): BIO[Any, Unit] =
        BIO.sleep(duration).executeOn(s)

      override def clock: Clock[BIO[Any, ?]] =
        BIO.clock(s)
    }
}

private[bio] abstract class TaskClocks {

  /**
    * Default, pure, globally visible `cats.effect.Clock`
    * implementation that defers the evaluation to `Task`'s default
    * [[monix.execution.Scheduler Scheduler]]
    * (that's being injected in [[BIO.runToFuture]]).
    */
  def clock[E]: Clock[BIO[E, ?]] =
    clockAny.asInstanceOf[Clock[BIO[E, ?]]]

  private[this] final val clockAny: Clock[BIO[Any, ?]] =
    new Clock[BIO[Any, ?]] {

      override def realTime(unit: TimeUnit): BIO[Any, Long] =
        BIO.deferAction(sc => BIO.now(sc.clockRealTime(unit)))

      override def monotonic(unit: TimeUnit): Task[Long] =
        BIO.deferAction(sc => BIO.now(sc.clockMonotonic(unit)))
    }

  /**
    * Builds a `cats.effect.Clock` instance, given a
    * [[monix.execution.Scheduler Scheduler]] reference.
    */
  def clock(s: Scheduler): Clock[BIO[Any, ?]] =
    new Clock[BIO[Any, ?]] {

      override def realTime(unit: TimeUnit): BIO[Any, Long] =
        BIO.eval(s.clockRealTime(unit))

      override def monotonic(unit: TimeUnit): BIO[Any, Long] =
        BIO.eval(s.clockMonotonic(unit))
    }
}
