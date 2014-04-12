package org.mitallast.queue.rest;

import com.google.inject.AbstractModule;
import org.mitallast.queue.rest.action.RestIndexAction;
import org.mitallast.queue.rest.action.queue.dequeue.RestDeQueueAction;
import org.mitallast.queue.rest.action.queue.enqueue.RestEnQueueAction;
import org.mitallast.queue.rest.action.queue.peek.RestPeekQueueAction;
import org.mitallast.queue.rest.action.queue.stats.RestQueueStatsAction;
import org.mitallast.queue.rest.action.queues.create.RestCreateQueueAction;
import org.mitallast.queue.rest.action.queues.remove.RestRemoveQueueAction;
import org.mitallast.queue.rest.action.queues.stats.RestQueuesStatsAction;

public class RestModule extends AbstractModule {
    @Override
    protected void configure() {
        bind(RestDeQueueAction.class).asEagerSingleton();
        bind(RestEnQueueAction.class).asEagerSingleton();
        bind(RestPeekQueueAction.class).asEagerSingleton();
        bind(RestQueueStatsAction.class).asEagerSingleton();

        bind(RestCreateQueueAction.class).asEagerSingleton();
        bind(RestRemoveQueueAction.class).asEagerSingleton();
        bind(RestQueuesStatsAction.class).asEagerSingleton();

        bind(RestIndexAction.class).asEagerSingleton();

        bind(RestController.class).asEagerSingleton();
    }
}