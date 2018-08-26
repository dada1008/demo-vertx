package com.demo.example.vertx.http;

import java.util.Arrays;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.rjeschke.txtmark.Processor;

import io.reactivex.Flowable;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.handler.sockjs.BridgeOptions;
import io.vertx.ext.web.handler.sockjs.PermittedOptions;
import io.vertx.reactivex.ext.web.handler.sockjs.SockJSHandler;
// tag::rx-imports[]
import io.vertx.reactivex.core.AbstractVerticle;
import io.vertx.reactivex.core.http.HttpServer;
import io.vertx.reactivex.ext.web.Router;
import io.vertx.reactivex.ext.web.RoutingContext;
import io.vertx.reactivex.ext.web.handler.BodyHandler;
import io.vertx.reactivex.ext.web.handler.CookieHandler;
import io.vertx.reactivex.ext.web.handler.SessionHandler;
import io.vertx.reactivex.ext.web.handler.StaticHandler;
import io.vertx.reactivex.ext.web.sstore.LocalSessionStore;

public class SinglePageHttpServerVerticle extends AbstractVerticle {

	public static final String CONFIG_HTTP_SERVER_PORT = "http.server.port";
	public static final String CONFIG_WIKIDB_QUEUE = "wikidb.queue";

	private static final Logger LOGGER = LoggerFactory.getLogger(SinglePageHttpServerVerticle.class);

	private com.demo.example.vertx.database.reactivex.DatabaseService dbService;

	// tag::rx-vertx-delegate[]
	@Override
	public void start(Future<Void> startFuture) throws Exception {

		String wikiDbQueue = config().getString(CONFIG_WIKIDB_QUEUE, "wikidb.queue");
		dbService = com.demo.example.vertx.database.DatabaseService.createProxy(vertx.getDelegate(), wikiDbQueue);
		// end::rx-vertx-delegate[]

		HttpServer server = vertx.createHttpServer();

		Router router = Router.router(vertx);

		router.route().handler(CookieHandler.create());
		router.route().handler(BodyHandler.create());
		router.route().handler(SessionHandler.create(LocalSessionStore.create(vertx)));

		// tag::sockjs-handler-setup[]
	    SockJSHandler sockJSHandler = SockJSHandler.create(vertx); // <1>
	    BridgeOptions bridgeOptions = new BridgeOptions()
	      .addInboundPermitted(new PermittedOptions().setAddress("app.markdown"))  // <2>
	      .addOutboundPermitted(new PermittedOptions().setAddress("page.saved")); // <3>
	    sockJSHandler.bridge(bridgeOptions); // <4>
	    router.route("/eventbus/*").handler(sockJSHandler); // <5>
	    // end::sockjs-handler-setup[]
	    
	    // tag::eventbus-markdown-consumer[]
	    vertx.eventBus().<String>consumer("app.markdown", msg -> {
	      String html = Processor.process(msg.body());
	      msg.reply(html);
	    });
	    // end::eventbus-markdown-consumer[]
	    
		// tag::static-assets[]
		router.get("/app/*").handler(StaticHandler.create().setCachingEnabled(false)); // <1> <2>
		router.get("/").handler(context -> context.reroute("/app/index.html"));
		// end::static-assets[]

		// tag::preview-rendering[]
		router.post("/app/markdown").handler(context -> {
			String html = Processor.process(context.getBodyAsString());
			context.response().putHeader("Content-Type", "text/html").setStatusCode(200).end(html);
		});
		// end::preview-rendering[]

		// tag::routes[]
		router.get("/api/pages").handler(this::apiRoot);
		router.get("/api/pages/:id").handler(this::apiGetPage);
		router.post().handler(BodyHandler.create());
		router.post("/api/pages").handler(this::apiCreatePage);
		router.put().handler(BodyHandler.create());
		router.put("/api/pages/:id").handler(this::apiUpdatePage);
		router.delete("/api/pages/:id").handler(this::apiDeletePage);
		// end::routes[]

		int portNumber = config().getInteger(CONFIG_HTTP_SERVER_PORT, 8080);
		server.requestHandler(router::accept).rxListen(portNumber).subscribe(s -> {
			LOGGER.info("HTTP server running on port " + portNumber);
			startFuture.complete();
		}, t -> {
			LOGGER.error("Could not start a HTTP server", t);
			startFuture.fail(t);
		});
	}

	private void apiDeletePage(RoutingContext context) {
		int id = Integer.valueOf(context.request().getParam("id"));
		dbService.rxDeletePage(id).subscribe(() -> apiResponse(context, 200, null, null), t -> apiFailure(context, t));
	}

	// tag::apiUpdatePage[]
	private void apiUpdatePage(RoutingContext context) {
		int id = Integer.valueOf(context.request().getParam("id"));
		JsonObject page = context.getBodyAsJson();
		if (!validateJsonPageDocument(context, page, "markdown")) {
			return;
		}
		dbService.rxSavePage(id, page.getString("markdown"))
		.doOnComplete(() -> { // <1>
	        JsonObject event = new JsonObject()
	          .put("id", id) // <2>
	          .put("client", page.getString("client")); // <3>
	        vertx.eventBus().publish("page.saved", event); // <4>
	      })
		.subscribe(() -> apiResponse(context, 200, null, null),
				t -> apiFailure(context, t));
	}
	// end::apiUpdatePage[]

	private boolean validateJsonPageDocument(RoutingContext context, JsonObject page, String... expectedKeys) {
		if (!Arrays.stream(expectedKeys).allMatch(page::containsKey)) {
			LOGGER.error("Bad page creation JSON payload: " + page.encodePrettily() + " from "
					+ context.request().remoteAddress());
			context.response().setStatusCode(400);
			context.response().putHeader("Content-Type", "application/json");
			context.response().end(new JsonObject().put("success", false).put("error", "Bad request payload").encode());
			return false;
		}
		return true;
	}

	private void apiCreatePage(RoutingContext context) {
		JsonObject page = context.getBodyAsJson();
		if (!validateJsonPageDocument(context, page, "name", "markdown")) {
			return;
		}
		dbService.rxCreatePage(page.getString("name"), page.getString("markdown"))
				.subscribe(() -> apiResponse(context, 201, null, null), t -> apiFailure(context, t));
	}

	private void apiGetPage(RoutingContext context) {
		int id = Integer.valueOf(context.request().getParam("id"));
		dbService.rxFetchPageById(id).subscribe(dbObject -> {
			if (dbObject.getBoolean("found")) {
				JsonObject payload = new JsonObject().put("name", dbObject.getString("name"))
						.put("id", dbObject.getInteger("id")).put("markdown", dbObject.getString("content"))
						.put("html", Processor.process(dbObject.getString("content")));
				apiResponse(context, 200, "page", payload);
			} else {
				apiFailure(context, 404, "There is no page with ID " + id);
			}
		}, t -> apiFailure(context, t));
	}

	private void apiRoot(RoutingContext context) {
		dbService.rxFetchAllPagesData().flatMapPublisher(Flowable::fromIterable)
				.map(obj -> new JsonObject().put("id", obj.getInteger("ID")).put("name", obj.getString("NAME")))
				.collect(JsonArray::new, JsonArray::add)
				.subscribe(pages -> apiResponse(context, 200, "pages", pages), t -> apiFailure(context, t));
	}

	private void apiResponse(RoutingContext context, int statusCode, String jsonField, Object jsonData) {
		context.response().setStatusCode(statusCode);
		context.response().putHeader("Content-Type", "application/json");
		JsonObject wrapped = new JsonObject().put("success", true);
		if (jsonField != null && jsonData != null) {
			wrapped.put(jsonField, jsonData);
		}
		context.response().end(wrapped.encode());
	}

	private void apiFailure(RoutingContext context, Throwable t) {
		apiFailure(context, 500, t.getMessage());
	}

	private void apiFailure(RoutingContext context, int statusCode, String error) {
		context.response().setStatusCode(statusCode);
		context.response().putHeader("Content-Type", "application/json");
		context.response().end(new JsonObject().put("success", false).put("error", error).encode());
	}
}
