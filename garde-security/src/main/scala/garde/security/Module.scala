/**
 * Copyright (C) 2014 Reactibility Inc. <http://www.reactibility.com>
 */

package garde.security

import akka.persistence.{SnapshotOffer, PersistentActor}
import akka.actor.{OneForOneStrategy, Stash, ActorRef, Props, Actor}
import akka.actor.SupervisorStrategy._
import akka.pattern.PipeToSupport
import scalaz._
import Scalaz._
import akka.util.Timeout

/**
 * This is the current state of any given module.
 * @param id String the module id
 * @param version Long the current version
 * @param name String the name of the module.
 */
sealed case class ModuleState(id: String, version: Long, name: String)

/**
 * Persistent actor representing an active module.
 */
class ActiveModule(id: String)(implicit val timeout: Timeout) extends PersistentActor {

  import CommonValidations._
  import ActiveModule._
  import ModuleSupervisor._

  var state: ModuleState = _
  val persistenceId = id

  val receiveCommand: Receive = {

    case msg @ ModuleCreation(client, cmd)                                   =>
      val v = create(cmd.id, cmd.expectedVersion, cmd.name)
      if (v.isSuccess)
        persist(ModuleCreated(v.toOption.get.id, v.toOption.get.version, v.toOption.get.name)) { event =>
          state = ModuleState(event.id, event.version, event.name)
          context.system.eventStream.publish(event)
        }

      client ! v
      throw CreationException(v.toEither.left.toString)

    case cmd @ ChangeModuleName(id, expectedVersion, name) if id == state.id =>
      val v = withName(expectedVersion, name)
      if (v.isSuccess)
        persist(ModuleNameChanged(v.toOption.get.id, v.toOption.get.version, v.toOption.get.name)) { event =>
          state = ModuleState(event.id, event.version, event.name)
          context.system.eventStream.publish(event)
        }

      sender ! v

    case "snap"  => saveSnapshot(state)
  }

  val receiveRecover: Receive = {
    case evt: ModuleCreated                      => state = ModuleState(evt.id, evt.version, evt.name)
    case evt: ModuleNameChanged                  => state = state.copy(name = evt.name)
    case SnapshotOffer(_, snapshot: ModuleState) => state = snapshot
  }

  def create(id: String, version: Long, name: String): DomainValidation[ModuleState] =
    (checkString(id, IdRequired).toValidationNel |@|
      0L.successNel |@|
      checkString(name, NameRequired).toValidationNel) { (i, v, n) =>
      ModuleState(i, v, n)
    }

  def withName(expectedVersion: Long, name: String): DomainValidation[ModuleState] =
    (checkVersion(expectedVersion, state.version, IncorrectVersion).toValidationNel |@|
      checkString(name, NameRequired).toValidationNel) { (v, n) =>
      ModuleState(state.id, v, n)
    }
}

/**
 * Companion object for ActiveModule.
 */
object ActiveModule {

  case object IncorrectVersion extends ValidationKey
  case object IdRequired extends ValidationKey
  case object NameRequired extends ValidationKey

  sealed trait ModuleCommand {
    def id: String
    def expectedVersion: Long
  }

  final case class CreateModule(id: String, expectedVersion: Long = -1L, name: String) extends ModuleCommand
  final case class ChangeModuleName(id: String, expectedVersion: Long, name: String) extends ModuleCommand

  final case class ModuleCreated(id: String, version: Long, name: String)
  final case class ModuleNameChanged(id: String, version: Long, name: String)

  case class CreationException(msg: String) extends Throwable
}

/**
 * An actor as a factory for modules and for supervision.
 */
class ModuleSupervisor(implicit val timeout: Timeout) extends Actor with PipeToSupport with Stash {

  import ActiveModule._
  import ModuleSupervisor._
  import context.dispatcher

  override val supervisorStrategy =
    OneForOneStrategy() {
      case _: CreationException => Stop
      case _: Exception         => Restart
    }

  def receive = {
    case cmd @ CreateModule(id, expectedVersion, name) =>
      context become waiting(sender, cmd)
      context.actorSelection(s"../$id").resolveOne() pipeTo self
  }

  import scala.util.{Success, Failure}

  /**
   * This is the wait state used in module creation when determining if a proposed new module
   * yet exists.
   * @param client ActorRef the original sender of the command.
   * @param cmd CreateModule the creation command.
   * @return Receive
   */
  def waiting(client: ActorRef, cmd: CreateModule): Receive = {

    case s @ Success(actorRef) =>
      client ! s"Module ${cmd.id} already exists.".failureNel
      context unbecome()
      unstashAll()

    case f @ Failure(e)        =>
      context.actorOf(Props(new ActiveModule(cmd.id)), cmd.id) ! ModuleCreation(client, cmd)
      context unbecome()
      unstashAll()

    case _                     => stash()
  }
}

/**
 * Companion object for ModuleSupervisor.
 */
object ModuleSupervisor {
  import ActiveModule._
  sealed case class ModuleCreation(client: ActorRef, cmd: CreateModule)
}