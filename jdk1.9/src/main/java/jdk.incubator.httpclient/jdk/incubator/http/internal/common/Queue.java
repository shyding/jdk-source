/*
 * Copyright (c) 2015, 2016, Oracle and/or its affiliates. All rights reserved.
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 */

package jdk.incubator.http.internal.common;

import java.io.IOException;
import java.util.LinkedList;
import java.util.stream.Stream;
import java.util.Objects;

// Each stream has one of these for input. Each Http2Connection has one
// for output. Can be used blocking or asynchronously.

public class Queue<T> implements ExceptionallyCloseable  {

    private final LinkedList<T> q = new LinkedList<>();
    private volatile boolean closed = false;
    private volatile Throwable exception = null;
    private Runnable callback;
    private boolean callbackDisabled = false;
    private int waiters; // true if someone waiting

    public synchronized int size() {
        return q.size();
    }

    public synchronized boolean tryPut(T obj) throws IOException {
        if (closed) return false;
        put(obj);
        return true;
    }

    public synchronized void put(T obj) throws IOException {
        if (closed) {
            throw new IOException("stream closed");
        }

        q.add(obj);

        if (waiters > 0) {
            notifyAll();
        }

        if (callbackDisabled) {
            return;
        }

        if (q.size() > 0 && callback != null) {
            // Note: calling callback while holding the lock is
            // dangerous and may lead to deadlocks.
            callback.run();
        }
    }

    public synchronized void disableCallback() {
        callbackDisabled = true;
    }

    public synchronized void enableCallback() {
        callbackDisabled = false;
        while (q.size() > 0) {
            callback.run();
        }
    }

    /**
     * callback is invoked any time put is called where
     * the Queue was empty.
     */
    public synchronized void registerPutCallback(Runnable callback) {
        Objects.requireNonNull(callback);
        this.callback = callback;
        if (q.size() > 0) {
            // Note: calling callback while holding the lock is
            // dangerous and may lead to deadlocks.
            callback.run();
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        notifyAll();
    }

    @Override
    public synchronized void closeExceptionally(Throwable t) {
        if (exception == null) exception = t;
        else if (t != null && t != exception) {
            if (!Stream.of(exception.getSuppressed())
                .filter(x -> x == t)
                .findFirst()
                .isPresent())
            {
                exception.addSuppressed(t);
            }
        }
        close();
    }

    public synchronized T take() throws IOException {
        if (closed) {
            throw newIOException("stream closed");
        }
        try {
            while (q.size() == 0) {
                waiters++;
                wait();
                if (closed) {
                    throw newIOException("Queue closed");
                }
                waiters--;
            }
            return q.removeFirst();
        } catch (InterruptedException ex) {
            throw new IOException(ex);
        }
    }

    public synchronized T poll() throws IOException {
        if (closed) {
            throw newIOException("stream closed");
        }

        if (q.isEmpty()) {
            return null;
        }
        T res = q.removeFirst();
        return res;
    }

    public synchronized T[] pollAll(T[] type) throws IOException {
        T[] ret = q.toArray(type);
        q.clear();
        return ret;
    }

    public synchronized void pushback(T v) {
        q.addFirst(v);
    }

    public synchronized void pushbackAll(T[] v) {
        for (int i=v.length-1; i>=0; i--) {
            q.addFirst(v[i]);
        }
    }

    private IOException newIOException(String msg) {
        if (exception == null) {
            return new IOException(msg);
        } else {
            return new IOException(msg, exception);
        }
    }

}
