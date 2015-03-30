package org.mitallast.queue.common.mmap;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.io.Closeable;
import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;

public class MemoryMappedPage implements Closeable {

    private final static AtomicIntegerFieldUpdater<MemoryMappedPage> referenceCountUpdater =
        AtomicIntegerFieldUpdater.newUpdater(MemoryMappedPage.class, "referenceCount");

    private final static AtomicLongFieldUpdater<MemoryMappedPage> timestampUpdater =
        AtomicLongFieldUpdater.newUpdater(MemoryMappedPage.class, "timestamp");

    private final long offset;
    private MappedByteBuffer buffer;
    private ByteBuf buf;
    private boolean dirty = false;
    private boolean closed = false;

    private volatile int referenceCount = 0;
    private volatile long timestamp = 0;

    public MemoryMappedPage(MappedByteBuffer buffer, long offset) {
        this.buffer = buffer;
        this.buf = Unpooled.wrappedBuffer(buffer);
        this.offset = offset;
    }

    public long getOffset() {
        return offset;
    }

    private int getIndex(long offset) {
        return (int) (offset - this.offset);
    }

    public void putLong(long offset, long value) {
        try {
            buffer.putLong(getIndex(offset), value);
        } catch (IndexOutOfBoundsException e) {
            System.out.println(offset + " : " + value + " ; " + (offset % 8));
            throw e;
        }
        dirty = true;
    }

    public long getLong(long offset) {
        return buffer.getLong(getIndex(offset));
    }

    public void putInt(long offset, int value) {
        buffer.putInt(getIndex(offset), value);
        dirty = true;
    }

    public int getInt(long offset) {
        return buffer.getInt(getIndex(offset));
    }

    public void putBytes(final long offset, ByteBuf byteBuf, int length) {
        int index = getIndex(offset);
        buf.setBytes(index, byteBuf, length);
        dirty = true;
    }

    public void putBytes(final long offset, ByteBuf byteBuf, int start, int length) {
        int index = getIndex(offset);
        buf.setBytes(index, byteBuf, start, length);
        dirty = true;
    }

    public void putBytes(long offset, byte[] data, int start, int length) {
        int index = getIndex(offset);
        for (int i = 0; i < length; i++, index++, start++) {
            buffer.put(index, data[start]);
        }
        dirty = true;
    }

    public void getBytes(long offset, ByteBuf buffer, int length) {
        int index = getIndex(offset);
        for (int i = 0; i < length; i++, index++) {
            buffer.writeByte(this.buffer.get(index));
        }
    }

    public void getBytes(long offset, ByteBuf buffer, int start, int length) {
        int index = getIndex(offset);
        for (int i = 0, j = start; i < length; i++, index++, j++) {
            byte byteValue = this.buffer.get(index);
            buffer.setByte(j, byteValue);
        }
    }

    public void getBytes(long offset, byte[] data, int start, int length) {
        int index = getIndex(offset);
        for (int i = 0; i < length; i++, index++, start++) {
            data[start] = buffer.get(index);
        }
    }

    public boolean acquire() {
        while (true) {
            int current = referenceCount;
            if (current >= 0) {
                if (referenceCountUpdater.compareAndSet(this, current, current + 1)) {
                    return true;
                }
            } else {
                return false;
            }
        }
    }

    public int release() {
        return referenceCountUpdater.decrementAndGet(this);
    }

    public boolean garbage() {
        while (true) {
            int current = referenceCount;
            if (current == 0) {
                if (referenceCountUpdater.compareAndSet(this, current, current - 1)) {
                    return true;
                }
            } else {
                return false;
            }
        }
    }

    public void lockTimestamp() {
        timestamp = -Math.abs(timestamp);
    }

    public void unlockTimestamp() {
        timestamp = Math.abs(timestamp);
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void updateTimestamp(long newTimestamp) {
        long current = timestampUpdater.get(this);
        if (current > 0 && current < newTimestamp) {
            timestampUpdater.compareAndSet(this, current, newTimestamp);
        }
    }

    public void flush() throws IOException {
        synchronized (this) {
            if (closed) return;
            if (dirty) {
                buffer.force();
                dirty = false;
            }
        }
    }

    @Override
    public void close() throws IOException {
        synchronized (this) {
            flush();
            if (closed) return;
            closed = true;
            MappedByteBufferCleaner.clean(buffer);
            buffer = null;
            buf = null;
        }
    }

    @Override
    public String toString() {
        return "MemoryMappedPage{" +
            "offset=" + offset +
            ", buffer=" + buffer +
            ", buf=" + buf +
            ", dirty=" + dirty +
            ", closed=" + closed +
            ", referenceCount=" + referenceCount +
            ", timestamp=" + timestamp +
            '}';
    }
}