/**
 * Copyright (C) 2014 Reactibility Inc. <http://www.reactibility.com>
 */

package garde.security

case class User(id: String, name: String, login: String, password: String, roles: List[String], tenants: List[String], version: Long)

class UserService {

  private var users: List[User] = Nil

  def findLogin(login: String, password: String): Option[User] = {
    users.find(u => u.login == login && u.password == password)
  }
}


