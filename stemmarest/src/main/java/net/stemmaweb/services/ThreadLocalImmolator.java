package net.stemmaweb.services;

import java.lang.ref.WeakReference;
import java.lang.reflect.Array;
import java.lang.reflect.Field;

/**
 *
 * @author Administrator
 *
 *         google-gson issue # 402: Memory Leak in web application; comment # 25
 *         https://code.google.com/p/google-gson/issues/detail?id=402
 */
public class ThreadLocalImmolator {

    Boolean debug;

    public ThreadLocalImmolator() {
        debug = true;
    }

    public Integer immolate() {
        int count = 0;
        try {
            final Field threadLocalsField = Thread.class
                    .getDeclaredField("threadLocals");
            threadLocalsField.setAccessible(true);
            final Field inheritableThreadLocalsField = Thread.class
                    .getDeclaredField("inheritableThreadLocals");
            inheritableThreadLocalsField.setAccessible(true);
            for (final Thread thread : Thread.getAllStackTraces().keySet()) {
                count += clear(threadLocalsField.get(thread));
                count += clear(inheritableThreadLocalsField.get(thread));
            }
        } catch (Exception e) {
            throw new Error("ThreadLocalImmolater.immolate()", e);
        }
        return count;
    }

    private int clear(final Object threadLocalMap) throws Exception {
        if (threadLocalMap == null)
            return 0;
        int count = 0;
        final Field tableField = threadLocalMap.getClass().getDeclaredField(
                "table");
        tableField.setAccessible(true);
        final Object table = tableField.get(threadLocalMap);
        for (int i = 0, length = Array.getLength(table); i < length; ++i) {
            final Object entry = Array.get(table, i);
            if (entry != null) {
                final Object threadLocal = ((WeakReference) entry).get();
                if (threadLocal != null) {
                    log(i, threadLocal);
                    Array.set(table, i, null);
                    ++count;
                }
            }
        }
        return count;
    }

    private void log(int i, final Object threadLocal) {
        if (!debug) {
            return;
        }
        if (threadLocal.getClass() != null
                && threadLocal.getClass().getEnclosingClass() != null
                && threadLocal.getClass().getEnclosingClass().getName() != null) {

            System.out.println("threadLocalMap(" + i + "): "
                    + threadLocal.getClass().getEnclosingClass().getName());
        } else if (threadLocal.getClass() != null
                && threadLocal.getClass().getName() != null) {
            System.out.println("threadLocalMap(" + i + "): "
                    + threadLocal.getClass().getName());
        } else {
            System.out.println("threadLocalMap(" + i
                    + "): cannot identify threadlocal class name");
        }
    }

}