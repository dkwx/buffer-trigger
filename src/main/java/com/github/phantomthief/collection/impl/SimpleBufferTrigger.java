/**
 * 
 */
package com.github.phantomthief.collection.impl;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import java.util.function.Supplier;

import com.github.phantomthief.collection.BufferTrigger;
import com.github.phantomthief.collection.ThrowingConsumer;
import com.google.common.util.concurrent.ThreadFactoryBuilder;

/**
 * @author w.vela
 */
public class SimpleBufferTrigger<E> implements BufferTrigger<E> {

    private static org.slf4j.Logger logger = org.slf4j.LoggerFactory
            .getLogger(SimpleBufferTrigger.class);

    /**
     * trigger like redis's rdb
     * 
     * save 900 1
     * save 300 10
     * save 60 10000
     * 
     */

    private final AtomicLong counter = new AtomicLong();
    private final ThrowingConsumer<Object> consumer;
    private final BiPredicate<Object, E> queueAdder;
    private final Supplier<Object> bufferFactory;
    private final BiConsumer<Throwable, Object> exceptionHandler;
    private final AtomicReference<Object> buffer = new AtomicReference<>();
    private final long maxBufferCount;
    private final long warningBufferThreshold;
    private final LongConsumer warningBufferHandler;
    private final Consumer<E> rejectHandler;

    private SimpleBufferTrigger(Supplier<Object> bufferFactory, BiPredicate<Object, E> queueAdder,
            ScheduledExecutorService scheduledExecutorService, ThrowingConsumer<Object> consumer,
            Map<Long, Long> triggerMap, BiConsumer<Throwable, Object> exceptionHandler,
            long maxBufferCount, Consumer<E> rejectHandler, long warningBufferThreshold,
            LongConsumer warningBufferHandler) {
        this.queueAdder = queueAdder;
        this.bufferFactory = bufferFactory;
        this.consumer = consumer;
        this.exceptionHandler = exceptionHandler;
        this.maxBufferCount = maxBufferCount;
        this.rejectHandler = rejectHandler;
        this.warningBufferHandler = warningBufferHandler;
        this.warningBufferThreshold = warningBufferThreshold;
        for (Entry<Long, Long> entry : triggerMap.entrySet()) {
            scheduledExecutorService.scheduleWithFixedDelay(() -> {
                synchronized (SimpleBufferTrigger.this) {
                    if (counter.get() < entry.getValue()) {
                        return;
                    }
                    Object old = null;
                    try {
                        old = buffer.getAndSet(bufferFactory.get());
                        counter.set(0);
                        if (old != null) {
                            consumer.acceptThrows(old);
                        }
                    } catch (Throwable e) {
                        if (this.exceptionHandler != null) {
                            try {
                                this.exceptionHandler.accept(e, old);
                            } catch (Throwable idontcare) {
                                e.printStackTrace();
                                idontcare.printStackTrace();
                            }
                        } else {
                            logger.error("Ops.", e);
                        }
                    }
                }
            }, entry.getKey(), entry.getKey(), MILLISECONDS);
        }
    }

    @Override
    public void enqueue(E element) {
        long currentCount = counter.get();
        if (warningBufferThreshold > 0 && maxBufferCount > 0 && warningBufferHandler != null) {
            if (currentCount >= warningBufferThreshold) {
                warningBufferHandler.accept(currentCount);
            }
        }
        if (maxBufferCount > 0 && currentCount >= maxBufferCount) {
            if (rejectHandler != null) {
                rejectHandler.accept(element);
            }
            return;
        }
        Object thisBuffer = buffer.updateAndGet(old -> old != null ? old : bufferFactory.get());
        boolean addSuccess = queueAdder.test(thisBuffer, element);
        if (addSuccess) {
            counter.incrementAndGet();
        }

    }

    /* (non-Javadoc)
     * @see com.github.phantomthief.collection.BufferTrigger#manuallyDoTrigger()
     */
    @Override
    public void manuallyDoTrigger() {
        synchronized (SimpleBufferTrigger.this) {
            Object old = null;
            try {
                old = buffer.getAndSet(bufferFactory.get());
                counter.set(0);
                if (old != null) {
                    consumer.accept(old);
                }
            } catch (Throwable e) {
                if (this.exceptionHandler != null) {
                    try {
                        this.exceptionHandler.accept(e, old);
                    } catch (Throwable idontcare) {
                        e.printStackTrace();
                        idontcare.printStackTrace();
                    }
                } else {
                    logger.error("Ops.", e);
                }
            }
        }
    }

    /* (non-Javadoc)
     * @see com.github.phantomthief.collection.BufferTrigger#getPendingChanges()
     */
    @Override
    public long getPendingChanges() {
        return counter.get();
    }

    @SuppressWarnings("unchecked")
    public static class Builder<E, C> {

        private ScheduledExecutorService scheduledExecutorService;
        private Supplier<C> bufferFactory;
        private BiPredicate<C, E> queueAdder;
        private ThrowingConsumer<C> consumer;
        private BiConsumer<Throwable, C> exceptionHandler;
        private long maxBufferCount = -1;
        private Consumer<E> rejectHandler;
        private long warningBufferThreshold;
        private LongConsumer warningBufferHandler;
        private final Map<Long, Long> triggerMap = new HashMap<>();

        /**
         * <b>warning:</b> the container must be thread-safed.
         * 
         * @param factory
         * @param queueAdder
         * @return
         */
        public <E1, C1> Builder<E1, C1> setContainer(Supplier<? extends C1> factory,
                BiPredicate<? super C1, ? super E1> queueAdder) {
            checkNotNull(factory);
            checkNotNull(queueAdder);

            Builder<E1, C1> thisBuilder = (Builder<E1, C1>) this;
            thisBuilder.bufferFactory = (Supplier<C1>) factory;
            thisBuilder.queueAdder = (BiPredicate<C1, E1>) queueAdder;
            return thisBuilder;
        }

        public Builder<E, C>
                setScheduleExecutorService(ScheduledExecutorService scheduledExecutorService) {
            this.scheduledExecutorService = scheduledExecutorService;
            return this;
        }

        public <E1, C1> Builder<E1, C1>
                setExceptionHandler(BiConsumer<? super Throwable, ? super C1> exceptionHandler) {
            Builder<E1, C1> thisBuilder = (Builder<E1, C1>) this;
            thisBuilder.exceptionHandler = (BiConsumer<Throwable, C1>) exceptionHandler;
            return thisBuilder;
        }

        public Builder<E, C> on(long interval, TimeUnit unit, long count) {
            triggerMap.put(unit.toMillis(interval), count);
            return this;
        }

        public <E1, C1> Builder<E1, C1> consumer(ThrowingConsumer<? super C1> consumer) {
            checkNotNull(consumer);
            Builder<E1, C1> thisBuilder = (Builder<E1, C1>) this;
            thisBuilder.consumer = (ThrowingConsumer<C1>) consumer;
            return thisBuilder;
        }

        /**
         * it's better dealing this in container
         */
        public Builder<E, C> maxBufferCount(long count) {
            checkArgument(count > 0);

            this.maxBufferCount = count;
            return this;
        }

        /**
         * it's better dealing this in container
         */
        public <E1, C1> Builder<E1, C1> maxBufferCount(long count,
                Consumer<? super E1> rejectHandler) {
            return (Builder<E1, C1>) maxBufferCount(count).rejectHandler(rejectHandler);
        }

        /**
         * it's better dealing this in container
         */
        public <E1, C1> Builder<E1, C1> rejectHandler(Consumer<? super E1> rejectHandler) {
            checkNotNull(rejectHandler);
            Builder<E1, C1> thisBuilder = (Builder<E1, C1>) this;
            thisBuilder.rejectHandler = (Consumer<E1>) rejectHandler;
            return thisBuilder;
        }

        public Builder<E, C> warningThreshold(long threshold, LongConsumer handler) {
            checkNotNull(handler);
            checkArgument(threshold > 0);

            this.warningBufferHandler = handler;
            this.warningBufferThreshold = threshold;
            return this;
        }

        public <E1> BufferTrigger<E1> build() {
            ensure();
            return new SimpleBufferTrigger<E1>((Supplier<Object>) bufferFactory,
                    (BiPredicate<Object, E1>) queueAdder, scheduledExecutorService,
                    (ThrowingConsumer<Object>) consumer, triggerMap,
                    (BiConsumer<Throwable, Object>) exceptionHandler, maxBufferCount,
                    (Consumer<E1>) rejectHandler, warningBufferThreshold, warningBufferHandler);
        }

        private void ensure() {
            checkNotNull(consumer);

            if (bufferFactory == null) {
                bufferFactory = () -> (C) Collections.synchronizedSet(new HashSet<>());
            }
            if (queueAdder == null) {
                queueAdder = (c, e) -> ((Set<E>) c).add(e);
            }
            if (!triggerMap.isEmpty() && scheduledExecutorService == null) {
                scheduledExecutorService = makeScheduleExecutor();
            }
            if (maxBufferCount > 0 && warningBufferThreshold > 0) {
                if (warningBufferThreshold >= maxBufferCount) {
                    logger.warn(
                            "invalid warning threshold:{}, it shouldn't be larger than maxBufferSize. ignore warning threshold.",
                            warningBufferThreshold);
                    warningBufferThreshold = 0;
                    warningBufferHandler = null;
                }
            }
        }

        private ScheduledExecutorService makeScheduleExecutor() {
            ScheduledExecutorService scheduledExecutorService = Executors.newScheduledThreadPool(
                    Math.max(1, triggerMap.size()), new ThreadFactoryBuilder()
                            .setNameFormat("pool-simple-buffer-trigger-thread-%d").build());

            return scheduledExecutorService;
        }
    }

    public static Builder<Object, Object> newBuilder() {
        return new Builder<>();
    }

    public static Builder<Object, Map<Object, Integer>> newCounterBuilder() {
        return new Builder<Object, Map<Object, Integer>>() //
                .setContainer(ConcurrentHashMap::new, (map, element) -> {
                    map.merge(element, 1,
                            (oldValue, appendValue) -> oldValue == null ? appendValue : oldValue
                                    + appendValue);
                    return true;
                });
    }
}
