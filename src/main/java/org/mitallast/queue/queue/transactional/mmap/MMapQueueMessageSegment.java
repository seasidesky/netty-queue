package org.mitallast.queue.queue.transactional.mmap;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.mitallast.queue.queue.QueueMessage;
import org.mitallast.queue.queue.transactional.TransactionalQueueComponent;
import org.mitallast.queue.queue.transactional.mmap.data.QueueMessageAppendSegment;
import org.mitallast.queue.queue.transactional.mmap.meta.QueueMessageMeta;
import org.mitallast.queue.queue.transactional.mmap.meta.QueueMessageMetaSegment;
import org.mitallast.queue.queue.transactional.mmap.meta.QueueMessageStatus;

import java.io.IOException;
import java.util.UUID;

public class MMapQueueMessageSegment implements TransactionalQueueComponent {

    private final QueueMessageAppendSegment messageAppendSegment;
    private final QueueMessageMetaSegment messageMetaSegment;

    public MMapQueueMessageSegment(QueueMessageAppendSegment messageAppendSegment, QueueMessageMetaSegment messageMetaSegment) {
        this.messageAppendSegment = messageAppendSegment;
        this.messageMetaSegment = messageMetaSegment;
    }

    private QueueMessage readMessage(QueueMessageMeta meta) throws IOException {
        ByteBuf buffer = Unpooled.buffer(meta.getLength());
        messageAppendSegment.read(buffer, meta.getOffset(), meta.getLength());
        return new QueueMessage(
            meta.getUuid(),
            meta.getType(),
            buffer
        );
    }

    @Override
    public QueueMessage get(UUID uuid) throws IOException {
        QueueMessageMeta meta = messageMetaSegment.readMeta(uuid);
        if (meta != null) {
            return readMessage(meta);
        }
        return null;
    }

    @Override
    public QueueMessage lock(UUID uuid) throws IOException {
        QueueMessageMeta meta = messageMetaSegment.lock(uuid);
        if (meta != null) {
            return readMessage(meta);
        }
        return null;
    }

    @Override
    public QueueMessage lockAndPop() throws IOException {
        QueueMessageMeta meta = messageMetaSegment.lockAndPop();
        if (meta != null) {
            return readMessage(meta);
        }
        return null;
    }

    @Override
    public QueueMessage unlockAndDelete(UUID uuid) throws IOException {
        QueueMessageMeta meta = messageMetaSegment.unlockAndDelete(uuid);
        if (meta != null) {
            return readMessage(meta);
        }
        return null;
    }

    @Override
    public QueueMessage unlockAndRollback(UUID uuid) throws IOException {
        QueueMessageMeta meta = messageMetaSegment.unlockAndQueue(uuid);
        if (meta != null) {
            return readMessage(meta);
        }
        return null;
    }

    @Override
    public boolean push(QueueMessage queueMessage) throws IOException {
        if (messageMetaSegment.writeLock(queueMessage.getUuid())) {
            ByteBuf source = queueMessage.getSource();
            source.resetReaderIndex();
            int length = source.readableBytes();
            long offset = messageAppendSegment.append(source);

            QueueMessageMeta messageMeta = new QueueMessageMeta(
                queueMessage.getUuid(),
                QueueMessageStatus.INIT,
                offset,
                length,
                queueMessage.getMessageType()
            );

            messageMetaSegment.writeMeta(messageMeta);
            return true;
        }
        return false;
    }

    @Override
    public long size() {
        return 0;
    }
}
