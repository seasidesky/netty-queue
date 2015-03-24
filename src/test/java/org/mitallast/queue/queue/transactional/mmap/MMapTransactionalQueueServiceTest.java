package org.mitallast.queue.queue.transactional.mmap;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mitallast.queue.common.BaseTest;
import org.mitallast.queue.common.settings.ImmutableSettings;
import org.mitallast.queue.queue.Queue;
import org.mitallast.queue.queue.QueueMessage;

public class MMapTransactionalQueueServiceTest extends BaseTest {

    private MMapTransactionalQueueService service;

    @Before
    public void setUp() throws Exception {
        service = new MMapTransactionalQueueService(
            ImmutableSettings.builder()
                .put("work_dir", testFolder.getRoot().getPath())
                .put("segment.max_size", 65536)
                .build(),
            ImmutableSettings.EMPTY,
            new Queue("test")
        );

        service.start();
    }

    @After
    public void tearDown() throws Exception {
        service.stop();
        service.close();
        service = null;
    }

    @Test
    public void testPush() throws Exception {
        QueueMessage message1 = createMessageWithUuid();
        QueueMessage message2 = createMessageWithUuid();
        QueueMessage message3 = createMessageWithUuid();

        Assert.assertTrue(service.push(message1));
        Assert.assertTrue(service.push(message2));
        Assert.assertTrue(service.push(message3));
    }

    @Test
    public void testPushAndGet() throws Exception {
        QueueMessage message1 = createMessageWithUuid();
        QueueMessage message2 = createMessageWithUuid();
        QueueMessage message3 = createMessageWithUuid();

        service.push(message1);
        service.push(message2);
        service.push(message3);

        Assert.assertEquals(message1, service.get(message1.getUuid()));
        Assert.assertEquals(message2, service.get(message2.getUuid()));
        Assert.assertEquals(message3, service.get(message3.getUuid()));
    }

    @Test
    public void testPushAndLock() throws Exception {
        QueueMessage message1 = createMessageWithUuid();
        QueueMessage message2 = createMessageWithUuid();
        QueueMessage message3 = createMessageWithUuid();

        service.push(message1);
        service.push(message2);
        service.push(message3);

        Assert.assertEquals(message1, service.lock(message1.getUuid()));
        Assert.assertEquals(message2, service.lock(message2.getUuid()));
        Assert.assertEquals(message3, service.lock(message3.getUuid()));

        Assert.assertNull(service.lock(message1.getUuid()));
        Assert.assertNull(service.lock(message2.getUuid()));
        Assert.assertNull(service.lock(message3.getUuid()));
    }

    @Test
    public void testPushAndLockAndPop() throws Exception {
        QueueMessage message1 = createMessageWithUuid();
        QueueMessage message2 = createMessageWithUuid();
        QueueMessage message3 = createMessageWithUuid();

        service.push(message1);
        service.push(message2);
        service.push(message3);

        Assert.assertNotNull(service.lockAndPop());
        Assert.assertNotNull(service.lockAndPop());
        Assert.assertNotNull(service.lockAndPop());

        Assert.assertNull(service.lockAndPop());
    }

    @Test
    public void testPushAndUnlockAndDelete() throws Exception {
        QueueMessage message1 = createMessageWithUuid();
        QueueMessage message2 = createMessageWithUuid();
        QueueMessage message3 = createMessageWithUuid();

        service.push(message1);
        service.push(message2);
        service.push(message3);

        service.lock(message1.getUuid());
        service.lock(message2.getUuid());
        service.lock(message3.getUuid());

        Assert.assertEquals(message1, service.unlockAndDelete(message1.getUuid()));
        Assert.assertEquals(message2, service.unlockAndDelete(message2.getUuid()));
        Assert.assertEquals(message3, service.unlockAndDelete(message3.getUuid()));
    }

    @Test
    public void testPushAndUnlockAndRollback() throws Exception {
        QueueMessage message1 = createMessageWithUuid();
        QueueMessage message2 = createMessageWithUuid();
        QueueMessage message3 = createMessageWithUuid();

        service.push(message1);
        service.push(message2);
        service.push(message3);

        service.lock(message1.getUuid());
        service.lock(message2.getUuid());
        service.lock(message3.getUuid());

        Assert.assertEquals(message1, service.unlockAndRollback(message1.getUuid()));
        Assert.assertEquals(message2, service.unlockAndRollback(message2.getUuid()));
        Assert.assertEquals(message3, service.unlockAndRollback(message3.getUuid()));
    }

    @Test
    public void testLongPush() throws Exception {
        logger.info("generate test data");
        QueueMessage[] messagesWithUuid = createMessagesWithUuid(100000);
        logger.info("write test data");
        for (QueueMessage message : messagesWithUuid) {
            Assert.assertTrue(service.push(message));
        }
        logger.info("read test data");
        for (QueueMessage expected : messagesWithUuid) {
            QueueMessage actual = service.get(expected.getUuid());
            Assert.assertNotNull(actual);
            Assert.assertEquals(expected, actual);
        }
    }
}
