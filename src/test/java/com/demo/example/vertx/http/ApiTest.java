package com.demo.example.vertx.http;

import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.JksOptions;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.ext.web.codec.BodyCodec;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import com.demo.example.vertx.database.DatabaseVerticle;

@RunWith(VertxUnitRunner.class)
public class ApiTest {

	private Vertx vertx;
	private WebClient webClient;

	@Before
	public void prepare(TestContext context) {
		vertx = Vertx.vertx();

		JsonObject dbConf = new JsonObject()
				.put(DatabaseVerticle.CONFIG_WIKIDB_JDBC_URL, "jdbc:hsqldb:mem:testdb;shutdown=true") // <1>
				.put(DatabaseVerticle.CONFIG_WIKIDB_JDBC_MAX_POOL_SIZE, 4);

		vertx.deployVerticle(new DatabaseVerticle(), new DeploymentOptions().setConfig(dbConf),
				context.asyncAssertSuccess());

		vertx.deployVerticle(new HttpServerVerticle(), context.asyncAssertSuccess());

		webClient = WebClient.create(vertx, 
				new WebClientOptions().setDefaultHost("localhost").setDefaultPort(8080)
				.setSsl(true)
				.setTrustOptions(new JksOptions().setPath("server-keystore.jks").setPassword("secret"))
				);
	}

	@After
	public void finish(TestContext context) {
		vertx.close(context.asyncAssertSuccess());
	}

	@Test
	public void play_with_api(TestContext context) {
		Async async = context.async();

		JsonObject page = new JsonObject().put("name", "Sample").put("markdown", "# A page");

		Future<JsonObject> postRequest = Future.future();
		webClient.post("/api/pages").as(BodyCodec.jsonObject()).sendJsonObject(page, ar -> {
			if (ar.succeeded()) {
				HttpResponse<JsonObject> postResponse = ar.result();
				postRequest.complete(postResponse.body());
			} else {
				context.fail(ar.cause());
			}
		});

		Future<JsonObject> getRequest = Future.future();
		postRequest.compose(h -> {
			webClient.get("/api/pages").as(BodyCodec.jsonObject()).send(ar -> {
				if (ar.succeeded()) {
					HttpResponse<JsonObject> getResponse = ar.result();
					getRequest.complete(getResponse.body());
				} else {
					context.fail(ar.cause());
				}
			});
		}, getRequest);

		Future<JsonObject> putRequest = Future.future();
		getRequest.compose(response -> {
			JsonArray array = response.getJsonArray("pages");
			context.assertEquals(1, array.size());
			context.assertEquals(0, array.getJsonObject(0).getInteger("id"));
			webClient.put("/api/pages/0").as(BodyCodec.jsonObject())
					.sendJsonObject(new JsonObject().put("id", 0).put("markdown", "Oh Yeah!"), ar -> {
						if (ar.succeeded()) {
							HttpResponse<JsonObject> putResponse = ar.result();
							putRequest.complete(putResponse.body());
						} else {
							context.fail(ar.cause());
						}
					});
		}, putRequest);

		Future<JsonObject> deleteRequest = Future.future();
		putRequest.compose(response -> {
			context.assertTrue(response.getBoolean("success"));
			webClient.delete("/api/pages/0").as(BodyCodec.jsonObject()).send(ar -> {
				if (ar.succeeded()) {
					HttpResponse<JsonObject> delResponse = ar.result();
					deleteRequest.complete(delResponse.body());
				} else {
					context.fail(ar.cause());
				}
			});
		}, deleteRequest);

		deleteRequest.compose(response -> {
			context.assertTrue(response.getBoolean("success"));
			async.complete();
		}, Future.failedFuture("Oh?"));
	}
}
