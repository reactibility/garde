/**
 * Copyright (C) 2014 Reactibility Inc. <http://www.reactibility.com>
 */

package garde.security

import scala.concurrent.Await
import scala.concurrent.duration._
import scalaz._
import java.io.File
import akka.actor.{PoisonPill, ActorRef, ActorSystem, Props}
import akka.pattern.AskSupport
import akka.testkit.{TestProbe, ImplicitSender, TestKit}
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import org.apache.commons.io.FileUtils
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, MustMatchers, WordSpecLike}

object ModuleSpec {

val Config = ConfigFactory.parseString(s"""
   akka.log-dead-letters = off
   akka.log-dead-letters-during-shutdown = off
   akka.persistence.journal.leveldb.native = off
   akka.persistence.journal.leveldb.dir = "target/journal"
   akka.persistence.snapshot-store.local.dir = "target/snapshots"
   """)
}

class ModuleSpec extends TestKit(ActorSystem("test", ModuleSpec.Config))
  with ImplicitSender
  with AskSupport
  with WordSpecLike
  with MustMatchers
  with BeforeAndAfterAll
  with BeforeAndAfterEach {

  import garde.security.ActiveModule._

  implicit val timeout = Timeout(5 seconds)

  val filesToClean = List(
    "akka.persistence.journal.leveldb.dir",
    "akka.persistence.journal.leveldb-shared.store.dir",
    "akka.persistence.snapshot-store.local.dir").map(s ⇒ new File(system.settings.config.getString(s)))

  override protected def beforeAll() {
    filesToClean.foreach(FileUtils.deleteDirectory)
    sup = system.actorOf(Props(new ModuleSupervisor()), ModuleSupervisor.ModuleSupervisorName)
  }

  override protected def afterAll() {
    system.shutdown()
    system.awaitTermination(10.seconds)
    filesToClean.foreach(FileUtils.deleteDirectory)
  }

  var sup: ActorRef = _

  "Module creation" must {

    "succeed" in {
      sup ! CreateModule("module-1", "Module1")
      expectMsg(timeout.duration, Success(ModuleCreated("module-1", 0L, "Module1")))
      val module = Await.result(system.actorSelection("/user/module-supervisor/module-1").resolveOne(), timeout.duration)
      module ! GetModuleState
      expectMsg(timeout.duration, ModuleState("module-1", 0L, "Module1"))
    }

    "fail with empty id" in {
      sup ! CreateModule("", "Module2")
      expectMsg(timeout.duration, Failure(NonEmptyList(IdRequired.toString)))
    }

    "fail with null id" in {
      sup ! CreateModule(null, "Module2")
      expectMsg(timeout.duration, Failure(NonEmptyList(IdRequired.toString)))
    }

    "fail with empty name" in {
      sup ! CreateModule("module-2", "")
      expectMsg(timeout.duration, Failure(NonEmptyList(NameRequired.toString)))
    }

    "fail with null name" in {
      sup ! CreateModule("module-2", null)
      expectMsg(timeout.duration, Failure(NonEmptyList(NameRequired.toString)))
    }

    "succeed with two modules" in {
      sup ! CreateModule("module-2", "Module2")
      expectMsg(timeout.duration, Success(ModuleCreated("module-2", 0L, "Module2")))
      sup ! CreateModule("module-3", "Module3")
      expectMsg(timeout.duration, Success(ModuleCreated("module-3", 0L, "Module3")))
    }

  }

  "Module change name" must {

    "succeed" in {
      val module = Await.result(system.actorSelection("/user/module-supervisor/module-1").resolveOne(), timeout.duration)
      module ! ChangeModuleName("module-1", 0L, "New Name")
      expectMsg(timeout.duration, Success(ModuleNameChanged("module-1", 1L, "New Name")))
    }

    "fail with empty name" in {
      val module = Await.result(system.actorSelection("/user/module-supervisor/module-1").resolveOne(), timeout.duration)
      module ! ChangeModuleName("module-1", 1L, "")
      expectMsg(timeout.duration, Failure(NonEmptyList(NameRequired.toString)))
    }

    "fail with null name" in {
      val module = Await.result(system.actorSelection("/user/module-supervisor/module-1").resolveOne(), timeout.duration)
      module ! ChangeModuleName("module-1", 1L, null)
      expectMsg(timeout.duration, Failure(NonEmptyList(NameRequired.toString)))
    }

    "fail with id mismatch" in {
      val module = Await.result(system.actorSelection("/user/module-supervisor/module-1").resolveOne(), timeout.duration)
      module ! ChangeModuleName("bad id", 1L, "New Name2")
      expectMsg(timeout.duration, Failure(NonEmptyList(IdMismatch.toString)))
    }

    "fail with incorrect version" in {
      val module = Await.result(system.actorSelection("/user/module-supervisor/module-1").resolveOne(), timeout.duration)
      module ! ChangeModuleName("module-1", 0L, "New Name2")
      expectMsg(timeout.duration, Failure(NonEmptyList(IncorrectVersion.toString)))
    }
  }

  "Module locator" must {

    import ModuleLocator._

    "successfuly locate an instantiated module" in {
      val m1 = Await.result(system.actorSelection("/user/module-supervisor/module-1").resolveOne(), timeout.duration)
      val locator = system.actorOf(Props(new ModuleLocator("module-1")))
      val located = Await.result(locator ? LocateModule, timeout.duration)
      located must be(m1)
    }

    "successfuly locate an non-instantiated module" in {
      val probe = TestProbe()
      val m1 = Await.result(system.actorSelection("/user/module-supervisor/module-1").resolveOne(), timeout.duration)
      probe watch m1
      m1 ! PoisonPill
      probe expectTerminated(m1, timeout.duration)

      val locator = system.actorOf(Props(new ModuleLocator("module-1")))
      val located = Await.result(locator ? LocateModule, timeout.duration).asInstanceOf[ActorRef]
      located.path.name must be(m1.path.name)
    }
  }

  "Module recovery" must {

    import ModuleSupervisor._

    "succeed" in {
      val probe = TestProbe()
      probe watch sup
      sup ! PoisonPill
      probe expectTerminated(sup, timeout.duration)

      sup = system.actorOf(Props(new ModuleSupervisor()), ModuleSupervisorName)
      val m = Await.result(sup ? RecoverModule("module-1"), timeout.duration).asInstanceOf[ActorRef]
      m ! GetModuleState
      expectMsg(timeout.duration, ModuleState("module-1", 1L, "New Name"))
    }
  }
}
