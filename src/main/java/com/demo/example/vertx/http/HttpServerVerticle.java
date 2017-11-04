package com.demo.example.vertx.http;

import java.util.Date;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.demo.example.vertx.database.DatabaseService;
import com.github.rjeschke.txtmark.Processor;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.ext.web.codec.BodyCodec;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.templ.FreeMarkerTemplateEngine;

public class HttpServerVerticle extends AbstractVerticle {
	private static final Logger LOGGER = LoggerFactory.getLogger(HttpServerVerticle.class);
	public static final String CONFIG_HTTP_SERVER_PORT = "http.server.port";
	public static final String CONFIG_WIKIDB_QUEUE = "wikidb.queue";

	private DatabaseService dbService;
	private WebClient webClient;
	@Override
	public void start(Future<Void> startFuture) throws Exception {
		String wikiDbQueue = config().getString(CONFIG_WIKIDB_QUEUE, "wikidb.queue");
		dbService = DatabaseService.createProxy(vertx, wikiDbQueue);
		
		webClient = WebClient.create(vertx, new WebClientOptions()
				  .setSsl(true)
				  .setUserAgent("vert-x3"));
		
		HttpServer server = vertx.createHttpServer();
		Router router = Router.router(vertx);
		router.get("/").handler(this::indexHandler);
		router.get("/wiki/:page").handler(this::pageRenderingHandler);
	    router.post().handler(BodyHandler.create());
	    router.post("/save").handler(this::pageUpdateHandler);
	    router.post("/create").handler(this::pageCreateHandler);
	    router.post("/delete").handler(this::pageDeletionHandler);
	    router.get("/backup").handler(this::backupHandler);
	    
	    int portNumber = config().getInteger(CONFIG_HTTP_SERVER_PORT, 8080);
	    
	    server.requestHandler(router::accept)
	    .listen(portNumber, ar -> {
	    		if(ar.succeeded()) {
	    			LOGGER.info("HTTP server running on port " + portNumber);
	    			startFuture.complete();
	    		} else {
	    			LOGGER.error("Could not start a HTTP server", ar.cause());
	    			startFuture.fail(ar.cause());
	    		}
	    });
	}
	
	private final FreeMarkerTemplateEngine templateEngine = FreeMarkerTemplateEngine.create();

	private void indexHandler(RoutingContext context) {
		dbService.fetchAllPages(reply -> {
			if(reply.succeeded()) {
				JsonArray body = reply.result();
				context.put("title", "Wiki home");
				context.put("pages", body.getList());
				templateEngine.render(context, "templates", "/index.ftl", ar -> {
					if(ar.succeeded()) {
						context.response().putHeader("Content-Type", "text/html");
						context.response().end(ar.result());
					} else {
						context.fail(ar.cause());
					}
				});
			} else {
				context.fail(reply.cause());
			}
		});
	}
	
	private static final String EMPTY_PAGE_MARKDOWN = "# A new page\n" + "\n" + "Feel-free to write in Markdown!\n";

	private void pageRenderingHandler(RoutingContext context) {
		String page = context.request().getParam("page");
		dbService.fetchPage(page, reply -> {
			if (reply.succeeded()) {
			      JsonObject body = reply.result();

			      boolean found = body.getBoolean("found");
			      String rawContent = body.getString("rawContent", EMPTY_PAGE_MARKDOWN);
			      context.put("title", page);
			      context.put("id", body.getInteger("id", -1));
			      context.put("newPage", found ? "no" : "yes");
			      context.put("rawContent", rawContent);
			      context.put("content", Processor.process(rawContent));
			      context.put("timestamp", new Date().toString());

			      templateEngine.render(context, "templates","/page.ftl", ar -> {
			        if (ar.succeeded()) {
			          context.response().putHeader("Content-Type", "text/html");
			          context.response().end(ar.result());
			        } else {
			          context.fail(ar.cause());
			        }
			      });

			    } else {
			      context.fail(reply.cause());
			    }
		});
	}
	
	private void pageUpdateHandler(RoutingContext context) {
		String title = context.request().getParam("title");

	    Handler<AsyncResult<Void>> handler = reply -> {
	      if (reply.succeeded()) {
	        context.response().setStatusCode(303);
	        context.response().putHeader("Location", "/wiki/" + title);
	        context.response().end();
	      } else {
	        context.fail(reply.cause());
	      }
	    };

	    String markdown = context.request().getParam("markdown");
	    if ("yes".equals(context.request().getParam("newPage"))) {
	      dbService.createPage(title, markdown, handler);
	    } else {
	      dbService.savePage(Integer.valueOf(context.request().getParam("id")), markdown, handler);
	    }
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

	private void pageDeletionHandler(RoutingContext context) {
		dbService.deletePage(Integer.valueOf(context.request().getParam("id")), reply -> {
		      if (reply.succeeded()) {
		        context.response().setStatusCode(303);
		        context.response().putHeader("Location", "/");
		        context.response().end();
		      } else {
		        context.fail(reply.cause());
		      }
		    });
	}
	
	private void backupHandler(RoutingContext context) {
		dbService.fetchAllPagesData(reply -> {
			if (reply.succeeded()) {

				JsonObject filesObject = new JsonObject();
				JsonObject gistPayload = new JsonObject()
						.put("files", filesObject).put("description", "A wiki backup").put("public", true);

				reply.result().forEach(page -> {
					JsonObject fileObject = new JsonObject();
					filesObject.put(page.getString("NAME"), fileObject);
					fileObject.put("content", page.getString("CONTENT"));
				});

				webClient.post(443, "api.github.com", "/gists")
						.putHeader("Accept", "application/vnd.github.v3+json")
						.putHeader("Content-Type", "application/json").as(BodyCodec.jsonObject())
						.sendJsonObject(gistPayload, ar -> {
							if (ar.succeeded()) {
								HttpResponse<JsonObject> response = ar.result();
								if (response.statusCode() == 201) {
									context.put("backup_gist_url", response.body().getString("html_url"));
									indexHandler(context);
								} else {
									StringBuilder message = new StringBuilder().append("Could not backup the wiki: ")
											.append(response.statusMessage());
									JsonObject body = response.body();
									if (body != null) {
										message.append(System.getProperty("line.separator"))
												.append(body.encodePrettily());
									}
									LOGGER.error(message.toString());
									context.fail(502);
								}
							} else {
								Throwable err = ar.cause();
								LOGGER.error("HTTP Client error", err);
								context.fail(err);
							}
						});

			} else {
				context.fail(reply.cause());
			}
		});
	}
}
