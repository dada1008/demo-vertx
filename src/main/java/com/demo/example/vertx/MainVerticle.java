package com.demo.example.vertx;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;

public class MainVerticle extends AbstractVerticle {

    @Override
    public void start(Future<Void> fut) throws Exception {
        vertx.createHttpServer().requestHandler(req -> {
              req.response()
                .putHeader("content-type", "text/plain")
                .end("Hello Vert.x from Dada!");
            }).listen(8080, result -> {
                if (result.succeeded()) {
                    fut.complete();
                  } else {
                    fut.fail(result.cause());
                  }
                });
        System.out.println("HTTP server started on port 8080");
    }
}
