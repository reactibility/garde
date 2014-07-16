/**
 * Copyright (C) 2014 Reactibility Inc. <http://www.reactibility.com>
 */

package garde.security

import akka.actor._
import akka.actor.OneForOneStrategy
import akka.actor.SupervisorStrategy._
import akka.persistence.{Recover, PersistentActor, SnapshotOffer}
import akka.util.Timeout
import garde.security.ModuleSupervisor.CreationException
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
sealed case class ModuleSupervisorState(var modules: Seq[String])

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

    case cmd @ CreateModule(id, name)                       =>
      try {
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
      }
      catch {
        case e: Exception =>
          //sender ! CreationException(id, e.getMessage.failureNel)
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
      case _: Exception => Restart
    }

  def receiveCommand = {
    case cmd @ CreateModule(id, name)           =>
      val v = checkString(id, IdRequired)
      if (v.isSuccess) {
        state.modules.find(_ == v.toOption.get) match {
          case Some(m) => sender ! s"Module ${cmd.id} already exists.".failureNel
          case None    =>
            try {
              val client = sender
              context actorOf(Props(new ModuleCreationSaga(client, cmd, context.actorOf(Props(new ActiveModule(cmd.id)), cmd.id))),
                s"module-creation-saga-$id")
            }
            catch {
              case e: Exception => sender ! e.getMessage.failureNel
            }
        }
      }
      else sender ! v.toValidationNel

    case evt @ ModuleCreated(id, version, name) =>
      persist(ModuleCreationReceived(id)) { event =>
        state.modules  = state.modules :+ event.id
      }

    case SaveModuleSupervisorSnapshot           => saveSnapshot(state)

    case GetSupervisorState                     => sender ! state
  }

  val receiveRecover: Receive = {
    case evt: ModuleCreationReceived                       =>
      state.modules = state.modules :+ evt.id
      context.actorOf(Props(new ActiveModule(evt.id)), evt.id) ! Recover

    case SnapshotOffer(_, snapshot: ModuleSupervisorState) => state = snapshot
  }
}

/**
 * Companion object for ModuleSupervisor.
 */
object ModuleSupervisor {
  import ActiveModule._

  final val PersistenceId = "module-supervisor-persistence-id"
  sealed case class CreationException(moduleId: String, validation: Failure[NonEmptyList[String], CreateModule])
  final case class ModuleCreationReceived(id: String)
  case object SaveModuleSupervisorSnapshot
  case object GetSupervisorState
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
      context.parent ! evt
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