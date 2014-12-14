package org.mitallast.queue.queue.service.translog;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import org.mitallast.queue.queue.QueueMessage;
import org.mitallast.queue.queue.QueueMessageType;
import org.mitallast.queue.queue.QueueMessageUuidDuplicateException;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLong;

/**
 * File structure
 * - int message count
 * - message meta info * size
 * - message data * size
 */
public class FileQueueMessageAppendTransactionLog implements Closeable {

    private final static long INT_SIZE = 4;
    private final static long LONG_SIZE = 8;
    private final static long MESSAGE_META_SIZE = LONG_SIZE * 8;
    private final static long MESSAGE_COUNT_OFFSET = 0;
    private final static long MESSAGE_META_OFFSET = MESSAGE_COUNT_OFFSET + INT_SIZE;
    private final MemoryMappedFile metaMemoryMappedFile;
    private final MemoryMappedFile dataMemoryMappedFile;

    private final AtomicLong messageWriteOffset = new AtomicLong();
    private final AtomicInteger messageCount = new AtomicInteger();

    private final ConcurrentLinkedQueue<QueueMessageMeta> messageMetaQueue = new ConcurrentLinkedQueue<>();
    private final ConcurrentHashMap<UUID, QueueMessageMeta> messageMetaMap = new ConcurrentHashMap<>(65536, 0.5f);

    public FileQueueMessageAppendTransactionLog(File metaFile, File dataFile) throws IOException {
        this(metaFile, dataFile, 1048576, 10);
    }

    public FileQueueMessageAppendTransactionLog(File metaFile, File dataFile, int pageSize, int maxPages) throws IOException {
        if (!metaFile.exists()) {
            if (!metaFile.createNewFile()) {
                throw new IOException("File not found, or not writable " + metaFile);
            }
        }
        if (!dataFile.exists()) {
            if (!dataFile.createNewFile()) {
                throw new IOException("File not found, or not writable " + metaFile);
            }
        }
        metaMemoryMappedFile = new MemoryMappedFile(new RandomAccessFile(metaFile, "rw"), pageSize, maxPages);
        dataMemoryMappedFile = new MemoryMappedFile(new RandomAccessFile(dataFile, "rw"), pageSize, maxPages);
    }

    public void initializeNew() throws IOException {
        writeMessageCount(0);
        messageMetaQueue.clear();
        messageMetaMap.clear();
        messageWriteOffset.set(0);
    }

    public void initializeExists() throws IOException {
        messageCount.set(readMessageCount());
        messageMetaMap.clear();
        messageMetaQueue.clear();

        for (int i = 0; i < messageCount.get(); i++) {
            QueueMessageMeta messageMeta = readMeta(i);
            messageMeta.pos = i;
            messageMetaMap.put(messageMeta.uuid, messageMeta);
            messageMetaQueue.add(messageMeta);
        }
        messageWriteOffset.set(dataMemoryMappedFile.length());
    }

    public int getMessageCount() {
        return messageCount.get();
    }

    /**
     * @param queueMessage new message
     * @return message position
     */
    public int putMessage(QueueMessage queueMessage) throws IOException {
        QueueMessageMeta messageMeta = new QueueMessageMeta();
        messageMeta.uuid = queueMessage.getUuid();
        messageMeta.pos = messageCount.getAndIncrement();
        if (messageMetaMap.putIfAbsent(messageMeta.uuid, messageMeta) != null) {
            throw new QueueMessageUuidDuplicateException(messageMeta.uuid);
        }

        ByteBuf source = queueMessage.getSource();
        source.resetReaderIndex();

        messageMeta.offset = messageWriteOffset.getAndAdd(source.readableBytes());
        messageMeta.length = source.readableBytes();
        messageMeta.type = queueMessage.getMessageType().ordinal();
        messageMeta.setStatus(QueueMessageMeta.Status.New);

        dataMemoryMappedFile.putBytes(messageMeta.offset, source);
//        dataMemoryMappedFile.flush();

        writeMeta(messageMeta, messageMeta.pos);
        writeMessageCount(messageCount.get());

        messageMetaQueue.add(messageMeta);

//        metaMemoryMappedFile.flush();
        return messageMeta.pos;
    }

    public void markMessageDeleted(UUID uuid) throws IOException {
        QueueMessageMeta meta = messageMetaMap.get(uuid);
        if (meta != null) {
            meta.setStatus(QueueMessageMeta.Status.Deleted);
            writeMeta(meta, meta.pos);
            messageMetaMap.remove(uuid);
        }
    }

    public QueueMessage peekMessage() throws IOException {
        for (QueueMessageMeta messageMeta : messageMetaQueue) {
            if (messageMeta.isStatus(QueueMessageMeta.Status.New)) {
                return readMessage(messageMeta, false);
            }
        }
        return null;
    }

    public QueueMessage dequeueMessage() throws IOException {
        QueueMessageMeta messageMeta;
        while ((messageMeta = messageMetaQueue.poll()) != null) {
            if (messageMeta.updateStatus(QueueMessageMeta.Status.New, QueueMessageMeta.Status.Deleted)) {
                messageMetaMap.remove(messageMeta.uuid);
                writeMeta(messageMeta, messageMeta.pos);
                return readMessage(messageMeta, false);
            }
        }
        return null;
    }

    public QueueMessage readMessage(UUID uuid) throws IOException {
        QueueMessageMeta meta = messageMetaMap.get(uuid);
        if (meta != null) {
            return readMessage(meta, true);
        }
        return null;
    }

    private QueueMessage readMessage(QueueMessageMeta meta, boolean checkDeletion) throws IOException {
        if (meta.isStatus(QueueMessageMeta.Status.None)) {
            return null;
        }
        if (checkDeletion && meta.isStatus(QueueMessageMeta.Status.Deleted)) {
            return null;
        }
        ByteBuf buffer = Unpooled.buffer(meta.length);
        dataMemoryMappedFile.getBytes(meta.offset, buffer, meta.length);
        return new QueueMessage(meta.uuid, QueueMessageType.values()[meta.type], buffer);
    }

    private void writeMessageCount(int maxSize) throws IOException {
        metaMemoryMappedFile.putInt(MESSAGE_COUNT_OFFSET, maxSize);
    }

    private int readMessageCount() throws IOException {
        return metaMemoryMappedFile.getInt(MESSAGE_COUNT_OFFSET);
    }

    public void writeMeta(QueueMessageMeta messageMeta, int pos) throws IOException {
        writeMeta(messageMeta, getMetaOffset(pos));
    }

    public void writeMeta(QueueMessageMeta messageMeta, long offset) throws IOException {
        ByteBuf buffer = PooledByteBufAllocator.DEFAULT.buffer((int) MESSAGE_META_SIZE);
        buffer.clear();
        try {
            buffer.writeLong(messageMeta.uuid.getMostSignificantBits());
            buffer.writeLong(messageMeta.uuid.getLeastSignificantBits());
            buffer.writeLong(messageMeta.offset);
            buffer.writeInt(messageMeta.status);
            buffer.writeInt(messageMeta.length);
            buffer.writeInt(messageMeta.type);
            buffer.resetReaderIndex();
            metaMemoryMappedFile.putBytes(offset, buffer);
        } finally {
            buffer.release();
        }
    }

    private QueueMessageMeta readMeta(int pos) throws IOException {
        QueueMessageMeta messageMeta = readMeta(getMetaOffset(pos));
        messageMeta.pos = pos;
        return messageMeta;
    }

    public QueueMessageMeta readMeta(long offset) throws IOException {
        ByteBuf buffer = PooledByteBufAllocator.DEFAULT.buffer((int) MESSAGE_META_SIZE);
        buffer.clear();
        try {
            metaMemoryMappedFile.getBytes(offset, buffer, (int) MESSAGE_META_SIZE);
            buffer.resetReaderIndex();

            QueueMessageMeta messageMeta = new QueueMessageMeta();
            long UUIDMost = buffer.readLong();
            long UUIDLeast = buffer.readLong();
            if (UUIDMost != 0 && UUIDLeast != 0) {
                messageMeta.uuid = new UUID(UUIDMost, UUIDLeast);
            } else {
                messageMeta.uuid = null;
            }
            messageMeta.offset = buffer.readLong();
            messageMeta.status = buffer.readInt();
            messageMeta.length = buffer.readInt();
            messageMeta.type = buffer.readInt();
            return messageMeta;
        } finally {
            buffer.release();
        }
    }

    private long getMetaOffset(int pos) {
        return MESSAGE_META_OFFSET + MESSAGE_META_SIZE * pos;
    }

    @Override
    public void close() throws IOException {
        metaMemoryMappedFile.close();
        dataMemoryMappedFile.close();
    }

    public static class QueueMessageMeta {

        private final static AtomicIntegerFieldUpdater<QueueMessageMeta> statusUpdater =
                AtomicIntegerFieldUpdater.newUpdater(QueueMessageMeta.class, "status");

        private UUID uuid;
        private long offset;
        private volatile int status;
        private int length;
        private int pos;
        private int type;

        public QueueMessageMeta() {
        }

        public QueueMessageMeta(UUID uuid, long offset, int status, int length, int type) {
            this.uuid = uuid;
            this.offset = offset;
            this.status = status;
            this.length = length;
            this.type = type;
        }

        public boolean isStatus(Status expectedStatus) {
            return expectedStatus.ordinal() == status;
        }

        public void setStatus(Status newStatus) {
            status = newStatus.ordinal();
        }

        public boolean updateStatus(Status expectedStatus, Status newStatus) {
            return statusUpdater.compareAndSet(this, expectedStatus.ordinal(), newStatus.ordinal());
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            QueueMessageMeta that = (QueueMessageMeta) o;

            if (length != that.length) return false;
            if (offset != that.offset) return false;
            if (status != that.status) return false;
            if (type != that.type) return false;
            if (uuid != null ? !uuid.equals(that.uuid) : that.uuid != null) return false;

            return true;
        }

        @Override
        public int hashCode() {
            int result = uuid != null ? uuid.hashCode() : 0;
            result = 31 * result + (int) (offset ^ (offset >>> 32));
            result = 31 * result + length;
            result = 31 * result + pos;
            result = 31 * result + type;
            return result;
        }

        @Override
        public String toString() {
            return "QueueMessageMeta{" +
                    "uuid=" + uuid +
                    ", offset=" + offset +
                    ", status=" + status +
                    ", length=" + length +
                    ", type=" + type +
                    '}';
        }

        enum Status {None, New, Deleted}
    }
}