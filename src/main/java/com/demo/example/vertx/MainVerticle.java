package com.demo.example.vertx;

import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.rjeschke.txtmark.Processor;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.jdbc.JDBCClient;
import io.vertx.ext.sql.SQLConnection;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.templ.FreeMarkerTemplateEngine;

public class MainVerticle extends AbstractVerticle {

	private static final String SQL_CREATE_PAGES_TABLE = "create table if not exists Pages (Id integer identity primary key, Name varchar(255) unique, Content clob)";
	private static final String SQL_GET_PAGE = "select Id, Content from Pages where Name = ?";
	private static final String SQL_CREATE_PAGE = "insert into Pages values (NULL, ?, ?)";
	private static final String SQL_SAVE_PAGE = "update Pages set Content = ? where Id = ?";
	private static final String SQL_ALL_PAGES = "select Name from Pages";
	private static final String SQL_DELETE_PAGE = "delete from Pages where Id = ?";

	private JDBCClient dbClient;

	private static final Logger LOGGER = LoggerFactory.getLogger(MainVerticle.class);

	@Override
	public void start(Future<Void> startFuture) throws Exception {
		//Default vertx http server
		/*
		 * vertx.createHttpServer().requestHandler(req -> { req.response()
		 * .putHeader("content-type", "text/plain") .end("Hello Vert.x from Dada!");
		 * }).listen(8080, result -> { if (result.succeeded()) { startFuture.complete();
		 * } else { startFuture.fail(result.cause()); } });
		 */
		
		//Step-1 Single class implementation
		/*Future<Void> steps = prepareDatabase().compose(v -> startHttpServer());
		steps.setHandler(startFuture.completer());
		System.out.println("HTTP server started on port 8080");*/
		
		//Step-2 Http and DB separate verticle implementation
		
		/*Future<String> dbVerticleDeployment = Future.future();
	    vertx.deployVerticle(new DatabaseVerticle(), dbVerticleDeployment.completer());

	    dbVerticleDeployment.compose(id -> {

	      Future<String> httpVerticleDeployment = Future.future();
	      vertx.deployVerticle(
	        "com.demo.example.vertx.HttpServerVerticle",
	        new DeploymentOptions().setInstances(2),
	        httpVerticleDeployment.completer());

	      return httpVerticleDeployment;

	    }).setHandler(ar -> {
	      if (ar.succeeded()) {
	        startFuture.complete();
	      } else {
	        startFuture.fail(ar.cause());
	      }
	    });*/
		
		//Step-3 Http and DB separate verticle, DB main code created as Service Proxy implementation
		
				Future<String> dbVerticleDeployment = Future.future();
			    vertx.deployVerticle(new com.demo.example.vertx.database.DatabaseVerticle(), dbVerticleDeployment.completer());

			    dbVerticleDeployment.compose(id -> {

			      Future<String> httpVerticleDeployment = Future.future();
			      vertx.deployVerticle(
			        "com.demo.example.vertx.http.HttpServerVerticle",
			        new DeploymentOptions().setInstances(2),
			        httpVerticleDeployment.completer());

			      return httpVerticleDeployment;

			    }).setHandler(ar -> {
			      if (ar.succeeded()) {
			        startFuture.complete();
			      } else {
			        startFuture.fail(ar.cause());
			      }
			    });
	}

	private Future<Void> prepareDatabase() {
		Future<Void> future = Future.future();

		dbClient = JDBCClient.createShared(vertx, new JsonObject().put("url", "jdbc:hsqldb:file:db/wiki")
				.put("driver_class", "org.hsqldb.jdbcDriver").put("max_pool_size", 30));

		dbClient.getConnection(ar -> {
			if (ar.failed()) {
				LOGGER.error("Could not open a database connection", ar.cause());
				future.fail(ar.cause());
			} else {
				SQLConnection connection = ar.result();
				connection.execute(SQL_CREATE_PAGES_TABLE, create -> {
					connection.close();
					if (create.failed()) {
						LOGGER.error("Database preparation error", create.cause());
						future.fail(create.cause());
					} else {
						future.complete();
					}
				});
			}
		});

		return future;
	}

	private Future<Void> startHttpServer() {
		Future<Void> future = Future.future();

		HttpServer server = vertx.createHttpServer();

		Router router = Router.router(vertx);
		router.get("/").handler(this::indexHandler);
		router.get("/wiki/:page").handler(this::pageRenderingHandler);
		router.post().handler(BodyHandler.create());
		router.post("/save").handler(this::pageUpdateHandler);
		router.post("/create").handler(this::pageCreateHandler);
		router.post("/delete").handler(this::pageDeletionHandler);

		server.requestHandler(router::accept).listen(8080, ar -> {
			if (ar.succeeded()) {
				LOGGER.info("HTTP server running on port 8080");
				future.complete();
			} else {
				LOGGER.error("Could not start a HTTP server", ar.cause());
				future.fail(ar.cause());
			}
		});

		return future;
	}

	private final FreeMarkerTemplateEngine templateEngine = FreeMarkerTemplateEngine.create();

	private void indexHandler(RoutingContext context) {
		dbClient.getConnection(car -> {
			if (car.succeeded()) {
				SQLConnection connection = car.result();
				connection.query(SQL_ALL_PAGES, res -> {
					connection.close();

					if (res.succeeded()) {
						List<String> pages = res.result() // <1>
								.getResults().stream().map(json -> json.getString(0)).sorted()
								.collect(Collectors.toList());

						context.put("title", "Wiki home"); // <2>
						context.put("pages", pages);
						templateEngine.render(context, "templates", "/index.ftl", ar -> { // <3>
							if (ar.succeeded()) {
								context.response().putHeader("Content-Type", "text/html");
								context.response().end(ar.result()); // <4>
							} else {
								context.fail(ar.cause());
							}
						});

					} else {
						context.fail(res.cause()); // <5>
					}
				});
			} else {
				context.fail(car.cause());
			}
		});
	}

	private static final String EMPTY_PAGE_MARKDOWN = "# A new page\n" + "\n" + "Feel-free to write in Markdown!\n";

	private void pageRenderingHandler(RoutingContext context) {
		String page = context.request().getParam("page"); // <1>

		dbClient.getConnection(car -> {
			if (car.succeeded()) {

				SQLConnection connection = car.result();
				connection.queryWithParams(SQL_GET_PAGE, new JsonArray().add(page), fetch -> { // <2>
					connection.close();
					if (fetch.succeeded()) {

						JsonArray row = fetch.result().getResults().stream().findFirst()
								.orElseGet(() -> new JsonArray().add(-1).add(EMPTY_PAGE_MARKDOWN));
						Integer id = row.getInteger(0);
						String rawContent = row.getString(1);

						context.put("title", page);
						context.put("id", id);
						context.put("newPage", fetch.result().getResults().size() == 0 ? "yes" : "no");
						context.put("rawContent", rawContent);
						context.put("content", Processor.process(rawContent)); // <3>
						context.put("timestamp", new Date().toString());

						templateEngine.render(context, "templates", "/page.ftl", ar -> {
							if (ar.succeeded()) {
								context.response().putHeader("Content-Type", "text/html");
								context.response().end(ar.result());
							} else {
								context.fail(ar.cause());
							}
						});
					} else {
						context.fail(fetch.cause());
					}
				});

			} else {
				context.fail(car.cause());
			}
		});
	}
	
	private void pageCreateHandler(RoutingContext context) {
	    String pageName = context.request().getParam("name");
	    String location = "/wiki/" + pageName;
	    if (pageName == null || pageName.isEmpty()) {
	      location = "/";
	    }
	    context.response().setStatusCode(303);
	    context.response().putHeader("Location", location);
	    context.response().end();
	  }
	
	private void pageUpdateHandler(RoutingContext context) {
	    String id = context.request().getParam("id");   // <1>
	    String title = context.request().getParam("title");
	    String markdown = context.request().getParam("markdown");
	    boolean newPage = "yes".equals(context.request().getParam("newPage"));  // <2>

	    dbClient.getConnection(car -> {
	      if (car.succeeded()) {
	        SQLConnection connection = car.result();
	        String sql = newPage ? SQL_CREATE_PAGE : SQL_SAVE_PAGE;
	        JsonArray params = new JsonArray();   // <3>
	        if (newPage) {
	          params.add(title).add(markdown);
	        } else {
	          params.add(markdown).add(id);
	        }
	        connection.updateWithParams(sql, params, res -> {   // <4>
	          connection.close();
	          if (res.succeeded()) {
	            context.response().setStatusCode(303);    // <5>
	            context.response().putHeader("Location", "/wiki/" + title);
	            context.response().end();
	          } else {
	            context.fail(res.cause());
	          }
	        });
	      } else {
	        context.fail(car.cause());
	      }
	    });
	  }
	
	private void pageDeletionHandler(RoutingContext context) {
	    String id = context.request().getParam("id");
	    dbClient.getConnection(car -> {
	      if (car.succeeded()) {
	        SQLConnection connection = car.result();
	        connection.updateWithParams(SQL_DELETE_PAGE, new JsonArray().add(id), res -> {
	          connection.close();
	          if (res.succeeded()) {
	            context.response().setStatusCode(303);
	            context.response().putHeader("Location", "/");
	            context.response().end();
	          } else {
	            context.fail(res.cause());
	          }
	        });
	      } else {
	        context.fail(car.cause());
	      }
	    });
	  }
}
