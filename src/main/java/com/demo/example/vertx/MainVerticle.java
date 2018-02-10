package com.demo.example.vertx;

import io.reactivex.Single;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.reactivex.core.AbstractVerticle;

public class MainVerticle extends AbstractVerticle {

	@Override
	public void start(Future<Void> startFuture) throws Exception {

		Single<String> dbVerticleDeployment = vertx
				.rxDeployVerticle("com.demo.example.vertx.database.DatabaseVerticle");

		dbVerticleDeployment.flatMap(id -> { // <1>

			// This Http verticle is to show authentication and JWT token use.
			/*Single<String> httpVerticleDeployment = vertx.rxDeployVerticle(
					"com.demo.example.vertx.http.HttpServerVerticle", new DeploymentOptions().setInstances(2));*/
			
			// This Http verticle is to show single page AngularJS implementation without authentication and JWT token
			Single<String> httpVerticleDeployment = vertx.rxDeployVerticle(
					"com.demo.example.vertx.http.SinglePageHttpServerVerticle", new DeploymentOptions().setInstances(2));

			return httpVerticleDeployment;
		}).subscribe(id -> startFuture.complete(), startFuture::fail); // <2>
	}
}
