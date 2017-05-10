package controllers

import javax.inject.{Inject, Singleton}

import akka.stream.scaladsl.Source
import model.TestData
import play.api.db.slick.DatabaseConfigProvider
import play.api.mvc._
import slick.driver.JdbcProfile

import scala.concurrent.ExecutionContext
import scala.util.Random

@Singleton
class QueryTimeoutController @Inject()(
                                        dbConfigProvider: DatabaseConfigProvider,
                                        components: ControllerComponents)
                                      (implicit ec: ExecutionContext) extends AbstractController(components) {
  val dbConfig = dbConfigProvider.get[JdbcProfile]

  import dbConfig._
  import profile.api._

  def stream = Action(Ok.chunked(Source.fromPublisher(
    db.stream(sql"select id from test_data".as[Int].transactionally.withStatementParameters(fetchSize = 1))
  ).map(_.toString)))

  def add = Action.async {
    val d = TestData(Random.nextInt())
    db.run(sqlu"insert into test_data values (${d.id})").map(i => Ok(s"inserted $i"))
  }
}
