package org.mitallast.queue.action.queues.create;

import com.google.inject.Inject;
import org.mitallast.queue.action.AbstractAction;
import org.mitallast.queue.action.ActionRequestValidationException;
import org.mitallast.queue.action.ActionType;
import org.mitallast.queue.common.concurrent.Listener;
import org.mitallast.queue.common.settings.Settings;
import org.mitallast.queue.queues.transactional.TransactionalQueuesService;
import org.mitallast.queue.transport.transport.TransportController;

import java.io.IOException;

public class CreateQueueAction extends AbstractAction<CreateQueueRequest, CreateQueueResponse> {

    private final TransactionalQueuesService queuesService;

    @Inject
    public CreateQueueAction(Settings settings, TransportController controller, TransactionalQueuesService queuesService) {
        super(settings, controller);
        this.queuesService = queuesService;
    }

    @Override
    public void execute(CreateQueueRequest request, Listener<CreateQueueResponse> listener) {
        ActionRequestValidationException validationException = request.validate();
        if (validationException != null) {
            listener.onFailure(validationException);
            return;
        }
        try {
            queuesService.createQueue(request.getQueue(), request.getSettings());
            listener.onResponse(new CreateQueueResponse());
        } catch (IOException e) {
            listener.onFailure(e);
        }
    }

    @Override
    public ActionType getActionId() {
        return ActionType.QUEUES_CREATE;
    }

    @Override
    public CreateQueueRequest createRequest() {
        return new CreateQueueRequest();
    }
}
