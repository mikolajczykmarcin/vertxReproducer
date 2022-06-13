package org.example;

import io.vertx.core.*;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.eventbus.Message;
import io.vertx.core.eventbus.MessageCodec;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.TimeoutHandler;
import io.vertx.ext.web.impl.RouterImpl;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.charset.StandardCharsets;
import java.util.Random;

public class Vertx4Reproducer {

    private static final int TIMEOUT_HANDLER_TIMEOUT = 1000;
    private static final int DELIVERY_OPTS_SEND_TIMEOUT = 400;
    private static final int HANDLER_SLEEP_MODULO = 500;
    private static final int VERTX_WORKER_POOL_SIZE = 128;
    private static final int NUMBER_OF_VERTICLE_INSTANCES = 128;
    Logger logger = LogManager.getLogger();
    private final Vertx vertx;

    public Vertx4Reproducer() {
        final VertxOptions vertxOptions = new VertxOptions();
        vertxOptions.setWorkerPoolSize(VERTX_WORKER_POOL_SIZE);
        vertx = Vertx.vertx(vertxOptions);
        vertx.exceptionHandler(h -> {
            logger.error("error in vtx exc handler" + h.getMessage(), h);
        });
        vertx.eventBus().registerCodec(new SampleFooMessageCodec());
        vertx.eventBus().registerCodec(new SampleBarMessageCodec());
        final DeploymentOptions options = new DeploymentOptions();
        options.setWorker(true);
        options.setInstances(NUMBER_OF_VERTICLE_INSTANCES);
        vertx.deployVerticle(SampleFooVerticle::new, options);
        vertx.deployVerticle(SampleBarVerticle::new, options);
        final Router router = new RouterImpl(vertx);
        createRoute(router, "foo");
        createRoute(router, "bar");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("closing vertx");
            vertx.close();
        }));
        logger.info("listening...");
        vertx.createHttpServer().requestHandler(router).listen();
    }

    private void createRoute(final Router router, final String name) {
        router.get("/" + name)
                .handler(TimeoutHandler.create(TIMEOUT_HANDLER_TIMEOUT, 408))
                .blockingHandler(context -> {
                    String generated = genStringId();
                    logger.info(generated + " got " + name + " request");
                    long start = System.currentTimeMillis();
                    DeliveryOptions opts = new DeliveryOptions();
                    opts.setCodecName(name);
                    opts.setSendTimeout(DELIVERY_OPTS_SEND_TIMEOUT);
                    opts.addHeader("id", generated);
                    vertx.eventBus().request(name, new JsonObject(), opts, result -> {
                        if (result.failed()) {
                            logger.error(opts.getHeaders().get("id") + " " + name + " failed" + result.cause().getMessage());
                            if (!context.response().headWritten()) {
                                logger.info(opts.getHeaders().get("id") + " " + name + " head not written");
                                context.response().setStatusCode(408).end();
                                return;
                            } else {
                                logger.info(opts.getHeaders().get("id") + " " + name + " head written with status " + context.response().getStatusCode());
                                return;
                            }
                        }
                        logger.info(result.result().headers().get("id") + " " + name + " time=" + (System.currentTimeMillis()-start) + "ms");
                        if (!context.response().ended()) {
                            context.response().setStatusCode(200).end(String.valueOf(result.result().body()));
                        } else {
                            logger.info(result.result().headers().get("id") + " " + name + " already ended");
                        }
                    });
                }, false)
                .failureHandler(fail -> {
                    logger.error(name + " router failure handler " + fail.failure());
                    fail.response().setStatusCode(503).end();
                });
    }

    private String genStringId() {
        byte[] array = new byte[7];
        new Random().nextBytes(array);
        return new String(array, StandardCharsets.UTF_8)+Thread.currentThread().getName().hashCode();
    }

    private class SampleBarVerticle implements Verticle {

        Vertx vvertx;


        @Override
        public Vertx getVertx() {
            return vvertx;
        }

        @Override
        public void init(Vertx vertx, Context context) {
            logger.info("initializing instance of bar verticle");
            this.vvertx = vertx;
        }

        @Override
        public void start(Promise<Void> startPromise) throws Exception {
            logger.info("starting instance of bar verticle");
            vvertx.eventBus().<JsonObject>consumer("bar").handler(new SampleBarMessageHandler());
            startPromise.complete();
        }

        @Override
        public void stop(Promise<Void> stopPromise) throws Exception {
            logger.info("stoping instance of bar verticle");
            stopPromise.complete();
        }
    }
    private class SampleFooVerticle implements Verticle {

        Vertx vvertx;


        @Override
        public Vertx getVertx() {
            return vvertx;
        }

        @Override
        public void init(Vertx vertx, Context context) {
            logger.info("initializing instance of foo verticle");
            this.vvertx = vertx;
        }

        @Override
        public void start(Promise<Void> startPromise) throws Exception {
            logger.info("starting instance of foo verticle");
            vvertx.eventBus().<JsonObject>consumer("foo").handler(new SampleFooMessageHandler());
            startPromise.complete();
        }

        @Override
        public void stop(Promise<Void> stopPromise) throws Exception {
            logger.info("stopping instance of foo verticle");
            stopPromise.complete();
        }
    }

    private class SampleFooMessageHandler implements Handler<Message<JsonObject>> {

        @Override
        public void handle(Message<JsonObject> event) {
            logger.info(event.headers().get("id") + " got foo message");
            try {
                Thread.sleep(Math.abs(new Random().nextInt()% HANDLER_SLEEP_MODULO));
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            DeliveryOptions deliveryOptions = new DeliveryOptions();
            deliveryOptions.addHeader("id", event.headers().get("id"));
            event.reply("{\"status\":\"foo\"}", deliveryOptions);
        }
    }

    private class SampleBarMessageHandler implements Handler<Message<JsonObject>> {

        @Override
        public void handle(Message<JsonObject> event) {
            logger.info(event.headers().get("id") + " got bar message");
            try {
                Thread.sleep(Math.abs(new Random().nextInt()% HANDLER_SLEEP_MODULO));
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            DeliveryOptions deliveryOptions = new DeliveryOptions();
            deliveryOptions.addHeader("id", event.headers().get("id"));
            event.reply("{\"status\":\"bar\"}", deliveryOptions);
        }
    }

    private class SampleFooMessageCodec implements MessageCodec<JsonObject, Object> {

        @Override
        public void encodeToWire(Buffer buffer, JsonObject o) {
            buffer.appendBuffer(o.toBuffer());
        }

        @Override
        public JsonObject decodeFromWire(int pos, Buffer buffer) {
            return buffer.toJsonObject();
        }

        @Override
        public JsonObject transform(JsonObject o) {
            return o;
        }

        @Override
        public String name() {
            return "foo";
        }

        @Override
        public byte systemCodecID() {
            return -1;
        }
    }

    private class SampleBarMessageCodec implements MessageCodec<JsonObject, Object> {

        @Override
        public void encodeToWire(Buffer buffer, JsonObject o) {
            buffer.appendBuffer(o.toBuffer());
        }

        @Override
        public JsonObject decodeFromWire(int pos, Buffer buffer) {
            return buffer.toJsonObject();
        }

        @Override
        public JsonObject transform(JsonObject o) {
            return o;
        }

        @Override
        public String name() {
            return "bar";
        }

        @Override
        public byte systemCodecID() {
            return -1;
        }
    }

    public static void main(String[] args) {
        new Vertx4Reproducer();
    }
}