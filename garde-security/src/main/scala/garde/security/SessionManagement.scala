/**
 * Copyright (C) 2014 Reactibility Inc. <http://www.reactibility.com>
 */

package garde.security

import scala.concurrent.duration._
import scala.collection.immutable._
import akka.actor.{Props, PoisonPill, Cancellable, Actor}
import akka.pattern.PipeToSupport
import java.util.UUID

/**
 * This represents an actor based user session that may be timed out after a period of inactivity.
 * @param sessionId String unique session uuid.
 * @param userId String the unique id of the user.
 * @param grantedRoles List[String] the list of roles granted to the user.
 * @param grantedTenants List[String] the list of tenants in which the user has access.
 */
case class Session(sessionId: String, userId: String, grantedRoles: List[String], grantedTenants: List[String]) extends Actor {

  import Session._
  import context.dispatcher

  val SessionTimeoutMinutes = 30
  var killCancellation: Cancellable = _

  override def preStart(): Unit = {
    touch
  }

  override def receive = {

    case msg @ UserInAnyRole(roles)     =>
      touch
      if (grantedRoles.union(roles).size <= (grantedRoles.size + roles.size - 1))
        sender ! AccessGranted(userId)
      else sender ! AccessDenied

    case msg @ UserInAnyTenant(tenants) =>
      touch
      if (grantedTenants.union(tenants).size <= (grantedTenants.size + tenants.size - 1))
        sender ! AccessGranted(userId)
      else sender ! AccessDenied
  }

  def touch = {
    killCancellation.cancel()
    killCancellation = context.system.scheduler.scheduleOnce(SessionTimeoutMinutes minutes, self, PoisonPill)
  }
}

object Session {
  sealed case class UserInAnyRole(roles: List[String])
  sealed case class AccessGranted(userId: String)
  sealed case class UserInAnyTenant(tenants: List[String])
  case object AccessDenied
}

/**
 * The session manager provides an overall context for all sessions.
 */
class SessionManager(userService: UserService) extends Actor with PipeToSupport {

  import SessionManager._

  override def receive = {

    case msg @ Authenticate(login, password) =>
      userService findLogin(login, password) match {
        case Some(u) =>
          val sessionId = UUID.randomUUID().toString
          context actorOf (Props(new Session(sessionId, u.id, u.roles, u.tenants)), sessionId)
          sender ! sessionId

        case None    =>
          sender ! AuthenticationFailure
      }

  }
}

object SessionManager {
  sealed case class Authenticate(login: String, password: String)
  case object AuthenticationFailure
}