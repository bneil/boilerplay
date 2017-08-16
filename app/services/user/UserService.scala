package services.user

import java.util.UUID

import com.mohiva.play.silhouette.api.LoginInfo
import com.mohiva.play.silhouette.api.util.PasswordHasher
import com.mohiva.play.silhouette.impl.providers.CredentialsProvider
import models.queries.auth._
import models.result.data.DataField
import models.result.filter.Filter
import models.result.orderBy.OrderBy
import models.user.{Role, User}
import services.ModelServiceHelper
import util.FutureUtils.databaseContext
import services.database.Database
import util.cache.UserCache

import scala.concurrent.Future

@javax.inject.Singleton
class UserService @javax.inject.Inject() (hasher: PasswordHasher) extends ModelServiceHelper[User] {
  def getByPrimaryKey(id: UUID) = Database.query(UserQueries.getByPrimaryKey(Seq(id)))
  def getByPrimaryKeySeq(idSeq: Seq[UUID]) = Database.query(UserQueries.getByPrimaryKeySeq(idSeq))

  def getByRoleSeq(roleSeq: Seq[Role]) = Database.query(UserQueries.getByRoleSeq(roleSeq))

  override def countAll(filters: Seq[Filter] = Nil) = Database.query(UserQueries.countAll(filters))
  override def getAll(filters: Seq[Filter], orderBys: Seq[OrderBy], limit: Option[Int] = None, offset: Option[Int] = None) = {
    Database.query(UserQueries.getAll(filters, orderBys, limit, offset))
  }

  override def searchCount(q: String, filters: Seq[Filter]) = Database.query(UserQueries.searchCount(q, filters))
  override def search(q: String, filters: Seq[Filter], orderBys: Seq[OrderBy], limit: Option[Int], offset: Option[Int]) = {
    Database.query(UserQueries.search(q, filters, orderBys, limit, offset))
  }

  def searchExact(q: String, orderBys: Seq[OrderBy], limit: Option[Int], offset: Option[Int]) = {
    Database.query(UserQueries.searchExact(q, orderBys, limit, offset))
  }

  def isUsernameInUse(name: String) = Database.query(UserSearchQueries.IsUsernameInUse(name))
  def usernameLookup(id: UUID) = Database.query(UserSearchQueries.GetUsername(id))

  def save(user: User, update: Boolean = false) = {
    log.info(s"${if (update) { "Updating" } else { "Creating" }} user [$user].")
    val statement = if (update) { UserQueries.UpdateUser(user) } else { UserQueries.insert(user) }
    Database.execute(statement).map { _ =>
      UserCache.cacheUser(user)
      user
    }
  }

  def usernameLookupMulti(ids: Set[UUID]) = if (ids.isEmpty) {
    Future.successful(Map.empty[UUID, String])
  } else {
    Database.query(UserSearchQueries.GetUsernames(ids))
  }

  def remove(userId: UUID) = Database.transaction { conn =>
    val startTime = System.nanoTime
    val f = getByPrimaryKey(userId).flatMap {
      case Some(user) => Database.execute(PasswordInfoQueries.removeByPrimaryKey(Seq(user.profile.providerID, user.profile.providerKey)), Some(conn))
      case None => throw new IllegalStateException("Invalid User")
    }
    f.flatMap { _ =>
      Database.execute(UserQueries.removeByPrimaryKey(Seq(userId)), Some(conn)).map { users =>
        UserCache.removeUser(userId)
        val timing = ((System.nanoTime - startTime) / 1000000).toInt
        Map("users" -> users, "timing" -> timing)
      }
    }
  }

  def update(id: UUID, username: String, email: String, password: Option[String], role: Role, originalEmail: String) = {
    val fields = Seq(
      DataField("username", Some(username)),
      DataField("email", Some(email)),
      DataField("role", Some(role.toString))
    )
    Database.execute(UserQueries.update(id, fields)).flatMap { _ =>
      val emailUpdated = if (email != originalEmail) {
        Database.execute(PasswordInfoQueries.UpdateEmail(originalEmail, email))
      } else {
        Future.successful(0)
      }
      emailUpdated.flatMap { _ =>
        password match {
          case Some(p) =>
            val loginInfo = LoginInfo(CredentialsProvider.ID, email)
            val authInfo = hasher.hash(p)
            Database.execute(PasswordInfoQueries.UpdatePasswordInfo(loginInfo, authInfo))
          case _ => Future.successful(id)
        }
      }.map { _ =>
        UserCache.removeUser(id)
        id
      }
    }
  }
}
