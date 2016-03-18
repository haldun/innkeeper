package org.zalando.spearheads.innkeeper.routes

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.server.Directives.{reject, handleWith, as, entity, post}
import akka.http.scaladsl.server.{AuthorizationFailedRejection, Route}
import com.google.inject.Inject
import org.slf4j.LoggerFactory
import org.zalando.spearheads.innkeeper.Rejections.UnmarshallRejection
import org.zalando.spearheads.innkeeper.RouteDirectives.{isStrictRoute, isRegexRoute}
import org.zalando.spearheads.innkeeper.api.{TeamName, UserName, RouteOut, RouteIn}
import org.zalando.spearheads.innkeeper.metrics.RouteMetrics
import org.zalando.spearheads.innkeeper.oauth.OAuthDirectives.{team, hasOneOfTheScopes}
import org.zalando.spearheads.innkeeper.oauth.{AuthenticatedUser, Scopes}
import org.zalando.spearheads.innkeeper.services.{ServiceResult, RoutesService}
import org.zalando.spearheads.innkeeper.services.team.TeamService
import org.zalando.spearheads.innkeeper.api.JsonProtocols._
import akka.http.scaladsl.server.Directives._
import scala.concurrent.{Future, ExecutionContext}

/**
 * @author dpersa
 */
class PostRoutes @Inject() (
    routesService: RoutesService,
    metrics: RouteMetrics,
    scopes: Scopes,
    implicit val teamService: TeamService,
    implicit val executionContext: ExecutionContext) {

  private val logger = LoggerFactory.getLogger(this.getClass)

  def apply(authenticatedUser: AuthenticatedUser, token: String): Route = {
    post {
      val reqDesc = "post /routes"
      logger.info(s"try to $reqDesc")
      entity(as[RouteIn]) { route =>
        logger.info(s"We Try to $reqDesc unmarshalled route ${route}")
        team(authenticatedUser, token, "path") { team =>
          logger.debug(s"post /routes team ${team}")
          (isStrictRoute(route.route) & hasOneOfTheScopes(authenticatedUser, s"$reqDesc strict", scopes.WRITE_STRICT, scopes.WRITE_REGEX)) {

            handleWith(saveRoute(UserName(authenticatedUser.username), TeamName(team.name), s"$reqDesc strict"))

          } ~ (isRegexRoute(route.route) & hasOneOfTheScopes(authenticatedUser, s"$reqDesc regex", scopes.WRITE_REGEX)) {

            handleWith(saveRoute(UserName(authenticatedUser.username), TeamName(team.name), s"$reqDesc regex"))

          } ~ hasOneOfTheScopes(authenticatedUser, s"$reqDesc regex", scopes.WRITE_REGEX) {

            handleWith(saveRoute(UserName(authenticatedUser.username), TeamName(team.name), s"$reqDesc other"))

          } ~ reject(AuthorizationFailedRejection)
        }
      } ~ {
        reject(UnmarshallRejection(reqDesc))
      }
    }
  }

  private def saveRoute(createdBy: UserName, ownedByTeam: TeamName, reqDesc: String): (RouteIn) => Future[Option[RouteOut]] = (route: RouteIn) => {
    metrics.postRoutes.time {
      logger.debug(s"$reqDesc saveRoute")
      routesService.create(route, ownedByTeam, createdBy).map {
        case ServiceResult.Success(route) => Some(route)
        case _                            => None
      }
    }
  }
}
