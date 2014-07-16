/**
 * Copyright (C) 2014 Reactibility Inc. <http://www.reactibility.com>
 */

package garde.security

import akka.actor._
import akka.actor.OneForOneStrategy
import akka.actor.SupervisorStrategy._
import akka.pattern.PipeToSupport
import akka.persistence.{RecoveryCompleted, Recover, PersistentActor, SnapshotOffer}
import akka.util.Timeout
import scalaz._
import Scalaz._

/**
 * Persistent actor representing an active module.
 */
class ActiveModule(id: String)(implicit val timeout: Timeout) extends PersistentActor {

  import CommonValidations._
  import ActiveModule._

  override def persistenceId = id
  var state: ModuleState = _

  val receiveCommand: Receive = {
    case cmd @ CreateModule(id, name)                      =>
      validateCreate(cmd) match {
        case Success(v)     =>
          persist(ModuleCreated(v.id, 0L, v.name)) { event =>
            state = ModuleState(event.id, event.version, event.name)
            sender ! event
          }
        case f @ Failure(v) =>
          sender ! CreationException(id, f)
          context stop self
      }

    case cmd @ ChangeModuleName(id, expectedVersion, name) =>
      validateChangeName(cmd) match {
        case Success(v)     =>
          persist(ModuleNameChanged(v.id, state.version + 1, v.name)) { event =>
            state = state copy (version = event.version, name = event.name)
            sender ! event.successNel
          }
        case f @ Failure(v) => sender ! f
      }

    case SaveModuleSnapshot                                => saveSnapshot(state)

    case GetModuleState                                    => sender ! state

    case RecoveryCompleted                                 => sender ! ModuleRecoveryComplete(recoveryFinished)
  }

  val receiveRecover: Receive = {
    case evt: ModuleCreated                      => state = ModuleState(evt.id, evt.version, evt.name)
    case evt: ModuleNameChanged                  => state = state.copy(name = evt.name, version = evt.version)
    case SnapshotOffer(_, snapshot: ModuleState) => state = snapshot
  }

  def validateCreate(cmd: CreateModule): DomainValidation[CreateModule] =
    (checkString(cmd.id, IdRequired).toValidationNel |@|
      0L.successNel |@|
      checkString(cmd.name, NameRequired).toValidationNel) { (i, v, n) =>
      CreateModule(i, n)
    }

  def validateChangeName(cmd: ChangeModuleName): DomainValidation[ChangeModuleName] =
    (checkId(cmd.id, state.id, IdMismatch).toValidationNel |@|
      checkVersion(cmd.expectedVersion, state.version, IncorrectVersion).toValidationNel |@|
      checkString(cmd.name, NameRequired).toValidationNel) { (i, v, n) =>
      ChangeModuleName(state.id, v, n)
    }
}

/**
 * Companion object for ActiveModule.
 */
object ActiveModule {

  sealed case class ModuleState(id: String, version: Long, name: String)

  case object IncorrectVersion extends ValidationKey
  case object IdRequired extends ValidationKey
  case object IdMismatch extends ValidationKey
  case object NameRequired extends ValidationKey

  sealed trait ModuleCommand {
    def id: String
    def expectedVersion: Long
  }

  final case class CreateModule(id: String, name: String)
  final case class ChangeModuleName(id: String, expectedVersion: Long, name: String) extends ModuleCommand

  final case class ModuleCreated(id: String, version: Long, name: String)
  final case class ModuleNameChanged(id: String, version: Long, name: String)

  sealed case class CreationException(moduleId: String, validation: Failure[NonEmptyList[String], CreateModule])
  case object SaveModuleSnapshot
  case object GetModuleState
  case class ModuleRecoveryComplete(completed: Boolean)
}

/**
 * An actor as a factory for modules and for supervision.
 */
class ModuleSupervisor(implicit val timeout: Timeout) extends Actor {

  import ActiveModule._
  import ModuleSupervisor._
  import CommonValidations._

  override val supervisorStrategy =
    OneForOneStrategy() {
      case _: Exception => Restart
    }

  def receive = {
    case cmd @ CreateModule(id, name)  =>
      val v = checkString(id, IdRequired)
      if (v.isSuccess) {
        val client = sender
        context actorOf(Props(new ModuleCreationSaga(client, cmd, context.actorOf(Props(new ActiveModule(cmd.id)), cmd.id))),
          s"module-creation-saga-$id")
      }
      else sender ! v.toValidationNel

    case msg @ RecoverModule(moduleId) =>
      val m = context.actorOf(Props(new ActiveModule(moduleId)), moduleId)
      m ! Recover
      sender ! m
  }
}

/**
 * Companion object for ModuleSupervisor.
 */
object ModuleSupervisor {
  final val ModuleSupervisorName = "module-supervisor"
  sealed case class RecoverModule(moduleId: String)
}

/**
 * A saga type actor to orchestration the creation of a new module and the syncing up between the
 * module supervisor and successful module creation.
 * @param client ActorRef original command sender
 * @param cmd CreateModule the module creation command
 * @param actorRef ActorRef the persistence module actor ref
 */
class ModuleCreationSaga(client: ActorRef, cmd: ActiveModule.CreateModule, actorRef: ActorRef)(implicit val timeout: Timeout) extends Actor {

  import ActiveModule._

  private case object Start

  context.setReceiveTimeout(timeout.duration)

  override def preStart() = {
    self ! Start
  }

  def receive = {
    case Start =>
      context become awaitCreation
      actorRef ! cmd
  }

  def awaitCreation: Receive = {
    case evt @ ModuleCreated(id, version, name) =>
      context.system.eventStream.publish(evt)
      client ! evt.success
      context stop self

    case msg @ CreationException(id, ex) =>
      context.system.eventStream.publish(msg)
      client ! ex
      context stop self

    case ReceiveTimeout                         =>
      client ! s"CreateModule timed out after ${timeout.duration.toSeconds} seconds for module:${cmd.id}".failureNel
      context stop self
  }
}

class ModuleLocator(moduleId: String)(implicit val timeout: Timeout) extends Actor {

  import ModuleLocator._
  import ActiveModule._
  import ModuleSupervisor._

  context.setReceiveTimeout(timeout.duration)

  var client: ActorRef = _

  def receive = {
    case LocateModule =>
      context become awaitLookup
      client = sender
      context.actorSelection(s"/user/$ModuleSupervisorName/module-1") ! Identify(moduleId)
  }

  def awaitLookup: Receive = {
    case ActorIdentity(identityId, Some(ref)) =>
      client ! ref
      context stop self

    case ActorIdentity(identityId, None)      =>
      context become awaitInstantiation
      context.actorSelection(s"/user/$ModuleSupervisorName") ! RecoverModule(moduleId)

    case ReceiveTimeout                       =>
      client ! s"Unable to establish identity for module $moduleId."
      context stop self
  }

  def awaitInstantiation: Receive = {
    case m: ActorRef    =>
      context become awaitRecovery
      m ! RecoveryCompleted

    case ReceiveTimeout =>
      client ! s"Unable to instantiate module $moduleId."
      context stop self
  }

  def awaitRecovery: Receive = {
    case msg @ ModuleRecoveryComplete(completed) =>
      if (completed) {
        val module = sender
        client ! module
        context stop self
      }
      else
        sender ! RecoveryCompleted

    case ReceiveTimeout =>
      client ! s"Unable to instantiate module $moduleId."
      context stop self
  }
}

object ModuleLocator {
  case object LocateModule
}