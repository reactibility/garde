/**
 * Copyright (C) 2014 Reactibility Inc. <http://www.reactibility.com>
 */

package garde.security

import akka.persistence.PersistentActor
import akka.actor._
import akka.actor.SupervisorStrategy._
import akka.pattern.PipeToSupport
import akka.util.Timeout
import akka.actor.OneForOneStrategy
import akka.persistence.SnapshotOffer
import scalaz._
import Scalaz._

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
  override def persistenceId = id

  val receiveCommand: Receive = {

    case msg @ ModuleCreation(client, cmd)                 =>
      validateCreate(cmd) match {
        case Success(v)     =>
          persist(ModuleCreated(v.id, 0L, v.name)) { event =>
            state = ModuleState(event.id, event.version, event.name)
            context.system.eventStream.publish(event)
            client ! event.success
            sender ! event
          }
        case f @ Failure(v) =>
          client ! f
          throw CreationException(v.toString)
      }

    case cmd @ ChangeModuleName(id, expectedVersion, name) =>
      validateChangeName(cmd) match {
        case Success(v)     =>
          persist(ModuleNameChanged(v.id, state.version + 1, v.name)) { event =>
            state = state copy (version = event.version, name = event.name)
            context.system.eventStream.publish(event)
            sender ! event.success
          }
        case f @ Failure(v) => sender ! f
      }

    case SaveSnapshot                                      => saveSnapshot(state)

    case GetState                                          => sender ! state
  }

  val receiveRecover: Receive = {
    case evt: ModuleCreated                      => state = ModuleState(evt.id, evt.version, evt.name)
    case evt: ModuleNameChanged                  => state = state.copy(name = evt.name)
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

  case object SaveSnapshot
  case object GetState
  case class CreationException(msg: String) extends Throwable
}

/**
 * An actor as a factory for modules and for supervision.
 */
class ModuleSupervisor(implicit val timeout: Timeout) extends Actor with PipeToSupport with Stash {

  import CommonValidations._
  import ActiveModule._
  import ModuleSupervisor._
  import context.dispatcher

  override val supervisorStrategy =
    OneForOneStrategy() {
      case e @ CreationException(msg) =>
        context unbecome()
        unstashAll()
        Stop
      case _: Exception         => Restart
    }

  def receive = {
    case cmd @ CreateModule(id, name) =>
      val v = checkString(id, IdRequired)
      if (v.isSuccess) {
        context become waiting(sender, cmd)
        context.actorSelection(s"/user/${self.path.name}/$id") ! Identify(id)
      }
      else sender ! v.toValidationNel
  }

  /**
   * This is the wait state used in module creation when determining if a proposed new module
   * yet exists.
   * @param client ActorRef the original sender of the command.
   * @param cmd CreateModule the creation command.
   * @return Receive
   */
  def waiting(client: ActorRef, cmd: CreateModule): Receive = {

    case ActorIdentity(cmd.id, Some(ref))       =>
      client ! s"Module ${cmd.id} already exists.".failureNel

    case ActorIdentity(cmd.id, None)            =>
      context.actorOf(Props(new ActiveModule(cmd.id)), cmd.id) ! ModuleCreation(client, cmd)

    case msg @ ModuleCreated(id, version, name) =>
      context unbecome()
      unstashAll()

    case _                                      => stash()
  }
}

/**
 * Companion object for ModuleSupervisor.
 */
object ModuleSupervisor {
  import ActiveModule._
  sealed case class ModuleCreation(client: ActorRef, cmd: CreateModule)
}