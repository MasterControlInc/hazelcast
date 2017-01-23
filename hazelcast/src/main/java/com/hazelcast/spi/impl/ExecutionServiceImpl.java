/*
 * Copyright (c) 2008-2013, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.spi.impl;

import com.hazelcast.config.ExecutorConfig;
import com.hazelcast.instance.Node;
import com.hazelcast.logging.ILogger;
import com.hazelcast.spi.ExecutionService;
import com.hazelcast.spi.annotation.PrivateApi;
import com.hazelcast.util.ConcurrencyUtil;
import com.hazelcast.util.ConstructorFunction;
import com.hazelcast.util.ExceptionUtil;
import com.hazelcast.util.executor.ManagedExecutorService;
import com.hazelcast.util.executor.PoolExecutorThreadFactory;
import com.hazelcast.util.executor.SingleExecutorThreadFactory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;

/**
 * @author mdogan 12/14/12
 */
public final class ExecutionServiceImpl implements ExecutionService {

    private final NodeEngineImpl nodeEngine;
    private final ExecutorService cachedExecutorService;
    private final ScheduledExecutorService scheduledExecutorService;
    private final ScheduledExecutorService defaultScheduledExecutorServiceDelegate;
    private final ILogger logger;

    private final ConcurrentMap<String, ManagedExecutorService> executors = new ConcurrentHashMap<String, ManagedExecutorService>();

    public ExecutionServiceImpl(NodeEngineImpl nodeEngine) {
        this.nodeEngine = nodeEngine;
        final Node node = nodeEngine.getNode();
        logger = node.getLogger(ExecutionService.class.getName());
        final ClassLoader classLoader = node.getConfigClassLoader();
        final ThreadFactory threadFactory = new PoolExecutorThreadFactory(node.threadGroup,
                node.getThreadPoolNamePrefix("cached"), classLoader);

        cachedExecutorService = new ThreadPoolExecutor(
                3, Integer.MAX_VALUE, 60L, TimeUnit.SECONDS,
                new SynchronousQueue<Runnable>(), threadFactory, new RejectedExecutionHandler() {
            public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
                logger.finest( "Node is shutting down; discarding the task: " + r);
            }
        });

        final String scheduledThreadName = node.getThreadNamePrefix("scheduled");
        scheduledExecutorService = new ScheduledThreadPoolExecutor(1, new SingleExecutorThreadFactory(node.threadGroup, classLoader, scheduledThreadName));
        enableRemoveOnCancelIfAvailable();

        final int coreSize = Runtime.getRuntime().availableProcessors();
        // default executors
        register(SYSTEM_EXECUTOR, coreSize, Integer.MAX_VALUE);
        register(OPERATION_EXECUTOR, coreSize * 2, Integer.MAX_VALUE);
        register(ASYNC_EXECUTOR, coreSize * 10, coreSize * 10000);
        register(CLIENT_EXECUTOR, coreSize * 10, coreSize * 10000);
        register(SCHEDULED_EXECUTOR, coreSize * 5, coreSize * 10000);
        defaultScheduledExecutorServiceDelegate = getScheduledExecutor(SCHEDULED_EXECUTOR);
    }

    public Set<String> getExecutorNames(){
        return new HashSet<String>(executors.keySet());
    }

    private void enableRemoveOnCancelIfAvailable() {
        try {
            final Method m = scheduledExecutorService.getClass().getMethod("setRemoveOnCancelPolicy", boolean.class);
            m.invoke(scheduledExecutorService, true);
        } catch (NoSuchMethodException ignored) {
        } catch (InvocationTargetException ignored) {
        } catch (IllegalAccessException ignored) {
        }
    }

    public ExecutorService register(String name, int poolSize, int queueCapacity) {
        ExecutorConfig cfg = nodeEngine.getConfig().getExecutorConfigs().get(name);
        if (cfg != null) {
            poolSize = cfg.getPoolSize();
            queueCapacity = cfg.getQueueCapacity() <= 0 ? Integer.MAX_VALUE : cfg.getQueueCapacity();
        }
        final ManagedExecutorService executor = new ManagedExecutorService(name, cachedExecutorService,
                poolSize, queueCapacity);
        if (executors.putIfAbsent(name, executor) != null) {
            throw new IllegalArgumentException();
        }
        return executor;
    }

    private final ConstructorFunction<String, ManagedExecutorService> constructor =
            new ConstructorFunction<String, ManagedExecutorService>() {
                public ManagedExecutorService createNew(String name) {
                    final ExecutorConfig cfg = nodeEngine.getConfig().findExecutorConfig(name);
                    final int queueCapacity = cfg.getQueueCapacity() <= 0 ? Integer.MAX_VALUE : cfg.getQueueCapacity();
                    return new ManagedExecutorService(name, cachedExecutorService, cfg.getPoolSize(), queueCapacity);
                }
            };

    public ManagedExecutorService getExecutor(String name) {
        return ConcurrencyUtil.getOrPutIfAbsent(executors, name, constructor);
    }

    public void execute(String name, Runnable command) {
        getExecutor(name).execute(command);
    }

    public Future<?> submit(String name, Runnable task) {
        return getExecutor(name).submit(task);
    }

    public <T> Future<T> submit(String name, Callable<T> task) {
        return getExecutor(name).submit(task);
    }

    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
        return defaultScheduledExecutorServiceDelegate.schedule(command, delay, unit);
    }
    public ScheduledFuture<?> schedule(String name, Runnable command, long delay, TimeUnit unit) {
        return getScheduledExecutor(name).schedule(command, delay, unit);
    }

    public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
        return defaultScheduledExecutorServiceDelegate.scheduleAtFixedRate(command, initialDelay, period, unit);
    }

    public ScheduledFuture<?> scheduleAtFixedRate(String name,Runnable command, long initialDelay, long period, TimeUnit unit) {
        return getScheduledExecutor(name).scheduleAtFixedRate(command, initialDelay, period, unit);
    }

    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long period, TimeUnit unit) {
        return defaultScheduledExecutorServiceDelegate.scheduleWithFixedDelay(command, initialDelay, period, unit);
    }

    public ScheduledFuture<?> scheduleWithFixedDelay(String name, Runnable command, long initialDelay, long period, TimeUnit unit) {
        return getScheduledExecutor(name).scheduleWithFixedDelay(command, initialDelay, period, unit);
    }

    @PrivateApi
    public Executor getCachedExecutor() {
        return new ExecutorDelegate(cachedExecutorService);
    }

    public ScheduledExecutorService getDefaultScheduledExecutor() {
        return defaultScheduledExecutorServiceDelegate;
    }
    public ScheduledExecutorService getScheduledExecutor(String name) {
        return new ScheduledExecutorServiceDelegate(scheduledExecutorService, getExecutor(name));
    }

    @PrivateApi
    void shutdown() {
        logger.finest( "Stopping executors...");
        cachedExecutorService.shutdown();
        scheduledExecutorService.shutdownNow();
        try {
            cachedExecutorService.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            logger.finest(e);
        }
        for (ExecutorService executorService : executors.values()) {
            executorService.shutdown();
        }
        executors.clear();
    }

    public void shutdownExecutor(String name) {
        final ExecutorService ex = executors.remove(name);
        if (ex != null) {
            ex.shutdown();
        }
    }

    private static class ExecutorDelegate implements Executor {
        private final Executor executor;

        private ExecutorDelegate(Executor executor) {
            this.executor = executor;
        }

        public void execute(Runnable command) {
            executor.execute(command);
        }
    }

    private static class ScheduledExecutorServiceDelegate implements ScheduledExecutorService {

        private final ScheduledExecutorService scheduledExecutorService;
        private final ExecutorService executor;

        private ScheduledExecutorServiceDelegate(ScheduledExecutorService scheduledExecutorService, ExecutorService executor) {
            this.scheduledExecutorService = scheduledExecutorService;
            this.executor = executor;
        }

        public void execute(Runnable command) {
            executor.execute(command);
        }

        public <T> Future<T> submit(Callable<T> task) {
            return executor.submit(task);
        }

        public <T> Future<T> submit(Runnable task, T result) {
            return executor.submit(task, result);
        }

        public Future<?> submit(Runnable task) {
            return executor.submit(task);
        }

        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            return scheduledExecutorService.schedule(new ScheduledTaskRunner(command, executor), delay, unit);
        }

        public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
            return scheduledExecutorService.scheduleAtFixedRate(new ScheduledTaskRunner(command, executor), initialDelay, period, unit);
        }

        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
            return scheduledExecutorService.scheduleWithFixedDelay(new ScheduledTaskRunner(command, executor), initialDelay, delay, unit);
        }

        public void shutdown() {
            throw new UnsupportedOperationException();
        }

        public List<Runnable> shutdownNow() {
            throw new UnsupportedOperationException();
        }

        public boolean isShutdown() {
            return false;
        }

        public boolean isTerminated() {
            return false;
        }

        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            throw new UnsupportedOperationException();
        }

        public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) throws InterruptedException {
            throw new UnsupportedOperationException();
        }

        public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) throws InterruptedException {
            throw new UnsupportedOperationException();
        }

        public <T> T invokeAny(Collection<? extends Callable<T>> tasks) throws InterruptedException, ExecutionException {
            throw new UnsupportedOperationException();
        }

        public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
            throw new UnsupportedOperationException();
        }

        public <V> ScheduledFuture<V> schedule(final Callable<V> callable, long delay, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }
    }
    private static class ScheduledTaskRunner implements Runnable {

        private final Executor executor;
        private final Runnable runnable;

        public ScheduledTaskRunner(Runnable runnable, Executor executor) {
            this.executor = executor;
            this.runnable = runnable;
        }

        public void run() {
            try {
                executor.execute(runnable);
            } catch (Throwable t) {
                ExceptionUtil.sneakyThrow(t);
            }
        }
    }



}