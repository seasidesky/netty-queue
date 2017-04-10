package org.mitallast.queue.crdt.rest;

import com.google.inject.Inject;
import io.netty.handler.codec.http.HttpMethod;
import org.mitallast.queue.common.json.JsonStreamable;
import org.mitallast.queue.common.stream.Streamable;
import org.mitallast.queue.crdt.CrdtService;
import org.mitallast.queue.crdt.commutative.LWWRegister;
import org.mitallast.queue.crdt.routing.ResourceType;
import org.mitallast.queue.crdt.routing.fsm.AddResource;
import org.mitallast.queue.raft.Raft;
import org.mitallast.queue.raft.discovery.ClusterDiscovery;
import org.mitallast.queue.raft.protocol.ClientMessage;
import org.mitallast.queue.rest.RestController;

import java.util.Optional;

public class RestLWWRegister {
    private final ClusterDiscovery clusterDiscovery;
    private final Raft raft;
    private final CrdtService crdtService;

    @Inject
    public RestLWWRegister(RestController controller, ClusterDiscovery clusterDiscovery, Raft raft, CrdtService crdtService) {
        this.clusterDiscovery = clusterDiscovery;
        this.raft = raft;
        this.crdtService = crdtService;

        controller.handler(this::create)
            .param(controller.param().toLong("id"))
            .response(controller.response().either(
                controller.response().created(),
                controller.response().badRequest()
            ))
            .handle(HttpMethod.POST, HttpMethod.PUT, "_crdt/{id}/lww-register");

        controller.handler(this::value)
            .param(controller.param().toLong("id"))
            .response(controller.response().optionalJson())
            .handle(HttpMethod.GET, "_crdt/{id}/lww-register/value");

        controller.handler(this::assign)
            .param(controller.param().toLong("id"))
            .param(controller.param().json(JsonStreamable.class))
            .response(controller.response().either(
                controller.response().ok(),
                controller.response().badRequest()
            ))
            .handle(HttpMethod.POST, HttpMethod.PUT, "_crdt/{id}/lww-register/value");
    }

    public boolean create(long id) {
        raft.apply(new ClientMessage(clusterDiscovery.self(), new AddResource(ResourceType.LWWRegister, id, 2)));
        return true;
    }

    public Optional<Streamable> value(long id) {
        return crdtService.crdt(id, LWWRegister.class).value();
    }

    public boolean assign(long id, JsonStreamable value) {
        crdtService.crdt(id, LWWRegister.class).assign(value);
        return true;
    }
}