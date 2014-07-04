/**
 * Copyright (C) 2014 Reactibility Inc. <http://www.reactibility.com>
 */

package garde.security

import scala.concurrent.duration._
import akka.testkit.{TestProbe, ImplicitSender, TestKit}
import akka.actor.{PoisonPill, Props, ActorSystem}
import org.scalatest.{BeforeAndAfterEach, BeforeAndAfterAll, MustMatchers, WordSpecLike}
import akka.util.Timeout
import scala.concurrent.Await
import com.typesafe.config.ConfigFactory
import scalaz._

object ModuleSpec {

val Config = ConfigFactory.parseString(s"""
   akka.log-dead-letters = off
   akka.log-dead-letters-during-shutdown = off
   akka.persistence.journal.leveldb.native = off
   queued-dispatcher {
     type = Dispatcher
     mailbox-type = "akka.dispatch.UnboundedDequeBasedMailbox"
     executor = "fork-join-executor"
     fork-join-executor {
       # Min number of threads to cap factor-based parallelism number to
       parallelism-min = 1
       # Parallelism (threads) ... ceil(available processors * factor)
       parallelism-factor = 0
       # Max number of threads to cap factor-based parallelism number to
       parallelism-max = 1
     }
     # Throughput defines the maximum number of messages to be
     # processed per actor before the thread jumps to the next actor.
     # Set to 1 for as fair as possible.
     throughput = 100
   }""")
}

class ModuleSpec extends TestKit(ActorSystem("test", ModuleSpec.Config))
  with ImplicitSender
  with WordSpecLike
  with MustMatchers
  with BeforeAndAfterAll
  with BeforeAndAfterEach {

  import ActiveModule._

  implicit val timeout = Timeout(5 seconds)

  override protected def afterAll() {
    // Final tear down.
    system.shutdown()
    system.awaitTermination(10.seconds)
  }

  val Id = "module-id"
  val Name = "Module Name"

  "Module creation" must {

    "succeed" in {
      val probe = TestProbe()
      val sup = system.actorOf(Props(new ModuleSupervisor()).withDispatcher("queued-dispatcher"), "sup")
      probe watch sup

      sup ! CreateModule(Id, Name)
      expectMsg(timeout.duration, Success(ModuleCreated(Id, 0L, Name)))
      val module = Await.result(system.actorSelection(s"/user/sup/$Id").resolveOne(), timeout.duration)
      module ! GetState
      expectMsg(timeout.duration, ModuleState(Id, 0L, Name))

      sup ! PoisonPill
      probe expectTerminated(sup, timeout.duration)
    }

    "fail with empty id" in {
      val probe = TestProbe()
      val sup = system.actorOf(Props(new ModuleSupervisor()).withDispatcher("queued-dispatcher"), "sup")
      probe watch sup

      sup ! CreateModule("", Name)
      expectMsg(timeout.duration, Failure(NonEmptyList(IdRequired.toString)))

      sup ! PoisonPill
      probe expectTerminated(sup, timeout.duration)
    }

    "fail with null id" in {
      val probe = TestProbe()
      val sup = system.actorOf(Props(new ModuleSupervisor()).withDispatcher("queued-dispatcher"), "sup")
      probe watch sup

      sup ! CreateModule(null, Name)
      expectMsg(timeout.duration, Failure(NonEmptyList(IdRequired.toString)))

      sup ! PoisonPill
      probe expectTerminated(sup, timeout.duration)
    }

    "fail with empty name" in {
      val probe = TestProbe()
      val sup = system.actorOf(Props(new ModuleSupervisor()).withDispatcher("queued-dispatcher"), "sup")
      probe watch sup

      sup ! CreateModule(Id, "")
      expectMsg(timeout.duration, Failure(NonEmptyList(NameRequired.toString)))

      sup ! PoisonPill
      probe expectTerminated(sup, timeout.duration)
    }

    "fail with null name" in {
      val probe = TestProbe()
      val sup = system.actorOf(Props(new ModuleSupervisor()).withDispatcher("queued-dispatcher"), "sup")
      probe watch sup

      sup ! CreateModule(Id, null)
      expectMsg(timeout.duration, Failure(NonEmptyList(NameRequired.toString)))

      sup ! PoisonPill
      probe expectTerminated(sup, timeout.duration)
    }

    "fail with duplicate" in {
      val probe = TestProbe()
      val sup = system.actorOf(Props(new ModuleSupervisor()).withDispatcher("queued-dispatcher"), "sup")
      probe watch sup

      sup ! CreateModule(Id, Name)
      expectMsg(timeout.duration, Success(ModuleCreated(Id, 0L, Name)))
      sup ! CreateModule(Id, Name)
      expectMsg(timeout.duration, Failure(NonEmptyList("Module module-id already exists.")))

      sup ! PoisonPill
      probe expectTerminated(sup, timeout.duration)
    }

    "succeed with two" in {
      val probe = TestProbe()
      val sup = system.actorOf(Props(new ModuleSupervisor()).withDispatcher("queued-dispatcher"), "sup")
      probe watch sup

      sup ! CreateModule(Id, Name)
      expectMsg(timeout.duration, Success(ModuleCreated(Id, 0L, Name)))
      sup ! CreateModule("id2", "New Module")
      expectMsg(timeout.duration, Success(ModuleCreated("id2", 0L, "New Module")))

      sup ! PoisonPill
      probe expectTerminated(sup, timeout.duration)
    }

    "fail on the first with a bad name but succeed in the second one" in {
      val probe = TestProbe()
      val sup = system.actorOf(Props(new ModuleSupervisor()).withDispatcher("queued-dispatcher"), "sup")
      probe watch sup

      sup ! CreateModule(Id, "")
      expectMsg(timeout.duration, Failure(NonEmptyList(NameRequired.toString)))
      sup ! CreateModule("id2", "New Module")
      expectMsg(timeout.duration, Success(ModuleCreated("id2", 0L, "New Module")))

      sup ! PoisonPill
      probe expectTerminated(sup, timeout.duration)
    }
  }

  "Module change name" must {

    "succeed" in {
      val probe = TestProbe()
      val sup = system.actorOf(Props(new ModuleSupervisor()).withDispatcher("queued-dispatcher"), "sup")
      probe watch sup

      sup ! CreateModule(Id, Name)
      expectMsg(timeout.duration, Success(ModuleCreated(Id, 0L, Name)))
      val module = Await.result(system.actorSelection(s"/user/sup/$Id").resolveOne(), timeout.duration)
      module ! ChangeModuleName(Id, 0L, "New Name")
      expectMsg(timeout.duration, Success(ModuleNameChanged(Id, 1L, "New Name")))

      sup ! PoisonPill
      probe expectTerminated(sup, timeout.duration)
    }

    "fail with empty name" in {
      val probe = TestProbe()
      val sup = system.actorOf(Props(new ModuleSupervisor()).withDispatcher("queued-dispatcher"), "sup")
      probe watch sup

      sup ! CreateModule(Id, Name)
      expectMsg(timeout.duration, Success(ModuleCreated(Id, 0L, Name)))
      val module = Await.result(system.actorSelection(s"/user/sup/$Id").resolveOne(), timeout.duration)
      module ! ChangeModuleName(Id, 0L, "")
      expectMsg(timeout.duration, Failure(NonEmptyList(NameRequired.toString)))

      sup ! PoisonPill
      probe expectTerminated(sup, timeout.duration)
    }

    "fail with null name" in {
      val probe = TestProbe()
      val sup = system.actorOf(Props(new ModuleSupervisor()).withDispatcher("queued-dispatcher"), "sup")
      probe watch sup

      sup ! CreateModule(Id, Name)
      expectMsg(timeout.duration, Success(ModuleCreated(Id, 0L, Name)))
      val module = Await.result(system.actorSelection(s"/user/sup/$Id").resolveOne(), timeout.duration)
      module ! ChangeModuleName(Id, 0L, null)
      expectMsg(timeout.duration, Failure(NonEmptyList(NameRequired.toString)))

      sup ! PoisonPill
      probe expectTerminated(sup, timeout.duration)
    }

    "fail with id mismatch" in {
      val probe = TestProbe()
      val sup = system.actorOf(Props(new ModuleSupervisor()).withDispatcher("queued-dispatcher"), "sup")
      probe watch sup

      sup ! CreateModule(Id, Name)
      expectMsg(timeout.duration, Success(ModuleCreated(Id, 0L, Name)))
      val module = Await.result(system.actorSelection(s"/user/sup/$Id").resolveOne(), timeout.duration)
      module ! ChangeModuleName("bad id", 0L, "New Name")
      expectMsg(timeout.duration, Failure(NonEmptyList(IdMismatch.toString)))

      sup ! PoisonPill
      probe expectTerminated(sup, timeout.duration)
    }

    "fail with incorrect version" in {
      val probe = TestProbe()
      val sup = system.actorOf(Props(new ModuleSupervisor()).withDispatcher("queued-dispatcher"), "sup")
      probe watch sup

      sup ! CreateModule(Id, Name)
      expectMsg(timeout.duration, Success(ModuleCreated(Id, 0L, Name)))
      val module = Await.result(system.actorSelection(s"/user/sup/$Id").resolveOne(), timeout.duration)
      module ! ChangeModuleName(Id, 1L, "New Name")
      expectMsg(timeout.duration, Failure(NonEmptyList(IncorrectVersion.toString)))

      sup ! PoisonPill
    }
  }

  "Module recover" must {

    "succeed" in {
       
    }
  }
}
