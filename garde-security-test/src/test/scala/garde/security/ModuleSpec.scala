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
import akka.persistence.Recover

object ModuleSpec {

val Config = ConfigFactory.parseString(s"""
   akka.log-dead-letters = off
   akka.log-dead-letters-during-shutdown = off
   akka.persistence.journal.leveldb.native = off
   akka.persistence.journal.leveldb.dir = "target/journal"
   akka.persistence.journal.leveldb-shared.store.dir = "target/journal"
   akka.persistence.journal.snapshotstore.local.dir = "target/snapshots"
   """)
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
      val sup = system.actorOf(Props(new ModuleSupervisor()), "sup")
      probe watch sup

      sup ! CreateModule(Id, Name)
      expectMsg(timeout.duration, Success(ModuleCreated(Id, 0L, Name)))
      //val module = Await.result(system.actorSelection(s"/user/sup/$Id").resolveOne(), timeout.duration)
      //module ! GetState
      //expectMsg(timeout.duration, ModuleState(Id, 0L, Name))

      sup ! PoisonPill
      probe expectTerminated(sup, timeout.duration)
    }

//    "fail with empty id" in {
//      val probe = TestProbe()
//      val sup = system.actorOf(Props(new ModuleSupervisor()).withDispatcher("queued-dispatcher"), "sup")
//      probe watch sup
//
//      sup ! CreateModule("", Name)
//      expectMsg(timeout.duration, Failure(NonEmptyList(IdRequired.toString)))
//
//      sup ! PoisonPill
//      probe expectTerminated(sup, timeout.duration)
//    }
//
//    "fail with null id" in {
//      val probe = TestProbe()
//      val sup = system.actorOf(Props(new ModuleSupervisor()).withDispatcher("queued-dispatcher"), "sup")
//      probe watch sup
//
//      sup ! CreateModule(null, Name)
//      expectMsg(timeout.duration, Failure(NonEmptyList(IdRequired.toString)))
//
//      sup ! PoisonPill
//      probe expectTerminated(sup, timeout.duration)
//    }
//
//    "fail with empty name" in {
//      val probe = TestProbe()
//      val sup = system.actorOf(Props(new ModuleSupervisor()).withDispatcher("queued-dispatcher"), "sup")
//      probe watch sup
//
//      sup ! CreateModule(Id, "")
//      expectMsg(timeout.duration, Failure(NonEmptyList(NameRequired.toString)))
//
//      sup ! PoisonPill
//      probe expectTerminated(sup, timeout.duration)
//    }
//
//    "fail with null name" in {
//      val probe = TestProbe()
//      val sup = system.actorOf(Props(new ModuleSupervisor()).withDispatcher("queued-dispatcher"), "sup")
//      probe watch sup
//
//      sup ! CreateModule(Id, null)
//      expectMsg(timeout.duration, Failure(NonEmptyList(NameRequired.toString)))
//
//      sup ! PoisonPill
//      probe expectTerminated(sup, timeout.duration)
//    }
//
//    "fail with duplicate" in {
//      val probe = TestProbe()
//      val sup = system.actorOf(Props(new ModuleSupervisor()).withDispatcher("queued-dispatcher"), "sup")
//      probe watch sup
//
//      sup ! CreateModule(Id, Name)
//      expectMsg(timeout.duration, Success(ModuleCreated(Id, 0L, Name)))
//      sup ! CreateModule(Id, Name)
//      expectMsg(timeout.duration, Failure(NonEmptyList("Module module-id already exists.")))
//
//      sup ! PoisonPill
//      probe expectTerminated(sup, timeout.duration)
//    }
//
//    "succeed with two" in {
//      val probe = TestProbe()
//      val sup = system.actorOf(Props(new ModuleSupervisor()).withDispatcher("queued-dispatcher"), "sup")
//      probe watch sup
//
//      sup ! CreateModule(Id, Name)
//      expectMsg(timeout.duration, Success(ModuleCreated(Id, 0L, Name)))
//      sup ! CreateModule("id2", "New Module")
//      expectMsg(timeout.duration, Success(ModuleCreated("id2", 0L, "New Module")))
//
//      sup ! PoisonPill
//      probe expectTerminated(sup, timeout.duration)
//    }
//
//    "fail on the first with a bad name but succeed in the second one" in {
//      val probe = TestProbe()
//      val sup = system.actorOf(Props(new ModuleSupervisor()).withDispatcher("queued-dispatcher"), "sup")
//      probe watch sup
//
//      sup ! CreateModule(Id, "")
//      expectMsg(timeout.duration, Failure(NonEmptyList(NameRequired.toString)))
//      sup ! CreateModule("id2", "New Module")
//      expectMsg(timeout.duration, Success(ModuleCreated("id2", 0L, "New Module")))
//
//      sup ! PoisonPill
//      probe expectTerminated(sup, timeout.duration)
//    }
  }

//  "Module change name" must {
//
//    "succeed" in {
//      val probe = TestProbe()
//      val sup = system.actorOf(Props(new ModuleSupervisor()).withDispatcher("queued-dispatcher"), "sup")
//      probe watch sup
//
//      sup ! CreateModule(Id, Name)
//      expectMsg(timeout.duration, Success(ModuleCreated(Id, 0L, Name)))
//      val module = Await.result(system.actorSelection(s"/user/sup/$Id").resolveOne(), timeout.duration)
//      module ! ChangeModuleName(Id, 0L, "New Name")
//      expectMsg(timeout.duration, Success(ModuleNameChanged(Id, 1L, "New Name")))
//
//      sup ! PoisonPill
//      probe expectTerminated(sup, timeout.duration)
//    }

//    "fail with empty name" in {
//      val probe = TestProbe()
//      val sup = system.actorOf(Props(new ModuleSupervisor()).withDispatcher("queued-dispatcher"), "sup")
//      probe watch sup
//
//      sup ! CreateModule(Id, Name)
//      expectMsg(timeout.duration, Success(ModuleCreated(Id, 0L, Name)))
//      val module = Await.result(system.actorSelection(s"/user/sup/$Id").resolveOne(), timeout.duration)
//      module ! ChangeModuleName(Id, 0L, "")
//      expectMsg(timeout.duration, Failure(NonEmptyList(NameRequired.toString)))
//
//      sup ! PoisonPill
//      probe expectTerminated(sup, timeout.duration)
//    }
//
//    "fail with null name" in {
//      val probe = TestProbe()
//      val sup = system.actorOf(Props(new ModuleSupervisor()).withDispatcher("queued-dispatcher"), "sup")
//      probe watch sup
//
//      sup ! CreateModule(Id, Name)
//      expectMsg(timeout.duration, Success(ModuleCreated(Id, 0L, Name)))
//      val module = Await.result(system.actorSelection(s"/user/sup/$Id").resolveOne(), timeout.duration)
//      module ! ChangeModuleName(Id, 0L, null)
//      expectMsg(timeout.duration, Failure(NonEmptyList(NameRequired.toString)))
//
//      sup ! PoisonPill
//      probe expectTerminated(sup, timeout.duration)
//    }
//
//    "fail with id mismatch" in {
//      val probe = TestProbe()
//      val sup = system.actorOf(Props(new ModuleSupervisor()).withDispatcher("queued-dispatcher"), "sup")
//      probe watch sup
//
//      sup ! CreateModule(Id, Name)
//      expectMsg(timeout.duration, Success(ModuleCreated(Id, 0L, Name)))
//      val module = Await.result(system.actorSelection(s"/user/sup/$Id").resolveOne(), timeout.duration)
//      module ! ChangeModuleName("bad id", 0L, "New Name")
//      expectMsg(timeout.duration, Failure(NonEmptyList(IdMismatch.toString)))
//
//      sup ! PoisonPill
//      probe expectTerminated(sup, timeout.duration)
//    }
//
//    "fail with incorrect version" in {
//      val probe = TestProbe()
//      val sup = system.actorOf(Props(new ModuleSupervisor()).withDispatcher("queued-dispatcher"), "sup")
//      probe watch sup
//
//      sup ! CreateModule(Id, Name)
//      expectMsg(timeout.duration, Success(ModuleCreated(Id, 0L, Name)))
//      val module = Await.result(system.actorSelection(s"/user/sup/$Id").resolveOne(), timeout.duration)
//      module ! ChangeModuleName(Id, 1L, "New Name")
//      expectMsg(timeout.duration, Failure(NonEmptyList(IncorrectVersion.toString)))
//
//      sup ! PoisonPill
//      probe expectTerminated(sup, timeout.duration)
//    }
//  }
//
//  "Module recover" must {
//
//    "succeed" in {
//      val probe = TestProbe()
//      val m = system.actorOf(Props(new ActiveModule(Id)), Id)
//      probe watch m
//      m ! Recover()
//      m ! GetState
//      expectMsg(timeout.duration, ModuleState(Id, 1L, "New Name"))
//
//      m ! PoisonPill
//      probe expectTerminated(m, timeout.duration)
//    }
//  }
}
