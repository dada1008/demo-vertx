package com.demo.example.vertx;

import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.rxjava.core.AbstractVerticle;
import rx.Single;

public class MainVerticle extends AbstractVerticle {

	@Override
	public void start(Future<Void> startFuture) throws Exception {

		Single<String> dbVerticleDeployment = vertx
				.rxDeployVerticle("com.demo.example.vertx.database.DatabaseVerticle");

		dbVerticleDeployment.flatMap(id -> { // <1>

			Single<String> httpVerticleDeployment = vertx.rxDeployVerticle(
					"com.demo.example.vertx.http.HttpServerVerticle", new DeploymentOptions().setInstances(2));

			return httpVerticleDeployment;
		}).subscribe(id -> startFuture.complete(), startFuture::fail); // <2>
	}
}
