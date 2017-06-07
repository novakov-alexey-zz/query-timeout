package controllers

import javax.inject.{Inject, Singleton}

import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import model.TestData
import org.apache.http.HttpHost
import org.elasticsearch.client.RestClient
import play.api.db.slick.DatabaseConfigProvider
import play.api.mvc._
import slick.driver.JdbcProfile
import spray.json.{JsObject, JsString}
import stream.{ElasticsearchSink, ElasticsearchSinkSettings, IncomingMessage}

import scala.concurrent.ExecutionContext
import scala.util.Random

@Singleton
class QueryTimeoutController @Inject()(dbConfigProvider: DatabaseConfigProvider,
                                       val controllerComponents: ControllerComponents)
                                      (implicit ec: ExecutionContext, sys: ActorSystem, mat: Materializer) extends BaseController {
  private val dbConfig = dbConfigProvider.get[JdbcProfile]

  import dbConfig._
  import profile.api._

  private implicit val client = RestClient.builder(new HttpHost("localhost", 9200)).build()

  def stream: Action[AnyContent] = Action.async {
    val start = System.currentTimeMillis()
    val f = Source.fromPublisher(
      db.stream(sql"select id from test_data".as[Int].transactionally.withStatementParameters(fetchSize = 10000))
    ).map(x => IncomingMessage(None, new JsObject(Map("id" -> new JsString(x.toString))))).runWith(ElasticsearchSink(
      "postgres",
      "test_data",
      ElasticsearchSinkSettings(10000),
      parallelism = 1
    ))

    f.map(_ => Ok((System.currentTimeMillis() - start).toString))
  }

  def add: Action[AnyContent] = Action.async {
    val d = TestData(Random.nextInt())
    db.run(sqlu"insert into test_data values (${d.id})").map(i => Ok(s"inserted $i"))
  }
}
