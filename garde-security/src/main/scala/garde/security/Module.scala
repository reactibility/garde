/**
 * Copyright (C) 2014 Reactibility Inc. <http://www.reactibility.com>
 */

package garde.security

import akka.persistence.{Recover, PersistentActor, SnapshotOffer}
import akka.actor._
import akka.actor.SupervisorStrategy._
import akka.util.Timeout
import akka.actor.OneForOneStrategy
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
 * This is the current state of the module supervisor, which must have a collection of all existing modules
 * in order to instantiate the module persistent actors.
 * @param modules Seq[String] the collection of module ids.
 */
sealed case class ModuleSupervisorState(modules: Seq[String])

/**
 * Persistent actor representing an active module.
 */
class ActiveModule(id: String)(implicit val timeout: Timeout) extends PersistentActor {

  import CommonValidations._
  import ActiveModule._
  import ModuleSupervisor._

  override def persistenceId = id
  var state: ModuleState = _

  val receiveCommand: Receive = {

    case cmd @ CreateModule(id, name)                 =>
      try {
        validateCreate(cmd) match {
          case Success(v)     =>
            persist(ModuleCreated(v.id, 0L, v.name)) { event =>
              state = ModuleState(event.id, event.version, event.name)
              context.system.eventStream.publish(event)
              sender ! event
            }
          case f @ Failure(v) =>
            throw CreationException(id)
        }
      }
      catch {
        case _: Exception => throw CreationException(id)
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

    case SaveModuleSnapshot                                      => saveSnapshot(state)

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

  case object SaveModuleSnapshot
  case object GetState
}

/**
 * An actor as a factory for modules and for supervision.
 */
class ModuleSupervisor(implicit val timeout: Timeout) extends PersistentActor {

  import CommonValidations._
  import ActiveModule._
  import ModuleSupervisor._

  override def persistenceId = PersistenceId
  var state = ModuleSupervisorState(Nil)

  override val supervisorStrategy =
    OneForOneStrategy() {
      case e @ CreationException(msg) =>
        state.modules filterNot(_ == msg)
        Stop
      case _: Exception               => Restart
    }

  def receiveCommand = {
    case cmd @ CreateModule(id, name) =>
      val v = checkString(id, IdRequired)
      if (v.isSuccess) {
        state.modules.find(_ == v.toOption.get) match {
          case Some(m) => sender ! s"Module ${cmd.id} already exists.".failureNel
          case None    =>
            val client = sender
            persist(ModuleCreationReceived(id)) { event =>
              state.modules :+ event.id
              context actorOf Props(new ModuleCreationSaga(client, cmd))
            }
        }
      }
      else sender ! v.toValidationNel

    case msg @ Instantiate(cmd)       =>
      sender ! Instantiated(context.actorOf(Props(new ActiveModule(cmd.id)), cmd.id))

    case SaveModuleSupervisorSnapshot => saveSnapshot(state)
  }

  val receiveRecover: Receive = {
    case evt: ModuleCreationReceived                       =>
      state.modules :+ evt.id
      context.actorOf(Props(new ActiveModule(evt.id)), evt.id) ! Recover

    case SnapshotOffer(_, snapshot: ModuleSupervisorState) => state = snapshot
  }
}

/**
 * Companion object for ModuleSupervisor.
 */
object ModuleSupervisor {
  import ActiveModule._
  final val PersistenceId = "persistence-id"
  sealed case class Instantiate(cmd: CreateModule)
  sealed case class Instantiated(module: ActorRef)
  sealed case class CreationException(moduleId: String) extends Throwable
  final case class ModuleCreationReceived(id: String)
  case object SaveModuleSupervisorSnapshot
}

/**
 * A saga type actor to orchestration the creation of a new module and the syncing up between the
 * module supervisor and successful module creation.
 * @param client ActorRef original command sender
 * @param cmd CreateModule the module creation command
 */
class ModuleCreationSaga(client: ActorRef, cmd: ActiveModule.CreateModule)(implicit val timeout: Timeout) extends Actor {

  import ModuleSupervisor._
  import ActiveModule._

  private case object Start

  context.setReceiveTimeout(timeout.duration)

  override def preStart() = {
    self ! Start
  }

  def receive = {
    case Start =>
      context become awaitUniqueness
      context.actorSelection(s"../$id") ! Identify(id)
  }

  def awaitUniqueness: Receive = {
    case ActorIdentity(cmd.id, Some(ref))       =>
      client ! s"Module ${cmd.id} already exists.".failureNel
      context stop self
    case ActorIdentity(cmd.id, None)            =>
      context become awaitInstantiation
      context.parent ! Instantiate(cmd)
    case ReceiveTimeout                         =>
      context.parent ! CreationException(cmd.id)
      context stop self
  }

  def awaitInstantiation: Receive = {
    case msg @ Instantiated(module) =>
      module ! cmd
      context become awaitCreation
    case ReceiveTimeout                         =>
      context.parent ! CreationException(cmd.id)
      context stop self
  }

  def awaitCreation: Receive = {
    case evt @ ModuleCreated(id, version, name) =>
      client ! evt
      context stop self
    case ReceiveTimeout                         =>
      context stop self
  }
}