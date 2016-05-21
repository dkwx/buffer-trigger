package com.github.phantomthief.util;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import com.github.phantomthief.collection.BufferTrigger;
import com.github.phantomthief.collection.impl.SimpleBufferTrigger;
import com.github.phantomthief.tuple.Tuple;
import com.github.phantomthief.tuple.TwoTuple;

/**
 * @author w.vela
 * Created on 16/5/21.
 */
public class TickerBatchInvoker<K, V> implements Function<K, CompletableFuture<V>> {

    private final ThrowableFunction<Collection<K>, Map<K, V>, ? extends Throwable> batchInvoker;
    private final ExecutorService executor;
    private final BufferTrigger<TwoTuple<K, CompletableFuture<V>>> bufferTrigger;

    private TickerBatchInvoker(long ticker,
            ThrowableFunction<Collection<K>, Map<K, V>, ? extends Throwable> batchInvoker,
            ExecutorService executor) {
        this.batchInvoker = batchInvoker;
        this.executor = executor;
        this.bufferTrigger = SimpleBufferTrigger.<TwoTuple<K, CompletableFuture<V>>, Map<K, CompletableFuture<V>>>newGenericBuilder() //
                .setContainer(ConcurrentHashMap::new, (map, e) -> {
                    map.put(e.getFirst(), e.getSecond());
                    return true;
                }) //
                .on(ticker, MILLISECONDS, 1) //
                .consumer(this::batchInvoke) //
                .build();
    }

    public static Builder newBuilder() {
        return new Builder();
    }

    private void batchInvoke(Map<K, CompletableFuture<V>> map) {
        executor.execute(() -> {
            try {
                Map<K, V> result = batchInvoker.apply(map.keySet());
                map.forEach((key, future) -> future.complete(result.get(key)));
            } catch (Throwable e) {
                map.values().forEach(future -> future.completeExceptionally(e));
            }
        });
    }

    @Override
    public CompletableFuture<V> apply(K key) {
        CompletableFuture<V> future = new CompletableFuture<>();
        bufferTrigger.enqueue(Tuple.tuple(key, future));
        return future;
    }

    public static class Builder {

        private long ticker;
        private ExecutorService executorService;

        private Builder() {
        }

        public Builder ticker(long time, TimeUnit unit) {
            this.ticker = unit.toMillis(time);
            return this;
        }

        public Builder executor(ExecutorService executor) {
            this.executorService = executor;
            return this;
        }

        public Builder threads(int nThreads) {
            this.executorService = Executors.newFixedThreadPool(nThreads);
            return this;
        }

        public <K, V> TickerBatchInvoker<K, V> build(
                ThrowableFunction<Collection<K>, Map<K, V>, ? extends Throwable> batchInvoker) {
            checkNotNull(batchInvoker);
            ensure();
            return new TickerBatchInvoker<>(ticker, batchInvoker, executorService);
        }

        private void ensure() {
            if (ticker <= 0) {
                ticker = SECONDS.toMillis(1);
            }
            if (executorService == null) {
                executorService = Executors.newCachedThreadPool();
            }
        }
    }
}
